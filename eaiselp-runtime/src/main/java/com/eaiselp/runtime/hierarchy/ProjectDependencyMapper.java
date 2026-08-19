package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 跨项目依赖 Mapper（V5 F3，含 C2 复活语义的两条自定义 SQL，随实体一并交付）。
 *
 * <p><b>为什么必须自定义 SQL（C2 收敛，tasks.md §0 / R3 自检）</b>：BaseEntity 的 @TableLogic
 * 令 MP 标准方法（selectOne/selectList）自动追加 {@code is_deleted=0}——逻辑删行被 select 过滤，
 * "删后同对重登"的复活判定根本查不到。以下两条 SQL 手写 is_deleted 条件（自定义 @Select 不走
 * MP 注入的 logic-delete 改写），是复活语义的唯一载体。</p>
 *
 * <p><b>租户条件手写说明</b>：t_project_dependency 是普通租户表（不在 IGNORE_TABLES），拦截器
 * 会对本 Mapper SQL 追加 tenant_id——手写 tenant_id 等值条件与拦截器注入值同源（TenantContext），
 * 冗余但一致；显式写是为不依赖拦截器行为（复活 UPDATE 的 where 必须钉死租户边界，C2 任务书要求）。</p>
 */
@Mapper
public interface ProjectDependencyMapper extends BaseMapper<ProjectDependency> {

    /**
     * 查同 (tenant, from, to, type) 的<b>逻辑删行</b>（绕过 @TableLogic select 过滤，C2 复活判定）。
     *
     * <p>uk 不含删除位 → 同对至多一条删行 + 一条活行；ORDER BY id DESC LIMIT 1 防御脏数据多行。</p>
     *
     * @return 命中返回逻辑删行（含 id）；未命中返回 null（此时 DuplicateKey 只能是活行冲突 → 400）
     */
    @Select("SELECT * FROM t_project_dependency "
            + "WHERE tenant_id = #{tenantId} AND from_project_id = #{fromProjectId} "
            + "AND to_project_id = #{toProjectId} AND dependency_type = #{dependencyType} "
            + "AND is_deleted = 1 ORDER BY id DESC LIMIT 1")
    ProjectDependency selectDeletedEdge(@Param("tenantId") Long tenantId,
                                        @Param("fromProjectId") Long fromProjectId,
                                        @Param("toProjectId") Long toProjectId,
                                        @Param("dependencyType") String dependencyType);

    /**
     * 复活逻辑删行（C2）：is_deleted 置 0、刷新 note（承载新的 orig 前缀+备注）与 update_by。
     *
     * <p>update_time 由 DB ON UPDATE CURRENT_TIMESTAMP 自动刷新，不手写。
     * 返回受影响行数（0 = 行已被并发复活/物理清理，调用方按未命中处理）。</p>
     */
    @Update("UPDATE t_project_dependency SET is_deleted = 0, note = #{note}, update_by = #{updateBy} "
            + "WHERE tenant_id = #{tenantId} AND from_project_id = #{fromProjectId} "
            + "AND to_project_id = #{toProjectId} AND dependency_type = #{dependencyType} "
            + "AND is_deleted = 1")
    int reviveEdge(@Param("tenantId") Long tenantId,
                   @Param("fromProjectId") Long fromProjectId,
                   @Param("toProjectId") Long toProjectId,
                   @Param("dependencyType") String dependencyType,
                   @Param("note") String note,
                   @Param("updateBy") String updateBy);
}
