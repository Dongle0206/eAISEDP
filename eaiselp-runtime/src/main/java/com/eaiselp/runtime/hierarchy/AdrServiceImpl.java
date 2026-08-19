package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.common.tenant.TenantContext;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.data.entity.GovernanceLog;
import com.eaiselp.data.mapper.GovernanceLogMapper;
import com.eaiselp.runtime.hierarchy.dto.AdrVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ADR 服务实现（V5 F4，case-20260818 T12）。
 *
 * <p><b>收敛点落地</b>：
 * <ul>
 *   <li><b>C3</b>：无 deprecate_reason 列——transit→deprecated 时 deprecateReason 必填校验照旧
 *       （空=400），值写 t_governance_log 审计 detail（adr_transit）+ 响应回显；详情 Vo 从最近
 *       一次 adr_transit 审计回显该字段（手写 tenant_id，不跨 IGNORE 边界 JOIN）。</li>
 *   <li><b>原则联动=提示而非自动</b>：principleSyncHints 只组装 [{code,title}]，系统不写
 *       t_architecture_principle（AC-F4.3）。</li>
 *   <li><b>relatedPrincipleCodes</b>：JSON 数组 String 承载，Jackson 在本层序列化/解析；
 *       保存时逐 code 存在性校验（整单 400 指名）；按原则筛选在内存过滤（不写 JSON_CONTAINS）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdrServiceImpl extends ServiceImpl<AdrMapper, Adr> implements AdrService {

    private final ArchitecturePrincipleMapper principleMapper;
    private final GovernanceLogMapper governanceLogMapper;
    private final AuditService auditService;

    private static final com.fasterxml.jackson.databind.ObjectMapper OM =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** 列表缺省状态筛选（proposed+accepted 双值，api-contracts §4） */
    private static final List<String> DEFAULT_STATUS = List.of("proposed", "accepted");

    // ==================== CRUD ====================

    @Override
    public Adr create(Adr adr) {
        validateForWrite(adr);
        adr.setStatus(AdrStatus.PROPOSED.dbValue());
        adr.setSupersededBy(null);
        insertWithCodeRetry(adr);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("adrCode", adr.getAdrCode());
        detail.put("title", adr.getTitle());
        detail.put("status", adr.getStatus());
        detail.put("relatedPrincipleCodes", parseCodes(adr.getRelatedPrincipleCodes()));
        audit("adr_create", adr.getId(), detail);
        return adr;
    }

    @Override
    public Adr edit(Long id, Adr patch) {
        Adr exist = loadOr404(id);
        validateForWrite(patch);
        Adr next = new Adr();
        next.setId(id);
        next.setAdrCode(exist.getAdrCode());          // 编号不可改（uk 语义）
        next.setTitle(patch.getTitle());
        next.setContextText(patch.getContextText());
        next.setDecisionText(patch.getDecisionText());
        next.setConsequenceText(patch.getConsequenceText());
        next.setRelatedPrincipleCodes(patch.getRelatedPrincipleCodes());
        next.setDecisionDate(patch.getDecisionDate());
        next.setAuthor(patch.getAuthor());
        // status/supersededBy 不在此改（只走 transit）
        updateById(next);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("adrCode", exist.getAdrCode());
        detail.put("title", patch.getTitle());
        detail.put("relatedPrincipleCodes", parseCodes(patch.getRelatedPrincipleCodes()));
        detail.put("变更摘要", "编辑五段式/关联原则（版本对比=范围外，审计即历史）");
        audit("adr_update", id, detail);
        return getById(id);
    }

    @Override
    public void remove(Long id) {
        Adr exist = loadOr404(id);
        removeById(id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("adrCode", exist.getAdrCode());
        detail.put("title", exist.getTitle());
        detail.put("status", exist.getStatus());
        audit("adr_delete", id, detail);
    }

    @Override
    public AdrVo detailVo(Long id) {
        Adr exist = loadOr404(id);
        AdrVo vo = toVo(exist);
        vo.setDeprecateReason(loadDeprecateReasonFromAudit(id));   // C3 审计回显
        return vo;
    }

    @Override
    public Adr loadOr404(Long id) {
        Adr adr = getById(id);
        if (adr == null) {
            throw new BizException(404, "ADR 不存在: " + id);
        }
        return adr;
    }

    // ==================== 状态流转（AC-F4.2/F4.3） ====================

    @Override
    public AdrVo transit(Long id, String target, String supersededBy, String deprecateReason) {
        AdrStatus to = AdrStatus.fromDbValue(target);
        if (to == null) {
            throw new BizException(400, "未知状态: " + target
                    + "（合法值: proposed/accepted/deprecated/superseded）");
        }
        Adr exist = loadOr404(id);
        AdrStatus from = AdrStatus.fromDbValue(exist.getStatus());
        if (from == null) {
            throw new BizException(400, "ADR 状态列脏数据: " + exist.getStatus());
        }
        if (!from.canTransitionTo(to)) {
            if (from.isTerminal()) {
                throw new BizException(400, "deprecated/superseded 为终态，不可回退");
            }
            throw new BizException(400, "非法状态流转: " + from.dbValue() + "→" + to.dbValue());
        }
        // 必填项校验（规则内聚枚举，Service 只翻译文案）
        if (AdrStatus.requiredFieldsFor(to).contains(AdrStatus.RequiredField.DEPRECATE_REASON)
                && (deprecateReason == null || deprecateReason.isBlank())) {
            throw new BizException(400, "deprecateReason 必填");
        }
        if (AdrStatus.requiredFieldsFor(to).contains(AdrStatus.RequiredField.SUPERSEDED_BY)) {
            if (supersededBy == null || supersededBy.isBlank()) {
                throw new BizException(400, "superseded_by 必填");
            }
            if (supersededBy.equals(exist.getAdrCode())) {
                throw new BizException(400, "superseded_by 不能指向自身");
            }
            Adr target0 = getOne(new LambdaQueryWrapper<Adr>()
                    .eq(Adr::getAdrCode, supersededBy).last("LIMIT 1"));
            if (target0 == null) {
                throw new BizException(404, "superseded_by 指向的 ADR 不存在: " + supersededBy);
            }
            if (!AdrStatus.ACCEPTED.dbValue().equals(target0.getStatus())) {
                throw new BizException(400, "superseded_by 指向的 ADR 须为 accepted");
            }
        }
        Adr next = new Adr();
        next.setId(id);
        next.setStatus(to.dbValue());
        next.setSupersededBy(to == AdrStatus.SUPERSEDED ? supersededBy : null);
        updateById(next);
        // superseded 态的 supersededBy 列有值；其他态显式清空（LambdaUpdateWrapper set null）
        if (to != AdrStatus.SUPERSEDED && exist.getSupersededBy() != null) {
            update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Adr>()
                    .eq(Adr::getId, id).set(Adr::getSupersededBy, null));
        }

        // 审计 detail：状态前后值 + superseded_by 链 + deprecateReason（C3 承载，不落列）
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("from", from.dbValue());
        detail.put("to", to.dbValue());
        if (supersededBy != null && !supersededBy.isBlank()) {
            detail.put("supersededBy", supersededBy);
            detail.put("supersededChain", exist.getAdrCode() + " → " + supersededBy);
        }
        if (deprecateReason != null && !deprecateReason.isBlank()) {
            detail.put("deprecateReason", deprecateReason);
        }
        audit("adr_transit", id, detail);

        // 响应 Vo：deprecateReason 回显 + 原则联动提示（流转离开 accepted 且关联非空，AC-F4.3）
        AdrVo vo = toVo(getById(id));
        vo.setDeprecateReason(to == AdrStatus.DEPRECATED ? deprecateReason
                : loadDeprecateReasonFromAudit(id));
        if (from == AdrStatus.ACCEPTED && to != AdrStatus.ACCEPTED) {
            vo.setPrincipleSyncHints(buildPrincipleSyncHints(parseCodes(exist.getRelatedPrincipleCodes())));
        }
        return vo;
    }

    /** 原则联动提示组装：关联原则 code → [{code,title}]（原则已删/查不到的 code 跳过）。 */
    private List<AdrVo.PrincipleHint> buildPrincipleSyncHints(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return null;
        }
        List<AdrVo.PrincipleHint> hints = new ArrayList<>();
        for (String code : codes) {
            ArchitecturePrinciple p = principleMapper.selectOne(new LambdaQueryWrapper<ArchitecturePrinciple>()
                    .eq(ArchitecturePrinciple::getCode, code).last("LIMIT 1"));
            if (p != null) {
                AdrVo.PrincipleHint h = new AdrVo.PrincipleHint();
                h.setCode(p.getCode());
                h.setTitle(p.getTitle());
                hints.add(h);
            }
        }
        return hints.isEmpty() ? null : hints;
    }

    // ==================== 查询 ====================

    @Override
    public IPage<AdrVo> pageFilter(String status, String principleCode, String keyword, long page, long size) {
        LambdaQueryWrapper<Adr> w = new LambdaQueryWrapper<Adr>();
        if (status != null && !status.isBlank()) {
            // 逗号分隔多状态（如缺省 "proposed,accepted"）；数组 in 标准形态
            w.in(Adr::getStatus, Arrays.stream(status.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList());
        } else {
            w.in(Adr::getStatus, DEFAULT_STATUS);
        }
        if (keyword != null && !keyword.isBlank()) {
            w.like(Adr::getTitle, keyword);
        }
        w.orderByDesc(Adr::getCreateTime);
        IPage<Adr> entityPage = page(new Page<>(page, size), w);
        IPage<AdrVo> result = entityPage.convert(this::toVo);
        // 按原则 code 内存过滤（JSON 列不进 SQL，防方言绑定与索引失效，SE §4.2 口径）
        if (principleCode != null && !principleCode.isBlank()) {
            String target = principleCode.trim();
            List<AdrVo> filtered = result.getRecords().stream()
                    .filter(vo -> vo.getRelatedPrincipleCodes() != null
                            && vo.getRelatedPrincipleCodes().contains(target))
                    .toList();
            result.setRecords(filtered);
            result.setTotal(filtered.size());
        }
        return result;
    }

    @Override
    public List<AdrVo> listByPrincipleCode(String principleCode) {
        if (principleCode == null || principleCode.isBlank()) {
            return List.of();
        }
        return list(new LambdaQueryWrapper<Adr>()
                        .isNotNull(Adr::getRelatedPrincipleCodes)
                        .orderByDesc(Adr::getCreateTime))
                .stream()
                .filter(adr -> parseCodes(adr.getRelatedPrincipleCodes())
                        .contains(principleCode.trim()))
                .map(this::toVo)
                .toList();
    }

    @Override
    public AdrVo toVo(Adr adr) {
        AdrVo vo = new AdrVo();
        vo.setId(adr.getId());
        vo.setAdrCode(adr.getAdrCode());
        vo.setTitle(adr.getTitle());
        vo.setStatus(adr.getStatus());
        vo.setContext(adr.getContextText());          // C4：API 语义名 ↔ V5 列 context_text
        vo.setDecision(adr.getDecisionText());
        vo.setConsequences(adr.getConsequenceText());
        vo.setRelatedPrincipleCodes(parseCodes(adr.getRelatedPrincipleCodes()));
        vo.setDecisionDate(adr.getDecisionDate());
        vo.setAuthor(adr.getAuthor());
        vo.setSupersededBy(adr.getSupersededBy());
        vo.setCreateTime(adr.getCreateTime());
        vo.setUpdateTime(adr.getUpdateTime());
        return vo;
    }

    // ==================== 内部工具 ====================

    /** 写入前校验：五段式必填（空串 400）+ 关联原则逐 code 存在性（整单 400 指名，AC-F4.1）。 */
    private void validateForWrite(Adr adr) {
        if (adr == null) {
            throw new BizException(400, "请求体不能为空");
        }
        requireText(adr.getTitle(), "title");
        if (adr.getTitle().length() > 200) {
            throw new BizException(400, "title 长度不能超过 200 字符");
        }
        requireText(adr.getContextText(), "context");
        requireText(adr.getDecisionText(), "decision");
        requireText(adr.getConsequenceText(), "consequences");
        List<String> codes = parseCodes(adr.getRelatedPrincipleCodes());
        for (String code : codes) {
            if (principleMapper.selectCount(new LambdaQueryWrapper<ArchitecturePrinciple>()
                    .eq(ArchitecturePrinciple::getCode, code)) == 0) {
                throw new BizException(400, "原则 " + code + " 不存在");
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(400, field + " 不能为空");
        }
    }

    /** related_principle_codes JSON 数组解析（null/坏 JSON → 空列表容忍，不 500）。 */
    private static List<String> parseCodes(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            String[] arr = OM.readValue(json, String[].class);
            return arr == null ? List.of() : Arrays.stream(arr).filter(Objects::nonNull).toList();
        } catch (Exception e) {
            log.warn("[ADR] relatedPrincipleCodes 解析失败（容忍降级空列表）: {}", json);
            return List.of();
        }
    }

    /** adrCode 生成 + uk 兜底重试（≤3 次，同 Milestone 先例）。 */
    private void insertWithCodeRetry(Adr adr) {
        if (adr.getAdrCode() != null && !adr.getAdrCode().isBlank()) {
            try {
                save(adr);
                return;
            } catch (DuplicateKeyException e) {
                throw new BizException(400, "ADR 编号已存在: " + adr.getAdrCode());
            }
        }
        long seq = maxVisibleSuffix() + 1;
        for (int attempt = 0; attempt <= 3; attempt++) {
            adr.setAdrCode("ADR-" + String.format("%03d", Math.min(seq + attempt, 999)));
            try {
                save(adr);
                return;
            } catch (DuplicateKeyException e) {
                log.warn("[ADR] adrCode 冲突重试 {}/3: code={}", attempt + 1, adr.getAdrCode());
            }
        }
        throw new BizException(400, "ADR 编号生成冲突，请自定义编号后重试");
    }

    private long maxVisibleSuffix() {
        long max = 0;
        for (String c : list(new LambdaQueryWrapper<Adr>().select(Adr::getAdrCode))
                .stream().map(Adr::getAdrCode).filter(Objects::nonNull).toList()) {
            if (c.startsWith("ADR-") && c.length() > 4) {
                try {
                    max = Math.max(max, Long.parseLong(c.substring(4)));
                } catch (NumberFormatException ignore) {
                    // 自定义编号不参与续推
                }
            }
        }
        return max;
    }

    /**
     * C3：从最近一次 adr_transit 审计回显 deprecateReason。
     *
     * <p>t_governance_log 在 IGNORE_TABLES——SQL 手写 tenant_id 等值 + action/resource_id 定位
     * + create_time 倒序取首条（命中 idx_tenant_action_time），不与业务表 JOIN。</p>
     */
    private String loadDeprecateReasonFromAudit(Long id) {
        try {
            GovernanceLog latest = governanceLogMapper.selectOne(new LambdaQueryWrapper<GovernanceLog>()
                    .eq(GovernanceLog::getTenantId, TenantContext.get())
                    .eq(GovernanceLog::getAction, "adr_transit")
                    .eq(GovernanceLog::getResourceId, String.valueOf(id))
                    .isNotNull(GovernanceLog::getDetail)
                    .orderByDesc(GovernanceLog::getCreateTime)
                    .last("LIMIT 1"));
            if (latest == null || latest.getDetail() == null) {
                return null;
            }
            var node = OM.readTree(latest.getDetail());
            return node.hasNonNull("deprecateReason") ? node.get("deprecateReason").asText() : null;
        } catch (Exception e) {
            log.warn("[ADR] deprecateReason 审计回显失败（容忍降级 null）id={}", id, e);
            return null;
        }
    }

    private void audit(String action, Long id, Object detail) {
        String json;
        try {
            json = OM.writeValueAsString(detail);
        } catch (Exception e) {
            json = "{}";
        }
        auditService.log(action, "adr", String.valueOf(id), json);
    }
}
