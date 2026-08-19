package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.runtime.hierarchy.dto.TechRadarVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 技术雷达服务实现（V5 F5，case-20260818 T13）。
 *
 * <p><b>实现要点</b>：
 * <ul>
 *   <li><b>一技术一当前态</b>：同名重复创建撞 uk_radar_tenant_name → 400 提示"编辑既有项"
 *       （AC-F5.1①）；环移动不落新行原地更新。</li>
 *   <li><b>环移动审计（AC-F5.3）</b>：改 ring 时 radar_update detail 记
 *       {"name","fromRing","toRing"}——t_governance_log 是环变更历史的唯一留痕
 *       （雷达版本管理属范围外，审计即闭环）。</li>
 *   <li><b>待复审（AC-F5.4）</b>：reviewedAt &lt; 今天−180d → Vo.pendingReview=true，
 *       展示层角标，不阻塞任何操作。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TechRadarServiceImpl extends ServiceImpl<TechRadarItemMapper, TechRadarItem>
        implements TechRadarService {

    private final AuditService auditService;

    /** 象限四值（P6 领域字典，集中一处定义） */
    public static final Set<String> QUADRANTS = Set.of("techniques", "tools", "platforms", "languages");

    /** 环四值（adopt 最内 → hold 最外） */
    public static final Set<String> RINGS = Set.of("adopt", "trial", "assess", "hold");

    /** 待复审阈值：评审日期距今超过 180 天（AC-F5.4） */
    static final long PENDING_REVIEW_DAYS = 180;

    private static final com.fasterxml.jackson.databind.ObjectMapper OM =
            new com.fasterxml.jackson.databind.ObjectMapper();

    // ==================== CRUD ====================

    @Override
    public TechRadarItem create(TechRadarItem item) {
        validateForWrite(item);
        // 同名唯一：uk 兜底翻译（selectOne 预检被逻辑删行遮蔽，DuplicateKey 为准）
        try {
            save(item);
        } catch (DuplicateKeyException e) {
            throw new BizException(400, "技术项已存在: " + item.getTechName() + "，请编辑既有项");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", item.getTechName());
        detail.put("quadrant", item.getQuadrant());
        detail.put("ring", item.getRing());
        audit("radar_create", item.getId(), detail);
        return item;
    }

    @Override
    public TechRadarItem edit(Long id, TechRadarItem patch) {
        TechRadarItem exist = loadOr404(id);
        validateForWrite(patch);
        TechRadarItem next = new TechRadarItem();
        next.setId(id);
        next.setTechName(patch.getTechName());
        next.setQuadrant(patch.getQuadrant());
        next.setRing(patch.getRing());
        next.setReason(patch.getReason());
        next.setReviewedAt(patch.getReviewedAt());
        next.setRemark(patch.getRemark());
        try {
            updateById(next);
        } catch (DuplicateKeyException e) {
            throw new BizException(400, "技术项已存在: " + patch.getTechName() + "，请编辑既有项");
        }
        // 环移动审计：改 ring 时 detail 记 fromRing→toRing（唯一留痕，AC-F5.3）
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", next.getTechName());
        if (!exist.getRing().equals(next.getRing())) {
            detail.put("fromRing", exist.getRing());
            detail.put("toRing", next.getRing());
        }
        audit("radar_update", id, detail);
        return getById(id);
    }

    @Override
    public void remove(Long id) {
        TechRadarItem exist = loadOr404(id);
        removeById(id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", exist.getTechName());
        detail.put("quadrant", exist.getQuadrant());
        detail.put("ring", exist.getRing());
        audit("radar_delete", id, detail);
    }

    @Override
    public TechRadarItem loadOr404(Long id) {
        TechRadarItem item = getById(id);
        if (item == null) {
            throw new BizException(404, "技术项不存在: " + id);
        }
        return item;
    }

    @Override
    public TechRadarVo detailVo(Long id) {
        return toVo(loadOr404(id));
    }

    // ==================== 查询与分组 ====================

    @Override
    public List<TechRadarVo> list(String quadrant, String ring) {
        return list(new LambdaQueryWrapper<TechRadarItem>()
                        .eq(quadrant != null && !quadrant.isBlank(), TechRadarItem::getQuadrant, quadrant)
                        .eq(ring != null && !ring.isBlank(), TechRadarItem::getRing, ring)
                        .orderByDesc(TechRadarItem::getReviewedAt))
                .stream().map(this::toVo).toList();
    }

    @Override
    public Map<String, List<TechRadarVo>> quadrantGroups(String ring) {
        Map<String, List<TechRadarVo>> groups = new LinkedHashMap<>();
        for (String q : QUADRANTS.stream().sorted().toList()) {
            groups.put(q, list(q, ring));   // 恒含四键，空象限空列表（前端 SVG 扇区零判空）
        }
        return groups;
    }

    @Override
    public TechRadarVo toVo(TechRadarItem item) {
        TechRadarVo vo = new TechRadarVo();
        vo.setId(item.getId());
        vo.setName(item.getTechName());               // C4：API 语义名 name ↔ V5 列 tech_name
        vo.setQuadrant(item.getQuadrant());
        vo.setRing(item.getRing());
        vo.setReason(item.getReason());
        vo.setReviewedAt(item.getReviewedAt());       // C4：reviewedAt ↔ reviewed_at
        vo.setRemark(item.getRemark());
        // 待复审：评审日期距今>180 天（展示层派生角标，不阻塞任何操作，AC-F5.4）
        vo.setPendingReview(item.getReviewedAt() != null
                && item.getReviewedAt().isBefore(LocalDate.now().minusDays(PENDING_REVIEW_DAYS)));
        return vo;
    }

    // ==================== 校验与工具 ====================

    private void validateForWrite(TechRadarItem item) {
        if (item == null) {
            throw new BizException(400, "请求体不能为空");
        }
        if (item.getTechName() == null || item.getTechName().isBlank()) {
            throw new BizException(400, "name 不能为空");
        }
        if (item.getQuadrant() == null || !QUADRANTS.contains(item.getQuadrant())) {
            throw new BizException(400, "quadrant 非法，应为 techniques/tools/platforms/languages");
        }
        if (item.getRing() == null || !RINGS.contains(item.getRing())) {
            throw new BizException(400, "ring 非法，应为 adopt/trial/assess/hold");
        }
        if (item.getReason() == null || item.getReason().isBlank()
                || item.getReviewedAt() == null) {
            throw new BizException(400, "reason/reviewedAt 必填");
        }
    }

    private void audit(String action, Long id, Object detail) {
        String json;
        try {
            json = OM.writeValueAsString(detail);
        } catch (Exception e) {
            json = "{}";
        }
        auditService.log(action, "tech_radar", String.valueOf(id), json);
    }
}
