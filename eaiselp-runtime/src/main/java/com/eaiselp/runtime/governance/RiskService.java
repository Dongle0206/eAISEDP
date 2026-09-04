package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.eaiselp.runtime.governance.dto.RiskDashboardVo;
import com.eaiselp.runtime.governance.dto.RiskVo;

/**
 * 风险登记册服务接口（case-20260821 T2 CRUD / T10 状态机 / T12 看板聚合）。
 */
public interface RiskService extends IService<Risk> {

    /**
     * 创建风险（R2）：校验（必填/枚举/P·I 1~5 整数/关联存在性）→ 计算列重算 → 落库
     * （status 固定 open）→ 审计 risk_create。
     */
    Risk create(Risk risk);

    /**
     * 编辑风险（R4）：open/mitigating 全字段可编辑且<b>编辑即重算覆盖</b>（AC-F1.5）；
     * closed 编辑任意字段 → 400 终态只读（AC-F1.4）；非 closed 状态 resolutionNote 置 NULL。
     */
    Risk edit(Long id, Risk patch);

    /** 逻辑删（R5）+ 审计 risk_delete；删除后列表不可见。 */
    void remove(Long id);

    /** 详情（R3）：全字段 + relatedObjects 解析 [{type,id,name,deleted}]（悬空占位，AC-F1.6）。 */
    RiskVo detailVo(Long id);

    /** 按 id 加载；不存在/跨租户 → 404（拦截器语义，AC-RBAC.4）。 */
    Risk loadOr404(Long id);

    /**
     * 列表（R1）：默认排序 riskValue DESC, id DESC（写死，QA 断言用）；筛选
     * category/level/status/overdueOnly（SQL 口径 review_date&lt;今天 AND status≠closed）/
     * keyword（riskName LIKE）；VO 含服务端判定 overdue（D-10）。
     */
    IPage<RiskVo> pageFilter(String category, String level, String status, Boolean overdueOnly,
                             String keyword, long page, long size);

    /**
     * 状态流转（R6，T10）：open→mitigating；mitigating→closed（必填 resolutionNote）；
     * mitigating→open 回退合法（§0.3-1 消解）；open→closed 跳级 400；closed 出边 400；
     * 自流转幂等；每次合法流转审计 risk_transit（from→to+操作者+resolutionNote）。
     */
    RiskVo transit(Long id, String target, String resolutionNote);

    /**
     * 风险看板聚合（R7，T12）：cells 恰 25 格（仅未 closed 计入）+ 等级分布四档 +
     * 高风险清单（level∈{high,critical} 未 closed，riskValue DESC, id DESC）。
     */
    RiskDashboardVo dashboard();

    /** 实体 → VO（overdue 服务端统一判定，D-10）。 */
    RiskVo toVo(Risk risk);
}
