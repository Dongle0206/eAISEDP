package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.eaiselp.runtime.governance.dto.BusinessCaseVo;
import com.eaiselp.runtime.governance.dto.PortfolioVo;

/**
 * 商业案例服务接口（case-20260821 T4 CRUD / T11 状态机与决策记录 / T13 组合聚合）。
 */
public interface BusinessCaseService extends IService<BusinessCase> {

    /**
     * 创建案例（B2）：校验（必填/金额≥0/R·I·E 1~10 整数/confidence 0.1 步进/战略存在性）
     * → 四计算列重算（netBenefit/paybackYears/roiPercent/riceScore）→ 落库（status 固定
     * draft）→ 审计 bizcase_create。
     */
    BusinessCase create(BusinessCase bizCase);

    /**
     * 编辑（B4，draft 专属）：draft → 200 且计算字段重算覆盖（AC-F2.4/F2.7）；
     * approved/executing → 400"已批准，输入不可改，请复盘或新建案例"；
     * rejected/done → 400 终态只读。
     */
    BusinessCase edit(Long id, BusinessCase patch);

    /** 逻辑删（B5）：仅 draft 可删；非 draft → 400（合规资产留痕，AC-F2.7）。 */
    void remove(Long id);

    /** 详情（B3）：全字段 + relatedStrategies 解析 [{id,title,deleted}]（战略逻辑删占位，AC-F2.8）。 */
    BusinessCaseVo detailVo(Long id);

    /** 按 id 加载；不存在/跨租户 → 404。 */
    BusinessCase loadOr404(Long id);

    /**
     * 列表（B1）：默认排序 riceScore DESC, id DESC；筛选 status/strategyId
     * （related_strategy_ids JSON 内存过滤，分页后过滤——V6 principleCode 口径，D-8）/
     * keyword（caseName LIKE）。
     */
    IPage<BusinessCaseVo> pageFilter(String status, Long strategyId, String keyword,
                                     long page, long size);

    /**
     * 决策记录更新（B6，T11）：draft/approved/executing 可更新（执行期进展记录）→ 200；
     * rejected/done → 400；审计 bizcase_decision_note（detail 含旧值→新值）。
     */
    BusinessCaseVo updateDecisionNote(Long id, String decisionNote);

    /**
     * 状态流转（B7，T11）：draft→approved / draft→rejected（必填 rejectedReason）/
     * approved→executing / executing→done；跳级/撤销/终态出边 400；自流转幂等；
     * 审计 bizcase_transit（from→to+操作者+rejectedReason/decisionNote 快照+计算字段）。
     */
    BusinessCaseVo transit(Long id, String target, String rejectedReason);

    /**
     * 投资组合聚合（B8，T13）：cases 全量（含 rejected/done，riceScore DESC, id DESC）+
     * summary 四项投资口径（status∈{approved,executing,done}，空集 COALESCE 0）+
     * statusDistribution 全量五态（双口径并存，AC-F2.10~F2.12）。
     */
    PortfolioVo portfolio(long page, long size);

    /** 实体 → VO。 */
    BusinessCaseVo toVo(BusinessCase bizCase);
}
