package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 项目-原则关联实体（t_project_principle，多对多，V4 r2 新增，PRJ-002 T04）。
 *
 * <p>绑定语义（DBA §3 注入解析契约）：项目无绑定行→继承租户全部启用原则；有绑定行→
 * 绑定 ∩ 双 enabled=1；绑定行全为 enabled=0→注入空集（显式豁免，不回退租户默认）。
 * enabled 是项目级覆盖位：租户级原则启用，但本项目可单独停用而保留绑定关系。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_project_principle")
public class ProjectPrinciple extends BaseEntity {

    /** 项目 ID（t_project.id） */
    private Long projectId;

    /** 架构原则 ID（t_architecture_principle.id） */
    private Long principleId;

    /** 项目级覆盖: 1=本项目启用(默认) 0=本项目停用但保留绑定 */
    private Integer enabled;
}
