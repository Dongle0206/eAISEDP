package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.eaiselp.runtime.governance.dto.StandardVo;

/**
 * 工程标准服务接口（V6 F1.1，case-20260820 T2；契约=api-contracts §1 S1~S5）。
 *
 * <p>CRUD（多版本行 uk(tenant,code,version)/编号缺省生成 STD-NNNN/draft 专属编辑）+
 * 关联校验（原则 code 存在性 + 门禁 name 存在且 enabled，§4.5 翻译口径）+ 按状态/原则/
 * 关键词筛选。状态流转 transit（S6）与 gateName 打通查询（§4.5 翻译口径）由批B T12/T13
 * 增补完毕。</p>
 */
public interface StandardService extends IService<Standard> {

    /**
     * 创建标准（状态固定 draft）。
     *
     * <p>title/version/content 必填（空串 400）；standardCode 缺省 {@code STD-}+四位序号
     * （续推+uk 兜底重试 ≤3 次，同编号同版本冲突 400 指名）；relatedPrincipleCodes 逐 code
     * 存在性校验（整单 400 指名"原则 P99 不存在"）；relatedGateNames 逐 name 存在且 enabled
     * 校验（不存在/已停用 400 指名）。审计 standard_create。</p>
     */
    Standard create(Standard standard);

    /**
     * 编辑标准（draft 专属，AC-F1.5）。
     *
     * <p>published/deprecated 编辑任意字段 → 400"发布后不可编辑，请升版"；draft 全字段可编辑
     * （含编号/版本，uk 冲突 400 兜底）；关联校验同创建。审计 standard_update。</p>
     */
    Standard edit(Long id, Standard patch);

    /** 逻辑删（列表不可见+审计留痕；门禁侧"已删除"占位由批B T13 查询侧实现）。审计 standard_delete。 */
    void remove(Long id);

    /** 详情 Vo（全字段 + deprecateReason 列直读回显）。跨租户/不存在 → 404。 */
    StandardVo detailVo(Long id);

    /** 实体详情（跨租户/不存在 → 404）。 */
    Standard loadOr404(Long id);

    /**
     * 列表筛选（S1，AC-F1.2）：status（缺省=draft,published 双值，显式可查 deprecated）/
     * principleCode（JSON 内存过滤，ADR §4.2 口径）/ keyword（title LIKE）。分页 createTime 倒序。
     */
    IPage<StandardVo> pageFilter(String status, String principleCode, String keyword, long page, long size);

    /**
     * 列表筛选（S1 批B T13 增补 gateName，AC-F1.7/F1.8 翻译口径）。
     *
     * <p>gateName 非空时走 D-9 打通查询：手写 SQL 旁路 @TableLogic（is_deleted IN (0,1)，
     * 逻辑删标准行以 {@code deleted=true} 占位返回）+ status 固定 published（门禁关联展示
     * 一律 published 过滤）+ relatedGateNames JSON 内存过滤——供打回解析与规则页
     * "已关联标准"只读区复用。gateName 为空时等价于 {@link #pageFilter(String, String, String, long, long)}。</p>
     */
    IPage<StandardVo> pageFilter(String status, String principleCode, String gateName, String keyword, long page, long size);

    /**
     * 状态流转（S6，批B T12，AC-F1.2/F1.4，D-7/D-8）。
     *
     * <p>状态机全路径：draft→published（触发发布自动取代事务——FOR UPDATE 锁同编号现行
     * published 版本 → 旧版置 deprecated（原因=「被 {code} {新版本} 取代」）→ 新版 published，
     * 双审计 standard_transit + standard_auto_deprecate）；draft/published→deprecated
     * （必填 deprecateReason，空 400）；deprecated 终态无出边、published→draft 非法
     * （canTransitionTo 400）；流转到自身幂等短路。事务保证同编号至多一个 published。</p>
     *
     * @param target          目标状态（published / deprecated；未知值 400）
     * @param deprecateReason target=deprecated 时必填（空 400）；发布取代时由事务自动生成
     * @return 流转后详情 Vo（deprecateReason 列直读回显）
     */
    StandardVo transit(Long id, String target, String deprecateReason);

    /** 实体→Vo（JSON 列解析；解析失败容忍降级空列表，不 500）。 */
    StandardVo toVo(Standard standard);
}
