package com.eaiselp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user")
public class User extends BaseEntity {
    private String username;
    @JsonIgnore
    private String password;
    private String displayName;
    private String email;
    private String phone;
    private String status;
    private String roles;
    private String organization;
    private String avatar;
    private LocalDateTime lastLoginAt;
}
