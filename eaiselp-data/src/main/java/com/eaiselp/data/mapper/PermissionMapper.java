package com.eaiselp.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaiselp.data.entity.Permission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface PermissionMapper extends BaseMapper<Permission> {

    /** 按一批角色 ID 查询去重后的权限码（多角色取并集）。 */
    @Select("<script>" +
            "SELECT DISTINCT p.permission_code FROM t_permission p " +
            "INNER JOIN t_role_permission rp ON rp.permission_id = p.id " +
            "WHERE rp.role_id IN " +
            "<foreach collection='roleIds' item='rid' open='(' separator=',' close=')'>#{rid}</foreach> " +
            "AND p.is_deleted = 0" +
            "</script>")
    List<String> selectPermissionCodesByRoleIds(@Param("roleIds") Collection<Long> roleIds);
}
