package com.eaiselp.data.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDate;

/**
 * 里程碑实体（t_milestone，V1 死表 + V5 ALTER 激活，case-20260818 F2 / T2）。
 *
 * <p><b>C8 收敛（tasks.md §0）</b>：本实体是 V1 既有产物（非 hierarchy 包新建），
 * 批A 按V5 ALTER 增量补 6 列（owner_type/owner_id/milestone_code/description/owner/achieved_date），
 * 不新建不迁移。死表无存量数据（PRD §1.1），零迁移成本。</p>
 *
 * <p><b>API 语义名 ↔ V5 列名（C4）</b>：API JSON 字段 owner(负责人)/milestoneCode 与实体属性同名；
 * status 列存 planned/achieved/delayed（V1 not_started 词汇废弃，状态机在
 * runtime.hierarchy.MilestoneStatus，系统永不自动置 achieved/delayed——逾期仅展示层 Vo.overdue）。</p>
 *
 * <p><b>legacy 列只读（V5 注释 / PRD §4.2.1，同 t_case.program_id 的 PRJ-002 Q6 先例）</b>：
 * programId(VARCHAR，与 V4 t_program.id BIGINT 错位的旧项目群弱关联)/milestoneId(旧编号)
 * 保留只读不写——updateStrategy=NEVER 挡实体驱动 UPDATE，INSERT 侧新代码恒 null（MP 默认
 * NOT_NULL 策略不进 SQL）；subprojects 语义转为"项目群级里程碑涉及项目多选（仅展示，
 * 不参与自动判定）"；integrationPoints 保留映射不启用。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_milestone")
public class Milestone extends BaseEntity {

    /** legacy(V1)：旧项目群弱关联（VARCHAR 与 V4 BIGINT 错位），只读不写 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String milestoneId;

    /** legacy(V1)：旧编号，只读不写；新功能一律用 {@link #milestoneCode} */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String programId;

    /** 标题（必填 ≤200，应用层校验） */
    private String title;

    /** 目标日期（逾期判定锚点：&lt;今天 且 planned → 展示层 overdue，不改库） */
    private LocalDate targetDate;

    /** 状态: planned / achieved / delayed（状态机应用层校验，V5 已改默认 'planned'） */
    private String status;

    /** 项目群级里程碑涉及项目多选（仅展示，不参与自动判定，PRD §4.2.2） */
    private String subprojects;

    /** legacy(V1)：保留映射不启用 */
    private String integrationPoints;

    /** 阻塞说明（手工维护，不与依赖管理联动） */
    private String blocker;

    // ===== V5 ALTER 新增六列（case-20260818 T2 激活改造） =====

    /** 归属层级: program=项目群级 / project=项目级（两级归属单列对，V5 D-3） */
    private String ownerType;

    /** 归属对象 ID：owner_type=program→t_program.id / project→t_project.id */
    private Long ownerId;

    /** 用户可见编号（如 MS-0001，租户内唯一 uk_ms_tenant_code，服务端生成+uk 兜底重试） */
    private String milestoneCode;

    /** 里程碑描述 */
    private String description;

    /** 负责人（API 语义名 owner，V5 列名同形） */
    private String owner;

    /** 达成日期（achieved 态必有值；撤销达成清空；达成一律人工确认，系统不自动置达成） */
    private LocalDate achievedDate;
}
