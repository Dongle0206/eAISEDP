package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.common.security.JwtClaims;
import com.eaiselp.common.security.LoginUser;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.data.entity.Case;
import com.eaiselp.data.service.CaseService;
import com.eaiselp.runtime.governance.dto.RelatedObjectVo;
import com.eaiselp.runtime.governance.dto.RiskDashboardVo;
import com.eaiselp.runtime.governance.dto.RiskVo;
import com.eaiselp.runtime.hierarchy.Program;
import com.eaiselp.runtime.hierarchy.ProgramMapper;
import com.eaiselp.runtime.hierarchy.Project;
import com.eaiselp.runtime.hierarchy.ProjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 风险登记册服务实现（case-20260821 T2 CRUD / T10 状态机 / T12 看板聚合）。
 *
 * <p><b>要点</b>：
 * <ul>
 *   <li><b>计算列防伪造链</b>（AC-F1.5）：riskValue/riskLevel 由 {@link RiskCalculator}
 *       在 create/edit 写库前重算覆盖——入参 DTO 无此二字段（Controller toEntity 不映射），
 *       客户端伪造值连绑定入口都没有，库值恒=P×I。</li>
 *   <li><b>状态机</b>（AC-F1.4 + §0.3-1 消解）：open→mitigating→closed（closed 必填
 *       resolutionNote）、mitigating→open 回退合法；closed 终态只读、无出边；自流转幂等短路。</li>
 *   <li><b>关联对象</b>（AC-F1.6）：relatedObjects 逐条 {type∈program/project/case, id}
 *       存在性校验（注入 hierarchy 的 ProgramMapper/ProjectMapper + data 的 CaseService，
 *       P3 既有依赖方向，V6 先例同构）；详情解析悬空/已逻辑删 → deleted=true 占位。</li>
 *   <li><b>逾期</b>（AC-F1.7，D-10）：服务端 VO 判定 reviewDate&lt;今天且未 closed；
 *       overdueOnly 筛选同口径 SQL 化（review_date &lt; CURRENT_DATE AND status &lt;&gt; 'closed'）。</li>
 *   <li><b>uk 兜底</b>：DuplicateKeyException → BizException(400, "风险已存在: xxx")（统一形态）。</li>
 *   <li><b>不限层</b>：/api/v1/risks 不注册 LayerGuardInterceptor（零改动即天然不限层）。</li>
 * </ul></p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskServiceImpl extends ServiceImpl<RiskMapper, Risk> implements RiskService {

    private final ProgramMapper programMapper;
    private final ProjectMapper projectMapper;
    private final CaseService caseService;
    private final AuditService auditService;

    private static final com.fasterxml.jackson.databind.ObjectMapper OM =
            new com.fasterxml.jackson.databind.ObjectMapper();

    // ==================== CRUD（R1~R5） ====================

    @Override
    public Risk create(Risk risk) {
        int riskValue = validateForWrite(risk);
        applyComputed(risk, riskValue);
        risk.setStatus(RiskStatus.OPEN.dbValue());
        risk.setResolutionNote(null);
        try {
            save(risk);
        } catch (DuplicateKeyException e) {
            throw new BizException(400, "风险已存在: " + risk.getRiskName());
        }
        Map<String, Object> detail = writeDetail(risk);
        detail.put("operator", operatorName());
        audit("risk_create", risk.getId(), detail);
        return risk;
    }

    @Override
    public Risk edit(Long id, Risk patch) {
        Risk exist = loadOr404(id);
        // 编辑限制（AC-F1.4 Then）：closed 终态只读，编辑任意字段 → 400
        if (RiskStatus.CLOSED.dbValue().equals(exist.getStatus())) {
            throw new BizException(400, "closed 为终态只读，不可编辑（复燃风险请新建条目并互链描述）");
        }
        int riskValue = validateForWrite(patch);
        applyComputed(patch, riskValue);
        // LambdaUpdateWrapper 显式 set：可空字段（mitigation/reviewDate/relatedObjects 等）
        // 置 null 必须落库（PUT 全量语义）；非 closed 状态 resolutionNote 强制置 NULL（V7 列契约）
        try {
            update(new LambdaUpdateWrapper<Risk>()
                    .eq(Risk::getId, id)
                    .set(Risk::getRiskName, patch.getRiskName())
                    .set(Risk::getCategory, patch.getCategory())
                    .set(Risk::getProbability, patch.getProbability())
                    .set(Risk::getImpact, patch.getImpact())
                    .set(Risk::getRiskValue, patch.getRiskValue())
                    .set(Risk::getRiskLevel, patch.getRiskLevel())
                    .set(Risk::getDescription, patch.getDescription())
        .set(Risk::getMitigation, patch.getMitigation())
                    .set(Risk::getContingencyPlan, patch.getContingencyPlan())
                    .set(Risk::getOwner, patch.getOwner())
                    .set(Risk::getRelatedObjects, patch.getRelatedObjects())
                    .set(Risk::getReviewDate, patch.getReviewDate())
                    .set(Risk::getResolutionNote, null));
        } catch (DuplicateKeyException e) {
            throw new BizException(400, "风险已存在: " + patch.getRiskName());
        }
        Map<String, Object> detail = writeDetail(patch);
        detail.put("operator", operatorName());
        audit("risk_update", id, detail);
        return getById(id);
    }

    @Override
    public void remove(Long id) {
        Risk exist = loadOr404(id);
        removeById(id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("riskName", exist.getRiskName());
        detail.put("category", exist.getCategory());
        detail.put("riskValue", exist.getRiskValue());
        detail.put("riskLevel", exist.getRiskLevel());
        detail.put("status", exist.getStatus());
        detail.put("operator", operatorName());
        audit("risk_delete", id, detail);
    }

    @Override
    public RiskVo detailVo(Long id) {
        Risk risk = loadOr404(id);
        RiskVo vo = toVo(risk);
        vo.setRelatedObjects(resolveRelatedObjects(parseRelatedObjects(risk.getRelatedObjects())));
        return vo;
    }

    @Override
    public Risk loadOr404(Long id) {
        Risk risk = getById(id);
        if (risk == null) {
            throw new BizException(404, "风险不存在: " + id);
        }
        return risk;
    }

    // ==================== 查询（R1） ====================

    @Override
    public IPage<RiskVo> pageFilter(String category, String level, String status, Boolean overdueOnly,
                                    String keyword, long page, long size) {
        LambdaQueryWrapper<Risk> w = new LambdaQueryWrapper<>();
        if (category != null && !category.isBlank()) {
            w.eq(Risk::getCategory, category.trim());
        }
        if (level != null && !level.isBlank()) {
            w.eq(Risk::getRiskLevel, level.trim());
        }
        if (status != null && !status.isBlank()) {
            w.eq(Risk::getStatus, status.trim());
        }
        if (Boolean.TRUE.equals(overdueOnly)) {
            // SQL 口径（AC-F1.7/D-10）：review_date < CURRENT_DATE AND status <> 'closed'
            //（lt 对 NULL 行恒 false——空复评日期天然不逾期，与 VO 判定同口径）
            w.lt(Risk::getReviewDate, LocalDate.now())
                    .ne(Risk::getStatus, RiskStatus.CLOSED.dbValue());
        }
        if (keyword != null && !keyword.isBlank()) {
            w.like(Risk::getRiskName, keyword);
        }
        // 默认排序写死（QA 断言用）：风险值降序，并列按 id 降序（新建在前，PRD §4.1.2）
        w.orderByDesc(Risk::getRiskValue).orderByDesc(Risk::getId);
        return page(new Page<>(page, size), w).convert(this::toVo);
    }

    // ==================== 状态流转（R6，T10） ====================

    @Override
    public RiskVo transit(Long id, String target, String resolutionNote) {
        RiskStatus to = RiskStatus.fromDbValue(target);
        if (to == null) {
            throw new BizException(400, "未知状态 " + target + "（合法值: "
                    + RiskStatus.OPEN.dbValue() + "/" + RiskStatus.MITIGATING.dbValue()
                    + "/" + RiskStatus.CLOSED.dbValue() + "）");
        }
        Risk exist = loadOr404(id);
        RiskStatus from = RiskStatus.fromDbValue(exist.getStatus());
        if (from == null) {
            throw new BizException(400, "风险状态列脏数据: " + exist.getStatus());
        }
        // 幂等短路：流转到自身合法直接返回（并发重试语义，不更新不审计，StandardStatus 先例）
        if (from == to) {
            return detailVo(id);
        }
        if (!from.canTransitionTo(to)) {
            if (from.isTerminal()) {
                throw new BizException(400, "closed 为终态，不可流转（复燃风险请新建条目并互链描述）");
            }
            throw new BizException(400, "非法状态流转 " + from.dbValue() + "→" + to.dbValue()
                    + "（open→closed 跳级非法，须先经 mitigating）");
        }
        // 必填项校验（规则内聚枚举，AC-F1.4）：→closed 必填处置说明
        if (RiskStatus.requiredFieldsFor(to).contains(RiskStatus.RequiredField.RESOLUTION_NOTE)
                && (resolutionNote == null || resolutionNote.isBlank())) {
            throw new BizException(400, "resolutionNote 必填（关闭风险必须填写处置说明）");
        }
        // 落库：status + resolutionNote（非 closed 目标强制置 NULL，V7 列契约）
        update(new LambdaUpdateWrapper<Risk>()
                .eq(Risk::getId, id)
                .set(Risk::getStatus, to.dbValue())
                .set(Risk::getResolutionNote, to == RiskStatus.CLOSED ? resolutionNote.trim() : null));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("riskName", exist.getRiskName());
        detail.put("from", from.dbValue());
        detail.put("to", to.dbValue());
        detail.put("operator", operatorName());
        if (resolutionNote != null && !resolutionNote.isBlank()) {
            detail.put("resolutionNote", resolutionNote);
        }
        audit("risk_transit", id, detail);
        return detailVo(id);
    }

    // ==================== 看板聚合（R7，T12） ====================

    @Override
    public RiskDashboardVo dashboard() {
        // 查询①：GROUP BY (probability, impact) 计数（仅未 closed，AC-F1.12）
        Map<String, Long> cellCount = new LinkedHashMap<>();
        Map<String, Long> levelCount = new LinkedHashMap<>();
        for (RiskLevel lv : RiskLevel.values()) {
            levelCount.put(lv.dbValue(), 0L);
        }
        for (RiskMapper.HeatCell cell : baseMapper.selectHeatCells()) {
            if (cell.getProbability() == null || cell.getImpact() == null || cell.getCount() == null) {
                continue;
            }
            cellCount.put(cellKey(cell.getProbability(), cell.getImpact()), cell.getCount());
            // 等级分布由 (P,I)→level 推导（单查双用，AC-F1.13）
            RiskLevel lv = RiskLevel.ofValue(cell.getProbability() * cell.getImpact());
            levelCount.merge(lv.dbValue(), cell.getCount(), Long::sum);
        }
        // 补齐 25 格（P1~5 × I1~5 全组合，未命中格 count=0；cells 元组自描述 X=影响/Y=概率）
        List<RiskDashboardVo.Cell> cells = new ArrayList<>(25);
        for (int p = 1; p <= 5; p++) {
            for (int i = 1; i <= 5; i++) {
                RiskDashboardVo.Cell c = new RiskDashboardVo.Cell();
                c.setProbability(p);
                c.setImpact(i);
                c.setRiskValue(p * i);
                c.setRiskLevel(RiskCalculator.riskLevel(p * i).dbValue());
                c.setCount(cellCount.getOrDefault(cellKey(p, i), 0L));
                cells.add(c);
            }
        }
        // 查询②：高风险清单（level∈{high,critical} 未 closed，riskValue DESC, id DESC，AC-F1.14）
        List<Risk> highs = list(new LambdaQueryWrapper<Risk>()
                .in(Risk::getRiskLevel, RiskLevel.HIGH.dbValue(), RiskLevel.CRITICAL.dbValue())
                .ne(Risk::getStatus, RiskStatus.CLOSED.dbValue())
                .orderByDesc(Risk::getRiskValue).orderByDesc(Risk::getId));
        List<RiskDashboardVo.HighRisk> highRisks = new ArrayList<>(highs.size());
        for (Risk r : highs) {
            RiskDashboardVo.HighRisk h = new RiskDashboardVo.HighRisk();
            h.setId(r.getId());
            h.setRiskName(r.getRiskName());
            h.setProbability(r.getProbability() == null ? null : r.getProbability().intValue());
            h.setImpact(r.getImpact() == null ? null : r.getImpact().intValue());
            h.setRiskValue(r.getRiskValue());
            h.setRiskLevel(r.getRiskLevel());
            h.setOwner(r.getOwner());
            h.setStatus(r.getStatus());
            h.setReviewDate(r.getReviewDate());
            h.setOverdue(isOverdue(r));
            highRisks.add(h);
        }
        RiskDashboardVo vo = new RiskDashboardVo();
        vo.setCells(cells);
        vo.setLevelDistribution(levelCount);
        vo.setHighRisks(highRisks);
        return vo;
    }

    @Override
    public RiskVo toVo(Risk risk) {
        RiskVo vo = new RiskVo();
        vo.setId(risk.getId());
        vo.setRiskName(risk.getRiskName());
        vo.setCategory(risk.getCategory());
        vo.setProbability(risk.getProbability() == null ? null : risk.getProbability().intValue());
        vo.setImpact(risk.getImpact() == null ? null : risk.getImpact().intValue());
        vo.setRiskValue(risk.getRiskValue());
        vo.setRiskLevel(risk.getRiskLevel());
        vo.setDescription(risk.getDescription());
        vo.setMitigation(risk.getMitigation());
        vo.setContingencyPlan(risk.getContingencyPlan());
        vo.setOwner(risk.getOwner());
        vo.setStatus(risk.getStatus());
        vo.setResolutionNote(risk.getResolutionNote());
        vo.setReviewDate(risk.getReviewDate());
        vo.setOverdue(isOverdue(risk));
        vo.setCreateBy(risk.getCreateBy());
        vo.setCreateTime(risk.getCreateTime());
        vo.setUpdateTime(risk.getUpdateTime());
        return vo;
    }

    // ==================== 内部工具 ====================

    /**
     * 写入前校验（AC-F1.1/F1.3/F1.6）：riskName/category/owner 必填 + category 枚举 +
     * P·I 1~5 整数（BigDecimal 整数性判校在计算前）+ relatedObjects 逐条存在性。
     *
     * @return 重算后的风险值（调用方落库）
     */
    private int validateForWrite(Risk risk) {
        if (risk == null) {
            throw new BizException(400, "请求体不能为空");
        }
        requireText(risk.getRiskName(), "riskName");
        if (risk.getRiskName().length() > 200) {
            throw new BizException(400, "riskName 长度不能超过 200 字符");
        }
        requireText(risk.getCategory(), "category");
        if (RiskCategory.fromDbValue(risk.getCategory()) == null) {
            throw new BizException(400, "category 非法: " + risk.getCategory()
                    + "（应为 " + RiskCategory.legalValues() + "）");
        }
        requireText(risk.getOwner(), "owner");
        validateRelatedObjects(risk.getRelatedObjects());
        // 先校验后计算（AC-F1.3：0/6/1.5/负数在计算前 400 指名）
        return RiskCalculator.riskValue(risk.getProbability(), risk.getImpact());
    }

    /** 计算列重算覆盖落库载体（AC-F1.5）：riskValue/riskLevel 恒=公式值；P/I 归一整数值。 */
    private void applyComputed(Risk risk, int riskValue) {
        risk.setProbability(BigDecimal.valueOf(
                risk.getProbability().stripTrailingZeros().intValueExact()));
        risk.setImpact(BigDecimal.valueOf(risk.getImpact().stripTrailingZeros().intValueExact()));
        risk.setRiskValue(riskValue);
        risk.setRiskLevel(RiskCalculator.riskLevel(riskValue).dbValue());
    }

    /**
     * relatedObjects 逐条存在性校验（AC-F1.6）：非法 type/不存在 id → 400 指名；空/不填合法。
     *
     * <p><b>id 语义</b>（编排者契约对齐裁决，2026-09-04）：program/project=数字主键字符串；
     * case=对外业务键 caseId（t_case.case_id VARCHAR，按 caseId 查存在性——与既有 API
     * 惯例一致，前端提交字符串）。</p>
     */
    private void validateRelatedObjects(String relatedObjectsJson) {
        for (RelatedObjectVo item : parseRelatedObjects(relatedObjectsJson)) {
            boolean exists;
            switch (item.getType() == null ? "" : item.getType()) {
                case "program" -> exists = programMapper.selectById(parseNumericId(item.getId(), "program")) != null;
                case "project" -> exists = projectMapper.selectById(parseNumericId(item.getId(), "project")) != null;
                case "case" -> exists = loadByCaseId(item.getId()) != null;
                default -> throw new BizException(400, "relatedObjects.type 非法: " + item.getType()
                        + "（应为 program/project/case）");
            }
            if (!exists) {
                throw new BizException(400, "关联对象不存在: " + item.getType() + " " + item.getId()
                        + "（租户内或已删除）");
            }
        }
    }

    /** JSON 数组解析（null/坏 JSON → 空列表容忍降级，同 Standard.parseCodes 先例）。 */
    static List<RelatedObjectVo> parseRelatedObjects(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            RelatedObjectVo[] arr = OM.readValue(json, RelatedObjectVo[].class);
            return arr == null ? List.of() : List.of(arr);
        } catch (Exception e) {
            log.warn("[Risk] relatedObjects JSON 解析失败（容忍降级空列表）: {}", json);
            return List.of();
        }
    }

    /**
     * 详情关联对象解析（AC-F1.6）：逐条查当前名；悬空/已逻辑删（MP @TableLogic 下
     * selectById 返回 null）→ deleted=true 占位（name=null），不 400 不静默丢。
     * id 语义同 {@link #validateRelatedObjects}（program/project 数字字符串、case=caseId）。
     */
    private List<RelatedObjectVo> resolveRelatedObjects(List<RelatedObjectVo> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<RelatedObjectVo> resolved = new ArrayList<>(items.size());
        for (RelatedObjectVo item : items) {
            RelatedObjectVo vo = new RelatedObjectVo();
            vo.setType(item.getType());
            vo.setId(item.getId());
            String name = null;
            boolean deleted = true;
            Long numericId;
            switch (item.getType() == null ? "" : item.getType()) {
                case "program" -> {
                    numericId = parseNumericIdNullable(item.getId());
                    Program pg = numericId == null ? null : programMapper.selectById(numericId);
                    if (pg != null) {
                        name = pg.getName();
                        deleted = false;
                    }
                }
                case "project" -> {
                    numericId = parseNumericIdNullable(item.getId());
                    Project pj = numericId == null ? null : projectMapper.selectById(numericId);
                    if (pj != null) {
                        name = pj.getName();
                        deleted = false;
                    }
                }
                case "case" -> {
                    Case c = loadByCaseId(item.getId());
                    if (c != null) {
                        name = c.getTitle();
                        deleted = false;
                    }
                }
                default -> {
                    // 历史 type 脏数据（保存时已挡新增）：悬空占位不 400（读路径容忍）
                }
            }
            vo.setName(name);
            vo.setDeleted(deleted);
            resolved.add(vo);
        }
        return resolved;
    }

    /** 按对外业务键 caseId 查 Case（t_case 走租户拦截器 + @TableLogic；空/坏值返回 null）。 */
    private Case loadByCaseId(String caseId) {
        if (caseId == null || caseId.isBlank()) {
            return null;
        }
        return caseService.getOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Case>()
                .eq(Case::getCaseId, caseId).last("LIMIT 1"));
    }

    /** program/project 数字 id 解析（非法数字 → 400 指名）。 */
    private static Long parseNumericId(String id, String type) {
        Long v = parseNumericIdNullable(id);
        if (v == null) {
            throw new BizException(400, "relatedObjects.id 非法: " + id
                    + "（type=" + type + " 须为数字 id 字符串）");
        }
        return v;
    }

    /** 数字 id 宽松解析（null/非数字返回 null——读路径悬空占位用，写路径 400）。 */
    private static Long parseNumericIdNullable(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(id.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 逾期服务端判定（D-10，AC-F1.7）：reviewDate&lt;今天 且 未 closed；closed 后恒 false。 */
    static boolean isOverdue(Risk risk) {
        return risk.getReviewDate() != null
                && risk.getReviewDate().isBefore(LocalDate.now())
                && !RiskStatus.CLOSED.dbValue().equals(risk.getStatus());
    }

    private static String cellKey(int probability, int impact) {
        return probability + ":" + impact;
    }

    private Map<String, Object> writeDetail(Risk risk) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("riskName", risk.getRiskName());
        detail.put("category", risk.getCategory());
        detail.put("probability", risk.getProbability());
        detail.put("impact", risk.getImpact());
        detail.put("riskValue", risk.getRiskValue());
        detail.put("riskLevel", risk.getRiskLevel());
        detail.put("relatedObjects", parseRelatedObjects(risk.getRelatedObjects()));
        return detail;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(400, field + " 不能为空");
        }
    }

    /** 操作者（审计 detail 用，AC-AUDIT.1；AuditService 侧亦从 LoginUser 取，双保险）。 */
    private static String operatorName() {
        JwtClaims claims = LoginUser.get();
        return claims != null ? claims.getUsername() : null;
    }

    private void audit(String action, Long id, Object detail) {
        String json;
        try {
            json = OM.writeValueAsString(detail);
        } catch (Exception e) {
            json = "{}";
        }
        auditService.log(action, "risk", String.valueOf(id), json);
    }
}
