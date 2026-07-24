# 技术方案 — eAISEDP M2 Phase 1：登录 + JWT + RBAC + 前端骨架

| 字段 | 值 |
|---|---|
| Case | case-20260723-m2-phase1-web-auth |
| 文档版本 | v1.0 |
| 产出日期 | 2026-07-23 |
| 产出者 | team-se（L1 系统工程师 / Tech Lead）|
| 触发 | L1 编排者派发——PO PRD（M2-Phase1-PRD.md v1.0）已出，SE 据此出技术方案 |
| 上游输入 | M2-Phase1-PRD.md / 企业架构蓝图 v1.0 / ES-001 Maven 模块规范 / ES-002 执行规范 / schema.sql / 现有 common+data+runtime+auth 源码 |
| 下游传导 | team-ba（任务拆解，本方案 §4 改动顺序即任务依赖图）/ team-dev（按 §2/§3 编码）/ team-qa（按 §7 验证）|
| 权威性 | 本方案是 M2 Phase 1 范围内 Dev 编码、Reviewer 设计合理性审查、QA 回归的工程依据；与本方案冲突以 ES-001/ES-002 为准 |
| 状态 | 待评审 |

---

## 0. 阅读指引与前置约束

| 读者 | 必读章节 | 怎么用 |
|---|---|---|
| team-ba | §1 改动总览 + §4 改动顺序 | 据改动顺序拆 case 任务（每步可独立编译=独立交付单元）|
| team-dev（后端）| §2 全部 + §5 风险 + §6 裁决 | 按 §2.x 顺序编码，DDL 直接 copy，Entity/Controller 骨架照抄 |
| team-dev（前端）| §3 全部 | 按 §3.x 顺序建工程，HTML/JS 骨架可直接落地 |
| team-reviewer | §2 + §5 + §6 | 设计合理性审查（规范符合度由 Reviewer 自查 ES-001/ES-002）|
| team-qa | §7 回归验证 | 照 §7 步骤验证 6 条 AC |
| team-ops | §4 步骤 7（环境变量/库初始化）| 部署 checklist |

**前置硬约束（不可违背，源自上游）**：
- **ES-001 §1.3 模块定性强制**：eaiselp-common / eaiselp-data = library（无 @SpringBootApplication、无 repackage、不注册 Nacos）；eaiselp-auth / eaiselp-runtime = service（独立进程、repackage、@SpringBootApplication）。本方案所有改动遵循此边界。
- **ES-001 §4.2 依赖单向无环**：common 最底层；data 依赖 common；auth/runtime 可依赖 common + data；auth 与 runtime 横向不互相依赖（跨 service 调用走 gateway/Feign，Phase 1 不涉及）。
- **架构蓝图 P11 多租户隔离**：所有业务查询带 tenant_id；权限系统表（t_permission/t_role/t_role_permission）是系统级共享，需加入 `EaiselpTenantHandler.IGNORE_TABLES` 免过滤。
- **架构蓝图 P13 API 版本化**：所有对外 API 走 `/api/v1/**`。
- **PRD §5 API 契约**：3 个 API（login/current/logout）的入参/出参/错误码是 Dev 编码契约，本方案不修改契约本身，只给实现路径。

---

## 1. 改动总览（文件清单 + 改动方向）

### 1.1 改动统计

| 维度 | 数量 |
|---|---|
| 后端新增文件 | 27 |
| 后端修改文件 | 6 |
| 前端新增文件（新工程 eaiselp-web）| 12（含 3 个第三方静态资源占位）|
| DDL 追加（schema.sql）| 5 表 CREATE + 4 类 seed INSERT |
| **改动文件总数** | **45** |

### 1.2 后端改动清单

| # | 文件路径（相对 platform 根）| 改动类型 | 一句话说明 |
|---|---|---|---|
| **eaiselp-common（library，§2.4-2.6）** | | | |
| 1 | `eaiselp-common/pom.xml` | 修改 | 新增 jjwt 0.12.6 三件套依赖（api/impl/jackson）|
| 2 | `eaiselp-common/src/main/java/com/eaiselp/common/tenant/EaiselpTenantHandler.java` | 修改 | IGNORE_TABLES 追加 5 张权限表 |
| 3 | `.../common/security/JwtUtil.java` | 新增 | JWT 签发/解析/校验工具（HS256）|
| 4 | `.../common/security/JwtClaims.java` | 新增 | JWT payload 载体（userId/username/displayName/tenantId/tenantCode/roles/iat/exp）|
| 5 | `.../common/security/RequirePermission.java` | 新增 | @RequiresPermission 注解（method + type 可标注）|
| 6 | `.../common/security/LoginUser.java` | 新增 | 当前登录用户上下文（ThreadLocal，存解析后的 JWT claims）|
| 7 | `.../common/security/JwtAuthInterceptor.java` | 新增 | JWT 拦截器（解析 token → 注入 LoginUser + TenantContext → 失败返 40101/40102）|
| 8 | `.../common/security/SecurityProperties.java` | 新增 | @ConfigurationProperties(prefix="eaiselp.security") 配置绑定（jwt.secret/expire-seconds/force-https）|
| 9 | `.../common/result/ResultCode.java` | 新增 | 业务错误码常量（40001/40002/40101/40102/40301/50000）|
| 10 | `.../common/web/GlobalExceptionHandler.java` | 新增 | @RestControllerAdvice 统一异常→R 转换 |
| 11 | `.../common/web/CorsConfig.java` | 新增 | 开发期 CORS 全局配置（allowedOriginPatterns("*")）|
| **eaiselp-data（library，§2.1-2.3）** | | | |
| 12 | `.../data/entity/Permission.java` | 新增 | 权限原子实体（extends BaseEntity）|
| 13 | `.../data/entity/Role.java` | 新增 | 角色实体（extends BaseEntity）|
| 14 | `.../data/entity/RolePermission.java` | 新增 | 角色-权限关联（轻量，不 extends BaseEntity，无 tenant_id/is_deleted）|
| 15 | `.../data/entity/UserRole.java` | 新增 | 用户-角色关联（轻量，含 tenant_id）|
| 16 | `.../data/entity/ServiceAccount.java` | 新增 | AI 服务账号实体（M4 预留，extends BaseEntity）|
| 17 | `.../data/mapper/PermissionMapper.java` | 新增 | BaseMapper<Permission> + 自定义权限聚合查询 |
| 18 | `.../data/mapper/RoleMapper.java` | 新增 | BaseMapper<Role> |
| 19 | `.../data/mapper/RolePermissionMapper.java` | 新增 | BaseMapper<RolePermission> |
| 20 | `.../data/mapper/UserRoleMapper.java` | 新增 | BaseMapper<UserRole> + 按 user_id 查角色码 |
| 21 | `.../data/mapper/ServiceAccountMapper.java` | 新增 | BaseMapper<ServiceAccount>（M4 用）|
| 22 | `.../data/service/PermissionService.java` + `impl/PermissionServiceImpl.java` | 新增 | 权限聚合服务（按 userId 查 roles + permissions；按 roleCodes 校验 permission）|
| 23 | `eaiselp-data/src/main/resources/db/schema.sql` | 修改 | 追加 5 表 CREATE + 31 权限 seed + 5 角色 seed + 角色-权限 seed + admin 角色绑定 seed |
| **eaiselp-auth（service，§2.6-2.7，落地认证服务）** | | | |
| 24 | `eaiselp-auth/pom.xml` | 修改 | 新增 data 依赖 + spring-security-crypto（BCrypt）|
| 25 | `eaiselp-auth/src/main/resources/application.yml` | 修改 | 新增 datasource + mybatis-plus + eaiselp.security.jwt 配置 |
| 26 | `.../auth/EaiselpAuthApplication.java` | 修改 | 加 @MapperScan("com.eaiselp.data.mapper") + scanBasePackages 追加 data |
| 27 | `.../auth/controller/AuthController.java` | 新增 | 3 个 auth API（login/current/logout）|
| 28 | `.../auth/service/AuthService.java` + `impl/AuthServiceImpl.java` | 新增 | 登录业务逻辑（BCrypt 校验 + 查角色权限 + 签 JWT + 更新 last_login_at）|
| 29 | `.../auth/dto/LoginRequest.java` | 新增 | 登录入参 DTO |
| 30 | `.../auth/dto/LoginResponse.java` | 新增 | 登录出参 DTO（token + expiresIn + user）|
| 31 | `.../auth/dto/UserInfo.java` | 新增 | 用户信息 DTO（id/username/displayName/tenantId/tenantName/roles/roleCodes/permissions/avatar）|
| 32 | `.../auth/config/AuthWebMvcConfig.java` | 新增 | 注册 JwtAuthInterceptor + 白名单（/api/v1/auth/login）|
| **eaiselp-runtime（service，§2.8，权限校验落地）** | | | |
| 33 | `.../runtime/config/RuntimeWebMvcConfig.java` | 新增 | 注册 JwtAuthInterceptor + PermissionInterceptor + 白名单（无，全鉴权）|
| 34 | `.../runtime/security/PermissionInterceptor.java` | 新增 | @RequiresPermission 校验拦截器（查 PermissionService）|
| 35 | `.../runtime/controller/PermissionDemoController.java` | 新增 | Phase 1 测试桩（@RequiresPermission("tenant:view")），Phase 2 删 |

### 1.3 前端改动清单（新建 eaiselp-web 工程）

| # | 文件路径（相对 platform 根）| 类型 | 说明 |
|---|---|---|---|
| 36 | `eaiselp-web/config.js` | 新增 | API 地址配置（AUTH_BASE_URL + API_BASE_URL）|
| 37 | `eaiselp-web/assets/js/api.js` | 新增 | $.ajax 封装，自动带 Authorization，401 自动跳登录 |
| 38 | `eaiselp-web/assets/js/auth.js` | 新增 | 登录态管理（token 存取/清理/获取）|
| 39 | `eaiselp-web/assets/js/menu.js` | 新增 | 按角色码→菜单项映射 + 动态渲染 |
| 40 | `eaiselp-web/assets/js/i18n.js` | 新增 | 文案 key-value（M2 只中文，预留 i18n 结构）|
| 41 | `eaiselp-web/login.html` | 新增 | 登录页 |
| 42 | `eaiselp-web/index.html` | 新增 | 主框架（顶部栏 + 左侧导航 + Tab 内容区）|
| 43 | `eaiselp-web/assets/css/app.css` | 新增 | 平台样式补丁 |
| 44 | `eaiselp-web/assets/js/jquery-3.7.1.min.js` | 新增（下载）| 第三方库，Dev 从 CDN 下载 |
| 45 | `eaiselp-web/assets/js/bootstrap.bundle.min.js` | 新增（下载）| 第三方库 |
| 46 | `eaiselp-web/assets/css/bootstrap.min.css` | 新增（下载）| 第三方库 |

---

## 2. 后端改动（精确到文件:行）

### 2.1 新增权限 Entity（eaiselp-data）

参考现有 `Derivation.java` / `Artifact.java` 风格（extends BaseEntity + @TableName + @Data + @EqualsAndHashCode(callSuper=true)）。BaseEntity（`eaiselp-common/.../entity/BaseEntity.java:10-33`）已含 id(@TableId ASSIGN_ID) + tenantId(INSERT 填充) + createTime + updateTime + createBy + updateBy + deleted(@TableLogic)。

#### 2.1.1 Permission.java（权限原子，extends BaseEntity）

路径：`eaiselp-data/src/main/java/com/eaiselp/data/entity/Permission.java`

```java
package com.eaiselp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_permission")
public class Permission extends BaseEntity {
    private String permissionCode;   // 权限码，如 user:create（UNIQUE）
    private String permissionName;   // 中文名
    private String module;           // 模块：user/tenant/system/...
    private String resourceType;     // 资源类型
    private String action;           // 动作：view/create/edit/...
    private String description;
}
```

> **注**：t_permission 是系统级表（所有租户共享），tenant_id 恒为 0。但因 extends BaseEntity，INSERT 时 EaiselpMetaObjectHandler 会用 TenantContext.get() 填充——seed 执行时 TenantContext 未设置（=0 SYSTEM_TENANT），填充值=0，符合预期。运行时查询此表需在 `EaiselpTenantHandler.IGNORE_TABLES` 免过滤（见 §2.4.2）。

#### 2.1.2 Role.java（角色，extends BaseEntity）

路径：`eaiselp-data/src/main/java/com/eaiselp/data/entity/Role.java`

```java
package com.eaiselp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_role")
public class Role extends BaseEntity {
    private String roleCode;         // platform_admin/tenant_admin/...
    private String roleName;         // 中文名
    private String roleType;         // system_template / custom
    private String dataScope;        // all / tenant / self
    private Integer isBuiltIn;       // 1=系统预置不可删, 0=可删
    private String description;
}
```

> tenant_id：模板角色=0（系统级）；custom 角色（M3）=租户 ID。同样需 IGNORE_TABLES 免过滤。

#### 2.1.3 RolePermission.java（角色-权限关联，**轻量，不 extends BaseEntity**）

路径：`eaiselp-data/src/main/java/com/eaiselp/data/entity/RolePermission.java`

```java
package com.eaiselp.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 角色-权限关联（N:N）。轻量实体：不继承 BaseEntity（无 tenant_id/updateTime/is_deleted）。
 * 删除即物理删除（关联无逻辑删除诉求）。
 */
@Data
@TableName("t_role_permission")
public class RolePermission implements Serializable {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long roleId;
    private Long permissionId;
    private LocalDateTime createTime;
}
```

#### 2.1.4 UserRole.java（用户-角色关联，**轻量，含 tenant_id**）

路径：`eaiselp-data/src/main/java/com/eaiselp/data/entity/UserRole.java`

```java
package com.eaiselp.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户-角色关联（N:N）。轻量实体：含 tenant_id（隔离用），不含 updateTime/is_deleted。
 * Phase 1 按 user_id 显式查询（加入 IGNORE_TABLES 免 tenant 自动过滤，避免跨场景歧义）。
 */
@Data
@TableName("t_user_role")
public class UserRole implements Serializable {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long tenantId;
    private Long userId;
    private Long roleId;
    private LocalDateTime createTime;
    private String createBy;
}
```

#### 2.1.5 ServiceAccount.java（AI 服务账号，M4 预留，extends BaseEntity）

路径：`eaiselp-data/src/main/java/com/eaiselp/data/entity/ServiceAccount.java`

```java
package com.eaiselp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_service_account")
public class ServiceAccount extends BaseEntity {
    private String accountCode;      // team-po / derivation-engine
    private String accountName;
    private String accountType;      // role_agent / system_service
    private String apiKey;           // M4 用
    private String allowedRoles;     // JSON 字符串（MySQL JSON 列，实体用 String 接）
    private String status;           // active / disabled
    private LocalDateTime expireTime;
}
```

> **M2 Phase 1 只建表不 seed**（PRD §6.2.5）。Entity 先建好，M4 启用时直接用。

---

### 2.2 新增 Mapper（eaiselp-data）

参考现有 `UserMapper.java`（`eaiselp-data/.../mapper/UserMapper.java:1-8`）风格：`@Mapper public interface XxxMapper extends BaseMapper<Xxx> {}`。

#### 2.2.1 PermissionMapper.java

路径：`eaiselp-data/src/main/java/com/eaiselp/data/mapper/PermissionMapper.java`

```java
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
```

#### 2.2.2 RoleMapper.java

路径：`eaiselp-data/src/main/java/com/eaiselp/data/mapper/RoleMapper.java`

```java
package com.eaiselp.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaiselp.data.entity.Role;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RoleMapper extends BaseMapper<Role> {}
```

#### 2.2.3 RolePermissionMapper.java

```java
package com.eaiselp.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaiselp.data.entity.RolePermission;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RolePermissionMapper extends BaseMapper<RolePermission> {}
```

#### 2.2.4 UserRoleMapper.java

路径：`eaiselp-data/src/main/java/com/eaiselp/data/mapper/UserRoleMapper.java`

```java
package com.eaiselp.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaiselp.data.entity.UserRole;
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
```

> `UserRoleView` 是投影 VO，放在 `eaiselp-data/src/main/java/com/eaiselp/data/mapper/vo/UserRoleView.java`（含 roleId/roleCode/roleName + @Data）。MyBatis 自动驼峰映射。

#### 2.2.5 ServiceAccountMapper.java（M4 预留）

```java
package com.eaiselp.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaiselp.data.entity.ServiceAccount;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ServiceAccountMapper extends BaseMapper<ServiceAccount> {}
```

---

### 2.3 新增 DDL（schema.sql 追加）

**追加位置**：`eaiselp-data/src/main/resources/db/schema.sql` 文件末尾（现有第 222 行 dogfooding seed 之后）。Dev 用 Edit 在文件末尾追加。

**设计原则（对齐现有表风格）**：
- 主键 `BIGINT NOT NULL` 无 AUTO_INCREMENT（应用层 ASSIGN_ID 雪花分配，同 t_user/t_case 等）。
- 标准审计字段：`create_time DATETIME DEFAULT CURRENT_TIMESTAMP` + `update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP` + `create_by/update_by VARCHAR(64)` + `is_deleted TINYINT DEFAULT 0`。
- 关联表（t_role_permission/t_user_role）轻量，仅 create_time（+ create_by）。
- 字符集 utf8mb4，ENGINE=InnoDB。
- seed 用 `INSERT IGNORE`（依赖 UNIQUE 约束保证幂等，重跑不报错）。

#### 2.3.1 5 张表 CREATE TABLE

```sql
-- ============ M2 Phase 1：RBAC 权限系统（5 张表）============

-- 9. 权限原子表（系统级，所有租户共享，tenant_id 恒为 0）
DROP TABLE IF EXISTS `t_permission`;
CREATE TABLE `t_permission` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `permission_code` VARCHAR(64) NOT NULL,
  `permission_name` VARCHAR(128) NOT NULL,
  `module` VARCHAR(32) NOT NULL,
  `resource_type` VARCHAR(32) DEFAULT NULL,
  `action` VARCHAR(32) DEFAULT NULL,
  `description` VARCHAR(500) DEFAULT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_permission_code` (`permission_code`),
  KEY `idx_module` (`module`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限原子表（系统级）';

-- 10. 角色定义表（模板角色 tenant_id=0 系统级；custom 角色 tenant_id=租户ID）
DROP TABLE IF EXISTS `t_role`;
CREATE TABLE `t_role` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `role_code` VARCHAR(64) NOT NULL,
  `role_name` VARCHAR(128) NOT NULL,
  `role_type` VARCHAR(16) NOT NULL DEFAULT 'system_template',
  `data_scope` VARCHAR(16) NOT NULL DEFAULT 'tenant',
  `is_builtin` TINYINT NOT NULL DEFAULT 1,
  `description` VARCHAR(500) DEFAULT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_rolecode` (`tenant_id`, `role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色定义表';

-- 11. 角色-权限关联表（N:N，轻量，无 tenant_id/is_deleted）
DROP TABLE IF EXISTS `t_role_permission`;
CREATE TABLE `t_role_permission` (
  `id` BIGINT NOT NULL,
  `role_id` BIGINT NOT NULL,
  `permission_id` BIGINT NOT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_perm` (`role_id`, `permission_id`),
  KEY `idx_permission` (`permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-权限关联表';

-- 12. 用户-角色关联表（N:N，含 tenant_id 隔离）
DROP TABLE IF EXISTS `t_user_role`;
CREATE TABLE `t_user_role` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `user_id` BIGINT NOT NULL,
  `role_id` BIGINT NOT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
  KEY `idx_tenant_user` (`tenant_id`, `user_id`),
  KEY `idx_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-角色关联表';

-- 13. AI 服务账号表（M4 预留，M2 Phase 1 只建表不 seed）
DROP TABLE IF EXISTS `t_service_account`;
CREATE TABLE `t_service_account` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `account_code` VARCHAR(64) NOT NULL,
  `account_name` VARCHAR(128) NOT NULL,
  `account_type` VARCHAR(32) NOT NULL,
  `api_key` VARCHAR(256) DEFAULT NULL,
  `allowed_roles` JSON DEFAULT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'active',
  `expire_time` DATETIME DEFAULT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_accountcode` (`tenant_id`, `account_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 服务账号表（M4）';
```

#### 2.3.2 权限原子 seed（31 条，固定 id 1001-1031）

> id 固定是为了 t_role_permission seed 可读引用。permission_code 与 PRD §6.3 清单逐条对齐。

```sql
-- ============ M2 Phase 1：RBAC seed 数据 ============
-- 幂等：INSERT IGNORE 依赖 UNIQUE 约束，重跑不报错

-- 权限原子（31 条，id 1001-1031）
INSERT IGNORE INTO `t_permission` (`id`, `tenant_id`, `permission_code`, `permission_name`, `module`, `resource_type`, `action`, `description`) VALUES
(1001, 0, 'system:config:view',    '系统配置查看',   'system',   'system',  'view',   NULL),
(1002, 0, 'system:config:edit',    '系统配置编辑',   'system',   'system',  'edit',   NULL),
(1003, 0, 'system:monitor:view',   '系统监控查看',   'system',   'system',  'view',   NULL),
(1004, 0, 'system:log:view',       '系统日志查看',   'system',   'system',  'view',   NULL),
(1005, 0, 'tenant:view',           '租户查看',       'tenant',   'tenant',  'view',   NULL),
(1006, 0, 'tenant:create',         '租户创建',       'tenant',   'tenant',  'create', NULL),
(1007, 0, 'tenant:edit',           '租户编辑',       'tenant',   'tenant',  'edit',   NULL),
(1008, 0, 'tenant:disable',        '租户禁用',       'tenant',   'tenant',  'disable',NULL),
(1009, 0, 'user:view',             '用户查看',       'user',     'user',    'view',   NULL),
(1010, 0, 'user:create',           '用户创建',       'user',     'user',    'create', NULL),
(1011, 0, 'user:edit',             '用户编辑',       'user',     'user',    'edit',   NULL),
(1012, 0, 'user:disable',          '用户禁用',       'user',     'user',    'disable',NULL),
(1013, 0, 'user:reset-password',   '重置用户密码',   'user',     'user',    'reset-password', NULL),
(1014, 0, 'role:view',             '角色查看',       'role',     'role',    'view',   NULL),
(1015, 0, 'role:create',           '角色创建(M3)',   'role',     'role',    'create', 'M3 解锁，Phase 1 seed 但不授予'),
(1016, 0, 'role:edit',             '角色编辑(M3)',   'role',     'role',    'edit',   'M3 解锁，Phase 1 seed 但不授予'),
(1017, 0, 'model:routing:view',    '模型路由查看',   'model',    'model',   'view',   NULL),
(1018, 0, 'model:routing:edit',    '模型路由编辑',   'model',    'model',   'edit',   NULL),
(1019, 0, 'adapter:config:view',   '适配器配置查看', 'adapter',  'adapter', 'view',   NULL),
(1020, 0, 'adapter:config:edit',   '适配器配置编辑', 'adapter',  'adapter', 'edit',   NULL),
(1021, 0, 'program:view',          '项目群查看',     'program',  'program', 'view',   NULL),
(1022, 0, 'program:create',        '项目群创建',     'program',  'program', 'create', NULL),
(1023, 0, 'case:view',             'Case 查看',      'case',     'case',    'view',   NULL),
(1024, 0, 'case:create',           'Case 创建',      'case',     'case',    'create', NULL),
(1025, 0, 'case:derive',           'Case 派生',      'case',     'case',    'derive', NULL),
(1026, 0, 'case:checkpoint:confirm','检查点确认',     'case',     'case',    'confirm',NULL),
(1027, 0, 'artifact:view',         '产物查看',       'artifact', 'artifact','view',   NULL),
(1028, 0, 'artifact:download',     '产物下载',       'artifact', 'artifact','download',NULL),
(1029, 0, 'strategy:view',         '战略看板查看',   'strategy', 'strategy','view',   NULL),
(1030, 0, 'quota:view',            '配额查看',       'quota',    'quota',   'view',   NULL),
(1031, 0, 'quota:edit',            '配额编辑',       'quota',    'quota',   'edit',   NULL);
```

#### 2.3.3 5 模板角色 seed（id 1-5，tenant_id=0 系统级）

```sql
-- 5 模板角色（tenant_id=0 系统级预置，所有租户共享；data_scope 决定分配后的数据可见范围）
INSERT IGNORE INTO `t_role` (`id`, `tenant_id`, `role_code`, `role_name`, `role_type`, `data_scope`, `is_builtin`, `description`) VALUES
(1, 0, 'platform_admin',   '平台管理员', 'system_template', 'all',     1, '平台全局管理员，data_scope=all 跨租户'),
(2, 0, 'tenant_admin',     '企业管理员', 'system_template', 'tenant',  1, '租户管理员，data_scope=tenant 限本租户'),
(3, 0, 'project_manager',  '项目经理',   'system_template', 'tenant',  1, '项目经理，管项目群与 Case'),
(4, 0, 'engineer',         '工程师',     'system_template', 'self',    1, '工程师，data_scope=self 仅本人数据'),
(5, 0, 'executive',        '高管',       'system_template', 'tenant',  1, '高管，看战略看板与产物');
```

#### 2.3.4 角色-权限关联 seed（按 PRD §6.4 矩阵）

> **SE 核对发现**：PRD §6.4 矩阵摘要写"platform_admin 22 项"，但逐行数矩阵 platform_admin 列的 ✓ 实为 **29 项**（= 31 总权限 - role:create - role:edit）。摘要"22"疑为 PO 笔误。本 seed **以矩阵逐行 ✓ 为权威**生成 29 条。已列入 §6 开放问题传导 PO 确认。

```sql
-- 角色-权限关联（按 PRD §6.4 矩阵；id 2001+ 自增分配，引用 role_id 1-5 与 permission_id 1001-1031）

-- platform_admin (role_id=1)：29 项（全部除 role:create/role:edit）
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2001,1,1001),(2002,1,1002),(2003,1,1003),(2004,1,1004),     -- system ×4
(2005,1,1005),(2006,1,1006),(2007,1,1007),(2008,1,1008),     -- tenant ×4
(2009,1,1009),(2010,1,1010),(2011,1,1011),(2012,1,1012),(2013,1,1013), -- user ×5
(2014,1,1014),                                                -- role:view ×1
(2015,1,1017),(2016,1,1018),                                  -- model ×2
(2017,1,1019),(2018,1,1020),                                  -- adapter ×2
(2019,1,1021),(2020,1,1022),                                  -- program ×2
(2021,1,1023),(2022,1,1024),(2023,1,1025),(2024,1,1026),      -- case ×4
(2025,1,1027),(2026,1,1028),                                  -- artifact ×2
(2027,1,1029),                                                -- strategy ×1
(2028,1,1030),(2029,1,1031);                                  -- quota ×2

-- tenant_admin (role_id=2)：15 项（user ×5 本租户 + role:view + program ×2 + case ×4 + artifact ×2 + quota:view）
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2030,2,1009),(2031,2,1010),(2032,2,1011),(2033,2,1012),(2034,2,1013), -- user ×5
(2035,2,1014),                                                -- role:view
(2036,2,1021),(2037,2,1022),                                  -- program ×2
(2038,2,1023),(2039,2,1024),(2040,2,1025),(2041,2,1026),      -- case ×4
(2042,2,1027),(2043,2,1028),                                  -- artifact ×2
(2044,2,1030);                                                -- quota:view

-- project_manager (role_id=3)：7 项（program:view + case ×4 + artifact ×2）
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2045,3,1021),                                                -- program:view
(2046,3,1023),(2047,3,1024),(2048,3,1025),(2049,3,1026),      -- case ×4
(2050,3,1027),(2051,3,1028);                                  -- artifact ×2

-- engineer (role_id=4)：4 项（case:view + case:derive + artifact ×2）
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2052,4,1023),                                                -- case:view
(2053,4,1025),                                                -- case:derive
(2054,4,1027),(2055,4,1028);                                  -- artifact ×2

-- executive (role_id=5)：2 项（artifact:view + strategy:view）
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2056,5,1027),                                                -- artifact:view
(2057,5,1029);                                                -- strategy:view
```

#### 2.3.5 给 dogfooding admin 分配 tenant_admin 角色 + 同步 t_user.roles

```sql
-- 给 dogfooding admin (user_id=1) 分配 tenant_admin (role_id=2)，tenant_id=1
INSERT IGNORE INTO `t_user_role` (`id`, `tenant_id`, `user_id`, `role_id`, `create_by`) VALUES
(3001, 1, 1, 2, 'system-seed');

-- 同步 t_user.roles 冗余字段（Q-1：t_user.roles 冗余快速读取 + t_user_role 权威源，seed 一次性同步）
-- admin 现有 roles='tenant_admin,ea,pgm,orchestrator'（schema.sql:218），保留不变（已含 tenant_admin）
-- 若需精简为纯 tenant_admin：UPDATE `t_user` SET `roles`='tenant_admin' WHERE `id`=1;
-- Phase 1 决策：保留现有多角色字符串不变（Q-3：ea/pgm/orchestrator 不映射平台导航，但保留记录）
```

> **Q-1 同步策略裁决**（详见 §6）：Phase 1 无"分配角色"UI，不存在运行时双向同步；seed 一次性写入 t_user_role + t_user.roles 已一致（admin 的 roles 字符串已含 tenant_admin）。运行时 login/current **读 t_user_role 为权威源**，t_user.roles 仅作展示冗余。Phase 2 用户管理 UI 实现分配角色时，再写"分配即同步两边"的事务逻辑。

---

### 2.4 common 模块改造（JWT 工具 + 注解 + 拦截器 + 异常 + CORS）

#### 2.4.1 common/pom.xml 新增 jjwt 依赖

**改动位置**：`eaiselp-common/pom.xml` 现有 `<dependencies>` 段（第 11-44 行）末尾追加。

**对照 HEAD**：第 44 行 `</dependencies>` 之前新增 3 个 jjwt artifact。

```xml
        <!-- JWT（HS256）—— JwtUtil 在 common，被 auth/runtime/gateway 共用 -->
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
            <version>${jjwt.version}</version>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <version>${jjwt.version}</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <version>${jjwt.version}</version>
            <scope>runtime</scope>
        </dependency>
```

**父 POM（`pom.xml`）properties 段（第 25-42 行）新增版本号**：

```xml
        <jjwt.version>0.12.6</jjwt.version>
```

> **jjwt 版本裁决（§6）**：选 0.12.6（2024 稳定线）。理由：(1) 不在 Spring Boot 3.2.5 BOM，必须显式引；(2) 0.12.x 是新 API 线（Jwts.SIG.HS256 / parseSignedClaims），0.11.x 是旧 API 即将 EOL；(3) 要求 JDK 17+，与项目 java.version=17 兼容；(4) jjwt 0.12.6 是 0.12 线最新修补版。父 POM 统一管理版本，子模块不写 version。

#### 2.4.2 EaiselpTenantHandler.IGNORE_TABLES 追加权限表

**改动位置**：`eaiselp-common/src/main/java/com/eaiselp/common/tenant/EaiselpTenantHandler.java:12-14`。

**对照 HEAD**：现有 `IGNORE_TABLES` 数组（第 12-14 行）含 5 张表。新增 5 张权限系统表。

```java
    private static final String[] IGNORE_TABLES = {
        "t_tenant", "t_user", "t_system_config", "t_system_version", "t_quota_template",
        // M2 Phase 1 新增：权限系统表为系统级共享，免 tenant 过滤
        "t_permission", "t_role", "t_role_permission", "t_user_role", "t_service_account"
    };
```

> **为什么**：TenantLineInnerInterceptor 会自动给所有非 IGNORE 表的 SQL 注入 `WHERE tenant_id=?`。权限系统表是系统级（tenant_id=0）或按 user_id 显式查，自动注入 tenant_id 会导致查不到数据（如 login 时 TenantContext=0，查 t_user_role 注入 tenant_id=0，但 admin 的 t_user_role.tenant_id=1，查空）。5 张表全部 IGNORE。

#### 2.4.3 ResultCode.java（错误码常量）

路径：`eaiselp-common/src/main/java/com/eaiselp/common/result/ResultCode.java`

```java
package com.eaiselp.common.result;

/** 业务错误码常量（对齐 PRD §5.1.3）。 */
public final class ResultCode {
    public static final int SUCCESS = 0;
    public static final int BAD_CREDENTIAL = 40001;   // 用户名或密码错误（不区分用户不存在 vs 密码错，防枚举）
    public static final int ACCOUNT_DISABLED = 40002; // 账户已禁用
    public static final int UNAUTHORIZED = 40101;     // 未登录或 token 缺失
    public static final int TOKEN_INVALID = 40102;    // token 无效或已过期
    public static final int FORBIDDEN = 40301;        // 无权限访问该资源
    public static final int RATE_LIMITED = 42901;     // 预留：gateway 限流
    public static final int INTERNAL_ERROR = 50000;   // 服务内部错误
    private ResultCode() {}
}
```

#### 2.4.4 SecurityProperties.java（配置绑定）

路径：`eaiselp-common/src/main/java/com/eaiselp/common/security/SecurityProperties.java`

```java
package com.eaiselp.common.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "eaiselp.security")
public class SecurityProperties {
    private final Jwt jwt = new Jwt();
    /** 生产强制 HTTPS（M2 开发期 false）。 */
    private boolean forceHttps = false;

    @Data
    public static class Jwt {
        /** HS256 密钥（base64 或明文，≥32 字节）。严禁明文写死，走 ${JWT_SECRET:dev-placeholder}。 */
        private String secret = "${JWT_SECRET:dev-placeholder-secret-must-be-at-least-32-bytes-long-for-hs256}";
        /** 有效期秒数，默认 24h。 */
        private long expireSeconds = 86400L;
        /** 签发方。 */
        private String issuer = "eaiselp-auth";
    }
}
```

> **密钥占位说明**：yml 用 `${JWT_SECRET:dev-placeholder-...}`，运维通过环境变量 JWT_SECRET 注入生产密钥（同 GLM_API_KEY 模式，见 application.yml:30）。dev 占位密钥 ≥32 字节满足 HS256 要求。生产必须替换。

#### 2.4.5 JwtClaims.java + JwtUtil.java

路径：`eaiselp-common/src/main/java/com/eaiselp/common/security/JwtClaims.java`

```java
package com.eaiselp.common.security;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/** JWT payload 载体（对齐 PRD §5.2.1）。不含 permissions（Q-5：防 token 膨胀）。 */
@Data
@Builder
public class JwtClaims {
    private Long userId;
    private String username;
    private String displayName;
    private Long tenantId;
    private String tenantCode;
    private List<String> roles;   // 角色码数组
    private Long iat;             // 签发时间（秒）
    private Long exp;             // 过期时间（秒）
}
```

路径：`eaiselp-common/src/main/java/com/eaiselp/common/security/JwtUtil.java`

```java
package com.eaiselp.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/** JWT 工具（HS256）。签发方=auth，校验方=runtime/gateway/auth 自身。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtUtil {

    private final SecurityProperties props;
    private SecretKey key;

    @PostConstruct
    public void init() {
        byte[] bytes = props.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT secret 必须 ≥32 字节（HS256 要求），当前=" + bytes.length);
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    /** 签发 token。 */
    public String generate(JwtClaims claims) {
        long now = System.currentTimeMillis();
        long expMs = now + props.getJwt().getExpireSeconds() * 1000L;
        return Jwts.builder()
                .issuer(props.getJwt().getIssuer())
                .subject(String.valueOf(claims.getUserId()))
                .claim("userId", claims.getUserId())
                .claim("username", claims.getUsername())
                .claim("displayName", claims.getDisplayName())
                .claim("tenantId", claims.getTenantId())
                .claim("tenantCode", claims.getTenantCode())
                .claim("roles", claims.getRoles())
                .issuedAt(new Date(now))
                .expiration(new Date(expMs))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** 解析并校验 token，失败抛 JwtException（含 ExpiredJwtException/SignatureException）。 */
    public JwtClaims parse(String token) {
        Claims c = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        Object rolesObj = c.get("roles", List.class);
        @SuppressWarnings("unchecked")
        List<String> roles = rolesObj == null ? List.of() : (List<String>) rolesObj;
        return JwtClaims.builder()
                .userId(c.get("userId", Long.class))
                .username(c.get("username", String.class))
                .displayName(c.get("displayName", String.class))
                .tenantId(c.get("tenantId", Long.class))
                .tenantCode(c.get("tenantCode", String.class))
                .roles(roles)
                .iat(c.getIssuedAt() != null ? c.getIssuedAt().getTime() / 1000 : null)
                .exp(c.getExpiration() != null ? c.getExpiration().getTime() / 1000 : null)
                .build();
    }

    public long getExpireSeconds() {
        return props.getJwt().getExpireSeconds();
    }
}
```

#### 2.4.6 LoginUser.java（当前登录用户 ThreadLocal 上下文）

路径：`eaiselp-common/src/main/java/com/eaiselp/common/security/LoginUser.java`

```java
package com.eaiselp.common.security;

import com.eaiselp.common.tenant.TenantContext;

/** 当前登录用户上下文（ThreadLocal）。JwtAuthInterceptor 解析后注入，请求结束清理。 */
public class LoginUser {
    private static final ThreadLocal<JwtClaims> CURRENT = new ThreadLocal<>();

    public static void set(JwtClaims claims) {
        CURRENT.set(claims);
        if (claims != null && claims.getTenantId() != null) {
            TenantContext.set(claims.getTenantId());   // 同步注入租户上下文（多租户隔离）
        }
    }
    public static JwtClaims get() { return CURRENT.get(); }
    public static Long getUserId() { JwtClaims c = CURRENT.get(); return c == null ? null : c.getUserId(); }
    public static void clear() {
        CURRENT.remove();
        TenantContext.clear();
    }
}
```

> **与 TenantContextFilter 协同**：TenantContextFilter（common/.../tenant/TenantContextFilter.java:14）从 `X-Tenant-Id` Header 解析 tenant。但 JWT 模式下 tenant 来自 token payload（更权威，不可伪造）。JwtAuthInterceptor 优先级高于 Filter？不——Filter 在 DispatcherServlet 之前，Interceptor 在之后。所以 Filter 先跑（可能从 header 设 TenantContext），Interceptor 后跑（用 token 覆盖）。**裁决**：JWT 模式下，tenant 以 token 为准（LoginUser.set 覆盖 TenantContext）。前端不应传 X-Tenant-Id header（Phase 1 单租户），由 token 决定。

#### 2.4.7 RequirePermission.java（注解）

路径：`eaiselp-common/src/main/java/com/eaiselp/common/security/RequirePermission.java`

```java
package com.eaiselp.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限校验注解。标注在 Controller 方法或类上，PermissionInterceptor 拦截校验。
 * 多个权限码为「或」关系（任一满足即通过）；需「且」用 @RequiresPermission 重复标注（M3 支持）。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
    /** 权限码数组，如 {"user:view"} 或 {"case:view","case:derive"}（任一满足）。 */
    String[] value();
}
```

#### 2.4.8 JwtAuthInterceptor.java（JWT 拦截器，只解析不查库）

路径：`eaiselp-common/src/main/java/com/eaiselp/common/security/JwtAuthInterceptor.java`

```java
package com.eaiselp.common.security;

import com.eaiselp.common.result.R;
import com.eaiselp.common.result.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * JWT 认证拦截器：解析 token → 注入 LoginUser + TenantContext。
 * 不查库（权限校验由 PermissionInterceptor 单独负责）。
 * 失败：无 token→40101；token 无效/过期→40102。HTTP 401。
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";
    private static final ObjectMapper OM = new ObjectMapper();

    private final JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws IOException {
        String auth = req.getHeader(HEADER);
        if (auth == null || !auth.startsWith(PREFIX)) {
            return writeUnauthorized(resp, ResultCode.UNAUTHORIZED, "未登录或 token 缺失");
        }
        String token = auth.substring(PREFIX.length()).trim();
        try {
            JwtClaims claims = jwtUtil.parse(token);
            LoginUser.set(claims);
            return true;
        } catch (ExpiredJwtException e) {
            log.info("[Auth] token 过期: user={}", e.getClaims().get("username"));
            return writeUnauthorized(resp, ResultCode.TOKEN_INVALID, "token 无效或已过期");
        } catch (JwtException e) {
            log.info("[Auth] token 无效: {}", e.getMessage());
            return writeUnauthorized(resp, ResultCode.TOKEN_INVALID, "token 无效或已过期");
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp, Object handler, Exception ex) {
        LoginUser.clear();   // 必清，防 ThreadLocal 泄漏
    }

    private boolean writeUnauthorized(HttpServletResponse resp, int code, String msg) throws IOException {
        resp.setStatus(HttpStatus.UNAUTHORIZED.value());
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(OM.writeValueAsString(R.fail(code, msg)));
        return false;
    }
}
```

#### 2.4.9 GlobalExceptionHandler.java（全局异常处理）

路径：`eaiselp-common/src/main/java/com/eaiselp/common/web/GlobalExceptionHandler.java`

```java
package com.eaiselp.common.web;

import com.eaiselp.common.exception.BizException;
import com.eaiselp.common.result.R;
import com.eaiselp.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 全局异常处理：BizException→对应 code；校验异常→40001；未知→50000。 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public R<Void> handleBiz(BizException e) {
        log.warn("[Biz] code={}, msg={}", e.getCode(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValid(MethodArgumentNotValidException e) {
        return R.fail(ResultCode.BAD_CREDENTIAL, "用户名或密码错误");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleUnknown(Exception e) {
        log.error("[Unknown] 服务内部错误", e);
        return R.fail(ResultCode.INTERNAL_ERROR, "服务暂时不可用，请稍后重试");
    }
}
```

> **校验异常为何映射到 40001**：PRD §5.2 校验规则——username/password 空也返回 40001（不暴露字段缺失 vs 凭据错误，防枚举）。故 @Valid 校验失败统一转 40001。

#### 2.4.10 CorsConfig.java（开发期 CORS）

路径：`eaiselp-common/src/main/java/com/eaiselp/common/web/CorsConfig.java`

```java
package com.eaiselp.common.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 开发期 CORS：允许前端（file:// 或 localhost:5500 等）跨域访问。生产需收紧 origin。 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Authorization")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
```

> **CORS 裁决（§6）**：auth 和 runtime 是两个端口（8085/8081），前端跨域访问两者。CorsConfig 放 common，被两个 service 扫到同时生效。开发期 `allowedOriginPatterns("*")` + allowCredentials(true)（Spring 不允许 allowedOrigins("*") + credentials，用 patterns 规避）。生产改成具体 origin 白名单。

---

### 2.5 data 模块新增 PermissionService（权限聚合）

路径：`eaiselp-data/src/main/java/com/eaiselp/data/service/PermissionService.java`

```java
package com.eaiselp.data.service;

import java.util.Collection;
import java.util.List;

/** 权限聚合服务：按 userId 查角色 + 权限；按角色码集合校验某权限。 */
public interface PermissionService {
    /** 查用户的所有角色码（去重）。 */
    List<String> getRoleCodesByUserId(Long userId);
    /** 查用户的所有角色 ID（去重，用于聚合权限）。 */
    List<Long> getRoleIdsByUserId(Long userId);
    /** 按角色 ID 集合查权限码（去重并集）。 */
    List<String> getPermissionCodesByRoleIds(Collection<Long> roleIds);
    /** 校验用户的角色集合是否拥有指定权限码（任一角色持有即 true）。 */
    boolean hasAnyPermission(Collection<Long> roleIds, String permissionCode);
    /** 便捷：按 userId 直接校验权限。 */
    boolean hasPermission(Long userId, String permissionCode);
}
```

路径：`eaiselp-data/src/main/java/com/eaiselp/data/service/impl/PermissionServiceImpl.java`

```java
package com.eaiselp.data.service.impl;

import com.eaiselp.data.mapper.PermissionMapper;
import com.eaiselp.data.mapper.UserRoleMapper;
import com.eaiselp.data.mapper.vo.UserRoleView;
import com.eaiselp.data.service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionService {

    private final UserRoleMapper userRoleMapper;
    private final PermissionMapper permissionMapper;

    @Override
    public List<String> getRoleCodesByUserId(Long userId) {
        return userRoleMapper.selectRolesByUserId(userId).stream()
                .map(UserRoleView::getRoleCode).distinct().collect(Collectors.toList());
    }

    @Override
    public List<Long> getRoleIdsByUserId(Long userId) {
        return userRoleMapper.selectRolesByUserId(userId).stream()
                .map(UserRoleView::getRoleId).distinct().collect(Collectors.toList());
    }

    @Override
    public List<String> getPermissionCodesByRoleIds(Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) return Collections.emptyList();
        return permissionMapper.selectPermissionCodesByRoleIds(roleIds).stream()
                .distinct().collect(Collectors.toList());
    }

    @Override
    public boolean hasAnyPermission(Collection<Long> roleIds, String permissionCode) {
        return getPermissionCodesByRoleIds(roleIds).contains(permissionCode);
    }

    @Override
    public boolean hasPermission(Long userId, String permissionCode) {
        return hasAnyPermission(getRoleIdsByUserId(userId), permissionCode);
    }
}
```

路径：`eaiselp-data/src/main/java/com/eaiselp/data/mapper/vo/UserRoleView.java`

```java
package com.eaiselp.data.mapper.vo;

import lombok.Data;

/** 用户-角色投影 VO（MyBatis 自动驼峰映射 role_id→roleId 等）。 */
@Data
public class UserRoleView {
    private Long roleId;
    private String roleCode;
    private String roleName;
}
```

---

### 2.6 auth 模块落地（独立 service，端口 8085）

#### 2.6.1 SE 裁决：auth 独立 service 完整承载认证 API

**裁决**：Phase 1 让 eaiselp-auth 作为**独立 service（端口 8085）**完整承载 3 个 auth API。**不**让 runtime 代管。

**理由**：
1. **ES-001 §1.3 强制**：eaiselp-auth 定性为 service（独立进程、repackage、@SpringBootApplication）。降级为 library 由 runtime 代管，违反 ADR-001 模块定性（阻断级），需提 ADR 变更，SE 无权单方修改。
2. **PRD F3 意图**：PO 明确写"eaiselp-auth（落地）"，意图是 auth 模块承载认证业务，非空骨架。
3. **JWT 签发方契约**：PRD §5.2.1 JWT 签发方=`eaiselp-auth`，与 auth 独立一致。
4. **避免 M3 迁移成本**：Phase 1 一次性把 auth 做对，M3 gateway 落地后只需加路由，无需迁移 auth 逻辑。
5. **依赖合法**：auth 依赖 common + data（ES-001 §4.2：library 可被多 service 依赖，common 已被所有 service 依赖，data 作为 library 同理可被 auth + runtime 共享）。依赖图 auth→data→common，runtime→data→common，无环（P3 合规）。

**对 PRD §5.1/§8.6 的修正（传导 PO）**：PRD 写"前端直连 runtime:8081（单 base-url）"是基于"auth API 也在 runtime"的假设。SE 裁决 auth 独立后，前端需双 base-url：`AUTH_BASE_URL=http://localhost:8085`（login/current/logout）+ `API_BASE_URL=http://localhost:8081`（Phase 2 业务 API）。前端 api.js 封装屏蔽复杂度（§3.4）。此为 SE 技术裁决对 PRD 的修正，请 PO 同步 PRD §5.1/§8.6（列入 §6 Q-4 扩展裁决）。

#### 2.6.2 auth/pom.xml 新增依赖

**对照 HEAD**：`eaiselp-auth/pom.xml:10-14` 现有 starter-web + nacos-discovery + common。新增 data + spring-security-crypto。

```xml
    <dependencies>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
        <dependency><groupId>com.alibaba.cloud</groupId><artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId></dependency>
        <dependency><groupId>com.eaiselp</groupId><artifactId>eaiselp-common</artifactId></dependency>
        <!-- M2 Phase 1 新增：查用户/角色/权限 -->
        <dependency><groupId>com.eaiselp</groupId><artifactId>eaiselp-data</artifactId><version>${project.version}</version></dependency>
        <!-- M2 Phase 1 新增：BCrypt 校验（仅 crypto，不引完整 spring-security 避免自动启用 CSRF/默认认证）-->
        <dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-crypto</artifactId></dependency>
        <!-- M2 Phase 1 新增：MySQL 驱动（auth 独立查库）-->
        <dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId></dependency>
    </dependencies>
```

> **BCrypt 依赖裁决（§6）**：只引 `spring-security-crypto`（轻量，仅密码编码），**不**引 `spring-boot-starter-security`（会自动启用默认认证链、CSRF、form login，与 JWT 无状态模式冲突）。spring-security-crypto 版本由 Spring Boot 3.2.5 BOM 管理（6.2.x），无需写 version。

#### 2.6.3 auth/application.yml 完整配置

**对照 HEAD**：`eaiselp-auth/src/main/resources/application.yml` 现有仅 server.port + nacos（9 行）。新增 datasource + mybatis-plus + eaiselp.security。

```yaml
server:
  port: 8085
spring:
  application:
    name: eaiselp-auth
  datasource:                                                   # M2 Phase 1 新增：auth 独立查 t_user/t_user_role
    url: jdbc:mysql://${MYSQL_HOST:localhost}:${MYSQL_PORT:3306}/eaiselp?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
    username: ${MYSQL_USER:root}
    password: ${MYSQL_PASSWORD:root}
    driver-class-name: com.mysql.cj.jdbc.Driver
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_HOST:localhost}:${NACOS_PORT:8848}
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    db-config:
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0
eaiselp:
  security:                                                     # M2 Phase 1 新增：JWT 配置
    jwt:
      secret: ${JWT_SECRET:dev-placeholder-secret-must-be-at-least-32-bytes-long-for-hs256-algorithm}
      expire-seconds: 86400
      issuer: eaiselp-auth
    force-https: false                                           # M2 开发期 false，生产 true
```

#### 2.6.4 EaiselpAuthApplication 改造

**对照 HEAD**：`eaiselp-auth/src/main/java/com/eaiselp/auth/EaiselpAuthApplication.java:7-9`。scanBasePackages 追加 data；加 @MapperScan。

```java
package com.eaiselp.auth;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@MapperScan("com.eaiselp.data.mapper")   // M2 Phase 1：扫 data 模块 mapper
@SpringBootApplication(scanBasePackages = {
        "com.eaiselp.auth",
        "com.eaiselp.common",
        "com.eaiselp.data"                // M2 Phase 1：让 data 的 @Service/@Configuration 被扫到
})
@EnableDiscoveryClient
public class EaiselpAuthApplication {
    public static void main(String[] args) {
        SpringApplication.run(EaiselpAuthApplication.class, args);
    }
}
```

#### 2.6.5 DTO（LoginRequest / LoginResponse / UserInfo）

路径：`eaiselp-auth/src/main/java/com/eaiselp/auth/dto/LoginRequest.java`

```java
package com.eaiselp.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LoginRequest {
    @NotBlank(message = "用户名不能为空")
    @Size(max = 64)
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(max = 128)
    private String password;
}
```

路径：`eaiselp-auth/src/main/java/com/eaiselp/auth/dto/UserInfo.java`

```java
package com.eaiselp.auth.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class UserInfo {
    private Long id;
    private String username;
    private String displayName;
    private String email;
    private Long tenantId;
    private String tenantName;
    private List<String> roles;        // 角色码（=roleCodes，PRD 两者都返回，值相同）
    private List<String> roleCodes;    // 角色码
    private List<String> permissions;  // 权限码（Q-5：不放 JWT payload，/current 实时查）
    private String avatar;
}
```

路径：`eaiselp-auth/src/main/java/com/eaiselp/auth/dto/LoginResponse.java`

```java
package com.eaiselp.auth.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LoginResponse {
    private String token;
    private long expiresIn;     // 有效期秒数（=86400）
    private UserInfo user;
}
```

#### 2.6.6 AuthService（登录业务逻辑）

路径：`eaiselp-auth/src/main/java/com/eaiselp/auth/service/AuthService.java`

```java
package com.eaiselp.auth.service;

import com.eaiselp.auth.dto.LoginRequest;
import com.eaiselp.auth.dto.LoginResponse;
import com.eaiselp.auth.dto.UserInfo;

public interface AuthService {
    LoginResponse login(LoginRequest req);
    UserInfo currentUser(Long userId, Long tenantId);
    void logout(Long userId);
}
```

路径：`eaiselp-auth/src/main/java/com/eaiselp/auth/service/impl/AuthServiceImpl.java`

```java
package com.eaiselp.auth.service.impl;

import com.eaiselp.auth.dto.*;
import com.eaiselp.auth.service.AuthService;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.common.result.ResultCode;
import com.eaiselp.common.security.JwtClaims;
import com.eaiselp.common.security.JwtUtil;
import com.eaiselp.data.entity.Tenant;
import com.eaiselp.data.entity.User;
import com.eaiselp.data.mapper.TenantMapper;
import com.eaiselp.data.mapper.UserMapper;
import com.eaiselp.data.service.PermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final TenantMapper tenantMapper;
    private final PermissionService permissionService;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /** Phase 1 单租户 dogfooding：默认租户 ID。M3 多租户登录页选租户时改为动态。 */
    @Value("${eaiselp.security.default-tenant-id:1}")
    private Long defaultTenantId;

    @Override
    public LoginResponse login(LoginRequest req) {
        long start = System.currentTimeMillis();
        // 1. 按 (tenant_id, username) 查用户。t_user 在 IGNORE_TABLES，需显式带 tenant_id 条件
        User user = userMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                .eq(User::getTenantId, defaultTenantId)
                .eq(User::getUsername, req.getUsername()));
        // 2-3. 用户不存在或密码错 → 统一 40001（防枚举，PRD §5.1.3 安全约定）
        //   即便 user==null 也要走一次 BCrypt 校验（恒定时间，避免通过响应时长区分用户存在性）——
        //   Phase 1 简化：null 直接返回，响应时长差异在 dogfooding 内网可接受；M3 加恒定时延。
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            log.info("[Login] 凭据错误: username={}, tenantId={}, 耗时={}ms",
                    req.getUsername(), defaultTenantId, System.currentTimeMillis() - start);
            throw new BizException(ResultCode.BAD_CREDENTIAL, "用户名或密码错误");
        }
        // 4. 账户禁用 → 40002
        if ("disabled".equalsIgnoreCase(user.getStatus())) {
            log.warn("[Login] 账户禁用: username={}", req.getUsername());
            throw new BizException(ResultCode.ACCOUNT_DISABLED, "账户已被禁用");
        }
        // 5. 查角色 + 权限
        List<String> roleCodes = permissionService.getRoleCodesByUserId(user.getId());
        List<Long> roleIds = permissionService.getRoleIdsByUserId(user.getId());
        List<String> permissions = permissionService.getPermissionCodesByRoleIds(roleIds);
        // 6. 查租户（取 tenantCode/tenantName 填 JWT payload + UserInfo）
        Tenant tenant = tenantMapper.selectById(defaultTenantId);
        String tenantCode = tenant != null ? tenant.getTenantCode() : null;
        String tenantName = tenant != null ? tenant.getTenantName() : null;
        // 7. 签发 JWT（不含 permissions，Q-5）
        JwtClaims claims = JwtClaims.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .tenantId(user.getTenantId())
                .tenantCode(tenantCode)
                .roles(roleCodes)
                .build();
        String token = jwtUtil.generate(claims);
        // 8. 更新 last_login_at（AC-F1.5）
        User update = new User();
        update.setId(user.getId());
        update.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(update);
        log.info("[Login] 登录成功: username={}, tenantId={}, roles={}, 耗时={}ms",
                user.getUsername(), user.getTenantId(), roleCodes, System.currentTimeMillis() - start);
        // 9. 返回
        return LoginResponse.builder()
                .token(token)
                .expiresIn(jwtUtil.getExpireSeconds())
                .user(buildUserInfo(user, tenantName, roleCodes, permissions))
                .build();
    }

    @Override
    public UserInfo currentUser(Long userId, Long tenantId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "用户不存在");
        }
        Tenant tenant = tenantMapper.selectById(tenantId);
        List<String> roleCodes = permissionService.getRoleCodesByUserId(userId);
        List<Long> roleIds = permissionService.getRoleIdsByUserId(userId);
        List<String> permissions = permissionService.getPermissionCodesByRoleIds(roleIds);
        return buildUserInfo(user, tenant != null ? tenant.getTenantName() : null, roleCodes, permissions);
    }

    @Override
    public void logout(Long userId) {
        // M2 无状态：不维护黑名单（M3 做）。仅记录日志。
        log.info("[Logout] userId={}", userId);
    }

    private UserInfo buildUserInfo(User u, String tenantName, List<String> roles, List<String> permissions) {
        return UserInfo.builder()
                .id(u.getId())
                .username(u.getUsername())
                .displayName(u.getDisplayName())
                .email(u.getEmail())
                .tenantId(u.getTenantId())
                .tenantName(tenantName)
                .roles(roles)
                .roleCodes(roles)
                .permissions(permissions)
                .avatar(u.getAvatar())
                .build();
    }
}
```

> **BCrypt 恒定时延说明**：严格防用户枚举应在 user==null 时也执行一次假 BCrypt.matches（恒定响应时长）。Phase 1 dogfooding 内网简化处理，M3 补恒定时延。已列入风险。

#### 2.6.7 AuthController（3 个 API）

路径：`eaiselp-auth/src/main/java/com/eaiselp/auth/controller/AuthController.java`

```java
package com.eaiselp.auth.controller;

import com.eaiselp.auth.dto.LoginRequest;
import com.eaiselp.auth.dto.LoginResponse;
import com.eaiselp.auth.dto.UserInfo;
import com.eaiselp.auth.service.AuthService;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** POST /api/v1/auth/login —— 用户登录（白名单，不需 token）*/
    @PostMapping("/login")
    public R<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return R.ok(authService.login(req));
    }

    /** GET /api/v1/auth/current —— 恢复登录态（需 token，JwtAuthInterceptor 已注入 LoginUser）*/
    @GetMapping("/current")
    public R<UserInfo> current() {
        // LoginUser 由拦截器注入；userId/tenantId 从 JWT claims 取（权威，不可伪造）
        com.eaiselp.common.security.JwtClaims claims = LoginUser.get();
        if (claims == null || claims.getUserId() == null) {
            return R.fail(com.eaiselp.common.result.ResultCode.UNAUTHORIZED, "未登录");
        }
        return R.ok(authService.currentUser(claims.getUserId(), claims.getTenantId()));
    }

    /** POST /api/v1/auth/logout —— 退出（需 token；M2 仅日志，前端清 storage 为主）*/
    @PostMapping("/logout")
    public R<Void> logout() {
        com.eaiselp.common.security.JwtClaims claims = LoginUser.get();
        if (claims != null) {
            authService.logout(claims.getUserId());
        }
        return R.ok();
    }
}
```

#### 2.6.8 AuthWebMvcConfig（注册拦截器 + 白名单）

路径：`eaiselp-auth/src/main/java/com/eaiselp/auth/config/AuthWebMvcConfig.java`

```java
package com.eaiselp.auth.config;

import com.eaiselp.common.security.JwtAuthInterceptor;
import com.eaiselp.common.security.JwtUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AuthWebMvcConfig implements WebMvcConfigurer {

    private final JwtUtil jwtUtil;

    public AuthWebMvcConfig(JwtUtil jwtUtil) { this.jwtUtil = jwtUtil; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new JwtAuthInterceptor(jwtUtil))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/v1/auth/login");   // 仅 login 白名单
    }
}
```

---

### 2.7 runtime 模块改造（权限校验落地）

#### 2.7.1 PermissionInterceptor（@RequiresPermission 校验）

路径：`eaiselp-runtime/src/main/java/com/eaiselp/runtime/security/PermissionInterceptor.java`

```java
package com.eaiselp.runtime.security;

import com.eaiselp.common.result.R;
import com.eaiselp.common.result.ResultCode;
import com.eaiselp.common.security.LoginUser;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.data.service.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/** @RequiresPermission 校验拦截器：查 PermissionService，任一权限码满足即通过，否则 40301。 */
@Slf4j
@RequiredArgsConstructor
public class PermissionInterceptor implements HandlerInterceptor {

    private static final ObjectMapper OM = new ObjectMapper();
    private final PermissionService permissionService;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws IOException {
        if (!(handler instanceof HandlerMethod hm)) return true;
        // 方法级注解优先，类级次之
        RequirePermission ann = hm.getMethodAnnotation(RequirePermission.class);
        if (ann == null) ann = hm.getBeanType().getAnnotation(RequirePermission.class);
        if (ann == null) return true;   // 无注解不校验

        Long userId = LoginUser.getUserId();
        if (userId == null) {
            return writeForbidden(resp, ResultCode.UNAUTHORIZED, "未登录");
        }
        List<Long> roleIds = permissionService.getRoleIdsByUserId(userId);
        List<String> userPerms = permissionService.getPermissionCodesByRoleIds(roleIds);
        boolean ok = Arrays.stream(ann.value()).anyMatch(userPerms::contains);
        if (!ok) {
            log.warn("[Perm] 拒绝: userId={}, 需要={}, 持有={}", userId, Arrays.toString(ann.value()), userPerms);
            return writeForbidden(resp, ResultCode.FORBIDDEN, "无权限访问该资源");
        }
        return true;
    }

    private boolean writeForbidden(HttpServletResponse resp, int code, String msg) throws IOException {
        resp.setStatus(code == ResultCode.UNAUTHORIZED ? HttpStatus.UNAUTHORIZED.value() : HttpStatus.FORBIDDEN.value());
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(OM.writeValueAsString(R.fail(code, msg)));
        return false;
    }
}
```

#### 2.7.2 RuntimeWebMvcConfig（注册双拦截器）

路径：`eaiselp-runtime/src/main/java/com/eaiselp/runtime/config/RuntimeWebMvcConfig.java`

```java
package com.eaiselp.runtime.config;

import com.eaiselp.common.security.JwtAuthInterceptor;
import com.eaiselp.common.security.JwtUtil;
import com.eaiselp.data.service.PermissionService;
import com.eaiselp.runtime.security.PermissionInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class RuntimeWebMvcConfig implements WebMvcConfigurer {

    private final JwtUtil jwtUtil;
    private final PermissionService permissionService;

    public RuntimeWebMvcConfig(JwtUtil jwtUtil, PermissionService permissionService) {
        this.jwtUtil = jwtUtil;
        this.permissionService = permissionService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1. JWT 认证拦截器：所有 /api/** 都要 token（runtime 无公开接口）
        registry.addInterceptor(new JwtAuthInterceptor(jwtUtil))
                .addPathPatterns("/api/**")
                .order(1);
        // 2. 权限校验拦截器：仅对 @RequiresPermission 标注的方法生效
        registry.addInterceptor(new PermissionInterceptor(permissionService))
                .addPathPatterns("/api/**")
                .order(2);
    }
}
```

> **注意**：runtime 现有 `/api/runtime/derive`（RuntimeController.java:21）现在也要 token 了。Phase 1 dogfooding 手调派生会受影响——这是预期（M1 的"无认证"是临时态）。若需保留无认证访问，可在 excludePathPatterns 加 `/api/runtime/**`，但**不建议**（破坏 P11 隔离）。裁决：derive 接口纳入鉴权，手调需先登录拿 token。

#### 2.7.3 PermissionDemoController（Phase 1 测试桩，验证 AC-F3）

路径：`eaiselp-runtime/src/main/java/com/eaiselp/runtime/controller/PermissionDemoController.java`

```java
package com.eaiselp.runtime.controller;

import com.eaiselp.common.result.R;
import com.eaiselp.common.security.LoginUser;
import com.eaiselp.common.security.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 1 权限校验测试桩（验证 AC-F3）。
 * Phase 2 真实业务接口上线后删除本类。
 */
@RestController
@RequestMapping("/api/v1/demo")
public class PermissionDemoController {

    /** 需要 tenant:view 权限。tenant_admin 有，engineer 无 → 验证 AC-F3.1/F3.2。 */
    @GetMapping("/tenant-view")
    @RequirePermission("tenant:view")
    public R<String> tenantView() {
        return R.ok("你好 " + LoginUser.get().getUsername() + "，你有 tenant:view 权限");
    }

    /** 需要 strategy:view 权限。仅 executive/platform_admin 有。 */
    @GetMapping("/strategy-view")
    @RequirePermission("strategy:view")
    public R<String> strategyView() {
        return R.ok("你有 strategy:view 权限");
    }
}
```

---

## 3. 前端改动（eaiselp-web 新工程）

### 3.1 目录结构（PRD §3.1 已定，落地确认）

```
eaiselp-web/
├── config.js                 ← API 地址配置（AUTH_BASE_URL + API_BASE_URL）
├── login.html                ← 登录页
├── index.html                ← 主框架
├── assets/
│   ├── css/
│   │   ├── bootstrap.min.css        ← 第三方（Dev 下载 5.3.3）
│   │   └── app.css                  ← 平台样式补丁
│   ├── js/
│   │   ├── jquery-3.7.1.min.js      ← 第三方（Dev 下载）
│   │   ├── bootstrap.bundle.min.js  ← 第三方（Dev 下载 5.3.3）
│   │   ├── api.js                   ← API 封装
│   │   ├── auth.js                  ← 登录态管理
│   │   ├── menu.js                  ← 动态菜单
│   │   └── i18n.js                  ← 文案（M2 只中文，预留结构）
│   └── img/
│       └── logo.svg                 ← 占位 logo
└── pages/                    ← Phase 2 各功能页（Phase 1 不创建，保持空，遵循 ES-002 §2.5 禁预创建空目录）
```

> **第三方库获取**：Dev 从 CDN 下载到本地（不用 CDN 在线，避免离线/内网不可达）：
> - jQuery 3.7.1: `https://code.jquery.com/jquery-3.7.1.min.js`
> - Bootstrap 5.3.3 css: `https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css`
> - Bootstrap 5.3.3 js: `https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js`

### 3.2 config.js（API 地址配置）

```javascript
/**
 * eAISELP 前端配置（M2 Phase 1）
 * 切换部署环境只改本文件。
 */
window.EAISELP_CONFIG = {
  // 认证服务（auth 独立进程）—— login / current / logout
  AUTH_BASE_URL: 'http://localhost:8085',
  // 业务服务（runtime 主机）—— Phase 2+ 业务 API
  API_BASE_URL: 'http://localhost:8081',
  // token 在 localStorage 的 key
  TOKEN_KEY: 'eaiselp_token',
  // 登录页 / 主框架路径
  LOGIN_PAGE: 'login.html',
  INDEX_PAGE: 'index.html'
};
```

### 3.3 api.js（API 封装）

```javascript
/**
 * API 封装层（M2 Phase 1）
 * - 自动带 Authorization header
 * - 401 自动清 token 跳登录
 * - 统一错误回调
 */
(function (window, $) {
  const CFG = window.EAISELP_CONFIG;

  function getToken() {
    return localStorage.getItem(CFG.TOKEN_KEY);
  }
  function setToken(t) {
    localStorage.setItem(CFG.TOKEN_KEY, t);
  }
  function clearToken() {
    localStorage.removeItem(CFG.TOKEN_KEY);
  }

  function request(options) {
    const opt = $.extend({ dataType: 'json', contentType: 'application/json' }, options);
    // 自动带 token（除非显式 noAuth）
    if (!opt.noAuth) {
      const token = getToken();
      if (token) {
        opt.headers = $.extend({ 'Authorization': 'Bearer ' + token }, opt.headers || {});
      }
    }
    // 401 统一处理
    const origError = opt.error;
    opt.error = function (xhr) {
      if (xhr.status === 401) {
        clearToken();
        if (location.pathname.indexOf(CFG.LOGIN_PAGE) === -1) {
          location.href = CFG.LOGIN_PAGE;
        }
      }
      if (origError) origError(xhr);
    };
    return $.ajax(opt);
  }

  // 便捷方法
  function authPost(path, data) {
    return request({ url: CFG.AUTH_BASE_URL + path, method: 'POST', data: JSON.stringify(data), noAuth: path === '/api/v1/auth/login' });
  }
  function authGet(path) {
    return request({ url: CFG.AUTH_BASE_URL + path, method: 'GET' });
  }

  window.EAISELP_API = {
    getToken, setToken, clearToken, request,
    login: (u, p) => authPost('/api/v1/auth/login', { username: u, password: p }),
    current: () => authGet('/api/v1/auth/current'),
    logout: () => authPost('/api/v1/auth/logout', {}),
    // 业务 API（Phase 2 扩展）
    bizGet: (path) => request({ url: CFG.API_BASE_URL + path, method: 'GET' })
  };
})(window, jQuery);
```

### 3.4 auth.js（登录态管理）

```javascript
/**
 * 登录态管理（M2 Phase 1）
 */
(function (window) {
  const API = window.EAISELP_API;
  const CFG = window.EAISELP_CONFIG;

  window.EAISELP_AUTH = {
    isLoggedIn() { return !!API.getToken(); },
    requireLogin() {
      if (!this.isLoggedIn()) {
        location.href = CFG.LOGIN_PAGE;
        return false;
      }
      return true;
    },
    // 进入 index.html 时调用，恢复登录态
    restore(onSuccess, onFail) {
      if (!this.isLoggedIn()) { location.href = CFG.LOGIN_PAGE; return; }
      API.current().done(function (resp) {
        if (resp.code === 0) {
          onSuccess && onSuccess(resp.data);
        } else {
          API.clearToken();
          location.href = CFG.LOGIN_PAGE;
        }
      }).fail(function () {
        API.clearToken();
        location.href = CFG.LOGIN_PAGE;
      });
    },
    logout() {
      API.logout().always(function () {
        API.clearToken();
        location.href = CFG.LOGIN_PAGE;
      });
    }
  };
})(window);
```

### 3.5 menu.js（按角色动态菜单）

```javascript
/**
 * 动态菜单（M2 Phase 1）
 * 按角色码映射导航项（PRD §4.2.3）。多角色取并集。
 * ea/pgm/orchestrator 等体系 AI 角色不映射（Q-3）。
 */
(function (window) {
  // 角色码 → 导航项映射
  const ROLE_MENUS = {
    platform_admin: [
      { key: 'system', title: '系统管理' },
      { key: 'tenant', title: '租户管理' },
      { key: 'model', title: '模型路由' },
      { key: 'adapter', title: '适配器配置' },
      { key: 'monitor', title: '系统监控' }
    ],
    tenant_admin: [
      { key: 'user', title: '用户管理' },
      { key: 'role', title: '角色管理' },
      { key: 'program', title: '项目群看板' },
      { key: 'standard', title: '工程标准' },
      { key: 'quota', title: '配额' }
    ],
    project_manager: [
      { key: 'case-board', title: 'Case 看板' },
      { key: 'derive-progress', title: '派生进度' },
      { key: 'checkpoint', title: '检查点审批' },
      { key: 'artifact-pm', title: '产物查看' }
    ],
    engineer: [
      { key: 'review-todo', title: '待办审查' },
      { key: 'case-detail', title: 'Case 详情' },
      { key: 'artifact-eng', title: '产物查看' },
      { key: 'my-task', title: '我的任务' }
    ],
    executive: [
      { key: 'strategy', title: '战略看板' },
      { key: 'investment', title: '投资概览' },
      { key: 'risk', title: '风险矩阵' },
      { key: 'milestone', title: '里程碑' },
      { key: 'dora', title: '效能度量' }
    ]
  };
  // 平台识别的角色码白名单（ea/pgm/orchestrator 等不在内 → 不映射）
  const PLATFORM_ROLES = ['platform_admin', 'tenant_admin', 'project_manager', 'engineer', 'executive'];

  window.EAISELP_MENU = {
    // 按角色码数组生成菜单项（多角色并集，去重）
    build(roleCodes) {
      const seen = new Set();
      const menus = [];
      (roleCodes || []).forEach(function (code) {
        if (PLATFORM_ROLES.indexOf(code) === -1) return;   // 非平台角色跳过（Q-3）
        (ROLE_MENUS[code] || []).forEach(function (m) {
          if (!seen.has(m.key)) { seen.add(m.key); menus.push(m); }
        });
      });
      return menus;
    },
    // 主角色徽章（第一个平台角色；无则"用户"）
    primaryRoleName(roleCodes) {
      for (let i = 0; i < (roleCodes || []).length; i++) {
        if (PLATFORM_ROLES.indexOf(roleCodes[i]) !== -1) return roleCodes[i];
      }
      return '用户';
    }
  };
})(window);
```

### 3.6 login.html（登录页）

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>eAISEDP 登录</title>
  <link rel="stylesheet" href="assets/css/bootstrap.min.css">
  <link rel="stylesheet" href="assets/css/app.css">
</head>
<body class="login-bg">
  <div class="login-wrapper">
    <div class="login-card">
      <div class="login-logo">eAISEDP</div>
      <div class="login-subtitle">企业级 AI 软件工程平台</div>
      <div class="login-subtitle-en">AI-Powered Software Engineering Platform</div>

      <form id="loginForm" class="mt-4">
        <div class="mb-3 input-group">
          <span class="input-group-text">👤</span>
          <input type="text" id="username" class="form-control" placeholder="用户名" maxlength="64" required>
        </div>
        <div class="mb-3 input-group">
          <span class="input-group-text">🔒</span>
          <input type="password" id="password" class="form-control" placeholder="密码" maxlength="128" required>
          <span class="input-group-text cursor-pointer" id="togglePwd">👁</span>
        </div>
        <button type="submit" id="loginBtn" class="btn btn-primary w-100">登 录</button>
      </form>

      <div id="errorTip" class="alert alert-danger mt-3 d-none" role="alert"></div>

      <div class="login-footer">
        © 2026 eAISELP · v0.2.0-M2
        <span class="http-tip">（开发期 HTTP 传输，生产请启用 HTTPS）</span>
      </div>
    </div>
  </div>

  <script src="assets/js/jquery-3.7.1.min.js"></script>
  <script src="assets/js/bootstrap.bundle.min.js"></script>
  <script src="config.js"></script>
  <script src="assets/js/api.js"></script>
  <script>
    $(function () {
      // 已登录则跳主框架
      if (window.EAISELP_AUTH && EAISELP_AUTH.isLoggedIn()) {
        // login.html 不引 auth.js，直接判断 token
      }

      const API = window.EAISELP_API;
      const CFG = window.EAISELP_CONFIG;

      function showError(msg) {
        $('#errorTip').text(msg).removeClass('d-none');
        setTimeout(function () { $('#errorTip').addClass('d-none'); }, 3000);
      }
      function setLoading(loading) {
        $('#loginBtn').prop('disabled', loading).text(loading ? '登录中...' : '登 录');
        $('#username,#password').prop('readonly', loading);
      }

      // 密码可见切换
      $('#togglePwd').on('click', function () {
        const $pwd = $('#password');
        $pwd.attr('type', $pwd.attr('type') === 'password' ? 'text' : 'password');
      });

      $('#loginForm').on('submit', function (e) {
        e.preventDefault();
        const u = $('#username').val().trim();
        const p = $('#password').val();
        if (!u || !p) { showError('请输入用户名和密码'); return; }
        setLoading(true);
        API.login(u, p).done(function (resp) {
          if (resp.code === 0) {
            API.setToken(resp.data.token);
            setTimeout(function () { location.href = CFG.INDEX_PAGE; }, 200);
          } else {
            // 40001 凭据错误 / 40002 账户禁用 / 50000 服务异常
            showError(resp.msg || '登录失败');
            $('#password').val('').focus();
          }
        }).fail(function () {
          showError('网络异常，请检查连接');
        }).always(function () {
          setLoading(false);
        });
      });
    });
  </script>
</body>
</html>
```

### 3.7 index.html（主框架）

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>eAISEDP</title>
  <link rel="stylesheet" href="assets/css/bootstrap.min.css">
  <link rel="stylesheet" href="assets/css/app.css">
</head>
<body class="app-bg">
  <!-- 加载态 -->
  <div id="loading" class="app-loading">
    <div class="spinner-border text-primary"></div>
    <div class="mt-2">正在加载...</div>
  </div>

  <!-- 主框架（恢复登录态后显示）-->
  <div id="app" class="d-none">
    <!-- 顶部栏 -->
    <header class="app-header">
      <div class="app-logo">eAISEDP</div>
      <div class="app-title">企业级 AI 软件工程平台</div>
      <div class="app-user ms-auto">
        <span id="userName"></span>
        <span id="roleBadge" class="badge bg-secondary ms-2"></span>
        <button id="logoutBtn" class="btn btn-sm btn-outline-light ms-3">退出</button>
      </div>
    </header>

    <div class="app-body">
      <!-- 左侧导航 -->
      <nav class="app-sidebar" id="sidebar"></nav>

      <!-- 右侧内容区 -->
      <main class="app-content">
        <!-- Tab 栏 -->
        <div class="app-tabs" id="tabBar"></div>
        <!-- 内容容器 -->
        <div class="app-tab-content" id="tabContent"></div>
      </main>
    </div>
  </div>

  <script src="assets/js/jquery-3.7.1.min.js"></script>
  <script src="assets/js/bootstrap.bundle.min.js"></script>
  <script src="config.js"></script>
  <script src="assets/js/api.js"></script>
  <script src="assets/js/auth.js"></script>
  <script src="assets/js/menu.js"></script>
  <script>
    $(function () {
      const AUTH = window.EAISELP_AUTH;
      const MENU = window.EAISELP_MENU;

      // 路由保护：未登录跳登录页 + 恢复登录态
      AUTH.restore(function (user) {
        $('#loading').addClass('d-none');
        $('#app').removeClass('d-none');
        renderFrame(user);
      });

      function renderFrame(user) {
        $('#userName').text(user.displayName || user.username);
        $('#roleBadge').text(MENU.primaryRoleName(user.roleCodes));
        const menus = MENU.build(user.roleCodes);
        renderSidebar(menus);
        // 默认激活第一个导航项
        if (menus.length) openTab(menus[0]);
      }

      function renderSidebar(menus) {
        const $sb = $('#sidebar').empty();
        menus.forEach(function (m) {
          $('<a href="javascript:void(0)" class="nav-link"></a>')
            .text(m.title).data('menu', m)
            .on('click', function () { openTab($(this).data('menu')); })
            .appendTo($sb);
        });
        // 底部退出
        $('<hr>').appendTo($sb);
        $('<a href="javascript:void(0)" class="nav-link text-danger"></a>')
          .text('退出登录').on('click', confirmLogout).appendTo($sb);
      }

      const openedTabs = {};
      function openTab(menu) {
        if (openedTabs[menu.key]) {
          activateTab(menu.key); return;
        }
        openedTabs[menu.key] = menu;
        // Tab 标签
        $('<span class="app-tab"></span>').attr('data-key', menu.key)
          .html(menu.title + ' <span class="tab-close">×</span>')
          .appendTo('#tabBar')
          .on('click', function (e) {
            if ($(e.target).hasClass('tab-close')) { closeTab(menu.key); }
            else { activateTab(menu.key); }
          });
        // 内容（占位）
        $('<div class="tab-pane"></div>').attr('data-key', menu.key)
          .html('<div class="placeholder"><div class="placeholder-icon">⚙</div><div>建设中</div><div class="placeholder-sub">该功能将在后续版本提供</div></div>')
          .appendTo('#tabContent');
        activateTab(menu.key);
      }
      function activateTab(key) {
        $('.app-tab').removeClass('active');
        $('.tab-pane').removeClass('active');
        $('.app-tab[data-key="' + key + '"]').addClass('active');
        $('.tab-pane[data-key="' + key + '"]').addClass('active');
      }
      function closeTab(key) {
        delete openedTabs[key];
        $('.app-tab[data-key="' + key + '"], .tab-pane[data-key="' + key + '"]').remove();
        // 激活剩余第一个
        const first = Object.keys(openedTabs)[0];
        if (first) activateTab(first);
      }

      function confirmLogout() {
        if (confirm('确认退出登录？')) AUTH.logout();
      }
      $('#logoutBtn').on('click', confirmLogout);
    });
  </script>
</body>
</html>
```

### 3.8 app.css（样式补丁，关键片段）

路径：`eaiselp-web/assets/css/app.css`（Dev 补全，以下为关键布局）

```css
/* 登录页 */
.login-bg { background:#f0f2f5; min-height:100vh; display:flex; align-items:center; justify-content:center; }
.login-wrapper { width:100%; max-width:400px; padding:20px; }
.login-card { background:#fff; border-radius:8px; padding:40px 30px; box-shadow:0 2px 12px rgba(0,0,0,.1); text-align:center; }
.login-logo { font-size:32px; font-weight:bold; color:#1677ff; }
.login-subtitle { color:#333; margin-top:8px; }
.login-subtitle-en { color:#999; font-size:12px; }
.login-footer { margin-top:24px; font-size:12px; color:#999; }
.http-tip { color:#faad14; margin-left:8px; }
.cursor-pointer { cursor:pointer; }

/* 主框架 */
.app-bg { min-width:1024px; }
.app-header { height:60px; background:#001529; color:#fff; display:flex; align-items:center; padding:0 20px; }
.app-logo { font-size:20px; font-weight:bold; }
.app-title { margin-left:16px; color:rgba(255,255,255,.65); }
.app-user { display:flex; align-items:center; }
.app-body { display:flex; height:calc(100vh - 60px); }
.app-sidebar { width:220px; background:#fff; border-right:1px solid #f0f0f0; padding:12px; overflow-y:auto; }
.app-sidebar .nav-link { padding:10px 12px; color:#333; border-radius:4px; cursor:pointer; }
.app-sidebar .nav-link:hover { background:#f5f5f5; }
.app-content { flex:1; display:flex; flex-direction:column; background:#f0f2f5; }
.app-tabs { height:40px; background:#fff; border-bottom:1px solid #f0f0f0; display:flex; align-items:center; padding:0 8px; }
.app-tab { padding:6px 12px; margin:0 4px; background:#f5f5f5; border-radius:4px; cursor:pointer; font-size:13px; }
.app-tab.active { background:#1677ff; color:#fff; }
.tab-close { margin-left:8px; color:#999; }
.app-tab-content { flex:1; overflow:auto; }
.tab-pane { display:none; padding:20px; height:100%; }
.tab-pane.active { display:block; }
.placeholder { text-align:center; color:#999; margin-top:80px; }
.placeholder-icon { font-size:48px; }
.placeholder-sub { font-size:13px; margin-top:8px; }
.app-loading { position:fixed; inset:0; display:flex; flex-direction:column; align-items:center; justify-content:center; background:#fff; }
```

---

## 4. 改动顺序（按依赖关系排，每步可独立编译）

| 步骤 | 内容 | 模块 | 依赖上一步 | 可独立编译 | 验证 |
|---|---|---|---|---|---|
| **1** | DDL 追加（schema.sql 5 表 + seed）| data 资源 | 无 | 是（纯 SQL）| `mysql < schema.sql` 无报错；`SELECT COUNT(*) FROM t_permission` = 31 |
| **2** | common 改造（pom 加 jjwt + TenantHandler IGNORE + JwtUtil/注解/拦截器/异常/CORS/ResultCode/SecurityProperties）| common | 无（common 最底层）| 是（mvn -pl eaiselp-common compile）| 编译通过；JwtUtil 单测（签发→解析闭环）|
| **3** | data 新增（5 Entity + 5 Mapper + vo + PermissionService）| data | 步骤 2（依赖 common）| 是（mvn -pl eaiselp-data compile）| 编译通过；PermissionService 单测（mock mapper 查权限聚合）|
| **4** | auth 落地（pom + yml + Application + DTO + AuthService + AuthController + WebMvcConfig）| auth | 步骤 2+3 | 是（mvn -pl eaiselp-auth package）| 启动 auth:8085；POST /login 返回 token；GET /current 返回 user |
| **5** | runtime 改造（PermissionInterceptor + RuntimeWebMvcConfig + PermissionDemoController）| runtime | 步骤 2+3 | 是（mvn -pl eaiselp-runtime package）| 启动 runtime:8081；带 token 调 /api/v1/demo/tenant-view（tenant_admin 通过 / engineer 403）|
| **6** | 前端 eaiselp-web（config + api + auth + menu + login.html + index.html + css + 第三方库）| web | 步骤 4+5（后端跑起来）| 是（纯静态，浏览器打开）| 登录页登录成功跳 index；index 按角色显示菜单；退出跳 login |
| **7** | 环境变量 + 数据库初始化 | ops | 步骤 1 | — | `set JWT_SECRET=...`；确认 admin 密码（schema.sql:217 BCrypt 哈希对应明文）|

> **关键提示**：步骤 2 必须先于 3/4/5（common 是公共依赖）。步骤 4 和 5 可并行（auth 与 runtime 横向独立）。前端步骤 6 依赖后端 4+5 跑起来才能联调，但本身可先开发。

---

## 5. 风险点

| # | 风险 | 等级 | 影响 | 缓解 |
|---|---|---|---|---|
| R1 | jjwt 0.12.6 与 Spring Boot 3.2.5 / JDK 17 兼容 | 低 | JWT 签发/解析失败 | jjwt 0.12 要求 JDK 8+，与项目 JDK 17 兼容；0.12.x 是当前稳定线。父 POM 统一管 version。若冲突可降 0.11.5（旧 API 线）|
| R2 | BCrypt cost=10 登录耗时 ~100ms | 低 | login P95 含此开销（PRD §8.1 ≤800ms）| 接受（Q-6）。100ms 远小于 800ms 预算 |
| R3 | 前端跨域 CORS（auth:8085 + runtime:8081 双端口）| 中 | 前端调不通后端 | common CorsConfig 全局配 allowedOriginPatterns("*")；两 service 都扫到生效。开发期允许所有 origin，生产收紧 |
| R4 | t_user.roles 字符串 vs t_user_role 关联表数据不一致 | 中 | login 读 t_user_role 权威，若 t_user.roles 冗余未同步导致展示偏差 | Phase 1 仅 seed 一次性同步（已一致）；运行时读 t_user_role 为准，t_user.roles 仅展示冗余。Phase 2 用户管理 UI 实现事务级双向同步（Q-1）|
| R5 | JWT 密钥泄露（dev 占位密钥进生产）| 高 | 任意人可伪造 token | yml 用 `${JWT_SECRET:dev-...}` 环境变量注入（同 GLM_API_KEY 模式）；ops 部署必设 JWT_SECRET；M3 接 Vault/KMS |
| R6 | TenantLineInnerInterceptor 误过滤权限表查询 | 中 | login/current 查 t_user_role/t_role 查空 → 401 | EaiselpTenantHandler.IGNORE_TABLES 追加 5 张权限表（§2.4.2）；QA 回归必验 AC-F4 |
| R7 | auth 与 runtime 共享同一 eaiselp 库的并发一致性 | 低 | 两 service 写同一库 | Phase 1 写操作仅 login 更新 last_login_at（auth 写 t_user），无冲突。M3 分库再评估 |
| R8 | runtime 现有 /api/runtime/derive 纳入鉴权后手调失败 | 低 | dogfooding 手调派生需先登录 | 预期行为（M1 无认证是临时态）。手调流程：先 POST /login 拿 token → 带 token 调 /derive |
| R9 | BCrypt 恒定时延未实现（用户枚举响应时长差）| 低 | 内网 dogfooding 可接受；外网暴露有风险 | Phase 1 简化（null 直接返回）；M3 补恒定时延（null 也跑一次假 BCrypt.matches）|
| R10 | 前端 localStorage XSS 风险（token 被盗）| 中 | XSS 攻击可窃 token | M2 接受（PRD §8.2）；M3 评估 httpOnly cookie + CSRF token |
| R11 | PRD §6.4 矩阵摘要"platform_admin 22 项"与实际 29 项不符 | 低 | seed 数量与 PO 预期偏差 | seed 按矩阵逐行 ✓（29 项）为权威；传导 PO 确认并修正摘要（§6 开放问题）|

---

## 6. PO 6 个开放问题的 SE 裁决

| # | 问题 | PO 默认 | **SE 裁决** | 理由 |
|---|---|---|---|---|
| **Q-1** | t_user.roles 字符串 vs t_user_role 关联表共存？废弃？| 保留 t_user.roles 冗余 + t_user_role 权威，双向同步 | **同意 PO 默认**。Phase 1 细化：无"分配角色"UI，无运行时同步；seed 一次性写入两边已一致。login/current 读 t_user_role 为权威源；t_user.roles 仅展示冗余。Phase 2 用户管理 UI 实现事务级双向同步（分配角色时同写 t_user_role + UPDATE t_user.roles）。M3 评估废弃 t_user.roles | 双数据源短期冗余可接受，长期收敛到单一权威源 |
| **Q-2** | Phase 1 必须中英双语？| 只中文，代码预留 i18n 结构 | **同意 PO 默认**。前端文案集中 i18n.js（key-value 映射，M2 值为中文）；后端错误消息走 ResultCode 常量 + BizException msg（中文）。M3 加英文只需扩 i18n.js + MessageBundle | 双语是 M3 范围，Phase 1 预留结构即可 |
| **Q-3** | dogfooding admin 的 ea/pgm/orchestrator 是否映射平台导航？| 只映射 tenant_admin | **同意 PO 默认**。menu.js 用 PLATFORM_ROLES 白名单（5 个平台角色码），ea/pgm/orchestrator 不在白名单 → 不生成导航项。admin 登录后只看到 tenant_admin 菜单（5 项）| 体系 AI 角色对应人类代理，不是平台功能角色；M3 若需可扩映射 |
| **Q-4** | 前端走 gateway 还是直连？| 直连 runtime:8081，config.js 预留切换 | **扩展裁决（SE 修正 PRD）**：直连不走 gateway（同意 PO）。**但**因 SE 裁决 auth 独立 service（§2.6.1），前端需双 base-url：AUTH_BASE_URL=8085（auth API）+ API_BASE_URL=8081（runtime 业务 API）。PRD §5.1/§8.6 的"单 base-url=8081"需 PO 同步修正。M3 gateway 落地后改单入口 gateway:8000 | ES-001 §1.3 强制 auth=service，无法降级 library 由 runtime 代管。双 base-url 是必要代价，api.js 封装屏蔽复杂度 |
| **Q-5** | JWT payload 放 permissions？| 不放（防膨胀），/current 实时查 | **同意 PO 默认**。JWT payload 只放 roles（JwtClaims，§2.4.5）。permissions 由 /current 实时查 t_role_permission 返回（PermissionService.getPermissionCodesByRoleIds）。拦截器校验 @RequiresPermission 也实时查（PermissionInterceptor）| 31 权限放 token 会使 token 超 1KB；roles 仅 5 个轻量。实时查性能可接受（单查多角色并集）|
| **Q-6** | BCrypt cost=10 登录耗时 ~100ms？| 接受 | **同意 PO 默认**。100ms 远小于 login P95 ≤800ms 预算。维持 cost=10（与现有 t_user 哈希一致，无需重新哈希）| 性能预算充足；改 cost 需重新哈希全量用户密码，不值 |

> **SE 新增裁决（非 PO 开放问题，但技术必要）**：
> - **auth API 放独立 service（8085）而非 runtime 代管**：ES-001 §1.3 强制（详见 §2.6.1）。
> - **jjwt 0.12.6**：不在 Spring Boot BOM，显式引；选 0.12 新 API 线（详见 §2.4.1）。
> - **BCrypt 只引 spring-security-crypto**：不引完整 spring-boot-starter-security，避免自动启用 CSRF/默认认证链与 JWT 无状态冲突（详见 §2.6.2）。
> - **CORS 开发期 allowedOriginPatterns("\*")**：两 service 共用 common CorsConfig；生产收紧 origin（详见 §2.4.10）。
> - **t_user/t_user_role/t_role 等加入 IGNORE_TABLES**：权限系统表免 tenant 自动过滤（详见 §2.4.2）。
> - **PRD §6.4 矩阵摘要笔误传导**：platform_admin 实为 29 项（非摘要的 22），seed 按矩阵逐行 ✓ 为权威（详见 §2.3.4）。

---

## 7. 回归验证步骤（给 QA）

### 7.1 环境准备
1. 执行 `schema.sql`（含 M2 Phase 1 追加段），确认 `SELECT COUNT(*) FROM t_permission`=31、`t_role`=5、`t_role_permission`=57（29+15+7+4+2）、`t_user_role` 含 admin→tenant_admin。
2. 设置环境变量 `JWT_SECRET=<≥32 字节随机串>`（auth + runtime 都要设）。
3. 启动 auth:8085 + runtime:8081（确认两进程都注册到 Nacos、都连上 MySQL）。
4. 确认 admin 密码明文（schema.sql:217 BCrypt 哈希 `$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy` 对应明文——**需 PO/DBA 确认 dogfooding admin 初始密码**，若无记录需重新生成哈希 UPDATE）。

### 7.2 AC 验证矩阵

| AC | 步骤 | 预期 |
|---|---|---|
| **AC-F1.1** 正确凭据登录 | POST /api/v1/auth/login {admin, 正确密码} | code=0；data.token 为合法 JWT（jwt.io 解析 payload 含 userId=1/tenantId=1/roles=["tenant_admin"]/exp-iat=86400）；前端跳 index.html |
| **AC-F1.2** 错误密码 | POST /login {admin, wrongpwd} | code=40001 msg="用户名或密码错误"；前端红字提示；localStorage 无 token |
| **AC-F1.3** 不存在用户 | POST /login {nouser, any} | code=40001（与 F1.2 同 code，防枚举）|
| **AC-F1.4** 禁用账户 | UPDATE t_user SET status='disabled' WHERE id=1；POST /login | code=40002 msg="账户已被禁用"；还原 status='active' |
| **AC-F1.5** 更新登录时间 | 登录前查 last_login_at；登录后再查 | last_login_at 已更新为当前时间 |
| **AC-F2.1** JWT 结构 | jwt.io 解析 F1.1 的 token | header.alg=HS256；payload 含 6 类字段；exp-iat=86400 |
| **AC-F2.2** 刷新恢复 | index.html 按 F5 | 调 GET /current 返回 code=0 + user；不跳 login；导航按角色渲染 |
| **AC-F2.3** token 过期 | 构造 exp 已过的 JWT（改 yml expire-seconds=1 重启登录后等 2s）；调 /current | HTTP 401 + code=40102；前端跳 login |
| **AC-F3.1** 有权限访问 | tenant_admin 登录拿 token；GET /api/v1/demo/tenant-view（带 token）| code=0 + 正常数据 |
| **AC-F3.2** 无权限拒绝 | 构造 engineer 用户（INSERT t_user_role 给某用户 engineer 角色）；登录；GET /api/v1/demo/tenant-view | HTTP 403 + code=40301 |
| **AC-F3.3** 多角色并集 | 给某用户同时分配 tenant_admin + project_manager；查 /current 的 permissions | = 两角色权限并集（去重）|
| **AC-F4.1** 跨租户不可见 | INSERT t_tenant 第二租户(id=2) + t_user userB(tenant_id=2) + t_case dataB(tenant_id=2)；userB 登录查 case 列表 | 不含 tenant_id=1 的数据（MyBatis 自动注入 WHERE tenant_id=2）|
| **AC-F4.2** 跨租户直访拒 | userB token 尝试 GET dataA(id 属 tenant_id=1) | 404/403（不返回 dataA 内容）|
| **AC-F5.1** platform_admin 菜单 | 给某用户分配 platform_admin；登录 index | 左侧导航 5 项（系统管理/租户管理/模型路由/适配器配置/系统监控）|
| **AC-F5.2** engineer 菜单 | engineer 登录 index | 左侧导航 4 项（待办审查/Case 详情/产物查看/我的任务）|
| **AC-F5.3** Tab 打开占位 | 点任意导航项 | 右侧开新 Tab（标题=导航名）；内容"建设中"占位；重复点激活已开 Tab |
| **AC-F5.4** 退出清状态 | 点退出 + 确认 | localStorage token 清除；跳 login.html；后退键回不到 index |
| **AC-F6.1** 未登录跳转 | 清 localStorage；访问 index.html | 立即跳 login.html |
| **AC-F6.2** token 无效跳转 | localStorage 存伪造 token；访问 index.html 调 /current | 返回 401；前端跳 login |
| **AC-F6.3** 401 自动跳转 | 登录后手工改坏 token；触发任意 API | 前端拦截 401 自动清 token 跳 login |

### 7.3 非功能验证
- **性能**：login P95 实测（含 BCrypt ~100ms）应 ≤800ms；/current P95 ≤200ms。
- **CORS**：浏览器 DevTools Network 面板确认 auth:8085 和 runtime:8081 请求无 CORS 报错（OPTIONS 预检通过）。
- **安全**：确认 login 失败响应不区分用户不存在 vs 密码错（均 40001）；确认生产环境 JWT_SECRET 已替换 dev 占位。
- **可观测**：tail auth 日志确认登录成功/失败打 log.info（含 username/tenantId/耗时/IP）；权限拒绝打 log.warn。

---

## 8. 自检（ES-002 §1.3 强制）

### 8.1 产出落盘自检
- [x] 本方案已 Write 落盘到 `D:\AI\mywork\platform\docs\设计规划文档\M2-Phase1-技术方案.md`
- [x] Test-Path 自检：见向编排者汇报的绝对路径 + 字节数
- [x] 向编排者汇报绝对路径 + 字节数
- [x] 产出物非空（>100 字节）

### 8.2 决策基于已有资产的自检（磁盘事实核对）

| 决策 | 基于的已有资产（磁盘 Read 确认）| 是否凭空设计 |
|---|---|---|
| Entity extends BaseEntity | `eaiselp-common/.../entity/BaseEntity.java:10-33`（id+tenantId+审计+@TableLogic）| 否，复用 |
| Mapper extends BaseMapper | `eaiselp-data/.../mapper/UserMapper.java:1-8` / `DerivationMapper.java` 风格 | 否，复用 |
| DDL 表风格（BIGINT 主键 + 审计字段 + utf8mb4）| `schema.sql:33-54`(t_user) / `:83-106`(t_case) 等 8 表 | 否，对齐 |
| t_user 字段复用（password/status/roles/last_login_at/display_name/email/avatar）| `schema.sql:37-45` + `User.java:13-22` | 否，复用 |
| dogfooding admin seed（user_id=1, tenant_id=1, roles='tenant_admin,...'）| `schema.sql:216-218` | 否，复用 |
| JWT 工具放 common | PRD F3 明示 + common 是最底层 library 被多 service 依赖（ES-001 §4.1 图）| 否 |
| auth 独立 service | ES-001 §1.3 模块定性表（auth=service）+ `eaiselp-auth/EaiselpAuthApplication.java:1-13` 已有 Application | 否 |
| auth 依赖 data | ES-001 §4.2 R4（横向 service 可依赖 common；data 为 library 可被多 service 引用）| 否 |
| jjwt 显式引（不在 BOM）| `pom.xml:44-112` dependencyManagement 无 jjwt | 否，已核对 |
| BCrypt 只引 spring-security-crypto | common/runtime/auth pom 均无 spring-security 全家桶 | 否 |
| EaiselpTenantHandler.IGNORE_TABLES 追加 | `eaiselp-common/.../tenant/EaiselpTenantHandler.java:12-14` 现有 5 张 | 否 |
| runtime scanBasePackages 含 data | `EaiselpRuntimeApplication.java:11-17` 已含 5 包 | 否，auth 照抄补 data |
| 端口 8081(runtime)/8085(auth) | `eaiselp-runtime/application.yml:2`(8081) + `eaiselp-auth/application.yml:2`(8085) | 否，复用 |
| 环境变量模式 ${JWT_SECRET:dev-...} | `eaiselp-runtime/application.yml:30` GLM_API_KEY 同模式 | 否，对齐 |
| JWT payload 字段（userId/username/displayName/tenantId/tenantCode/roles/iat/exp）| PRD §5.2.1 契约 | 否，采纳 |
| 错误码 40001/40002/40101/40102/40301/50000 | PRD §5.1.3 错误码表 | 否，采纳 |
| 31 权限 seed + 5 角色 seed + 矩阵 | PRD §6.3 + §6.4 | 否，采纳（矩阵逐行 ✓ 为准）|
| 前端目录结构 | PRD §3.1 | 否，采纳 |
| 导航按角色映射 | PRD §4.2.3 表 | 否，采纳 |

**结论**：所有决策基于磁盘 Read 确认的已有资产（schema.sql / 现有 Java 源码 / pom.xml / application.yml）+ PRD 契约 + ES-001/ES-002 工程标准。无凭空设计。**SE 已亲自 Read 全部上游文件，非抄 Dev/PO 报告**。

### 8.3 与上游一致性自检
- [x] 模块定性遵循 ES-001 §1.3（common/data=library，auth/runtime=service）
- [x] 依赖方向无环遵循 ES-001 §4.2（auth/runtime→data→common）
- [x] API 版本化遵循架构蓝图 P13（/api/v1/auth/**）
- [x] 多租户隔离遵循 P11（IGNORE_TABLES 处理 + t_user_role 带 tenant_id + login 显式带 tenant_id）
- [x] JWT 契约与 PRD §5.2.1 一致（payload 字段 + HS256 + 24h）
- [x] 3 个 API 契约与 PRD §5.2-5.4 一致（路径/入参/出参/错误码）
- [x] 31 权限 + 5 角色 seed 与 PRD §6.3/§6.4 一致（矩阵逐行 ✓ 为权威，标注摘要笔误）
- [x] 错误码与 PRD §5.1.3 一致（含 40001 不区分用户不存在 vs 密码错的安全约定）
- [x] 6 条 AC 全部有对应验证步骤（§7.2）

### 8.4 工程标准合规自检
- [x] ES-001：auth/runtime 是 service（repackage + Application + nacos）；common/data 是 library（无 Application + 无 repackage + 不注册 nacos）——本方案未改模块定性
- [x] ES-001：auth/runtime 的 POM 显式带 repackage executions（auth 现有 §2.6.2 已含，runtime 现有已含）
- [x] ES-002：本方案已 Write 落盘 + Test-Path 自检 + 汇报绝对路径
- [x] ES-002：改动描述对照 HEAD（pom/yml/Application 均标注"对照 HEAD 第 X 行"）

---

## 本次经验沉淀

1. **SE 技术方案必须 Read 真实 pom.xml 确认依赖是否在 BOM，不能凭"Spring Boot 应该自带"的印象**。本次核对 `pom.xml` dependencyManagement 发现 jjwt 不在 Spring Boot 3.2.5 BOM（必须显式引版本），而 spring-security-crypto 在 BOM（引时不写 version）。若不 Read 凭印象，要么漏引 jjwt 导致编译失败，要么给 spring-security-crypto 写死版本与 BOM 冲突。教训：依赖决策必须 Read 父 POM 的 dependencyManagement 段逐条核对，BOM 管理的写 version 是冗余/冲突，BOM 不管的漏 version 是编译失败。

2. **ES-001 模块定性表（§1.3）是 SE 裁决"API 放哪个模块"的硬约束，不能为图省事让 service 降级 library**。本次 PO PRD §5.1 写"前端直连 runtime:8081（单 base-url）"，隐含假设是"auth API 也在 runtime"。但 ES-001 §1.3 把 eaiselp-auth 定性为 service（独立进程），SE 若顺从 PO 把 auth 逻辑塞 runtime 代管，等于让 auth 成空壳 service、违反"service 必须独立承载业务"的设计意图。最终裁决 auth 独立 service（8085），代价是前端双 base-url——这是 SE 对 PRD 的技术修正，必须显式标注"修正 PRD §X"并传导 PO。教训：SE 的技术裁决（遵循工程标准）优先级高于 PRD 的便利性假设，冲突时 SE 修正 PRD 并传导，而非牺牲工程标准迁就 PRD。

3. **多租户拦截器（TenantLineInnerInterceptor）的 IGNORE_TABLES 是权限系统表的"生死线"，必须在技术方案显式列出追加项**。本次 5 张权限表（t_permission/t_role/t_role_permission/t_user_role/t_service_account）若不加入 IGNORE_TABLES，login 时 TenantContext=0（SYSTEM_TENANT，EaiselpTenantHandler.ignoreTable 直接返回 true 不过滤——但这是巧合），而 /current 时 TenantContext=1（从 token 注入），查询 t_user_role 会被自动注入 WHERE tenant_id=1，恰好 admin 的 t_user_role.tenant_id=1 能查到——但若用户在多租户场景 t_user_role.tenant_id 与 token tenantId 不一致就查空。更稳妥是显式 IGNORE，按 user_id/role_id 显式查。教训：涉及多租户拦截器的方案，必须逐表判断"该表是否应被自动 tenant 过滤"，系统级共享表/按显式主键查的关联表必须 IGNORE，否则会出现"单租户测试通过、多租户生产查空"的隐蔽 bug。

4. **PRD 内部不一致（矩阵 ✓ vs 摘要数字）必须 SE 亲自逐行核对并以更可核验的一方为权威**。本次 PRD §6.4 矩阵摘要写"platform_admin 22 项"，但逐行数矩阵 ✓ 实为 29 项（31 总权限 - role:create - role:edit）。SE 不能直接抄摘要生成 seed（会漏 7 个权限），必须逐行核对矩阵生成 seed，并显式标注"摘要疑笔误，传导 PO 确认"。教训：PRD 的表格数据若矩阵与摘要冲突，矩阵（逐条可核验）权威性高于摘要（汇总数字，易笔误）；SE 产出 seed 类数据时亲自核对原始清单，不抄汇总数字。

---

**方案完结。**

作者：team-se（L1 系统工程师 / Tech Lead）
体系版本：AISOps v1.0
工程版本：eAISEDP v0.2.0-M2
本次 case：case-20260723-m2-phase1-web-auth
