-- eAISEDP Platform 数据库初始化 (M1)
-- utf8mb4 + 多租户 + 配额 + dogfooding 示例数据

CREATE DATABASE IF NOT EXISTS `eaiselp` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `eaiselp`;

-- 1. 租户表
DROP TABLE IF EXISTS `t_tenant`;
CREATE TABLE `t_tenant` (
  `id` BIGINT NOT NULL,
  `tenant_code` VARCHAR(64) NOT NULL,
  `tenant_name` VARCHAR(200) NOT NULL,
  `deploy_mode` VARCHAR(16) NOT NULL DEFAULT 'shared',
  `edition` VARCHAR(16) NOT NULL DEFAULT 'pro',
  `status` VARCHAR(16) NOT NULL DEFAULT 'active',
  `expire_time` DATETIME DEFAULT NULL,
  `contact_name` VARCHAR(64) DEFAULT NULL,
  `contact_email` VARCHAR(128) DEFAULT NULL,
  `contact_phone` VARCHAR(32) DEFAULT NULL,
  `system_repo_url` VARCHAR(512) DEFAULT NULL,
  `system_branch` VARCHAR(64) DEFAULT 'main',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_code` (`tenant_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户表';

-- 2. 用户表
DROP TABLE IF EXISTS `t_user`;
CREATE TABLE `t_user` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `username` VARCHAR(64) NOT NULL,
  `password` VARCHAR(128) NOT NULL,
  `display_name` VARCHAR(128) DEFAULT NULL,
  `email` VARCHAR(128) DEFAULT NULL,
  `phone` VARCHAR(32) DEFAULT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'active',
  `roles` VARCHAR(512) DEFAULT NULL,
  `organization` VARCHAR(128) DEFAULT NULL,
  `avatar` VARCHAR(512) DEFAULT NULL,
  `last_login_at` DATETIME DEFAULT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_username` (`tenant_id`, `username`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 3. 配额表
DROP TABLE IF EXISTS `t_quota`;
CREATE TABLE `t_quota` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `period` VARCHAR(7) NOT NULL,
  `token_limit` BIGINT NOT NULL DEFAULT 10000000,
  `token_used` BIGINT NOT NULL DEFAULT 0,
  `case_limit` INT NOT NULL DEFAULT 100,
  `case_used` INT NOT NULL DEFAULT 0,
  `derivation_limit` INT NOT NULL DEFAULT 1000,
  `derivation_used` INT NOT NULL DEFAULT 0,
  `storage_limit_mb` BIGINT NOT NULL DEFAULT 10240,
  `storage_used_mb` BIGINT NOT NULL DEFAULT 0,
  `monthly_cost` DECIMAL(12,4) DEFAULT 0,
  `alert_threshold` INT NOT NULL DEFAULT 80,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_period` (`tenant_id`, `period`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配额表';

-- 4. Case 主表
DROP TABLE IF EXISTS `t_case`;
CREATE TABLE `t_case` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `case_id` VARCHAR(128) NOT NULL,
  `title` VARCHAR(200) NOT NULL,
  `layer` VARCHAR(8) NOT NULL DEFAULT 'L1',
  `tier` VARCHAR(16) NOT NULL DEFAULT 'standard',
  `status` VARCHAR(32) NOT NULL DEFAULT 'drafting',
  `current_stage` VARCHAR(32) DEFAULT 'stage_0',
  `orchestrator_id` VARCHAR(64) DEFAULT NULL,
  `subproject` VARCHAR(100) DEFAULT NULL,
  `program_id` VARCHAR(64) DEFAULT NULL,
  `system_version` VARCHAR(40) DEFAULT NULL,
  `requirement` TEXT DEFAULT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_caseid` (`tenant_id`, `case_id`),
  KEY `idx_tenant_status` (`tenant_id`, `status`),
  KEY `idx_program` (`program_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Case 主表';

-- 5. 派生记录表
DROP TABLE IF EXISTS `t_derivation`;
CREATE TABLE `t_derivation` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `case_id` VARCHAR(128) NOT NULL,
  `role` VARCHAR(64) NOT NULL,
  `stage` VARCHAR(32) DEFAULT NULL,
  `model` VARCHAR(64) DEFAULT NULL,
  `model_tier` VARCHAR(16) DEFAULT NULL,
  `input_tokens` INT NOT NULL DEFAULT 0,
  `output_tokens` INT NOT NULL DEFAULT 0,
  `cost` DECIMAL(10,4) DEFAULT 0,
  `status` VARCHAR(32) NOT NULL DEFAULT 'running',
  `error_msg` TEXT DEFAULT NULL,
  `produced_artifacts` JSON DEFAULT NULL,
  `experience` TEXT DEFAULT NULL,
  `retry_count` INT NOT NULL DEFAULT 0,
  `started_at` DATETIME DEFAULT NULL,
  `finished_at` DATETIME DEFAULT NULL,
  `duration_ms` BIGINT DEFAULT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_case` (`tenant_id`, `case_id`),
  KEY `idx_tenant_role` (`tenant_id`, `role`),
  KEY `idx_case_role` (`case_id`, `role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='派生记录表';

-- 6. 产物表
DROP TABLE IF EXISTS `t_artifact`;
CREATE TABLE `t_artifact` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `case_id` VARCHAR(128) NOT NULL,
  `role` VARCHAR(64) DEFAULT NULL,
  `stage` VARCHAR(32) DEFAULT NULL,
  `type` VARCHAR(32) NOT NULL,
  `title` VARCHAR(200) DEFAULT NULL,
  `content` MEDIUMTEXT DEFAULT NULL,
  `doc_key` VARCHAR(200) DEFAULT NULL,
  `frontmatter` JSON DEFAULT NULL,
  `derivation_id` BIGINT DEFAULT NULL,
  `contract_key` VARCHAR(200) DEFAULT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_case` (`tenant_id`, `case_id`),
  KEY `idx_tenant_type` (`tenant_id`, `type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产物表';

-- 7. 里程碑表
DROP TABLE IF EXISTS `t_milestone`;
CREATE TABLE `t_milestone` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `milestone_id` VARCHAR(32) NOT NULL,
  `program_id` VARCHAR(64) NOT NULL,
  `title` VARCHAR(200) NOT NULL,
  `target_date` DATE DEFAULT NULL,
  `status` VARCHAR(32) NOT NULL DEFAULT 'not_started',
  `subprojects` JSON DEFAULT NULL,
  `integration_points` TEXT DEFAULT NULL,
  `blocker` VARCHAR(500) DEFAULT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_program_ms` (`tenant_id`, `program_id`, `milestone_id`),
  KEY `idx_program` (`program_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='里程碑表';

-- 8. 检查点表
DROP TABLE IF EXISTS `t_checkpoint`;
CREATE TABLE `t_checkpoint` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `case_id` VARCHAR(128) NOT NULL,
  `operation` VARCHAR(64) NOT NULL,
  `operation_detail` JSON DEFAULT NULL,
  `status` VARCHAR(32) NOT NULL DEFAULT 'pending',
  `requested_at` DATETIME DEFAULT NULL,
  `confirmed_at` DATETIME DEFAULT NULL,
  `confirmed_by` VARCHAR(64) DEFAULT NULL,
  `comment` VARCHAR(500) DEFAULT NULL,
  `derivation_id` BIGINT DEFAULT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_case` (`tenant_id`, `case_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='检查点表';

-- ============ dogfooding 示例数据 ============
INSERT INTO `t_tenant` (`id`, `tenant_code`, `tenant_name`, `deploy_mode`, `edition`, `status`, `system_repo_url`, `system_branch`)
VALUES (1, 'eaiselp-self', 'eAISEDP 平台开发(dogfooding)', 'shared', 'enterprise', 'active',
        'https://github.com/Dongle0206/eAISEDP-system.git', 'main');

INSERT INTO `t_user` (`id`, `tenant_id`, `username`, `password`, `display_name`, `status`, `roles`)
VALUES (1, 1, 'admin', '$2a$10$YenQ7QqiarkwMhKS2hGYF.bytyjKAGjJ7fbkaIMx9SIzfdMvr9bq2', '平台管理员', 'active',
        'tenant_admin,ea,pgm,orchestrator');

INSERT INTO `t_quota` (`id`, `tenant_id`, `period`, `token_limit`, `case_limit`, `derivation_limit`, `storage_limit_mb`, `alert_threshold`)
VALUES (1, 1, DATE_FORMAT(NOW(), '%Y-%m'), 100000000, 1000, 10000, 102400, 80);

-- ============ M2 Phase 1：RBAC 权限系统（5 张表）============

-- 9. 权限原子表（系统级，所有租户共享，tenant_id 恒为 0；已加入 EaiselpTenantHandler.IGNORE_TABLES）
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
  `is_built_in` TINYINT NOT NULL DEFAULT 1,
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

-- ============ M2 Phase 1：RBAC seed 数据 ============
-- 幂等：INSERT IGNORE 依赖 UNIQUE 约束，重跑不报错

-- 权限原子（31 条，id 1001-1031；id 固定便于 t_role_permission seed 引用）
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

-- 5 模板角色（tenant_id=0 系统级预置，所有租户共享；data_scope 决定分配后的数据可见范围）
INSERT IGNORE INTO `t_role` (`id`, `tenant_id`, `role_code`, `role_name`, `role_type`, `data_scope`, `is_built_in`, `description`) VALUES
(1, 0, 'platform_admin',   '平台管理员', 'system_template', 'all',     1, '平台全局管理员，data_scope=all 跨租户'),
(2, 0, 'tenant_admin',     '企业管理员', 'system_template', 'tenant',  1, '租户管理员，data_scope=tenant 限本租户'),
(3, 0, 'project_manager',  '项目经理',   'system_template', 'tenant',  1, '项目经理，管项目群与 Case'),
(4, 0, 'engineer',         '工程师',     'system_template', 'self',    1, '工程师，data_scope=self 仅本人数据'),
(5, 0, 'executive',        '高管',       'system_template', 'tenant',  1, '高管，看战略看板与产物');

-- 角色-权限关联（按 PRD §6.4 矩阵；id 2001+ 自增分配，引用 role_id 1-5 与 permission_id 1001-1031）
-- 注：PRD §6.4 矩阵摘要写"platform_admin 22 项"，逐行数矩阵 platform_admin 列实为 29 项（= 31 总权限 - role:create - role:edit），
--     疑为 PO 笔误。本 seed 以矩阵逐行 ✓ 为权威生成（已传导 PO 确认，见 SE 方案 §5 R11）。

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

-- 给 dogfooding admin (user_id=1) 分配 tenant_admin (role_id=2)，tenant_id=1
-- Q-1 同步策略（见 SE 方案 §6）：运行时 login/current 读 t_user_role 为权威源；
-- admin 的 t_user.roles 冗余字符串已含 tenant_admin（schema.sql:218），保持不变（保留 ea/pgm/orchestrator 记录，不映射平台导航）。
INSERT IGNORE INTO `t_user_role` (`id`, `tenant_id`, `user_id`, `role_id`, `create_by`) VALUES
(3001, 1, 1, 2, 'system-seed');

-- ============ M2 SP-6：模型路由表（P8 解耦层落地，门禁 G12）============
-- 解耦"角色能力档位"与"具体模型/provider"：角色定义只写 tier（reasoning/structured/mechanical/code），
-- 路由表映射到具体 provider+model。换模型/换厂商只改 DB，不改 18 个角色定义、不改 Java 代码（model-registry skill 落地）。
-- 系统级全局配置表，无 tenant_id（模型档位是平台级配置，已加入 EaiselpTenantHandler.IGNORE_TABLES）。
DROP TABLE IF EXISTS `t_model_routing`;
CREATE TABLE `t_model_routing` (
  `id` BIGINT NOT NULL,
  `tier` VARCHAR(32) NOT NULL COMMENT '能力档位：reasoning/structured/mechanical/code',
  `provider` VARCHAR(32) NOT NULL COMMENT 'LLM 厂商：glm/deepseek/qwen/openai/ollama',
  `model` VARCHAR(64) NOT NULL COMMENT '具体模型名：glm-4-plus/deepseek-r1/qwen-max',
  `priority` INT NOT NULL DEFAULT 0 COMMENT '优先级（同档位多 provider 时按优先级选，值小者优先）',
  `enabled` TINYINT NOT NULL DEFAULT 1,
  `api_key_env` VARCHAR(64) DEFAULT NULL COMMENT 'API Key 环境变量名（如 GLM_API_KEY / DEEPSEEK_API_KEY）',
  `base_url` VARCHAR(256) DEFAULT NULL COMMENT 'API base URL',
  `role_hint` VARCHAR(256) DEFAULT NULL COMMENT '推荐角色（如 reasoning 档推荐 EA/SE 用 deepseek-r1）',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tier_provider_model` (`tier`, `provider`, `model`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型路由表（系统级，P8 解耦层）';

-- seed：每个档位预置 GLM（当前可用，priority 低值优先），其他 provider 预留（需配 Key 后启用）。
-- 同时为历史档位名（opus/sonnet/haiku）补等价映射行——现有 agent markdown 仍用历史名（DerivationEngine 默认 sonnet），
-- 不补会导致路由查不到。两套档位名都合法（ES-003 §2.5 tier 注释：opus/sonnet/haiku/reasoning/structured/mechanical）。
-- 幂等：INSERT IGNORE 依赖 UNIQUE 约束 uk_tier_provider_model，重跑不报错。
INSERT IGNORE INTO `t_model_routing` (`id`, `tier`, `provider`, `model`, `priority`, `api_key_env`, `base_url`, `role_hint`) VALUES
-- 新档位名（推荐）
(1001, 'reasoning',  'glm',      'glm-4-plus',     10, 'GLM_API_KEY',      'https://open.bigmodel.cn/api/paas/v4',                'EA/SE 复杂决策'),
(1002, 'reasoning',  'deepseek', 'deepseek-r1',    20, 'DEEPSEEK_API_KEY', 'https://api.deepseek.com/v1',                         '推理最强（需配 Key）'),
(1003, 'structured', 'glm',      'glm-4',          10, 'GLM_API_KEY',      'https://open.bigmodel.cn/api/paas/v4',                'Dev/Reviewer/PO 标准任务'),
(1004, 'structured', 'qwen',     'qwen-max',       20, 'QWEN_API_KEY',     'https://dashscope.aliyuncs.com/compatible-mode/v1',  '通义千问（需配 Key）'),
(1005, 'mechanical', 'glm',      'glm-4-flash',    10, 'GLM_API_KEY',      'https://open.bigmodel.cn/api/paas/v4',                'Ops 简单任务，免费'),
(1006, 'code',       'deepseek', 'deepseek-coder', 10, 'DEEPSEEK_API_KEY', 'https://api.deepseek.com/v1',                         'Dev 代码生成最强（需配 Key）'),
-- 历史档位名等价映射（兼容现有 agent markdown 的 opus/sonnet/haiku，迁移自原 GlmLlmAdapter 内联映射）
(1007, 'opus',       'glm',      'glm-4-plus',     10, 'GLM_API_KEY',      'https://open.bigmodel.cn/api/paas/v4',                '历史档位名=reasoning'),
(1008, 'sonnet',     'glm',      'glm-4',          10, 'GLM_API_KEY',      'https://open.bigmodel.cn/api/paas/v4',                '历史档位名=structured'),
(1009, 'haiku',      'glm',      'glm-4-flash',    10, 'GLM_API_KEY',      'https://open.bigmodel.cn/api/paas/v4',                '历史档位名=mechanical');

-- ============ M3-2：审计日志表（GRC 治理要求：操作可追溯，who/when/what/before/after）============
-- 审计日志按 tenant_id 显式记录（落库时由 AuditService 从 LoginUser 取 tenant_id 写入 detail.tenant_id 字段，
-- 表本身保留 tenant_id 列以便按租户隔离查询）。已加入 EaiselpTenantHandler.IGNORE_TABLES
-- ——不走拦截器自动注入（拦截器在 INSERT 时只填 tenant_id，不会自动给审计日志的所有维度做隔离，
-- 审计日志是只追加型 append-only 表，显式记录主操作上下文更清晰）。
DROP TABLE IF EXISTS `t_governance_log`;
CREATE TABLE `t_governance_log` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `user_id` BIGINT DEFAULT NULL,
  `username` VARCHAR(64) DEFAULT NULL,
  `action` VARCHAR(64) NOT NULL,
  `resource_type` VARCHAR(32) DEFAULT NULL,
  `resource_id` VARCHAR(128) DEFAULT NULL,
  `detail` JSON DEFAULT NULL,
  `ip_address` VARCHAR(64) DEFAULT NULL,
  `result` VARCHAR(16) NOT NULL DEFAULT 'success',
  `error_msg` VARCHAR(500) DEFAULT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_user` (`tenant_id`, `user_id`),
  KEY `idx_action` (`action`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计日志表';
