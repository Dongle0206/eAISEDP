package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.runtime.governance.dto.StandardVo;
import com.eaiselp.runtime.hierarchy.ArchitecturePrinciple;
import com.eaiselp.runtime.hierarchy.ArchitecturePrincipleMapper;
import com.eaiselp.runtime.hierarchy.QualityGateRule;
import com.eaiselp.runtime.hierarchy.QualityGateRuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 工程标准服务实现（V6 F1.1，case-20260820 T2）。
 *
 * <p><b>批A 范围（本类）</b>：CRUD S1~S5——多版本行 uk(tenant,code,version) 兜底
 * （DuplicateKeyException→400 统一形态）、编号缺省生成 STD-NNNN（%04d 续推，复刻
 * AdrServiceImpl.maxVisibleSuffix）、draft 专属编辑（published/deprecated 400
 * "发布后不可编辑，请升版"）、关联原则/门禁校验、逻辑删、审计。</p>
 *
 * <p><b>批B 增补（T12/T13）</b>：状态流转 transit（S6 状态机 + 发布自动取代事务 D-7
 * FOR UPDATE，双审计）与 gateName 打通查询（D-9 已删除占位手写 SQL + S3 被引用门禁解析，
 * §4.5 翻译口径——门禁判定逻辑零改动，仅展示级打通）。</p>
 *
 * <p><b>关联校验（§4.5 翻译口径）</b>：relatedPrincipleCodes 逐 code 查
 * t_architecture_principle 存在性；relatedGateNames 逐 name 查 t_quality_gate_rule
 * 存在且 enabled=1（不存在/已停用整单 400 指名，AC-F1.3）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StandardServiceImpl extends ServiceImpl<StandardMapper, Standard> implements StandardService {

    private final ArchitecturePrincipleMapper principleMapper;
    private final QualityGateRuleMapper gateRuleMapper;
    private final AuditService auditService;

    private static final com.fasterxml.jackson.databind.ObjectMapper OM =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** 列表缺省状态筛选（draft+published 双值，AC-F1.2；deprecated 需显式筛选） */
    private static final List<String> DEFAULT_STATUS = List.of("draft", "published");

    /** 正文建议上限（PRD §4.1.1 "建议 ≤20000 字符"，软校验提示性约束） */
    private static final int CONTENT_MAX_SUGGEST = 20000;

    // ==================== CRUD（S1~S5） ====================

    @Override
    public Standard create(Standard standard) {
        validateForWrite(standard);
        standard.setStatus(StandardStatus.DRAFT.dbValue());
        standard.setDeprecateReason(null);
        insertWithCodeRetry(standard);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("standardCode", standard.getStandardCode());
        detail.put("title", standard.getTitle());
        detail.put("version", standard.getVersion());
        detail.put("status", standard.getStatus());
        detail.put("relatedPrincipleCodes", parseCodes(standard.getRelatedPrincipleCodes()));
        detail.put("relatedGateNames", parseCodes(standard.getRelatedGateNames()));
        audit("standard_create", standard.getId(), detail);
        return standard;
    }

    @Override
    public Standard edit(Long id, Standard patch) {
        Standard exist = loadOr404(id);
        // 编辑限制（AC-F1.5）：只有 draft 可编辑；published/deprecated 全字段只读（变更=升版建新行）
        if (!StandardStatus.DRAFT.dbValue().equals(exist.getStatus())) {
            throw new BizException(400, "发布后不可编辑，请升版");
        }
        validateForWrite(patch);
        Standard next = new Standard();
        next.setId(id);
        // draft 全字段可编辑（tasks.md T2：含编号/版本；uk 冲突 400 兜底）
        next.setStandardCode(patch.getStandardCode() == null || patch.getStandardCode().isBlank()
                ? exist.getStandardCode() : patch.getStandardCode());
        next.setTitle(patch.getTitle());
        next.setVersion(patch.getVersion());
        next.setContent(patch.getContent());
        next.setRelatedPrincipleCodes(patch.getRelatedPrincipleCodes());
        next.setRelatedGateNames(patch.getRelatedGateNames());
        try {
            updateById(next);
        } catch (DuplicateKeyException e) {
            throw new BizException(400, "标准已存在: " + next.getStandardCode() + " " + next.getVersion());
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("standardCode", next.getStandardCode());
        detail.put("title", next.getTitle());
        detail.put("version", next.getVersion());
        detail.put("relatedPrincipleCodes", parseCodes(next.getRelatedPrincipleCodes()));
        detail.put("relatedGateNames", parseCodes(next.getRelatedGateNames()));
        audit("standard_update", id, detail);
        return getById(id);
    }

    @Override
    public void remove(Long id) {
        Standard exist = loadOr404(id);
        removeById(id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("standardCode", exist.getStandardCode());
        detail.put("title", exist.getTitle());
        detail.put("version", exist.getVersion());
        detail.put("status", exist.getStatus());
        audit("standard_delete", id, detail);
    }

    @Override
    public StandardVo detailVo(Long id) {
        StandardVo vo = toVo(loadOr404(id));
        // S3 详情"被引用门禁列表"（批B T13，AC-F1.7 双向关联）：relatedGateNames 解析
        // name→规则当前信息；悬空 name（规则已删/改名）以 deleted=true 占位（SE §4.5/R6）
        vo.setReferencedByGates(resolveReferencedGates(vo.getRelatedGateNames()));
        return vo;
    }

    @Override
    public Standard loadOr404(Long id) {
        Standard standard = getById(id);
        if (standard == null) {
            throw new BizException(404, "标准不存在: " + id);
        }
        return standard;
    }

    // ==================== 查询（S1） ====================

    @Override
    public IPage<StandardVo> pageFilter(String status, String principleCode, String keyword, long page, long size) {
        LambdaQueryWrapper<Standard> w = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            // 逗号分隔多状态（如缺省 "draft,published"）；显式传 deprecated 可查废弃（AC-F1.2）
            w.in(Standard::getStatus, Arrays.stream(status.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList());
        } else {
            w.in(Standard::getStatus, DEFAULT_STATUS);
        }
        if (keyword != null && !keyword.isBlank()) {
            w.like(Standard::getTitle, keyword);
        }
        w.orderByDesc(Standard::getCreateTime);
        IPage<Standard> entityPage = page(new Page<>(page, size), w);
        IPage<StandardVo> result = entityPage.convert(this::toVo);
        // 按原则 code 内存过滤（JSON 列不进 SQL，防方言绑定与索引失效，ADR §4.2 口径）
        if (principleCode != null && !principleCode.isBlank()) {
            String target = principleCode.trim();
            List<StandardVo> filtered = result.getRecords().stream()
                    .filter(vo -> vo.getRelatedPrincipleCodes() != null
                            && vo.getRelatedPrincipleCodes().contains(target))
                    .toList();
            result.setRecords(filtered);
            result.setTotal(filtered.size());
        }
        return result;
    }

    @Override
    public IPage<StandardVo> pageFilter(String status, String principleCode, String gateName,
                                         String keyword, long page, long size) {
        if (gateName != null && !gateName.isBlank()) {
            // 批B T13：gateName 打通查询（§4.5 翻译口径）——D-9 旁路 @TableLogic 含已删除行
            // + status 固定 published（门禁关联展示一律 published，draft 发布前不出现在门禁侧）
            return pageFilterByGateName(gateName.trim(), page, size);
        }
        return pageFilter(status, principleCode, keyword, page, size);
    }

    /**
     * gateName 反查（批B T13，AC-F1.7/F1.8）：D-9 手写 SQL 取 published 全量候选行（含
     * 逻辑删行，deleted 占位）→ relatedGateNames JSON 内存过滤 → 内存分页。
     *
     * <p>打回解析（case-detail）与规则页"已关联标准"只读区复用本路径：未删行正常展示
     * "{code}《{title}》{version}"，删行由前端按 {@code deleted=true} 渲染"已删除"占位。</p>
     *
     * <p><b>M1（安全评审）输出瘦身</b>：本路径旁路 @TableLogic 返回逻辑删行，出参走
     * {@link #toGateRefVo} 占位 VO（仅 id/code/title/version/status/deleted），不复用
     * 全字段 {@link #toVo}——否则已删标准正文（content/deprecateReason）对
     * standard:view 只读用户泄露，逻辑删"删除后不可见"保护被放大为正文可读（SE §4.5）。</p>
     */
    private IPage<StandardVo> pageFilterByGateName(String gateName, long page, long size) {
        List<StandardVo> hits = baseMapper.selectPublishedWithDeletedForGateRef().stream()
                .filter(s -> parseCodes(s.getRelatedGateNames()).contains(gateName))
                .map(this::toGateRefVo)
                .toList();
        Page<StandardVo> result = new Page<>(page, size, hits.size());
        int from = (int) Math.min(Math.max(page - 1, 0) * Math.max(size, 1), hits.size());
        int to = (int) Math.min(from + Math.max(size, 1), hits.size());
        result.setRecords(hits.subList(from, to));
        return result;
    }

    /**
     * gateName 反查专用瘦身 VO（M1，安全评审）：仅占位展示必要字段，强制不填
     * content/deprecateReason/createBy 等正文与敏感列（删行/未删行统一瘦身，契约收窄）。
     * 前端消费方（gate-rule-list 已关联标准区、case-detail 打回依据）仅用
     * code/title/version/status/deleted，渲染不受影响。
     */
    private StandardVo toGateRefVo(Standard s) {
        StandardVo vo = new StandardVo();
        vo.setId(s.getId());
        vo.setStandardCode(s.getStandardCode());
        vo.setTitle(s.getTitle());
        vo.setVersion(s.getVersion());
        vo.setStatus(s.getStatus());
        vo.setDeleted(s.getDeleted() != null && s.getDeleted() == 1);
        return vo;
    }

    // ==================== 状态流转 + 发布自动取代（S6，批B T12，D-7/D-8） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StandardVo transit(Long id, String target, String deprecateReason) {
        StandardStatus to = StandardStatus.fromDbValue(target);
        if (to == null) {
            throw new BizException(400, "未知状态 " + target + "（合法值: draft/published/deprecated）");
        }
        Standard exist = loadOr404(id);
        StandardStatus from = StandardStatus.fromDbValue(exist.getStatus());
        if (from == null) {
            throw new BizException(400, "标准状态列脏数据: " + exist.getStatus());
        }
        // 幂等短路：流转到自身合法直接返回（并发重试语义，StandardStatus 注释约定，
        // deprecated→deprecated 无业务效果不更新不审计）
        if (from == to) {
            return detailVo(id);
        }
        if (!from.canTransitionTo(to)) {
            if (from.isTerminal()) {
                throw new BizException(400, "deprecated 为终态，不可流转（如需生效请升版建新行）");
            }
            throw new BizException(400, "非法状态流转 " + from.dbValue() + "→" + to.dbValue());
        }
        // 必填项校验（规则内聚枚举，Service 只翻译文案：AC-F1.2 published→deprecated
        // 与 draft→deprecated 均必填原因，空 400）
        if (StandardStatus.requiredFieldsFor(to).contains(StandardStatus.RequiredField.DEPRECATE_REASON)
                && (deprecateReason == null || deprecateReason.isBlank())) {
            throw new BizException(400, "deprecateReason 必填（废弃/作废必须填写原因）");
        }
        // 发布自动取代（D-7）：FOR UPDATE 锁同编号现行 published → 事务内先置旧版 deprecated
        // 再发布新版（顺序钉死，SE §3.2.1），保证同编号至多一个 published（AC-F1.4）
        String supersededVersion = null;
        if (to == StandardStatus.PUBLISHED) {
            supersededVersion = autoDeprecateCurrentPublished(exist.getStandardCode(), exist.getVersion());
        }
        Standard next = new Standard();
        next.setId(id);
        next.setStatus(to.dbValue());
        next.setDeprecateReason(to == StandardStatus.DEPRECATED ? deprecateReason : null);
        updateById(next);
        // 审计 standard_transit：from→to + deprecateReason + 被取代链（§8.1 清单）
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("standardCode", exist.getStandardCode());
        detail.put("version", exist.getVersion());
        detail.put("from", from.dbValue());
        detail.put("to", to.dbValue());
        if (deprecateReason != null && !deprecateReason.isBlank()) {
            detail.put("deprecateReason", deprecateReason);
        }
        if (supersededVersion != null) {
            detail.put("supersededChain", exist.getStandardCode() + " " + supersededVersion + "→" + exist.getVersion());
        }
        audit("standard_transit", id, detail);
        return detailVo(id);
    }

    /**
     * 发布自动取代（D-7，AC-F1.4）：{@code SELECT ... WHERE standard_code=? AND status='published'
     * FOR UPDATE} 行级锁现行 published 版本 → 置 deprecated（原因=「被 {code} {新版本} 取代」）
     * → 写 standard_auto_deprecate 审计。锁下无竞态窗口，发布为低频管理操作（SE R4 论证）。
     *
     * @return 被取代的旧版本号；无现行 published（首发）返回 null
     */
    private String autoDeprecateCurrentPublished(String code, String newVersion) {
        Standard current = baseMapper.selectOne(new LambdaQueryWrapper<Standard>()
                .eq(Standard::getStandardCode, code)
                .eq(Standard::getStatus, StandardStatus.PUBLISHED.dbValue())
                .last("LIMIT 1 FOR UPDATE"));
        if (current == null) {
            return null;
        }
        Standard patch = new Standard();
        patch.setId(current.getId());
        patch.setStatus(StandardStatus.DEPRECATED.dbValue());
        patch.setDeprecateReason("被 " + code + " " + newVersion + " 取代");
        updateById(patch);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("standardCode", code);
        detail.put("from", StandardStatus.PUBLISHED.dbValue());
        detail.put("to", StandardStatus.DEPRECATED.dbValue());
        detail.put("deprecateReason", patch.getDeprecateReason());
        detail.put("supersededChain", code + " " + current.getVersion() + "→" + newVersion);
        audit("standard_auto_deprecate", current.getId(), detail);
        return current.getVersion();
    }

    @Override
    public StandardVo toVo(Standard standard) {
        StandardVo vo = new StandardVo();
        vo.setId(standard.getId());
        vo.setStandardCode(standard.getStandardCode());
        vo.setTitle(standard.getTitle());
        vo.setVersion(standard.getVersion());
        vo.setStatus(standard.getStatus());
        vo.setContent(standard.getContent());
        vo.setRelatedPrincipleCodes(parseCodes(standard.getRelatedPrincipleCodes()));
        vo.setRelatedGateNames(parseCodes(standard.getRelatedGateNames()));
        vo.setDeprecateReason(standard.getDeprecateReason());
        vo.setCreateBy(standard.getCreateBy());
        vo.setCreateTime(standard.getCreateTime());
        vo.setUpdateTime(standard.getUpdateTime());
        return vo;
    }

    // ==================== 内部工具 ====================

    /**
     * S3 被引用门禁解析（批B T13，AC-F1.7）：relatedGateNames 逐 name 查规则当前信息
     * （{@code @TableLogic} 默认过滤已删规则 → 查不到即悬空）。
     * 悬空 name（规则已逻辑删/改名）置 {@code deleted=true} 占位，不 400 不静默丢弃——
     * 展示级打通边界（SE R6），门禁判定不依赖标准/关联存在。
     */
    private List<StandardVo.ReferencedGate> resolveReferencedGates(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<StandardVo.ReferencedGate> list = new ArrayList<>(names.size());
        for (String name : names) {
            StandardVo.ReferencedGate g = new StandardVo.ReferencedGate();
            g.setName(name);
            QualityGateRule rule = gateRuleMapper.selectOne(new LambdaQueryWrapper<QualityGateRule>()
                    .eq(QualityGateRule::getName, name).last("LIMIT 1"));
            if (rule == null) {
                g.setDeleted(true);
            } else {
                g.setDeleted(false);
                g.setGateType(rule.getGateType());
                g.setStage(rule.getStage());
                g.setEnabled(rule.getEnabled());
            }
            list.add(g);
        }
        return list;
    }

    /** 写入前校验：title/version/content 必填 + 关联原则逐 code 存在性 + 关联门禁逐 name 存在且 enabled。 */
    private void validateForWrite(Standard standard) {
        if (standard == null) {
            throw new BizException(400, "请求体不能为空");
        }
        requireText(standard.getTitle(), "title");
        if (standard.getTitle().length() > 200) {
            throw new BizException(400, "title 长度不能超过 200 字符");
        }
        requireText(standard.getVersion(), "version");
        requireText(standard.getContent(), "content");
        if (standard.getContent().length() > CONTENT_MAX_SUGGEST) {
            throw new BizException(400, "content 长度建议不超过 " + CONTENT_MAX_SUGGEST + " 字符");
        }
        // 关联原则逐 code 存在性校验（AC-F1.6，同 ADR 先例）
        for (String code : parseCodes(standard.getRelatedPrincipleCodes())) {
            if (principleMapper.selectCount(new LambdaQueryWrapper<ArchitecturePrinciple>()
                    .eq(ArchitecturePrinciple::getCode, code)) == 0) {
                throw new BizException(400, "原则 " + code + " 不存在");
            }
        }
        // 关联门禁逐 name 存在且 enabled 校验（AC-F1.3 翻译口径：选择器与提交双重保险）
        for (String name : parseCodes(standard.getRelatedGateNames())) {
            QualityGateRule rule = gateRuleMapper.selectOne(new LambdaQueryWrapper<QualityGateRule>()
                    .eq(QualityGateRule::getName, name).last("LIMIT 1"));
            if (rule == null) {
                throw new BizException(400, "门禁规则 " + name + " 不存在");
            }
            if (rule.getEnabled() == null || rule.getEnabled() != 1) {
                throw new BizException(400, "门禁规则 " + name + " 已停用");
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(400, field + " 不能为空");
        }
    }

    /** standardCode 生成 + uk 兜底重试（≤3 次，复刻 AdrServiceImpl.insertWithCodeRetry）。 */
    private void insertWithCodeRetry(Standard standard) {
        if (standard.getStandardCode() != null && !standard.getStandardCode().isBlank()) {
            try {
                save(standard);
                return;
            } catch (DuplicateKeyException e) {
                // 自定义编号冲突直接 400（tasks.md T2）
                throw new BizException(400, "标准已存在: " + standard.getStandardCode()
                        + " " + standard.getVersion());
            }
        }
        long seq = maxVisibleSuffix() + 1;
        for (int attempt = 0; attempt <= 3; attempt++) {
            standard.setStandardCode("STD-" + String.format("%04d", Math.min(seq + attempt, 9999)));
            try {
                save(standard);
                return;
            } catch (DuplicateKeyException e) {
                log.warn("[Standard] standardCode 冲突重试 {}/3: code={}", attempt + 1, standard.getStandardCode());
            }
        }
        throw new BizException(400, "标准编号生成冲突，请自定义编号后重试");
    }

    /** 求租户内可见 STD-NNNN 编号的最大序号（自定义编号不参与续推，复刻 AdrServiceImpl.maxVisibleSuffix）。 */
    private long maxVisibleSuffix() {
        long max = 0;
        for (String c : list(new LambdaQueryWrapper<Standard>().select(Standard::getStandardCode))
                .stream().map(Standard::getStandardCode).filter(Objects::nonNull).toList()) {
            if (c.startsWith("STD-") && c.length() > 4) {
                try {
                    max = Math.max(max, Long.parseLong(c.substring(4)));
                } catch (NumberFormatException ignore) {
                    // 自定义编号不参与续推
                }
            }
        }
        return max;
    }

    /** JSON 数组解析（null/坏 JSON → 空列表容忍，不 500；同 ADR parseCodes 先例）。 */
    static List<String> parseCodes(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            String[] arr = OM.readValue(json, String[].class);
            return arr == null ? List.of() : Arrays.stream(arr).filter(Objects::nonNull).toList();
        } catch (Exception e) {
            log.warn("[Standard] JSON 数组列解析失败（容忍降级空列表）: {}", json);
            return List.of();
        }
    }

    private void audit(String action, Long id, Object detail) {
        String json;
        try {
            json = OM.writeValueAsString(detail);
        } catch (Exception e) {
            json = "{}";
        }
        auditService.log(action, "standard", String.valueOf(id), json);
    }
}
