package com.eaiselp.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaiselp.data.entity.UserRole;
import com.eaiselp.data.mapper.vo.UserRoleView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserRoleMapper extends BaseMapper<UserRole> {

    /** 查用户的所有角色（含 role_id + role_code + role_name），用于登录聚合。 */
    @Select("SELECT r.id AS role_id, r.role_code, r.role_name " +
            "FROM t_user_role ur INNER JOIN t_role r ON r.id = ur.role_id " +
            "WHERE ur.user_id = #{userId} AND r.is_deleted = 0")
    List<UserRoleView> selectRolesByUserId(@Param("userId") Long userId);
}
