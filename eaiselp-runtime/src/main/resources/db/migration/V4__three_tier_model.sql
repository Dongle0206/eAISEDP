-- V4: 三层贯通数据模型（L3战略 → L2项目群/项目 → L1 Case）
-- 企业级平台骨架：四级联动 + 下行约束（架构原则/门禁规则）+ 上行事件（进度）
-- 
-- 修订 r2（2026-08-18，team-dba，评审 PRD v1.0 后）：
--   1. 新增 t_project_principle 项目-原则关联表（PRD 缺口一/Q3：租户全局默认 + 项目级覆盖，
--      AC-F3.3/AC-F7.1 的数据载体）
--   2. t_tenant 增加 strategy_enabled / program_project_enabled 分层开关（PRD 缺口二/Q8/F10，
--      DEFAULT 1 保证存量租户行为不变——AC-F10.4；L1 恒开不设列）
--   3. t_orchestration 增加 injected_json 注入清单快照列（PRD F7 P0 注入留痕：上下文/清单/日志三处落点之一）
--   4. 索引修正：跨表关联索引统一 tenant_id 打头（租户拦截器下所有查询都带 tenant_id 等值条件，
--      复合索引 (tenant_id, 关联列) 完全命中；原单列关联索引需回表后再过滤）
--   5. t_quality_gate_rule 索引 (tenant_id, stage, enabled) → (tenant_id, enabled, priority)：
--      编排启动主路径是 WHERE tenant_id=? AND enabled=1 ORDER BY priority（原索引 stage 在中间无法
--      用于 enabled 过滤，也不覆盖 priority 排序）；stage 筛选为低频 UI 查询，走前缀回表足够
--   6. t_quality_gate_rule 增加 uk_gate_tenant_name（规则名租户内唯一：管理页防重 + seed 幂等重放）
--   7. seed：为存量租户预置六条架构原则（AC-F5.3）+ 门禁规则（AC-F6.1/Q1：与现状 GATE_ROLES
--      等价，升级行为不变）；新建租户的 seed 由应用层租户初始化负责（见《数据库设计说明》§6）
-- 
-- 增补 r3（2026-08-18，team-dev，任务 T01/T02）：
--   8. 权限 seed 增补 14 原子（id 1032~1045）+ 模板角色授权行（id 2058 起，对齐 SE §10 矩阵：
--      engineer 零新增授权、executive 无 program:edit、project_manager 无 program/principle/gate:edit
--      与 tenant:layer:edit；strategy:view(1029)/program:view(1021)/program:create(1022) 复用 V1 既有原子）
--   9. 门禁 seed 增补第 3 条规则 c（部署人工审批 human_approval/pre_deploy/block/max_retries=1，
--      承接 team-ops 部署前检查点硬编码的等价行为，T02 裁决采纳 SE §6.5 等价表——
--      保证升级后 pre_deploy 审批行为不变，AC-F6.5；r2 的 2 条 a/b 不变）
-- 
-- 幂等说明（r4 修订，2026-08-20，#20 部署失败教训）：部署机库曾被手工 V4 草案部分执行
-- （DDL 隐式提交永久生效），"Flyway schema history 保证只执行一次"的假设破产——失败重放 +
-- 残留对象并存时，ADD COLUMN 撞已存在列报 1060 致命失败。本版全部非幂等 DDL
-- （ADD COLUMN / CREATE INDEX）改为 information_schema 动态判断（存储过程 + PREPARE），
-- 列/索引缺失才执行；任意半污染库重跑自动收敛。已验证（MySQL 8.0.29 + Flyway 9.22.3）：
-- 全新库 / 旧库 baseline / 污染库自愈重放 三场景全过。重放 WARN（1050 表已存在 /
-- 1062 重复 seed 被 IGNORE / 1305 DROP 不存在过程）均为预期无害告警。

-- ============ L3: 战略目标 ============
CREATE TABLE IF NOT EXISTS `t_strategy` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `title` VARCHAR(200) NOT NULL COMMENT '战略目标标题',
  `description` TEXT COMMENT '目标描述（背景/价值/衡量标准）',
  `horizon` VARCHAR(16) DEFAULT '1y' COMMENT '时间维度: 1q/1y/3y',
  `status` VARCHAR(32) NOT NULL DEFAULT 'draft' COMMENT 'draft/active/achieved/archived',
  `kpi` JSON DEFAULT NULL COMMENT 'KPI 指标（名称→目标值/当前值）',
  `owner` VARCHAR(64) DEFAULT NULL COMMENT '战略负责人',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_strategy_tenant` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='战略目标表（L3）';

-- ============ L2: 项目群 ============
CREATE TABLE IF NOT EXISTS `t_program` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `strategy_id` BIGINT DEFAULT NULL COMMENT '关联战略（L3→L2 联动；可空=P13 场景B 从 L2 接入）',
  `name` VARCHAR(200) NOT NULL COMMENT '项目群名称',
  `charter` TEXT COMMENT '项目群章程（下行约束：从战略目标继承的目标/边界）',
  `status` VARCHAR(32) NOT NULL DEFAULT 'planning' COMMENT 'planning/active/suspended/closed',
  `start_date` DATE DEFAULT NULL,
  `end_date` DATE DEFAULT NULL,
  `pgm_manager` VARCHAR(64) DEFAULT NULL COMMENT '项目群经理',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_program_tenant` (`tenant_id`, `status`),
  KEY `idx_program_strategy` (`tenant_id`, `strategy_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目群表（L2）';

-- ============ L2: 项目 ============
CREATE TABLE IF NOT EXISTS `t_project` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `program_id` BIGINT DEFAULT NULL COMMENT '所属项目群（L2 内层级；可空=独立项目）',
  `name` VARCHAR(200) NOT NULL COMMENT '项目名称',
  `description` TEXT COMMENT '项目描述/约束（非空时下行注入 Case 编排，见 F7）',
  `status` VARCHAR(32) NOT NULL DEFAULT 'planning' COMMENT 'planning/in_progress/delivered/closed',
  `priority` INT DEFAULT 5 COMMENT '优先级 1(高)-9(低)',
  `progress` INT DEFAULT 0 COMMENT '进度百分比 0-100（Case 完成自动上行汇总，页面只读）',
  `case_total` INT DEFAULT 0 COMMENT 'Case 总数（自动统计，F8 汇总算法）',
  `case_done` INT DEFAULT 0 COMMENT '已完成 Case 数（自动统计，F8 汇总算法）',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_project_tenant` (`tenant_id`, `status`),
  KEY `idx_project_program` (`tenant_id`, `program_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目表（L2）';

-- 残缺表补齐防御（部署机污染自救 2）：IF NOT EXISTS 会静默跳过"已存在但缺列"的表。
-- 测试机若手工跑过 V4 草案的残缺版建表（缺 program_id/status/progress 等列），
-- 应用层查询会运行时崩溃。此处逐列校验，缺失才补（仅覆盖 V4 本脚本定义的列）。
DROP PROCEDURE IF EXISTS `ensure_t_project_full`;
DELIMITER $$
CREATE PROCEDURE `ensure_t_project_full`()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_project' AND COLUMN_NAME='program_id') THEN
    ALTER TABLE `t_project` ADD COLUMN `program_id` BIGINT DEFAULT NULL COMMENT '所属项目群（L2 内层级；可空=独立项目）';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_project' AND COLUMN_NAME='description') THEN
    ALTER TABLE `t_project` ADD COLUMN `description` TEXT COMMENT '项目描述/约束（非空时下行注入 Case 编排，见 F7）';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_project' AND COLUMN_NAME='status') THEN
    ALTER TABLE `t_project` ADD COLUMN `status` VARCHAR(32) NOT NULL DEFAULT 'planning' COMMENT 'planning/in_progress/delivered/closed';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_project' AND COLUMN_NAME='priority') THEN
    ALTER TABLE `t_project` ADD COLUMN `priority` INT DEFAULT 5 COMMENT '优先级 1(高)-9(低)';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_project' AND COLUMN_NAME='progress') THEN
    ALTER TABLE `t_project` ADD COLUMN `progress` INT DEFAULT 0 COMMENT '进度百分比 0-100（Case 完成自动上行汇总，页面只读）';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_project' AND COLUMN_NAME='case_total') THEN
    ALTER TABLE `t_project` ADD COLUMN `case_total` INT DEFAULT 0 COMMENT 'Case 总数（自动统计，F8 汇总算法）';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_project' AND COLUMN_NAME='case_done') THEN
    ALTER TABLE `t_project` ADD COLUMN `case_done` INT DEFAULT 0 COMMENT '已完成 Case 数（自动统计，F8 汇总算法）';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_project' AND INDEX_NAME='idx_project_tenant') THEN
    SET @ddl = 'ALTER TABLE `t_project` ADD KEY `idx_project_tenant` (`tenant_id`, `status`)';
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_project' AND INDEX_NAME='idx_project_program') THEN
    SET @ddl = 'ALTER TABLE `t_project` ADD KEY `idx_project_program` (`tenant_id`, `program_id`)';
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;
CALL `ensure_t_project_full`();
DROP PROCEDURE IF EXISTS `ensure_t_project_full`;

-- ============ L2→L1: Case 关联项目（可空=P13 不强制） ============
-- legacy 说明：t_case 既有 program_id(VARCHAR)/subproject 为历史字段，保留只读、不写（PRD Q6）；
-- 本期所有新功能一律使用 project_id。
-- 幂等改造（部署机污染自救）：ADD COLUMN 列已存在报 1060，CREATE INDEX 索引已存在报 1061，
-- 均会致命失败。用 information_schema 动态判断，缺失才执行（存储过程 + PREPARE）。
DROP PROCEDURE IF EXISTS `add_col_case_project`;
DELIMITER $$
CREATE PROCEDURE `add_col_case_project`()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_case' AND COLUMN_NAME = 'project_id'
  ) THEN
    ALTER TABLE `t_case` ADD COLUMN `project_id` BIGINT DEFAULT NULL
      COMMENT '所属项目（L2→L1 联动；可空=不关联项目，全流程行为一致 AC-F4.3）';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_case' AND INDEX_NAME = 'idx_case_project'
  ) THEN
    -- 索引 tenant_id 打头：Case 列表按项目过滤的查询经租户拦截器改写为 WHERE tenant_id=? AND project_id=?
    SET @ddl = 'CREATE INDEX `idx_case_project` ON `t_case`(`tenant_id`, `project_id`)';
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;
CALL `add_col_case_project`();
DROP PROCEDURE IF EXISTS `add_col_case_project`;

-- ============ 项目-原则关联（r2 新增，PRD Q3/F3/F7） ============
-- 语义（注入解析，对齐 PRD F7 默认语义/Q2）：
--   项目无绑定行            → 注入租户全部 enabled=1 原则（全局强制默认）
--   项目有绑定行            → 注入 绑定行.enabled=1 且 原则.enabled=1 的原则（绑定即收窄）
--   绑定行全为 enabled=0    → 注入空集（项目显式豁免，不回退租户默认——fallback 判定条件是
--                             "无绑定行"而非"无启用绑定行"，避免歧义）
--   原则侧停用（enabled=0） → 绑定行保留，重新启用后自动恢复注入（AC-F5.2）
-- enabled 为项目级覆盖位：租户级原则启用，但本项目可单独停用该原则而保留绑定关系。
CREATE TABLE IF NOT EXISTS `t_project_principle` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `project_id` BIGINT NOT NULL COMMENT '项目 ID（t_project.id）',
  `principle_id` BIGINT NOT NULL COMMENT '架构原则 ID（t_architecture_principle.id）',
  `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '项目级覆盖: 1=本项目启用(默认) 0=本项目停用但保留绑定',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_principle` (`project_id`, `principle_id`),
  KEY `idx_pp_principle` (`principle_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目-原则关联表（F3 原则集绑定 / F7 注入解析，多对多）';
-- 索引说明：
--   uk_project_principle(project_id, principle_id) —— 防重复绑定 + 服务正向主查询
--     （项目详情/F7 注入解析：WHERE project_id=? AND enabled=1 AND is_deleted=0，uk 前缀完全命中）
--   idx_pp_principle(principle_id) —— 反向查询（原则删除时同步清理绑定 F5.4、原则影响面分析）
--   不加物理外键：全库为逻辑外键风格（与 V1 一致），联动清理在应用层实现（F2/F5 删除语义）
--   tenant_id 不入唯一键：project_id 为全局雪花 ID，(project_id, principle_id) 已逻辑唯一；
--     租户隔离由拦截器 WHERE 条件保证，tenant_id 入 uk 不增加隔离性反而弱化唯一约束

-- ============ 下行约束: 架构原则（EA 管理的，L1 编排强制注入） ============
CREATE TABLE IF NOT EXISTS `t_architecture_principle` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `code` VARCHAR(64) NOT NULL COMMENT '原则编号（如 P3/P6/P11）',
  `title` VARCHAR(200) NOT NULL COMMENT '原则标题',
  `content` TEXT NOT NULL COMMENT '原则内容（注入 L1 编排上下文，单条建议 ≤2000 字符）',
  `principle_type` VARCHAR(32) DEFAULT 'tech' COMMENT 'tech/data/security/governance',
  `enforce_level` VARCHAR(16) DEFAULT 'must' COMMENT 'must/should/may（must 违反时 Reviewer 门禁拦截）',
  `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '租户级启停（AC-F5.2：停用即时退出注入，绑定关系保留）',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_principle_code` (`tenant_id`, `code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='架构原则表（L3→L1 下行约束）';
-- uk_principle_code(tenant_id, code) 前缀即服务"租户启用原则列表"查询（AC-F7.2/F7.3），无需额外索引

-- ============ 下行约束: 质量门禁规则（企业可配置，不硬编码） ============
CREATE TABLE IF NOT EXISTS `t_quality_gate_rule` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `name` VARCHAR(200) NOT NULL COMMENT '规则名称（如"代码必须过语法验证"；租户内唯一）',
  `gate_type` VARCHAR(32) NOT NULL COMMENT 'auto_check/llm_review/human_approval',
  `gate_role` VARCHAR(64) DEFAULT NULL COMMENT 'llm_review 时的门禁角色（team-reviewer等）',
  `check_key` VARCHAR(64) DEFAULT NULL COMMENT 'auto_check 时的检查项（code_validation/api_contract等）',
  `applies_to` VARCHAR(64) DEFAULT 'all' COMMENT '生效范围: all/code/design/doc',
  `stage` VARCHAR(32) DEFAULT 'post_dev' COMMENT '挂载阶段: post_dev/post_test/pre_deploy',
  `max_retries` INT DEFAULT 2 COMMENT '门禁 FAIL 打回重做上限',
  `fail_action` VARCHAR(32) DEFAULT 'block' COMMENT 'block(阻断)/warn(告警放行)',
  `enabled` TINYINT NOT NULL DEFAULT 1,
  `priority` INT DEFAULT 100 COMMENT '同阶段多规则执行顺序（小者先）',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_gate_tenant_name` (`tenant_id`, `name`),
  KEY `idx_gate_tenant_enabled` (`tenant_id`, `enabled`, `priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质量门禁规则表（L2 治理→L1 执行，企业可配置）';
-- r2 索引变更：原 idx_gate_tenant_stage(tenant_id, stage, enabled) 删除，
--   换为 idx_gate_tenant_enabled(tenant_id, enabled, priority)——编排启动加载规则的
--   WHERE tenant_id=? AND enabled=1 ORDER BY priority 完全命中；按 stage 的 UI 筛选低频，走前缀回表。

-- ============ 注入留痕（r2 新增，PRD F7 P0：编排详情可见注入清单） ============
-- t_orchestration 建于 V1/V2，本列仅快照"本次编排实际注入了什么"，运行中编排不受规则修改影响
-- （快照语义 F6.5）与此列配合构成 AC-F7.1 的三处落点之一。
-- 幂等：information_schema 判断，列缺失才 ADD（部署机半污染库重跑自救，1060 不再致命）。
DROP PROCEDURE IF EXISTS `add_col_orch_injected`;
DELIMITER $$
CREATE PROCEDURE `add_col_orch_injected`()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_orchestration' AND COLUMN_NAME = 'injected_json'
  ) THEN
    ALTER TABLE `t_orchestration`
      ADD COLUMN `injected_json` JSON DEFAULT NULL COMMENT '下行注入清单快照（原则code列表+是否含项目约束+是否截断，AC-F7 留痕）';
  END IF;
END$$
DELIMITER ;
CALL `add_col_orch_injected`();
DROP PROCEDURE IF EXISTS `add_col_orch_injected`;

-- ============ 分层开关（r2 新增，PRD F10/Q8：P13 灵活接入的存储位） ============
-- DEFAULT 1 —— AC-F10.4：存量租户升级后默认全开，dogfooding 行为不变。开关是 opt-out 机制：
-- 场景B（关 L3）/场景C（关 L3+L2）由实施人员显式关闭（US-5），升级永不改变现有租户行为。
-- 关闭语义 = 菜单隐藏 + API 返回"功能未启用"业务错误 + 数据保留（可逆，AC-F10.3）。
-- 命名对齐 PRD §4.10 配置模型（strategy_enabled / program_project_enabled），不用 l3/l2 行话，
-- 实施人员读库排障直指功能层；L1 恒开不设列（F10：L1 不可关）。
-- t_tenant 在 EaiselpTenantHandler.IGNORE_TABLES 中，本表读写不走拦截器、按 id 直查，天然无越权面。
-- 幂等：两列分别判断，缺失才 ADD（部署机半污染库重跑自救）。
DROP PROCEDURE IF EXISTS `add_col_tenant_layers`;
DELIMITER $$
CREATE PROCEDURE `add_col_tenant_layers`()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_tenant' AND COLUMN_NAME = 'strategy_enabled'
  ) THEN
    ALTER TABLE `t_tenant`
      ADD COLUMN `strategy_enabled` TINYINT NOT NULL DEFAULT 1 COMMENT 'L3 战略层开关: 1=启用(默认) 0=关闭(菜单隐藏+API业务错误,数据保留可逆)';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_tenant' AND COLUMN_NAME = 'program_project_enabled'
  ) THEN
    ALTER TABLE `t_tenant`
      ADD COLUMN `program_project_enabled` TINYINT NOT NULL DEFAULT 1 COMMENT 'L2 项目群+项目层开关(一体,PRD Q8): 1=启用(默认) 0=关闭';
  END IF;
END$$
DELIMITER ;
CALL `add_col_tenant_layers`();
DROP PROCEDURE IF EXISTS `add_col_tenant_layers`;

-- ============ Seed: 存量租户预置六条架构原则（AC-F5.3，对齐战略地图 §4 / PRD F5） ============
-- 内容要点取自《企业架构蓝图》§7 原则定义与 PRD §0 前置约束；P13 取战略地图新增语义（灵活接入）。
-- 注意：蓝图 §7 的 P13 是"落库失败不阻塞主流程"，与战略地图/PRD 的 P13（灵活接入）编号冲突，
--       本 seed 按 PRD 契约执行，编号冲突待 team-ea 裁决（见《数据库设计说明》§8）。
-- id 用保留区间 7000000+（租户序号×10 + 原则序号）：避开 V1 权限 seed 的小 ID 与应用层雪花 ID
-- （雪花为 19 位十进制数，永不碰撞）。INSERT IGNORE + uk_principle_code 保证重放幂等。
-- 新建租户的默认原则由应用层租户初始化复制（迁移只覆盖存量租户，见《数据库设计说明》§6）。
INSERT IGNORE INTO `t_architecture_principle`
  (`id`, `tenant_id`, `code`, `title`, `content`, `principle_type`, `enforce_level`, `enabled`, `create_by`)
SELECT 7000000 + (DENSE_RANK() OVER (ORDER BY t.id) - 1) * 10 + n.ord,
       t.id, n.code, n.title, n.content, n.principle_type, n.enforce_level, 1, 'system'
FROM `t_tenant` t
CROSS JOIN (
  SELECT 1 AS ord, 'P3' AS code, '依赖方向单向无环' AS title,
         '模块依赖 runtime → capability/adapter/data → common 单向无环，禁止反向引用；分层架构 L3→L2→L1 单向引用，L1 编排不得反向依赖 L3 存在。' AS content,
         'tech' AS principle_type, 'must' AS enforce_level
  UNION ALL SELECT 2, 'P6', '平台零硬编码',
         '平台代码不得硬编码角色名、门禁角色集合、流程阶段与流水线注入内容，必须由租户可配置数据（如 t_quality_gate_rule）驱动，体系变更不改平台代码。',
         'governance', 'must'
  UNION ALL SELECT 3, 'P7', '唯一调度入口',
         '所有角色派生必经统一编排入口（OrchestrationService → DerivationEngine → AdapterFactory），不得绕过直调 LLM/Git/DocStore，保证埋点、持久化、配额与检查点治理一致。',
         'tech', 'must'
  UNION ALL SELECT 4, 'P8', '模型档位与具体模型解耦',
         '角色定义只写能力档位（reasoning/structured/mechanical），不得写具体模型名；档位→模型映射走 t_model_routing 配置表，模型换代只改路由表不改代码。',
         'tech', 'must'
  UNION ALL SELECT 5, 'P11', '多租户隔离贯穿',
         '所有业务表带 tenant_id，所有查询经租户拦截器自动注入过滤；架构原则、门禁规则与注入行为按租户生效，禁止任何跨租户数据访问。',
         'security', 'must'
  UNION ALL SELECT 6, 'P13', '灵活接入',
         '企业客户可从 L3/L2/L1 任一层接入，平台不强制全层使用；每层可独立启用，任一层关闭或数据为空时下层功能必须完整可用，上层存在不得成为下层运行的前置条件。',
         'governance', 'must'
) n
WHERE t.is_deleted = 0;

-- ============ Seed: 存量租户预置三条门禁规则（AC-F6.1/Q1：与现状等价，升级行为不变） ============
-- 替换 OrchestrationService 硬编码 GATE_ROLES 与 team-ops 部署前检查点的配套默认数据；
-- Java 常量集合/硬编码删除后无隐藏兜底（AC-F6.3）。
--   a) llm_review / team-reviewer / post_dev / block / max_retries=2   ← 等价 GATE_ROLES 中 reviewer
--   b) llm_review / team-qa       / post_test / block / max_retries=2  ← 等价 GATE_ROLES 中 qa
--   c) human_approval / (无角色)  / pre_deploy / block / max_retries=1 ← 等价 team-ops 前置检查点
--      （r3 增补，T02 裁决采纳 SE §6.5 等价表：删硬编码后若无规则 c，
--        升级后 pre_deploy 审批默认消失 = 行为变化，故显式补齐）
-- team-security/team-performance 不 seed（现状 FAST_PIPELINE 不含它们，仅智能规划偶发命中，
-- 租户可按需自建规则）；无 auto_check seed（收尾验证维持"只记录不阻断"现状，升级等价）。
-- id 保留区间 8000000+；INSERT IGNORE + uk_gate_tenant_name 幂等。
INSERT IGNORE INTO `t_quality_gate_rule`
  (`id`, `tenant_id`, `name`, `gate_type`, `gate_role`, `check_key`, `applies_to`, `stage`,
   `max_retries`, `fail_action`, `enabled`, `priority`, `create_by`)
SELECT 8000000 + (DENSE_RANK() OVER (ORDER BY t.id) - 1) * 10 + n.ord,
       t.id, n.name, n.gate_type, n.gate_role, NULL, 'all', n.stage,
       n.max_retries, 'block', 1, n.priority, 'system'
FROM `t_tenant` t
CROSS JOIN (
  SELECT 1 AS ord, '开发评审门禁（team-reviewer）' AS name, 'llm_review' AS gate_type,
         'team-reviewer' AS gate_role, 'post_dev' AS stage, 2 AS max_retries, 100 AS priority
  UNION ALL SELECT 2, '测试评审门禁（team-qa）', 'llm_review',
         'team-qa', 'post_test', 2, 110
  UNION ALL SELECT 3, '部署人工审批', 'human_approval',
         NULL, 'pre_deploy', 1, 120
) n
WHERE t.is_deleted = 0;

-- ============ Seed: 三层贯通权限原子 14 条 + 模板角色授权（T01 增补，AC-RBAC.1/2/3） ============
-- 对齐 SE §10 矩阵；strategy:view(1029)/program:view(1021)/program:create(1022) 复用 V1 既有原子，不重复 seed。
-- 矩阵约束：engineer 零新增授权（AC-RBAC.2 全 403）；executive 无 program:edit（只读联动 AC-RBAC.1）；
--           project_manager 无 program:edit/principle:edit/gate:edit/tenant:layer:edit。
-- 存量租户无角色新建动作：5 模板角色 tenant_id=0 系统级共享（V1 机制），授权行对所有租户生效。
-- 幂等：INSERT IGNORE（uk_permission_code / uk_role_perm 兜底），重放安全。

INSERT IGNORE INTO `t_permission` (`id`, `tenant_id`, `permission_code`, `permission_name`, `module`, `resource_type`, `action`, `description`) VALUES
(1032, 0, 'strategy:create',   '战略创建',     'strategy',  'strategy',  'create', NULL),
(1033, 0, 'strategy:edit',     '战略编辑',     'strategy',  'strategy',  'edit',   NULL),
(1034, 0, 'program:edit',      '项目群编辑',   'program',   'program',   'edit',   NULL),
(1035, 0, 'project:view',      '项目查看',     'project',   'project',   'view',   NULL),
(1036, 0, 'project:create',    '项目创建',     'project',   'project',   'create', NULL),
(1037, 0, 'project:edit',      '项目编辑',     'project',   'project',   'edit',   NULL),
(1038, 0, 'principle:view',    '架构原则查看', 'principle', 'principle', 'view',   NULL),
(1039, 0, 'principle:create',  '架构原则创建', 'principle', 'principle', 'create', NULL),
(1040, 0, 'principle:edit',    '架构原则编辑', 'principle', 'principle', 'edit',   NULL),
(1041, 0, 'gate:view',         '门禁规则查看', 'gate',      'gate',      'view',   NULL),
(1042, 0, 'gate:create',       '门禁规则创建', 'gate',      'gate',      'create', NULL),
(1043, 0, 'gate:edit',         '门禁规则编辑', 'gate',      'gate',      'edit',   NULL),
(1044, 0, 'tenant:layer:edit', '分层开关编辑', 'tenant',    'tenant',    'edit',   'PRJ-002 F10 分层开关'),
(1045, 0, 'case:delete',       'Case 删除',    'case',      'case',      'delete', 'PRJ-002 F8.3 删除触发进度重算');

-- 授权行（id 2058 起，V1 已用至 2057；引用 role_id 1~5 模板角色与 permission_id 1032~1045）

-- platform_admin (role_id=1)：14 项全量
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2058,1,1032),(2059,1,1033),(2060,1,1034),(2061,1,1035),(2062,1,1036),(2063,1,1037),
(2064,1,1038),(2065,1,1039),(2066,1,1040),(2067,1,1041),(2068,1,1042),(2069,1,1043),
(2070,1,1044),(2071,1,1045);

-- tenant_admin (role_id=2)：12 项（program:edit + project ×3 + principle ×3 + gate ×3 + tenant:layer:edit + case:delete；
-- 无 strategy:*——战略管理归 executive/platform_admin）
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2072,2,1034),                                                -- program:edit
(2073,2,1035),(2074,2,1036),(2075,2,1037),                    -- project ×3
(2076,2,1038),(2077,2,1039),(2078,2,1040),                    -- principle ×3
(2079,2,1041),(2080,2,1042),(2081,2,1043),                    -- gate ×3
(2082,2,1044),                                                -- tenant:layer:edit
(2083,2,1045);                                                -- case:delete

-- project_manager (role_id=3)：6 项（project ×3 + principle:view + gate:view + case:delete；
-- 无 program:edit/principle:edit/gate:edit/tenant:layer:edit，AC-RBAC.2）
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2084,3,1035),(2085,3,1036),(2086,3,1037),                    -- project ×3
(2087,3,1038),                                                -- principle:view
(2088,3,1041),                                                -- gate:view
(2089,3,1045);                                                -- case:delete

-- executive (role_id=5)：2 项（strategy:create/edit，战略管理联动；无 program:edit，只读联动 AC-RBAC.1）
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2090,5,1032),(2091,5,1033);

-- engineer (role_id=4)：0 项（零新增授权，AC-RBAC.2，无 INSERT）
