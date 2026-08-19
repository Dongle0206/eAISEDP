package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.extension.service.IService;
import com.eaiselp.runtime.hierarchy.dto.TechRadarVo;

import java.util.List;
import java.util.Map;

/**
 * 技术雷达服务接口（V5 F5，case-20260818 T13；契约=api-contracts §5）。
 *
 * <p>CRUD（四值枚举校验 400/必填校验/同名唯一冲突提示"编辑既有项"）+ 环移动审计
 * （改 ring 时 radar_update detail 记 {"name","fromRing","toRing"}，唯一留痕）+
 * 待复审标记（reviewedAt&lt;今天−180d→Vo.pendingReview，不阻塞操作）+ 四象限分组。</p>
 */
public interface TechRadarService extends IService<TechRadarItem> {

    /**
     * 创建技术项（AC-F5.1）：quadrant/ring 四值枚举校验（非法 400）、reason/reviewedAt 必填、
     * 同名唯一冲突 400 "技术项已存在: {name}，请编辑既有项"（uk_radar_tenant_name）。
     * 审计 radar_create。
     */
    TechRadarItem create(TechRadarItem item);

    /**
     * 编辑（AC-F5.3 环移动审计）：改 ring 时审计 radar_update detail 记
     * {"name","fromRing","toRing"}（环变更历史唯一留痕，雷达版本管理=范围外）。
     * 审计 radar_update。
     */
    TechRadarItem edit(Long id, TechRadarItem patch);

    /** 逻辑删。审计 radar_delete。 */
    void remove(Long id);

    /** 详情（跨租户/不存在 → 404）。 */
    TechRadarItem loadOr404(Long id);

    /** Vo 详情（含 pendingReview 派生标记）。 */
    TechRadarVo detailVo(Long id);

    /**
     * 列表（quadrant/ring 可组合筛选，含 pendingReview 派生标记），按 reviewedAt 倒序。
     */
    List<TechRadarVo> list(String quadrant, String ring);

    /**
     * 四象限分组（techniques/tools/platforms/languages → 各自条目列表；恒含四键，
     * 空象限为空列表——前端 SVG 扇区渲染零判空）。ring 可选过滤。
     */
    Map<String, List<TechRadarVo>> quadrantGroups(String ring);

    /** 实体→Vo（name/reviewedAt 语义名换 V5 列 tech_name/reviewed_at，C4；pendingReview 派生）。 */
    TechRadarVo toVo(TechRadarItem item);
}
