package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 数据资产目录实体（t_data_asset，V6 F2.1 新表，租户级知识资产，不限层，case-20260820 T4）。
 *
 * <p>uk(tenant_id, system_name, asset_name)：同系统同名资产不可重复登记、跨系统同名合法
 * （AC-F2.1）。assetType/sensitivity 为领域数据字典枚举（应用层校验非法 400，DB 不加约束）；
 * tags 自由值 JSON 数组随录入自然扩展，无独立标签管理页（AC-F2.4）。</p>
 *
 * <p>逻辑删联动（AC-F2.7）：资产逻辑删时其关联质量规则由 Service 同步逻辑删
 * （t_data_quality_rule idx_dqr_tenant_asset 定位）并写审计（detail 含 ruleIds）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_data_asset")
public class DataAsset extends BaseEntity {

    /** 资产名称（如 t_order；同系统内同名冲突，跨系统合法） */
    private String assetName;

    /** 所属系统（自由填写，如 ERP/CRM） */
    private String systemName;

    /** 资产类型: database/table/api/report/file（{@link AssetType} 应用层校验） */
    private String assetType;

    /** 责任人（自由填写，本期不联动 t_user） */
    private String owner;

    /** 敏感等级: public/internal/sensitive/confidential 四档（{@link Sensitivity} 应用层校验） */
    private String sensitivity;

    /** 资产描述 */
    private String description;

    /** 标签列表（JSON 数组 String，自由值随录入自然扩展；解析在 Service） */
    private String tags;
}
