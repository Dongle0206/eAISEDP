-- V6: L2 治理收口——标准库/模板库/数据资产/质量规则 + 试用到期拦截（case-20260820-L2治理收口）
-- 覆盖 PRD §4 功能点：
--   F1.1 工程标准   → 新表 t_standard（多版本行模型：每编号每版本一行，同编号至多一个 published，
--                      发布新版自动 deprecated 旧版——编排者裁决 Q7）
--   F1.2 模板库     → 新表 t_template（单行当前版原地升版：一模板一当前态，版本变更写审计——裁决 Q7，
--                      与标准的多版本行模型有意不同：模板=操作资产、标准=合规资产）
--   F1.3 标准-门禁  → 关联放标准侧 related_gate_names JSON（裁决 Q1：不改已发布的 t_quality_gate_rule，
--                      已发布迁移不可改，Flyway checksum）
--   F2.1 数据资产   → 新表 t_data_asset（敏感等级四档，裁决 Q2）
--   F2.2 质量规则   → 新表 t_data_quality_rule（单资产关联 + 阈值百分比 0~100，裁决 Q5）
--   F3   试用到期拦截 → 零 DDL：复用 t_tenant 既有列 edition/expire_time（V1 已建），本迁移对既有表
--                      零结构变更、零 UPDATE、零 seed；拦截点在应用层（登录凭据校验后 + 派生前置校验，
--                      错误码 40003——裁决 Q3/Q9），数据库侧无任何动作。
--
-- 幂等说明（对齐 V5 r2 / V4 r4，#20 部署失败教训强制）：本版对既有表零 ALTER / 零 UPDATE，
--   仅有 4 张新表 CREATE TABLE IF NOT EXISTS + seed INSERT IGNORE（唯一键兜底）——天然幂等，
--   不涉及存储过程与 DELIMITER（无 ADD COLUMN/ADD KEY 类非幂等 DDL，无 1060/1061 风险面），
--   任意污染库重放自动收敛。重放 WARN（1050 表已存在 / 1062 重复 seed 被 IGNORE）均为预期无害告警。
--
-- ID 区间顺延（V5 用毕 permission id 1058 / role_permission id 2133）：
--   权限原子 id 1059~1070（12 条）；角色授权行 id 2134~2169（36 行）。
--   V7 起从 1071 / 2170 续排。
--
-- 租户隔离：四张新表均为租户级业务表，不进 EaiselpTenantHandler.IGNORE_TABLES
--   （租户拦截器自动生效，无 Java 改动）；全部索引按"拦截器改写后的真实 SQL"设计（tenant_id 打头，
--   唯一键例外见各表注释）。四域均不限层（PRD §1.3，场景 C 可用），不注册进 LayerGuardInterceptor。

-- ============ F1.1: 工程标准（租户级知识资产，不限层，PRD §4.1.1） ============
-- 多版本行模型（裁决 Q7，区别于 ADR 单记录/模板原地升版）：同一编号允许多版本行共存
--   （v1.0 deprecated 与 v2.0 published 并存可追溯），合规资产发布版本不可变。
-- 状态机（应用层校验，非法流转 400，DB 不加约束——对齐 V5 ADR 先例）：
--   draft→published（发布）；published→deprecated（必填废弃原因）；draft→deprecated（作废，必填原因）；
--   deprecated 为终态；published→draft 等其余流转一律 400。
-- "同编号至多一个 published"由应用层保证（发布事务内：SELECT 旧 published 版本 → 置 deprecated
--   （废弃原因=被 {standard_code} {新版本} 取代）→ 置新版本 published，各写一条审计，AC-F1.4）。
--   不用生成列 + 条件唯一索引（MySQL 无部分索引，需 generated column 模拟）实现 DB 级约束：
--   发布本身是事务性写操作，行级锁下无竞态窗口，DB 约束只防应用层 bug 不防并发错序，
--   且引入生成列破坏"零 ALTER/纯 IF NOT EXISTS"的幂等简洁性——权衡取应用层（见设计文档 §7 风险项）。
CREATE TABLE IF NOT EXISTS `t_standard` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `standard_code` VARCHAR(64) NOT NULL COMMENT '标准编号（租户内标识一个标准族，默认 STD-NNNN 由应用层生成，可自定义；同编号多版本行共存）',
  `title` VARCHAR(200) NOT NULL COMMENT '标题',
  `version` VARCHAR(32) NOT NULL COMMENT '版本号（如 v1.0；同编号内不可重复，编辑 published 禁止——升版建新行）',
  `status` VARCHAR(16) NOT NULL DEFAULT 'draft' COMMENT 'draft/published/deprecated（终态；流转应用层校验，DB 不加约束，对齐 V5 ADR 先例）',
  `content` MEDIUMTEXT COMMENT '标准正文 markdown（应用层建议 ≤20000 字符——MEDIUMTEXT 而非 TEXT 的纠偏：utf8mb4 下 20000 汉字约 80000 字节，超 TEXT 65535 字节上限会写入失败/截断，同 V5 VARCHAR(8) 存不下 FAIL_WARN 的宽度纠偏先例）',
  `related_principle_codes` JSON DEFAULT NULL COMMENT '关联架构原则 code 列表（引用 t_architecture_principle.code，可空多选，应用层存在性校验，同 V5 ADR related_principle_codes 先例）',
  `related_gate_names` JSON DEFAULT NULL COMMENT '关联门禁规则 name 列表（裁决 Q1：关联放标准侧，不改已发布的 t_quality_gate_rule；name 为其租户内唯一键 uk_gate_tenant_name 的业务标识，打回展示按此解析，规则删除时展示层置已删除占位 AC-F1.7）',
  `deprecate_reason` VARCHAR(500) DEFAULT NULL COMMENT '废弃原因（status=deprecated 时必填——应用层校验；发布新版自动取代时由应用层写入被 {编号} {新版本} 取代语义文案）',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_std_tenant_code_version` (`tenant_id`, `standard_code`, `version`),
  KEY `idx_std_tenant_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工程标准表（租户级知识资产，不限层，多版本行模型，F1.1）';
-- 索引说明：
--   uk_std_tenant_code_version(tenant_id, standard_code, version) —— PRD 契约 uk(tenant, 编号, 版本)：
--     同编号同版本不可重复（AC-F1.1 第二次创建被拒），同编号不同版本合法共存（多版本行模型）；
--     "标准族的全部版本行"查询（WHERE tenant_id=? AND standard_code=?，发布取代/版本时间线）uk 前缀
--     (tenant_id, standard_code) 完全命中。standard_code 含租户自定义值非全局雪花，必须带 tenant_id。
--   idx_std_tenant_status(tenant_id, status) —— 列表默认筛选 draft+published（AC-F1.2，deprecated 需
--     显式筛选）与按状态筛选；发布取代前的"查旧 published 版本"（WHERE tenant_id=? AND standard_code=?
--     AND status=published）先走 uk 前缀再过滤，单编号版本行数极少无需并入 status。
--   related_principle_codes / related_gate_names 用 JSON 而非关联表：同 V5 ADR 论证——纯展示级引用，
--     无行级启停/独立生命周期诉求，JSON_CONTAINS 租户内扫描（标准 ≤1000 行量级 PRD §6.5，毫秒级，
--     无需索引）；不建关联表避免过度设计。

-- ============ F1.2: 模板库（租户级知识资产，不限层，L1 编排注入源，PRD §4.1.2） ============
-- 单行当前版原地升版模型（裁决 Q7）：一个模板一行一个当前态，uk 不含 version——版本是描述字段，
--   编辑正文时版本必须变更且不等于当前值（应用层校验，不比较大小，AC-F1.12），
--   旧版本内容唯一留痕 = t_governance_log 审计 detail（含旧版本号，同 V5 雷达环移动先例，不落历史行）。
-- 模板类型为开放字典（PRD §0 P6 裁决）：前端预置 PRD/技术方案/任务清单/测试用例/部署方案 5 常用值 +
--   允许自由输入自定义，应用层不枚举校验（本列无 COMMENT 穷举值即此意，防止字典散落两处）。
-- 占位符清单不落库：详情实时从 content 提取合法 {{标识符}} 列表（AC-F1.11），无独立列——
--   派生存储与源头 content 保持一致，避免双写不同步。
CREATE TABLE IF NOT EXISTS `t_template` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `template_type` VARCHAR(64) NOT NULL COMMENT '模板类型（开放字典：前端预置常用值+允许自定义，应用层不枚举校验，PRD §0 P6 裁决）',
  `template_name` VARCHAR(200) NOT NULL COMMENT '模板名称',
  `version` VARCHAR(32) NOT NULL COMMENT '版本号（原地升版：编辑时必须变更且不等于当前值，应用层校验；旧版本仅存审计 detail）',
  `content` MEDIUMTEXT COMMENT '模板正文 markdown，支持 {{标识符}} 占位符（标识符限字母数字下划线，无占位符合法）；MEDIUMTEXT 纠偏论证同 t_standard.content',
  `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '启用状态: 1=启用(默认) 0=停用（列表默认隐藏、筛选可见；本期启停无编排行为影响，同原则/门禁 enabled 先例）',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tpl_tenant_type_name` (`tenant_id`, `template_type`, `template_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模板库表（租户级知识资产，不限层，单行当前版原地升版，F1.2）';
-- 索引说明：
--   uk_tpl_tenant_type_name(tenant_id, template_type, template_name) —— PRD 契约 uk(tenant, 类型, 名称)：
--     同类型同名不可重复、不同类型同名合法（AC-F1.9 三例逐条对应）；列表主查询（WHERE tenant_id=?）
--     与类型筛选（WHERE tenant_id=? AND template_type=?，含自定义类型值 AC-F1.10）均由 uk 前缀命中。
--   不加 (tenant_id, enabled) 索引：默认列表"隐藏停用"= WHERE tenant_id=? AND enabled=1，模板 ≤500
--     行/租户（PRD §6.5）uk 的 tenant_id 前缀扫描后过滤毫秒级；enabled 基数仅 2，区分度极差，
--     按 V5 gate_result 索引评估先例"按需加列不按需加索引"，待量级证明慢查询后再评估。

-- ============ F2.1: 数据资产目录（租户级知识资产，不限层，PRD §4.2.1） ============
-- 资产类型/敏感等级为领域数据字典枚举（PRD §0 P6 裁决），应用层校验非法 400，DB 不加约束
--   （对齐 V5 ADR 状态机先例）：asset_type=database/table/api/report/file；
--   sensitivity=public/internal/sensitive/confidential（裁决 Q2，四档对齐 PRD 中文枚举，
--   不做租户自定义，防筛选/色阶逻辑碎片化）。
-- 责任人为自由 VARCHAR（本期不联动 t_user）；tags 自由值 JSON 数组随录入自然扩展，
--   无独立标签管理页（AC-F2.4），筛选器候选由应用层聚合查询生成。
CREATE TABLE IF NOT EXISTS `t_data_asset` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `asset_name` VARCHAR(128) NOT NULL COMMENT '资产名称（如 t_order；同系统内同名冲突，跨系统合法）',
  `system_name` VARCHAR(128) NOT NULL COMMENT '所属系统（自由填写，如 ERP/CRM）',
  `asset_type` VARCHAR(16) NOT NULL COMMENT '资产类型: database/table/api/report/file（应用层枚举校验非法 400，DB 不加约束）',
  `owner` VARCHAR(64) DEFAULT NULL COMMENT '责任人（自由填写，本期不联动 t_user；命名对齐 V5 t_milestone.owner）',
  `sensitivity` VARCHAR(16) NOT NULL COMMENT '敏感等级: public/internal/sensitive/confidential 四档（裁决 Q2；应用层枚举校验；元数据标注，行级可见性为范围外 §7-9）',
  `description` TEXT COMMENT '资产描述（描述类短文本，TEXT 足够；区别于标准/模板正文类 MEDIUMTEXT）',
  `tags` JSON DEFAULT NULL COMMENT '标签列表（自由值数组，随录入自然扩展，无独立标签管理页 AC-F2.4）',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_asset_tenant_system_name` (`tenant_id`, `system_name`, `asset_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据资产目录表（租户级知识资产，不限层，F2.1）';
-- 索引说明：
--   uk_asset_tenant_system_name(tenant_id, system_name, asset_name) —— PRD 契约 uk(tenant, 系统, 名称)：
--     同系统同名资产不可重复登记、跨系统同名合法（AC-F2.1 三例逐条对应）；列表主查询（WHERE tenant_id=?）
--     与关键字筛选（名称+系统模糊，左模糊不走索引，租户内扫描）由 uk 前缀命中。
--   不加 (tenant_id, asset_type) / (tenant_id, sensitivity) 筛选索引：两列基数仅 5/4，区分度极差；
--     资产 ≤5000 行/租户（PRD §6.5）uk 的 tenant_id 前缀扫描后过滤毫秒级（P95 < 500ms 水位内）；
--     标签筛选本就只走 JSON_CONTAINS 租户内扫描（AC-F2.4）。四维筛选均为低频管理页操作，
--     按需加列不按需加索引——待量级证明慢查询后再评估（见设计文档 §7 风险项）。

-- ============ F2.2: 数据质量规则（租户级知识资产，不限层，PRD §4.2.2） ============
-- 单资产关联（裁决 Q5：质量规则针对具体资产的具体维度，多资产拆多条规则）；asset_id 存在性校验
--   应用层（无效 400，含指向已逻辑删资产 AC-F2.7）；无物理外键——全库逻辑外键风格（V1 先例），
--   资产逻辑删时关联规则同步逻辑删由应用层实现并写审计（AC-F2.7）。
-- 检查类型枚举（PRD §0 P6 裁决）：completeness/accuracy/consistency/timeliness，应用层校验 DB 不加约束。
-- 阈值语义=百分比达标线 0~100（裁决 Q5，登记人按检查类型解释，展示附百分比）；边界 0 与 100 合法、
--   越界 100.5/-1 应用层 400（AC-F2.5）——DECIMAL(5,2) 本身可容 0~999.99，区间约束在应用层。
-- 最近检查结果为单值当前态（手动登记覆盖式更新，AC-F2.6）：历史唯一留痕 = t_governance_log 审计
--   detail（同 V5 雷达环移动先例，不建历史表）；pass/fail 由登记人判定，平台不做阈值自动判定（§7-5）。
CREATE TABLE IF NOT EXISTS `t_data_quality_rule` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `rule_name` VARCHAR(200) NOT NULL COMMENT '规则名（租户内唯一，同 V4 t_quality_gate_rule uk_gate_tenant_name 先例）',
  `asset_id` BIGINT NOT NULL COMMENT '关联资产 ID（单选 t_data_asset.id，裁决 Q5；存在性/未删除应用层校验，无物理外键——V1 逻辑外键风格）',
  `check_type` VARCHAR(16) NOT NULL COMMENT '检查类型: completeness/accuracy/consistency/timeliness（应用层枚举校验非法 400，DB 不加约束）',
  `threshold` DECIMAL(5,2) NOT NULL COMMENT '阈值=百分比达标线 0~100（如 99.5 表示 ≥99.5% 达标；区间应用层校验，边界 0/100 合法 AC-F2.5）',
  `last_result` VARCHAR(8) DEFAULT NULL COMMENT '最近检查结果: pass/fail（登记人判定，平台不按阈值自动判定；NULL=从未登记）',
  `last_actual_value` DECIMAL(10,4) DEFAULT NULL COMMENT '最近检查实测值（登记时可选；百分比语义同 threshold，精度放宽容纳比率类实测）',
  `last_check_time` DATETIME DEFAULT NULL COMMENT '最近检查时间（登记时可选，缺省由应用层取当前时刻）',
  `last_check_remark` VARCHAR(500) DEFAULT NULL COMMENT '最近检查备注（如 字段缺失）',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dqr_tenant_name` (`tenant_id`, `rule_name`),
  KEY `idx_dqr_tenant_asset` (`tenant_id`, `asset_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据质量规则表（租户级知识资产，不限层，最近结果单值当前态，F2.2）';
-- 索引说明：
--   uk_dqr_tenant_name(tenant_id, rule_name) —— PRD 契约 uk(tenant, 规则名)：规则名租户内唯一（AC-F2.5
--     同名再建被拒）+ 列表主查询前缀；命名对齐 V4 uk_gate_tenant_name 先例。
--   idx_dqr_tenant_asset(tenant_id, asset_id) —— 资产详情页聚合展示其关联规则与最近结果（AC-F2.6，
--     WHERE tenant_id=? AND asset_id=? 拦截器改写后二等值完全命中）+ 资产逻辑删时联动逻辑删关联规则
--     （AC-F2.7 的定位扫描）。规则 ≤5000 行/租户（PRD §6.5）下索引命中。

-- ============ Seed: L2 治理收口权限原子 12 条 + 模板角色授权（PRD §4.4 / AC-RBAC.4） ============
-- 权限 code 前缀锁定 PRD §4.4 契约：standard/template/asset/dqrule × view/create/edit
--   （dqrule 而非 dq——PRD AC-RBAC.4 code 集合原文；module/resource_type 同前缀）。
-- 角色矩阵（PRD §4.4 权威矩阵 + 裁决 Q6：standards/steward 角色缺位由 tenant_admin 兼任）：
--   platform_admin 12 项全量（V1/V4/V5 惯例）
--   tenant_admin   12 项全量（兼任标准负责人/模板管理员/数据管家，US-1/US-3/US-4）
--   project_manager 4 项只读（四域 view——工程师首要消费方裁决同样适用于 PM 写方案场景）
--   engineer        4 项只读（四域 view——标准/模板首要消费方 + 资产元数据可见 Q8，ADR/雷达 view 先例）
--   executive       4 项只读（四域 view，US-7 合规风险量化视图）
-- F3 不新增权限原子：租户状态查询仅 tenant_admin/platform_admin（应用层角色校验，与 llm-key 端点同模式），
--   恢复路径 API 仅 platform_admin（PRD §4.3.2/§4.3.3）。
-- 权限/授权均为系统级（tenant_id=0 共享，t_permission/t_role_permission 在 IGNORE_TABLES），
--   对存量与新建租户一体生效，无需按租户 provisioning。
-- 幂等：INSERT IGNORE（uk_permission_code / uk_role_perm 兜底），重放安全。
INSERT IGNORE INTO `t_permission` (`id`, `tenant_id`, `permission_code`, `permission_name`, `module`, `resource_type`, `action`, `description`) VALUES
(1059, 0, 'standard:view',   '工程标准查看', 'standard', 'standard', 'view',   NULL),
(1060, 0, 'standard:create', '工程标准创建', 'standard', 'standard', 'create', NULL),
(1061, 0, 'standard:edit',   '工程标准编辑', 'standard', 'standard', 'edit',   '含发布/废弃/升版（发布新版自动 deprecated 旧 published 版本并写审计，AC-F1.4）'),
(1062, 0, 'template:view',   '模板库查看',   'template', 'template', 'view',   NULL),
(1063, 0, 'template:create', '模板库创建',   'template', 'template', 'create', NULL),
(1064, 0, 'template:edit',   '模板库编辑',   'template', 'template', 'edit',   '含原地升版（版本必须变更）与启停（本期启停无编排行为影响）'),
(1065, 0, 'asset:view',      '数据资产查看', 'asset',    'asset',    'view',   NULL),
(1066, 0, 'asset:create',    '数据资产创建', 'asset',    'asset',    'create', NULL),
(1067, 0, 'asset:edit',      '数据资产编辑', 'asset',    'asset',    'edit',   '含逻辑删（关联质量规则同步逻辑删并写审计，AC-F2.7）'),
(1068, 0, 'dqrule:view',     '质量规则查看', 'dqrule',   'dqrule',   'view',   NULL),
(1069, 0, 'dqrule:create',   '质量规则创建', 'dqrule',   'dqrule',   'create', NULL),
(1070, 0, 'dqrule:edit',     '质量规则编辑', 'dqrule',   'dqrule',   'edit',   '含手动登记最近检查结果（覆盖式更新，历史留痕走审计，AC-F2.6）');

-- 授权行（id 2134 起：V5 用毕 2133；引用 role_id 1~5 模板角色与 permission_id 1059~1070）

-- platform_admin (role_id=1)：12 项全量
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2134,1,1059),(2135,1,1060),(2136,1,1061),                    -- standard ×3
(2137,1,1062),(2138,1,1063),(2139,1,1064),                    -- template ×3
(2140,1,1065),(2141,1,1066),(2142,1,1067),                    -- asset ×3
(2143,1,1068),(2144,1,1069),(2145,1,1070);                    -- dqrule ×3

-- tenant_admin (role_id=2)：12 项全量（裁决 Q6：standards/steward 角色缺位由其兼任）
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2146,2,1059),(2147,2,1060),(2148,2,1061),                    -- standard ×3
(2149,2,1062),(2150,2,1063),(2151,2,1064),                    -- template ×3
(2152,2,1065),(2153,2,1066),(2154,2,1067),                    -- asset ×3
(2155,2,1068),(2156,2,1069),(2157,2,1070);                    -- dqrule ×3

-- project_manager (role_id=3)：4 项只读（四域 view，PRD §4.4 矩阵）
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2158,3,1059),                                                -- standard:view
(2159,3,1062),                                                -- template:view
(2160,3,1065),                                                -- asset:view
(2161,3,1068);                                                -- dqrule:view

-- engineer (role_id=4)：4 项只读（标准/模板首要消费方 + 资产/质量元数据可见，Q8/US-7）
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2162,4,1059),                                                -- standard:view
(2163,4,1062),                                                -- template:view
(2164,4,1065),                                                -- asset:view
(2165,4,1068);                                                -- dqrule:view

-- executive (role_id=5)：4 项只读（四域 view，US-7 合规风险量化视图；无任何 create/edit）
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2166,5,1059),                                                -- standard:view
(2167,5,1062),                                                -- template:view
(2168,5,1065),                                                -- asset:view
(2169,5,1068);                                                -- dqrule:view
