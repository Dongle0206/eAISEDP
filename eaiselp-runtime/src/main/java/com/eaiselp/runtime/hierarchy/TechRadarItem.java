package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 技术雷达技术项实体（t_tech_radar_item，V5 F5 新表，租户级知识资产，不限层）。
 *
 * <p><b>一技术一当前态</b>：tech_name 租户内唯一（uk_radar_tenant_name，同名重复 400 提示
 * "编辑既有项"）；环移动（trial→adopt）不落新行原地更新，变更历史唯一留痕 =
 * t_governance_log 审计 detail(fromRing→toRing)——雷达版本管理属范围外。</p>
 *
 * <p><b>C4 列名</b>：API 语义名 name/reviewedAt ↔ V5 列 tech_name/reviewed_at，实体按 V5 列名。</p>
 *
 * <p>枚举校验在 Service（quadrant 四值 / ring 四值，非法 400，AC-F5.1）；实体保持先例风格
 * 不加校验注解。待复审（reviewedAt&lt;今天−180d）为展示层派生标记 Vo.pendingReview，不落库。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tech_radar_item")
public class TechRadarItem extends BaseEntity {

    /** 技术项名称（租户内唯一，一个技术一个当前态；API 语义名 name） */
    private String techName;

    /** 象限: techniques / tools / platforms / languages（应用层枚举校验） */
    private String quadrant;

    /** 环: adopt(最内) / trial / assess / hold(最外，视觉警示)——环移动写审计 */
    private String ring;

    /** 定环理由（必填） */
    private String reason;

    /** 评审日期（必填；距今>180天由展示层标"待复审"，不落库不阻塞操作） */
    private LocalDate reviewedAt;

    /** 备注 */
    private String remark;
}
