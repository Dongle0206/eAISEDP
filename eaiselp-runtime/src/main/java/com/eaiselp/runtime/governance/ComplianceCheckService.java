package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.eaiselp.runtime.governance.dto.ComplianceCheckVo;

/**
 * 合规检查服务接口（case-20260821 T3，契约 C1~C5）。
 */
public interface ComplianceCheckService extends IService<ComplianceCheck> {

    /**
     * 创建检查项（C2）：checkName/framework/result 必填与枚举校验、custom↔frameworkName
     * 双向联动（AC-F1.9）、checkDate 缺省应用层取当天 → 落库 → 审计 compliance_create。
     */
    ComplianceCheck create(ComplianceCheck check);

    /**
     * 编辑（C4）：result 覆盖式单值当前态更新——<b>审计 detail 含 oldResult→newResult+证据</b>
     * （历史唯一留痕，AC-F1.10）；非法 result/custom 联动破坏 → 400。
     */
    ComplianceCheck edit(Long id, ComplianceCheck patch);

    /** 逻辑删（C5）+ 审计 compliance_delete。 */
    void remove(Long id);

    /** 详情（C3）：全字段 + overdue 服务端判定。 */
    ComplianceCheckVo detailVo(Long id);

    /** 按 id 加载；不存在/跨租户 → 404。 */
    ComplianceCheck loadOr404(Long id);

    /**
     * 列表（C1）：默认 id DESC（PRD 未锁排序）；筛选 framework/result/overdueOnly
     * （recheck_date&lt;今天，na 不豁免）/keyword（checkName LIKE）。
     */
    IPage<ComplianceCheckVo> pageFilter(String framework, String result, Boolean overdueOnly,
                                        String keyword, long page, long size);

    /** 实体 → VO（overdue 服务端统一判定：recheckDate&lt;今天，na 不豁免，D-10）。 */
    ComplianceCheckVo toVo(ComplianceCheck check);
}
