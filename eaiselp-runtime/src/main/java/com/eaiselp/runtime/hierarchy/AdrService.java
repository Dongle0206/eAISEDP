package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.eaiselp.runtime.hierarchy.dto.AdrVo;

import java.util.List;

/**
 * ADR 服务接口（V5 F4，case-20260818 T12；契约=api-contracts §4）。
 *
 * <p>CRUD（五段式必填/编号缺省生成）+ transit（AdrStatus 状态机/supersededBy 目标校验/
 * deprecateReason 必填——值写审计 detail 不落列，C3）+ relatedPrincipleCodes 逐 code 存在性
 * 校验 + principleSyncHints 组装（提示而非自动，系统不写 t_architecture_principle）+
 * 按状态/原则/关键词筛选 + 按原则 code 反查（供 T17 反查端点）。</p>
 */
public interface AdrService extends IService<Adr> {

    /**
     * 创建 ADR（状态固定 proposed）。
     *
     * <p>五段式必填（title/context/decision/consequences 空串 400 "context 不能为空"式）；
     * adrCode 缺省 {@code ADR-}+租户自增序（uk 兜底重试 3 次，同编号重复 400 指名）；
     * relatedPrincipleCodes 逐 code 存在性校验（不存在整单 400 指名"原则 P99 不存在"）。
     * 审计 adr_create。</p>
     */
    Adr create(Adr adr);

    /** 编辑（五段式/关联原则校验同创建；status/supersededBy 不在此改——只走 transit）。审计 adr_update。 */
    Adr edit(Long id, Adr patch);

    /** 逻辑删。审计 adr_delete。 */
    void remove(Long id);

    /** 详情 Vo（含 deprecateReason 审计回显 C3 与关联原则）。跨租户/不存在 → 404。 */
    AdrVo detailVo(Long id);

    /** 实体详情（跨租户/不存在 → 404）。 */
    Adr loadOr404(Long id);

    /**
     * 状态流转（AC-F4.2/F4.3）。
     *
     * <p>AdrStatus 状态机校验（非法 400 "非法状态流转: proposed→superseded"）；superseded：
     * supersededBy 必填、目标 ADR 存在且 accepted 且≠自身；deprecated：deprecateReason 必填
     * （空=400，PRD §4.4.2 行为权威）。deprecateReason 不落列——写审计 detail 并在响应回显（C3）。
     * 流转离开 accepted 且关联原则非空 → principleSyncHints=[{code,title}]（提示而非自动）。
     * 审计 adr_transit（detail 含 from/to/supersededBy/deprecateReason）。</p>
     *
     * @return 流转后的 Vo（含 principleSyncHints 与 deprecateReason 回显）
     */
    AdrVo transit(Long id, String target, String supersededBy, String deprecateReason);

    /**
     * 列表筛选（AC-F4.4）：status（缺省=proposed,accepted 双值）/ principleCode（JSON 内存过滤）/
     * keyword（title LIKE）。分页按 createTime 倒序。
     */
    IPage<AdrVo> pageFilter(String status, String principleCode, String keyword, long page, long size);

    /**
     * 按原则 code 反查关联 ADR（全部状态，供 T17 原则侧聚合"关联的 ADR 列表"）。
     */
    List<AdrVo> listByPrincipleCode(String principleCode);

    /** 实体→Vo（relatedPrincipleCodes JSON 解析；解析失败容忍降级空列表，不 500）。 */
    AdrVo toVo(Adr adr);
}
