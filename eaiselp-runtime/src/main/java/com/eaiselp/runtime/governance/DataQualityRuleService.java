package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.eaiselp.runtime.governance.dto.DataQualityRuleVo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 数据质量规则服务接口（V6 F2.2，case-20260820 T5；契约=api-contracts §4 Q1~Q6）。
 *
 * <p>CRUD（uk(tenant,ruleName)/assetId 存在且未删/checkType 枚举/threshold ∈ [0,100]）+
 * 登记最近检查结果（覆盖式更新 last_* 四列 + 审计旧值→新值）。本期不做自动探测（PRD §7-2）。</p>
 */
public interface DataQualityRuleService extends IService<DataQualityRule> {

    /**
     * 创建规则（Q2）。
     *
     * <p>ruleName/assetId/checkType/threshold 必填；assetId 存在且未逻辑删（已删资产 id → 400，
     * AC-F2.7/Q2 端校验承载）；checkType 枚举校验；threshold ∈ [0,100]（100.5/−1 → 400，
     * 边界 0/100 合法，AC-F2.5）；uk(tenant,ruleName) 冲突 400。审计 dqrule_create。</p>
     */
    DataQualityRule create(DataQualityRule rule);

    /** 编辑规则（Q4，校验同 Q2；assetId 可换绑）。审计 dqrule_update。 */
    DataQualityRule edit(Long id, DataQualityRule patch);

    /** 逻辑删（Q5）。审计 dqrule_delete。 */
    void remove(Long id);

    /** 详情 Vo（Q3：全字段 + 关联资产摘要）。跨租户/不存在 → 404。 */
    DataQualityRuleVo detailVo(Long id);

    /** 实体详情（跨租户/不存在 → 404）。 */
    DataQualityRule loadOr404(Long id);

    /**
     * 登记最近检查结果（Q6，AC-F2.6 覆盖式更新）。
     *
     * <p>result ∈ pass|fail（登记人判定，平台不按阈值自动判定）；checkTime 缺省当前时刻；
     * 覆盖 last_* 四列（单值当前态，不落历史行）；审计 dqrule_check_result detail 含
     * 旧值→新值 + 登记人（"旧值不覆盖审计"）。</p>
     *
     * @return 更新后的规则 Vo
     */
    DataQualityRuleVo registerCheckResult(Long id, String result, BigDecimal actualValue,
                                          LocalDateTime checkTime, String remark);

    /**
     * 列表筛选（Q1）：checkType / lastResult（pass/fail）/ assetId / keyword（ruleName LIKE）。
     * 分页 createTime 倒序；记录含关联资产摘要（批量装配，避免 N+1）。
     */
    IPage<DataQualityRuleVo> pageFilter(String checkType, String lastResult, Long assetId, String keyword,
                                        long page, long size);

    /** 实体→Vo（不带资产摘要；摘要由列表/详情装配）。 */
    DataQualityRuleVo toVo(DataQualityRule rule);
}
