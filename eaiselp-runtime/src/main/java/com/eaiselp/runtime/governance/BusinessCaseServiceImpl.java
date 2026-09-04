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
import com.eaiselp.runtime.governance.dto.BusinessCaseVo;
import com.eaiselp.runtime.governance.dto.PortfolioVo;
import com.eaiselp.runtime.governance.dto.RelatedStrategyVo;
import com.eaiselp.runtime.hierarchy.Strategy;
import com.eaiselp.runtime.hierarchy.StrategyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 商业案例服务实现（case-20260821 T4 CRUD / T11 状态机与决策记录 / T13 组合聚合）。
 *
 * <p><b>要点</b>：
 * <ul>
 *   <li><b>计算列防伪造链</b>（AC-F2.4）：netBenefit/paybackYears/roiPercent/riceScore 由
 *       {@link BizCaseCalculator} 在 create/edit 写库前重算覆盖——入参 DTO 无此四字段，
 *       提交 rice_score=999 连绑定入口都没有，库值恒=公式值。编辑走 LambdaUpdateWrapper
 *       显式 set（payback/roi 可变为 NULL——N/A 边界，MP updateById 忽略 null 会让旧值残留）。</li>
 *   <li><b>状态机</b>（AC-F2.6，D-5）：draft→approved/rejected（必填原因）、approved→executing、
 *       executing→done；跳级/批准后撤销/终态出边 400；自流转幂等短路。</li>
 *   <li><b>编辑/删除限制</b>（AC-F2.7）：draft 全改且重算；approved/executing 改输入 →
 *       400"已批准，输入不可改"（decisionNote 走 B6）；rejected/done 全只读；仅 draft 可删。</li>
 *   <li><b>关联战略</b>（AC-F2.8，裁决 Q4）：relatedStrategyIds 存 t_strategy.id，逐 id
 *       存在性校验（注入 hierarchy StrategyMapper）；战略逻辑删 → 详情 deleted 占位，
 *       计算与流转不受影响。strategyId 筛选 = JSON 内存过滤（分页后，V6 principleCode 口径）。</li>
 *   <li><b>uk 兜底</b>：DuplicateKeyException → 400 "案例已存在: xxx"（统一形态）。</li>
 * </ul></p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessCaseServiceImpl extends ServiceImpl<BusinessCaseMapper, BusinessCase>
        implements BusinessCaseService {

    private final StrategyMapper strategyMapper;
    private final AuditService auditService;

    private static final com.fasterxml.jackson.databind.ObjectMapper OM =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** 投资口径状态集（AC-F2.11，裁决 Q8：draft 未决策、rejected 已否决不计钱） */
    private static final List<String> INVESTMENT_STATUS = List.of("approved", "executing", "done");

    // ==================== CRUD（B1~B5） ====================

    @Override
    public BusinessCase create(BusinessCase bizCase) {
        Computed computed = validateForWrite(bizCase);
        applyComputed(bizCase, computed);
        bizCase.setStatus(BizCaseStatus.DRAFT.dbValue());
        bizCase.setRejectedReason(null);
        bizCase.setDecisionNote(null);
        try {
            save(bizCase);
        } catch (DuplicateKeyException e) {
            throw new BizException(400, "案例已存在: " + bizCase.getCaseName());
        }
        Map<String, Object> detail = writeDetail(bizCase);
        detail.put("operator", operatorName());
        audit("bizcase_create", bizCase.getId(), detail);
        return bizCase;
    }

    @Override
    public BusinessCase edit(Long id, BusinessCase patch) {
        BusinessCase exist = loadOr404(id);
        BizCaseStatus st = requireKnownStatus(exist);
        // 编辑限制（AC-F2.7）：draft 专属；approved/executing 输入只读（仅 B6 决策记录可更新）；
        // rejected/done 终态全只读
        if (st != BizCaseStatus.DRAFT) {
            if (st == BizCaseStatus.REJECTED || st == BizCaseStatus.DONE) {
                throw new BizException(400, st.dbValue() + " 为终态只读，不可编辑（审计留痕承担追溯）");
            }
            throw new BizException(400, "已批准，输入不可改，请复盘或新建案例（决策记录请走 decision-note 端点）");
        }
        Computed computed = validateForWrite(patch);
        applyComputed(patch, computed);
        try {
            // 显式 set：payback/roi 可为 NULL（N/A 边界），updateById 忽略 null 会让旧计算值残留
            update(new LambdaUpdateWrapper<BusinessCase>()
                    .eq(BusinessCase::getId, id)
                    .set(BusinessCase::getCaseName, patch.getCaseName())
                    .set(BusinessCase::getDescription, patch.getDescription())
                    .set(BusinessCase::getRelatedStrategyIds, patch.getRelatedStrategyIds())
                    .set(BusinessCase::getOnetimeCost, patch.getOnetimeCost())
                    .set(BusinessCase::getAnnualOpCost, patch.getAnnualOpCost())
                    .set(BusinessCase::getAnnualBenefit, patch.getAnnualBenefit())
                    .set(BusinessCase::getNetBenefit, patch.getNetBenefit())
                    .set(BusinessCase::getPaybackYears, patch.getPaybackYears())
                    .set(BusinessCase::getRoiPercent, patch.getRoiPercent())
                    .set(BusinessCase::getReach, patch.getReach())
                    .set(BusinessCase::getImpact, patch.getImpact())
                    .set(BusinessCase::getConfidence, patch.getConfidence())
                    .set(BusinessCase::getEffort, patch.getEffort())
                    .set(BusinessCase::getRiceScore, patch.getRiceScore())
                    // status/rejectedReason/decisionNote 不在编辑改写（流转/B6 专属）
            );
        } catch (DuplicateKeyException e) {
            throw new BizException(400, "案例已存在: " + patch.getCaseName());
        }
        Map<String, Object> detail = writeDetail(patch);
        detail.put("operator", operatorName());
        audit("bizcase_update", id, detail);
        return getById(id);
    }

    @Override
    public void remove(Long id) {
        BusinessCase exist = loadOr404(id);
        BizCaseStatus st = requireKnownStatus(exist);
        // 删除限制（AC-F2.7 Then）：仅 draft 可逻辑删——已进入决策/执行流的案例是合规资产，只能留痕
        if (st != BizCaseStatus.DRAFT) {
            throw new BizException(400, "非 draft 不可删除（已进入决策/执行流的案例是合规资产，只能留痕）");
        }
        removeById(id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("caseName", exist.getCaseName());
        detail.put("status", exist.getStatus());
        detail.put("riceScore", exist.getRiceScore());
        detail.put("operator", operatorName());
        audit("bizcase_delete", id, detail);
    }

    @Override
    public BusinessCaseVo detailVo(Long id) {
        BusinessCase exist = loadOr404(id);
        BusinessCaseVo vo = toVo(exist);
        vo.setRelatedStrategies(resolveRelatedStrategies(parseStrategyIds(exist.getRelatedStrategyIds())));
        return vo;
    }

    @Override
    public BusinessCase loadOr404(Long id) {
        BusinessCase bizCase = getById(id);
        if (bizCase == null) {
            throw new BizException(404, "案例不存在: " + id);
        }
        return bizCase;
    }

    // ==================== 查询（B1） ====================

    @Override
    public IPage<BusinessCaseVo> pageFilter(String status, Long strategyId, String keyword,
                                             long page, long size) {
        LambdaQueryWrapper<BusinessCase> w = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            w.eq(BusinessCase::getStatus, status.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            w.like(BusinessCase::getCaseName, keyword);
        }
        // 默认排序写死（QA 断言用）：RICE 降序，并列按 id 降序（AC-F2.10）
        w.orderByDesc(BusinessCase::getRiceScore).orderByDesc(BusinessCase::getId);
        IPage<BusinessCase> entityPage = page(new Page<>(page, size), w);
        IPage<BusinessCaseVo> result = entityPage.convert(this::toVo);
        // strategyId = related_strategy_ids JSON 列内存过滤（分页后过滤，V6 principleCode 口径，
        // 供战略反向区复用 D-8；JSON 不进 SQL——方言绑定+索引无效）
        if (strategyId != null) {
            List<BusinessCaseVo> filtered = result.getRecords().stream()
                    .filter(vo -> vo.getRelatedStrategyIds() != null
                            && vo.getRelatedStrategyIds().contains(strategyId))
                    .toList();
            result.setRecords(filtered);
            result.setTotal(filtered.size());
        }
        return result;
    }

    // ==================== 决策记录（B6，T11） ====================

    @Override
    public BusinessCaseVo updateDecisionNote(Long id, String decisionNote) {
        if (decisionNote == null || decisionNote.isBlank()) {
            throw new BizException(400, "decisionNote 不能为空");
        }
        BusinessCase exist = loadOr404(id);
        BizCaseStatus st = requireKnownStatus(exist);
        // 更新限制（AC-F2.7）：draft/approved/executing 可更新（执行期进展记录）；终态 400
        if (st.isTerminal()) {
            throw new BizException(400, st.dbValue() + " 为终态，决策记录不可更新");
        }
        update(new LambdaUpdateWrapper<BusinessCase>()
                .eq(BusinessCase::getId, id)
                .set(BusinessCase::getDecisionNote, decisionNote));
        // 审计 detail 含旧值→新值（覆盖式唯一留痕，AC-AUDIT.1）
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("caseName", exist.getCaseName());
        detail.put("oldDecisionNote", exist.getDecisionNote());
        detail.put("newDecisionNote", decisionNote);
        detail.put("operator", operatorName());
        audit("bizcase_decision_note", id, detail);
        return detailVo(id);
    }

    // ==================== 状态流转（B7，T11） ====================

    @Override
    public BusinessCaseVo transit(Long id, String target, String rejectedReason) {
        BizCaseStatus to = BizCaseStatus.fromDbValue(target);
        if (to == null) {
            throw new BizException(400, "未知状态 " + target + "（合法值: draft/approved/"
                    + "rejected/executing/done）");
        }
        BusinessCase exist = loadOr404(id);
        BizCaseStatus from = requireKnownStatus(exist);
        // 幂等短路：流转到自身合法直接返回（并发重试语义，不更新不审计）
        if (from == to) {
            return detailVo(id);
        }
        if (!from.canTransitionTo(to)) {
            if (from.isTerminal()) {
                throw new BizException(400, from.dbValue() + " 为终态，不可流转（审计留痕承担追溯）");
            }
            throw new BizException(400, "非法状态流转 " + from.dbValue() + "→" + to.dbValue()
                    + "（跳级非法；批准后不可撤销，要拒走新案例或执行后复盘）");
        }
        // 必填项校验（规则内聚枚举，AC-F2.6）：→rejected 必填原因
        if (BizCaseStatus.requiredFieldsFor(to).contains(BizCaseStatus.RequiredField.REJECTED_REASON)
                && (rejectedReason == null || rejectedReason.isBlank())) {
            throw new BizException(400, "rejectedReason 必填（拒绝案例必须填写原因）");
        }
        update(new LambdaUpdateWrapper<BusinessCase>()
                .eq(BusinessCase::getId, id)
                .set(BusinessCase::getStatus, to.dbValue())
                .set(BusinessCase::getRejectedReason,
                        to == BizCaseStatus.REJECTED ? rejectedReason.trim() : null));
        // 审计（§8.1）：from→to + 操作者 + rejectedReason/decisionNote 快照 + 计算字段
        Map<String, Object> detail = writeDetail(exist);
        detail.put("from", from.dbValue());
        detail.put("to", to.dbValue());
        detail.put("operator", operatorName());
        if (rejectedReason != null && !rejectedReason.isBlank()) {
            detail.put("rejectedReason", rejectedReason);
        }
        if (exist.getDecisionNote() != null) {
            detail.put("decisionNote", exist.getDecisionNote());
        }
        audit("bizcase_transit", id, detail);
        return detailVo(id);
    }

    // ==================== 投资组合聚合（B8，T13） ====================

    @Override
    public PortfolioVo portfolio(long page, long size) {
        PortfolioVo vo = new PortfolioVo();
        // cases 全量（含 rejected/done，riceScore DESC, id DESC——AC-F2.10）
        vo.setCases(pageFilter(null, null, null, page, size));
        // summary 投资口径（status∈{approved,executing,done}，空集 COALESCE 0——AC-F2.11）
        PortfolioVo.Summary summary = baseMapper.selectInvestmentSummary();
        if (summary == null) {
            summary = new PortfolioVo.Summary();
            summary.setTotalOnetimeCost(BigDecimal.ZERO);
            summary.setTotalAnnualOpCost(BigDecimal.ZERO);
            summary.setTotalAnnualNetBenefit(BigDecimal.ZERO);
        }
        // 总 3 年净收益 = 3×Σnet（与 ROI 3 年口径一致，AC-F2.11）
        summary.setTotalThreeYearNetBenefit(
                summary.getTotalAnnualNetBenefit().multiply(BigDecimal.valueOf(3)));
        vo.setSummary(summary);
        // statusDistribution 全量五态（与汇总口径有意不同——同用例双断言，AC-F2.12）
        Map<String, Long> distribution = new LinkedHashMap<>();
        for (BizCaseStatus s : BizCaseStatus.values()) {
            distribution.put(s.dbValue(), 0L);
        }
        for (PortfolioVo.StatusCount sc : baseMapper.selectStatusDistribution()) {
            if (sc.getStatus() != null && sc.getCnt() != null) {
                distribution.put(sc.getStatus(), sc.getCnt());
            }
        }
        vo.setStatusDistribution(distribution);
        return vo;
    }

    @Override
    public BusinessCaseVo toVo(BusinessCase bizCase) {
        BusinessCaseVo vo = new BusinessCaseVo();
        vo.setId(bizCase.getId());
        vo.setCaseName(bizCase.getCaseName());
        vo.setDescription(bizCase.getDescription());
        vo.setRelatedStrategyIds(parseStrategyIds(bizCase.getRelatedStrategyIds()));
        vo.setOnetimeCost(bizCase.getOnetimeCost());
        vo.setAnnualOpCost(bizCase.getAnnualOpCost());
        vo.setAnnualBenefit(bizCase.getAnnualBenefit());
        vo.setNetBenefit(bizCase.getNetBenefit());
        vo.setPaybackYears(bizCase.getPaybackYears());
        vo.setRoiPercent(bizCase.getRoiPercent());
        vo.setRiceScore(bizCase.getRiceScore());
        vo.setReach(bizCase.getReach() == null ? null : bizCase.getReach().intValue());
        vo.setImpact(bizCase.getImpact() == null ? null : bizCase.getImpact().intValue());
        vo.setConfidence(bizCase.getConfidence());
        vo.setEffort(bizCase.getEffort() == null ? null : bizCase.getEffort().intValue());
        vo.setStatus(bizCase.getStatus());
        vo.setRejectedReason(bizCase.getRejectedReason());
        vo.setDecisionNote(bizCase.getDecisionNote());
        vo.setCreateBy(bizCase.getCreateBy());
        vo.setCreateTime(bizCase.getCreateTime());
        vo.setUpdateTime(bizCase.getUpdateTime());
        return vo;
    }

    // ==================== 内部工具 ====================

    /** 校验+计算结果载体（validateForWrite 校验通过后一次性产出四计算列）。 */
    private record Computed(BigDecimal netBenefit, BigDecimal paybackYears, BigDecimal roiPercent,
                            BigDecimal riceScore) {
    }

    /**
     * 写入前校验（AC-F2.1/F2.4/F2.5/F2.8）：caseName 必填 + 三金额 ≥0（BigDecimal）+
     * R·I·E 1~10 整数（BigDecimal 整数性判校）+ confidence 0.1 步进离散 + 战略 id 存在性；
     * 校验通过后按 PRD §4.4.1 唯一口径产出四计算列。
     */
    private Computed validateForWrite(BusinessCase bizCase) {
        if (bizCase == null) {
            throw new BizException(400, "请求体不能为空");
        }
        requireText(bizCase.getCaseName(), "caseName");
        if (bizCase.getCaseName().length() > 200) {
            throw new BizException(400, "caseName 长度不能超过 200 字符");
        }
        BizCaseCalculator.validateAmount(bizCase.getOnetimeCost(), "onetimeCost");
        BizCaseCalculator.validateAmount(bizCase.getAnnualOpCost(), "annualOpCost");
        BizCaseCalculator.validateAmount(bizCase.getAnnualBenefit(), "annualBenefit");
        int reach = BizCaseCalculator.validateFactor10(bizCase.getReach(), "reach");
        int impact = BizCaseCalculator.validateFactor10(bizCase.getImpact(), "impact");
        int effort = BizCaseCalculator.validateFactor10(bizCase.getEffort(), "effort");
        BizCaseCalculator.validateConfidence(bizCase.getConfidence());
        // 关联战略逐 id 存在性校验（AC-F2.8；跨租户 id 由租户拦截器天然过滤=不存在 400）
        for (Long sid : parseStrategyIds(bizCase.getRelatedStrategyIds())) {
            if (strategyMapper.selectById(sid) == null) {
                throw new BizException(400, "战略不存在: " + sid);
            }
        }
        BigDecimal net = BizCaseCalculator.netBenefit(bizCase.getAnnualBenefit(), bizCase.getAnnualOpCost());
        return new Computed(
                net,
                BizCaseCalculator.paybackYears(bizCase.getOnetimeCost(), net),
                BizCaseCalculator.roi(bizCase.getOnetimeCost(), net),
                BizCaseCalculator.riceScore(reach, impact, bizCase.getConfidence(), effort));
    }

    /** 计算列重算覆盖落库载体（AC-F2.4）：四计算列恒=公式值；R/I/E 归一整数值。 */
    private void applyComputed(BusinessCase bizCase, Computed computed) {
        bizCase.setReach(BigDecimal.valueOf(bizCase.getReach().stripTrailingZeros().intValueExact()));
        bizCase.setImpact(BigDecimal.valueOf(bizCase.getImpact().stripTrailingZeros().intValueExact()));
        bizCase.setEffort(BigDecimal.valueOf(bizCase.getEffort().stripTrailingZeros().intValueExact()));
        bizCase.setNetBenefit(computed.netBenefit());
        bizCase.setPaybackYears(computed.paybackYears());
        bizCase.setRoiPercent(computed.roiPercent());
        bizCase.setRiceScore(computed.riceScore());
    }

    /** 状态列解析（脏数据防御：未知值 400 而非 NPE）。 */
    private static BizCaseStatus requireKnownStatus(BusinessCase exist) {
        BizCaseStatus st = BizCaseStatus.fromDbValue(exist.getStatus());
        if (st == null) {
            throw new BizException(400, "案例状态列脏数据: " + exist.getStatus());
        }
        return st;
    }

    /** JSON id 数组解析（null/坏 JSON → 空列表容忍降级，同 Standard.parseCodes 先例）。 */
    static List<Long> parseStrategyIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Long[] arr = OM.readValue(json, Long[].class);
            return arr == null ? List.of() : List.of(arr);
        } catch (Exception e) {
            log.warn("[BizCase] relatedStrategyIds JSON 解析失败（容忍降级空列表）: {}", json);
            return List.of();
        }
    }

    /**
     * 详情关联战略解析（AC-F2.8）：逐 id 查当前标题；悬空/已逻辑删（@TableLogic 下
     * selectById 返回 null）→ deleted=true 占位——计算与流转不受影响。
     */
    private List<RelatedStrategyVo> resolveRelatedStrategies(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<RelatedStrategyVo> list = new ArrayList<>(ids.size());
        for (Long id : ids) {
            RelatedStrategyVo vo = new RelatedStrategyVo();
            vo.setId(id);
            Strategy s = id == null ? null : strategyMapper.selectById(id);
            if (s == null) {
                vo.setDeleted(true);
            } else {
                vo.setDeleted(false);
                vo.setTitle(s.getTitle());
            }
            list.add(vo);
        }
        return list;
    }

    private Map<String, Object> writeDetail(BusinessCase bizCase) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("caseName", bizCase.getCaseName());
        detail.put("onetimeCost", bizCase.getOnetimeCost());
        detail.put("annualOpCost", bizCase.getAnnualOpCost());
        detail.put("annualBenefit", bizCase.getAnnualBenefit());
        detail.put("netBenefit", bizCase.getNetBenefit());
        detail.put("paybackYears", bizCase.getPaybackYears());
        detail.put("roiPercent", bizCase.getRoiPercent());
        detail.put("riceScore", bizCase.getRiceScore());
        detail.put("relatedStrategyIds", parseStrategyIds(bizCase.getRelatedStrategyIds()));
        return detail;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(400, field + " 不能为空");
        }
    }

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
        auditService.log(action, "bizcase", String.valueOf(id), json);
    }
}
