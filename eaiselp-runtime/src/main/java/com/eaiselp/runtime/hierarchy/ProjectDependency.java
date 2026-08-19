package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 跨项目依赖实体（t_project_dependency，V5 F3 新表，case-20260818）。
 *
 * <p><b>归一化存储（D-5 + C1 收敛，tasks.md §0）</b>：入参三值 blocks/depends_on/relates_to，
 * 落库一律"依赖方→被依赖方"规范向——blocks 登记换向存 from=被阻塞方、dependency_type 归一为
 * depends_on（V5 注释里的 blocks 存储值应用层不写）；原始类型语义由 note 承载：原始类型=blocks
 * 时 note 写结构化前缀 {@code [orig:blocks]}+用户备注，读取时解析还原 origType（失败默认
 * depends_on 文案）。uk(tenant,from,to,type) 对归一化语义天然去重（AC-F3.1 DB 兜底）。</p>
 *
 * <p><b>C2 复活语义</b>：uk 四列不含删除位 + 全库 @TableLogic Integer（置 1）——删后同对重登必撞
 * uk。DuplicateKey 时经 {@link ProjectDependencyMapper#selectDeletedEdge} 查逻辑删行命中则复活
 * （is_deleted=0、刷新 note/update_by），无命中才 400 唯一冲突（V6 候选优化已提请 DBA）。</p>
 *
 * <p><b>环检测与 blocked 判定</b>：均为应用层实时计算不落库；仅 dependency_type='depends_on'
 * 强边参与（relates_to 豁免，AC-F3.4）。</p>
 *
 * <p>注：API 语义名 remark ↔ V5 列名 note（C4，实体映射处标注差异，Service 层换名）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_project_dependency")
public class ProjectDependency extends BaseEntity {

    /** 依赖方项目 ID（t_project.id；blocks 登记换向后 = 被阻塞方） */
    private Long fromProjectId;

    /** 被依赖方项目 ID（t_project.id）；from≠to 禁止自依赖（应用层校验） */
    private Long toProjectId;

    /** depends_on=强依赖（blocked 判定+环检测）/ relates_to=弱关联（两判定均豁免）；blocks 归一不落库 */
    private String dependencyType;

    /** 备注（C1：原始类型=blocks 时前缀 [orig:blocks] 保留用户原始表述；API 语义名 remark） */
    private String note;
}
