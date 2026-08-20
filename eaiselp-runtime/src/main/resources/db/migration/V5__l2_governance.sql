-- V5: L2 治理核心 + 治理知识资产（case-20260818-L2治理核心 / PRJ-003 主体 + PRJ-004 拆分项）
-- 覆盖 PRD §4 五功能点：
--   F1 DORA 埋点   → t_derivation 新增 gate_result 列（恢复时间 RT 精确化，PRD Q1）
--   F2 里程碑激活   → t_milestone 死表 ALTER 补列（V1 已发布表，普通 ALTER）
--   F3 跨项目依赖   → 新表 t_project_dependency（L2，受 program_project_enabled 约束）
--   F4 ADR 库      → 新表 t_adr（不限层，租户级知识资产）
--   F5 技术雷达    → 新表 t_tech_radar_item（不限层）
--
-- 命名对齐说明（任务书简写 → 本迁移落地名，以 PRD §4 契约为准）：
--   t_tech_radar                → t_tech_radar_item（PRD §4.5 明确表名）
--   dep_type / from_project     → dependency_type / from_project_id / to_project_id（PRD §4.3 列名）
--   related_principle_code 单值 → related_principle_codes JSON 数组（PRD §4.4"可空多选"契约，AC-F4.4 按原则筛选）
--   gate_result VARCHAR(8)      → VARCHAR(16)——DBA 纠偏：FAIL_WARN 为 9 字符，VARCHAR(8) 存不下会写入失败/截断
--
-- 幂等说明（r2 修订，2026-08-20，同 V4 r4 教训）：部署机半污染库重放时 ADD COLUMN 撞
-- 已存在列报 1060 致命失败，本版全部非幂等 DDL（ADD COLUMN / ADD KEY）改为
-- information_schema 动态判断（存储过程 + PREPARE），缺失才执行，任意污染库重跑收敛。
-- 建表语句用 IF NOT EXISTS，seed 用 INSERT IGNORE（唯一键兜底）。
-- 已发布迁移 V1~V4 注释与结构不改一字（V4 幂等化在其自身 r4 完成），对已发布表
-- （t_milestone / t_derivation / t_governance_log）用动态判断 ALTER。
--
-- ID 区间顺延（V4 r3 用毕 permission id 1045 / role_permission id 2091）：
--   权限原子 id 1046~1058（13 条）；角色授权行 id 2092~2133（42 行）。V6 起从 1059 / 2134 续排。
--
-- 租户隔离：三张新表均为租户级业务表，不进 EaiselpTenantHandler.IGNORE_TABLES
-- （租户拦截器自动生效，无 Java 改动）；全部索引按"拦截器改写后的真实 SQL"设计（tenant_id 打头，
-- 唯一键例外见各表注释）。

-- ============ F3: 跨项目依赖（L2，PRD §4.3） ============
-- 规范向存储（PRD Q4 默认裁决）：一律存"依赖方→被依赖方"（from 依赖 to）；
--   "A 阻塞 B"快捷录入由应用层换向存为 from=B,to=A——blocks 与 depends_on 在 blocked 判定/环检测上等价，
--   仅文案区分；relates_to 为弱关联（不计 blocked、不参与环检测）。
-- 环检测（P3 落地）与 blocked 判定（依赖项目 status ∉ {delivered,closed}）均为应用层实时计算，不落库。
CREATE TABLE IF NOT EXISTS `t_project_dependency` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `from_project_id` BIGINT NOT NULL COMMENT '依赖方项目 ID（t_project.id）',
  `to_project_id` BIGINT NOT NULL COMMENT '被依赖方项目 ID（t_project.id）；from≠to 禁止自依赖（应用层校验）',
  `dependency_type` VARCHAR(16) NOT NULL COMMENT 'depends_on=强依赖(blocks 判定+环检测)/blocks=语义等价，换向存储仅文案区分/relates_to=弱关联不判定',
  `note` VARCHAR(500) DEFAULT NULL COMMENT '备注（如保留用户原始表述"A 阻塞 B"，PRD Q4）',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dep_tenant_from_to_type` (`tenant_id`, `from_project_id`, `to_project_id`, `dependency_type`),
  KEY `idx_dep_tenant_to` (`tenant_id`, `to_project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跨项目依赖表（L2 治理，F3）';
-- 索引说明：
--   uk_dep_tenant_from_to_type(tenant_id, from, to, type) —— "同一对项目同类型依赖唯一"（PRD §4.3 防重复
--     登记，AC-F3.1 第二次登记被拒）+ 正向查询"项目 P 依赖谁"：拦截器改写后的真实 SQL 是
--     WHERE tenant_id=? AND from_project_id=?，(tenant_id, from_project_id) 前缀完全命中。
--     与 V4 t_project_principle"tenant 不入 uk"先例相反的权衡说明：该先例论证依赖"两端均为全局雪花 ID，
--     跨租户同对不可能"；本表同样成立，但租户粒度即业务规则粒度（唯一性是租户内治理规则，P11 隔离边界），
--     且沙箱实测 uk 不含 tenant 时"租户 B 登记与租户 A 相同 ID 对"会被误拦（导入/修复脚本使用小 ID 的
--     防御场景），入 tenant_id 代价仅索引项 +8 字节——取租户粒度唯一。任务书 uk(from,to,type) 为本复合键尾部。
--   idx_dep_tenant_to(tenant_id, to_project_id) —— 反向查询"谁依赖 P"（责任项/项目删除联动清理 AC-F3.5）。
--     看板全图加载（WHERE tenant_id=?）由 uk 的 tenant_id 前缀扫描承担，无需单独 tenant 单列索引。
--   无物理外键：全库逻辑外键风格（V1 先例），项目删除时依赖边同步逻辑删由应用层实现（AC-F3.5）。

-- ============ F4: ADR 架构决策记录（不限层，租户级知识资产，PRD §4.4） ============
-- 五段式：编号/上下文/决策/后果/关联原则；状态机应用层校验（非法流转 400，AC-F4.2）：
--   proposed→accepted；accepted→deprecated(必填废弃说明)；accepted→superseded(必填 superseded_by，
--   且新 ADR 须为 accepted)；superseded/deprecated 为终态。ADR-NNN 默认编号由应用层生成（DB 无序列）。
CREATE TABLE IF NOT EXISTS `t_adr` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `adr_code` VARCHAR(64) NOT NULL COMMENT 'ADR 编号（租户内唯一，默认 ADR-NNN 由应用层生成，可自定义）',
  `title` VARCHAR(200) NOT NULL COMMENT '标题',
  `status` VARCHAR(16) NOT NULL DEFAULT 'proposed' COMMENT 'proposed/accepted/deprecated/superseded（终态后两者不可回退）',
  `context_text` TEXT COMMENT '上下文（五段式，应用层必填校验，建议 ≤5000 字符）',
  `decision_text` TEXT COMMENT '决策（五段式，应用层必填校验）',
  `consequence_text` TEXT COMMENT '后果（五段式，应用层必填校验）',
  `related_principle_codes` JSON DEFAULT NULL COMMENT '关联架构原则 code 列表（引用 t_architecture_principle.code，可空多选，如 ["P3","P11"]）',
  `decision_date` DATE DEFAULT NULL COMMENT '决策日期',
  `author` VARCHAR(64) DEFAULT NULL COMMENT '作者',
  `superseded_by` VARCHAR(64) DEFAULT NULL COMMENT '被取代指向的新 ADR 编号（status=superseded 时必填，应用层校验指向 accepted 的 ADR）',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_adr_tenant_code` (`tenant_id`, `adr_code`),
  KEY `idx_adr_tenant_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ADR 架构决策记录表（租户级知识资产，不限层，F4）';
-- 索引说明：
--   uk_adr_tenant_code(tenant_id, adr_code) —— PRD 契约 uk(tenant, adr_code)：编号租户内唯一（AC-F4.1
--     同编号重复创建被拒）+ 主查询前缀；adr_code 含租户自定义值，非全局雪花，必须带 tenant_id。
--   idx_adr_tenant_status(tenant_id, status) —— 列表默认筛选 proposed+accepted / 按状态筛选（AC-F4.4），
--     单租户 5000 ADR 量级（§6.2）下索引命中。
--   related_principle_codes 用 JSON 而非关联表/单值列：PRD §4.4"可空多选"+AC-F4.4 按原则筛选 +
--     原则侧反向聚合——筛选/反向查走 JSON_CONTAINS 租户内扫描（5000 行毫秒级，无需索引）；
--     不建 t_adr_principle 关联表：原则引用不要求行级启停/独立生命周期，一张纯展示用关联表过度设计
--     （对照 V4 D2：t_project_principle 建表是因需要 enabled 覆盖位与删除联动，此处无此诉求）。

-- ============ F5: 技术雷达（不限层，租户级知识资产，PRD §4.5） ============
-- 一个技术一个当前态（tech_name 租户内唯一）；环移动（如 trial→adopt）不落新行、原地更新，
-- 变更历史唯一留痕 = t_governance_log 审计 detail(from_ring→to_ring)——雷达版本管理属范围外（PRD §7-9）。
CREATE TABLE IF NOT EXISTS `t_tech_radar_item` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `tech_name` VARCHAR(128) NOT NULL COMMENT '技术项名称（租户内唯一，一个技术一个当前态）',
  `quadrant` VARCHAR(16) NOT NULL COMMENT '象限: techniques/tools/platforms/languages（应用层枚举校验，非法值 400）',
  `ring` VARCHAR(16) NOT NULL COMMENT '环: adopt(最内)/trial/assess/hold(最外，视觉警示)——环移动写审计',
  `reason` TEXT NOT NULL COMMENT '定环理由（必填，建议 ≤2000 字符）',
  `reviewed_at` DATE NOT NULL COMMENT '评审日期（必填；距今 >180 天由展示层标"待复审"，不落库）',
  `remark` VARCHAR(500) DEFAULT NULL COMMENT '备注',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_radar_tenant_name` (`tenant_id`, `tech_name`),
  KEY `idx_radar_tenant_quadrant_ring` (`tenant_id`, `quadrant`, `ring`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='技术雷达表（租户级知识资产，不限层，F5）';
-- 索引说明：
--   uk_radar_tenant_name(tenant_id, tech_name) —— PRD 契约 uk(tenant, tech_name)：同名技术不可重复创建
--     （AC-F5.1 ①唯一性冲突）+ 雷达全量列表主查询前缀。
--   idx_radar_tenant_quadrant_ring(tenant_id, quadrant, ring) —— 列表/四象限按象限×环筛选（AC-F5.2）。

-- ============ F2: t_milestone 死表激活（V1 已发布表，普通 ALTER，PRD §4.2） ============
-- V1 现结构评估结论：不够用。缺两级归属（Q3：owner_type+owner_id 单列对，比双列二选一非空的
--   OR 查询可索引、约束清晰）、缺达成日期/负责人/描述/用户可见编号；status 默认 not_started 与
--   本期词汇表不符。V1 死表无数据（PRD §1.1），无需存量数据迁移。
-- legacy 处置（PRD §4.2.1，同 t_case.program_id 的 PRJ-002 Q6 先例）：
--   program_id(VARCHAR)/milestone_id(VARCHAR) 保留只读不写——放松 NOT NULL 以支持"不写"
--   （否则新插入仍被迫填假值污染 legacy 列）；uk_tenant_program_ms/idx_program 两列转 NULL 后
--   对新行不再构成约束（MySQL 唯一索引允许多个 NULL），保留不动，避免对死表历史结构做无谓手术。
--   subprojects 语义转为"项目群级里程碑涉及项目多选（仅展示）"；integration_points 保留不启用。
-- 状态机（应用层校验，系统永不自动置 achieved/delayed，PRD §4.2.3）：
--   planned→achieved（人工确认，achieved_date 必填=当天可改）；planned→delayed（人工标记）；
--   delayed→achieved（达成日期必填）；achieved→planned（撤销，清空达成日期，留审计）。
-- 幂等改造（部署机污染自救）：ADD COLUMN 列已存在报 1060、ADD KEY 索引已存在报 1061，
-- 均致命失败；用 information_schema 动态判断，缺失才执行。MODIFY 针对 V1 既有核心列
-- （status/program_id/milestone_id）重复定义无害（幂等），保持直接执行。
DROP PROCEDURE IF EXISTS `alter_ms_v5`;
DELIMITER $$
CREATE PROCEDURE `alter_ms_v5`()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_milestone' AND COLUMN_NAME='owner_type') THEN
    ALTER TABLE `t_milestone`
      ADD COLUMN `owner_type` VARCHAR(16) NOT NULL DEFAULT 'program' COMMENT '归属层级: program=项目群级/project=项目级（二选一，PRD Q3 裁决；死表无存量行，DEFAULT 仅占位，应用层必写）';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_milestone' AND COLUMN_NAME='owner_id') THEN
    ALTER TABLE `t_milestone`
      ADD COLUMN `owner_id` BIGINT NOT NULL DEFAULT 0 COMMENT '归属对象 ID: owner_type=program→t_program.id / project→t_project.id（雪花 ID；同上 DEFAULT 仅占位）';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_milestone' AND COLUMN_NAME='milestone_code') THEN
    ALTER TABLE `t_milestone`
      ADD COLUMN `milestone_code` VARCHAR(32) NOT NULL COMMENT '用户可见编号（如 MS-0001，租户内唯一，应用层生成；与 legacy milestone_id 分离）';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_milestone' AND COLUMN_NAME='description') THEN
    ALTER TABLE `t_milestone` ADD COLUMN `description` TEXT COMMENT '里程碑描述';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_milestone' AND COLUMN_NAME='owner') THEN
    ALTER TABLE `t_milestone` ADD COLUMN `owner` VARCHAR(64) DEFAULT NULL COMMENT '负责人';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_milestone' AND COLUMN_NAME='achieved_date') THEN
    ALTER TABLE `t_milestone`
      ADD COLUMN `achieved_date` DATE DEFAULT NULL COMMENT '达成日期（achieved 状态必有值，撤销达成时清空；达成一律人工确认，系统不自动置达成）';
  END IF;
  -- MODIFY 既有 V1 核心列（幂等，重复定义无害）
  ALTER TABLE `t_milestone`
    MODIFY COLUMN `status` VARCHAR(32) NOT NULL DEFAULT 'planned' COMMENT 'planned/achieved/delayed（V1 not_started 词汇废弃；逾期仅展示层高亮，不自动改状态）',
    MODIFY COLUMN `program_id` VARCHAR(64) DEFAULT NULL COMMENT 'legacy(V1): 旧项目群弱关联(与 V4 t_program.id BIGINT 错位)，保留只读不写',
    MODIFY COLUMN `milestone_id` VARCHAR(32) DEFAULT NULL COMMENT 'legacy(V1): 旧编号，保留只读不写，新功能用 milestone_code';
  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_milestone' AND INDEX_NAME='uk_ms_tenant_code') THEN
    SET @ddl = 'ALTER TABLE `t_milestone` ADD UNIQUE KEY `uk_ms_tenant_code` (`tenant_id`, `milestone_code`)';
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_milestone' AND INDEX_NAME='idx_ms_tenant_owner') THEN
    SET @ddl = 'ALTER TABLE `t_milestone` ADD KEY `idx_ms_tenant_owner` (`tenant_id`, `owner_type`, `owner_id`)';
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;
CALL `alter_ms_v5`();
DROP PROCEDURE IF EXISTS `alter_ms_v5`;
-- 索引说明：
--   uk_ms_tenant_code(tenant_id, milestone_code) —— 用户可见编号租户内唯一（PRD §4.2.1）。
--   idx_ms_tenant_owner(tenant_id, owner_type, owner_id) —— 主查询"某项目群/某项目的里程碑列表"
--     （详情页时间线/聚合时间线：WHERE tenant_id=? AND owner_type=? AND owner_id=? ORDER BY target_date），
--     拦截器改写后三等值完全命中；"存在 planned 里程碑"的达成提示查询同走此索引后按 status 过滤
--     （单 owner 行数小，无需单独 status 索引）。

-- ============ F1: DORA 埋点 t_derivation.gate_result（PRD §4.1.2④ / Q1 裁决：加列） ============
-- 语义：本条派生记录对应的门禁判定结果 PASS/FAIL/FAIL_WARN；NULL=埋点上线前的历史记录
--  （RT 对其走"同 case+同 gate 角色取首条 started_at 至末条 finished_at"的近似口径，看板标"≈"）。
-- 写入点由 SE 定（编排层门禁判定解析处随派生记录写入）；写入失败仅记 ERROR 不影响编排主流程（PRD §6.3）。
-- 宽度纠偏：VARCHAR(16)（任务书 VARCHAR(8) 存不下 9 字符的 FAIL_WARN）。
-- 可空列追加在表尾，MySQL 8.0 走 ALGORITHM=INSTANT 不重建表——t_derivation 为高写量表，秒级完成。
-- 幂等：列缺失才 ADD（部署机半污染库重跑自救，1060 不再致命）。
DROP PROCEDURE IF EXISTS `add_col_deriv_gate`;
DELIMITER $$
CREATE PROCEDURE `add_col_deriv_gate`()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_derivation' AND COLUMN_NAME='gate_result') THEN
    ALTER TABLE `t_derivation`
      ADD COLUMN `gate_result` VARCHAR(16) DEFAULT NULL COMMENT '门禁判定结果: PASS/FAIL/FAIL_WARN（RT 埋点，AC-F1.4）；NULL=埋点上线前历史记录（RT 近似口径）';
  END IF;
END$$
DELIMITER ;
CALL `add_col_deriv_gate`();
DROP PROCEDURE IF EXISTS `add_col_deriv_gate`;

-- gate_result 索引评估结论：不加新索引。
--   RT 计算的访问路径总是"先定位 Case 集合（周期内 done/终态 Case）→ 再取其派生记录"，命中入口是
--   case_id(±role)，既有 idx_tenant_case(tenant_id, case_id) / idx_case_role(case_id, role) 已完全覆盖；
--   gate_result 基数仅 3+NULL，单列索引选择性极差；并入 (tenant_id, case_id, gate_result) 也不覆盖
--   finished_at 仍需回表；单 Case 派生行数少（§6.2 量级 5000 行/租户），case 内过滤毫秒级。
--   按需加列不按需加索引——待全租户聚合证明慢查询后再评估（预留《数据库设计说明》§7 风险项）。

-- ============ Seed: L2 治理权限原子 13 条 + 模板角色授权（PRD §4.6 / AC-RBAC） ============
-- 对齐 PRD §4.6 权限表（view/create/edit 三段制）：任务书摘要的 9 原子（view/edit）按 PRD 契约
-- 落为 13——缺 create 原子则 create 接口的 @RequirePermission 全员 403，AC-RBAC.1
-- （project_manager create 200）直接挂掉。dora:view 为纯只读无 create/edit。
-- 角色矩阵（PRD §4.6 角色矩阵为权威，US-5 高管只读五功能区）：
--   platform_admin 13 项全量（V1/V4 惯例：除 role:create/edit 外全量）
--   tenant_admin   13 项全量（DORA/里程碑/依赖管理 + ADR/雷达编辑，PRD Q5：EA 缺位由其兼任）
--   project_manager 9 项（里程碑×3 + 依赖×3 + dora:view + adr:view/radar:view 只读）
--   engineer        2 项（adr:view/radar:view——PRD 明示打破"engineer 零新增"仅此 view 级，AC-RBAC.2）
--   executive       5 项（五功能区 view 只读，US-5；无任何 create/edit）
-- 权限/授权均为系统级（tenant_id=0 共享，t_permission/t_role_permission 在 IGNORE_TABLES），
-- 对存量与新建租户一体生效，无需按租户 provisioning。
-- 幂等：INSERT IGNORE（uk_permission_code / uk_role_perm 兜底），重放安全。
INSERT IGNORE INTO `t_permission` (`id`, `tenant_id`, `permission_code`, `permission_name`, `module`, `resource_type`, `action`, `description`) VALUES
(1046, 0, 'milestone:view',   '里程碑查看',   'milestone', 'milestone', 'view',   NULL),
(1047, 0, 'milestone:create', '里程碑创建',   'milestone', 'milestone', 'create', NULL),
(1048, 0, 'milestone:edit',   '里程碑编辑',   'milestone', 'milestone', 'edit',   '含状态流转与达成确认（人工，系统不自动置达成）'),
(1049, 0, 'dependency:view',   '项目依赖查看', 'dependency', 'dependency', 'view',   NULL),
(1050, 0, 'dependency:create', '项目依赖创建', 'dependency', 'dependency', 'create', NULL),
(1051, 0, 'dependency:edit',   '项目依赖编辑', 'dependency', 'dependency', 'edit',   '含删除（限依赖双方项目经理或 tenant_admin）'),
(1052, 0, 'dora:view',        'DORA看板查看', 'dora',      'dora',      'view',   '四指标只读聚合视图（口径以 PRD §4.1.2 为唯一权威）'),
(1053, 0, 'adr:view',         'ADR查看',      'adr',       'adr',       'view',   NULL),
(1054, 0, 'adr:create',       'ADR创建',      'adr',       'adr',       'create', NULL),
(1055, 0, 'adr:edit',         'ADR编辑',      'adr',       'adr',       'edit',   '含状态机流转（proposed→accepted→deprecated/superseded）'),
(1056, 0, 'radar:view',       '技术雷达查看', 'radar',     'radar',     'view',   NULL),
(1057, 0, 'radar:create',     '技术雷达创建', 'radar',     'radar',     'create', NULL),
(1058, 0, 'radar:edit',       '技术雷达编辑', 'radar',     'radar',     'edit',   '含环移动（trial→adopt 等必须写审计留痕）');

-- 授权行（id 2092 起：V4 r3 用毕 2091；引用 role_id 1~5 模板角色与 permission_id 1046~1058）

-- platform_admin (role_id=1)：13 项全量
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2092,1,1046),(2093,1,1047),(2094,1,1048),                    -- milestone ×3
(2095,1,1049),(2096,1,1050),(2097,1,1051),                    -- dependency ×3
(2098,1,1052),                                                -- dora:view
(2099,1,1053),(2100,1,1054),(2101,1,1055),                    -- adr ×3
(2102,1,1056),(2103,1,1057),(2104,1,1058);                    -- radar ×3

-- tenant_admin (role_id=2)：13 项全量（含 ADR/雷达编辑，PRD Q5 EA 缺位兼任）
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2105,2,1046),(2106,2,1047),(2107,2,1048),                    -- milestone ×3
(2108,2,1049),(2109,2,1050),(2110,2,1051),                    -- dependency ×3
(2111,2,1052),                                                -- dora:view
(2112,2,1053),(2113,2,1054),(2114,2,1055),                    -- adr ×3
(2115,2,1056),(2116,2,1057),(2117,2,1058);                    -- radar ×3

-- project_manager (role_id=3)：9 项（里程碑/依赖管理 ×3+×3 + dora:view + adr/radar 只读，AC-RBAC.1）
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2118,3,1046),(2119,3,1047),(2120,3,1048),                    -- milestone ×3
(2121,3,1049),(2122,3,1050),(2123,3,1051),                    -- dependency ×3
(2124,3,1052),                                                -- dora:view
(2125,3,1053),                                                -- adr:view 只读
(2126,3,1056);                                                -- radar:view 只读

-- engineer (role_id=4)：2 项（adr:view/radar:view——PRD 裁决的知识资产传播价值，AC-RBAC.2）
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2127,4,1053),                                                -- adr:view
(2128,4,1056);                                                -- radar:view

-- executive (role_id=5)：5 项（五功能区只读，US-5；无 create/edit）
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2129,5,1046),                                                -- milestone:view
(2130,5,1049),                                                -- dependency:view
(2131,5,1052),                                                -- dora:view
(2132,5,1053),                                                -- adr:view
(2133,5,1056);                                                -- radar:view


-- SE D-1: DORA audit-table index (IGNORE_TABLES table, index must carry tenant_id)
-- 幂等：索引已存在报 1061，动态判断缺失才建（部署机半污染库重跑自救）。
DROP PROCEDURE IF EXISTS `add_idx_audit_action_time`;
DELIMITER $$
CREATE PROCEDURE `add_idx_audit_action_time`()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_governance_log' AND INDEX_NAME='idx_tenant_action_time') THEN
    SET @ddl = 'ALTER TABLE t_governance_log ADD INDEX idx_tenant_action_time (tenant_id, action, create_time)';
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;
CALL `add_idx_audit_action_time`();
DROP PROCEDURE IF EXISTS `add_idx_audit_action_time`;
