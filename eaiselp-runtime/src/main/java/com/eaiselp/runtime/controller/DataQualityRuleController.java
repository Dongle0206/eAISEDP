package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.runtime.governance.DataQualityRule;
import com.eaiselp.runtime.governance.DataQualityRuleService;
import com.eaiselp.runtime.governance.dto.DataQualityRuleVo;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 数据质量规则 REST API（case-20260820 T5，路径前缀 /api/v1/data-quality-rules，
 * 契约=api-contracts §4）。
 *
 * <p><b>不限层</b>（AC-SWITCH.1）：不注册 LayerGuard。薄控制器：assetId/枚举/threshold/uk
 * 校验与登记覆盖式更新全在 {@link DataQualityRuleService}。</p>
 *
 * <p>权限（V6 seed 1068~1070）：读 {@code dqrule:view}、建 {@code dqrule:create}、
 * 改/删/登记 {@code dqrule:edit}。写审计（dqrule_create/update/delete/check_result）在 Service。</p>
 */
@RestController
@RequestMapping("/api/v1/data-quality-rules")
@RequiredArgsConstructor
public class DataQualityRuleController {

    private final DataQualityRuleService ruleService;

    /** 列表筛选（Q1）：checkType / lastResult / assetId / keyword（ruleName LIKE）。 */
    @GetMapping
    @RequirePermission("dqrule:view")
    public R<IPage<DataQualityRuleVo>> page(@RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "20") long size,
                                            @RequestParam(required = false) String checkType,
                                            @RequestParam(required = false) String lastResult,
                                            @RequestParam(required = false) Long assetId,
                                            @RequestParam(required = false) String keyword) {
        return R.ok(ruleService.pageFilter(checkType, lastResult, assetId, keyword, page, size));
    }

    /** 创建规则（Q2）：assetId 存在且未删 / checkType 枚举 / threshold ∈ [0,100]。 */
    @PostMapping
    @RequirePermission("dqrule:create")
    public R<DataQualityRuleVo> create(@RequestBody RuleSaveRequest req) {
        DataQualityRule created = ruleService.create(toEntity(req));
        return R.ok(ruleService.detailVo(created.getId()));
    }

    /** 规则详情（Q3）：全字段 + 关联资产摘要。 */
    @GetMapping("/{id}")
    @RequirePermission("dqrule:view")
    public R<DataQualityRuleVo> get(@PathVariable Long id) {
        return R.ok(ruleService.detailVo(id));
    }

    /** 编辑规则（Q4，校验同 Q2；last_* 不在编辑改写）。 */
    @PutMapping("/{id}")
    @RequirePermission("dqrule:edit")
    public R<DataQualityRuleVo> update(@PathVariable Long id, @RequestBody RuleSaveRequest req) {
        DataQualityRule updated = ruleService.edit(id, toEntity(req));
        return R.ok(ruleService.detailVo(updated.getId()));
    }

    /** 逻辑删（Q5）。 */
    @DeleteMapping("/{id}")
    @RequirePermission("dqrule:edit")
    public R<Void> delete(@PathVariable Long id) {
        ruleService.remove(id);
        return R.ok();
    }

    /** 登记最近检查结果（Q6，覆盖式更新）：result pass|fail 登记人判定，平台不按阈值自动判定。 */
    @PostMapping("/{id}/check-results")
    @RequirePermission("dqrule:edit")
    public R<DataQualityRuleVo> registerCheckResult(@PathVariable Long id,
                                                    @RequestBody CheckResultRequest req) {
        return R.ok(ruleService.registerCheckResult(id, req.getResult(), req.getActualValue(),
                req.getCheckTime(), req.getRemark()));
    }

    // ------------------------------------------------------------------
    // 请求 DTO 与内部工具
    // ------------------------------------------------------------------

    /** 规则创建/编辑请求（契约 §4 Q2）。 */
    @Data
    public static class RuleSaveRequest {
        private String ruleName;
        private Long assetId;
        /** completeness / accuracy / consistency / timeliness */
        private String checkType;
        /** 百分比达标线 0~100（边界 0 与 100 合法） */
        private BigDecimal threshold;
    }

    /** 登记最近检查结果请求（契约 §4 Q6）。 */
    @Data
    public static class CheckResultRequest {
        /** pass / fail（登记人判定） */
        private String result;
        /** 实测值（可选） */
        private BigDecimal actualValue;
        /** 检查时间（可选，缺省当前时刻）；yyyy-MM-dd HH:mm:ss */
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime checkTime;
        /** 备注（如"字段缺失"） */
        private String remark;
    }

    /** 请求 → 实体（last_* 不映射——登记走独立端点 Q6）。 */
    private static DataQualityRule toEntity(RuleSaveRequest req) {
        DataQualityRule r = new DataQualityRule();
        r.setRuleName(req.getRuleName());
        r.setAssetId(req.getAssetId());
        r.setCheckType(req.getCheckType());
        r.setThreshold(req.getThreshold());
        return r;
    }
}
