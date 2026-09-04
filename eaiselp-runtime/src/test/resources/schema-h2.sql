-- H2 兼容版（MySQL 模式），仅测试用，按 case 逐步追加涉及的表（非完整生产 schema）
-- 不可复用生产 schema.sql（含 ENGINE=InnoDB / JSON / ON UPDATE CURRENT_TIMESTAMP，H2 不全认）

CREATE TABLE IF NOT EXISTS t_derivation (
  id BIGINT NOT NULL PRIMARY KEY,
  tenant_id BIGINT DEFAULT 0,
  case_id VARCHAR(128),
  role VARCHAR(64),
  stage VARCHAR(32),
  model VARCHAR(64),
  model_tier VARCHAR(16),
  input_tokens INT DEFAULT 0,
  output_tokens INT DEFAULT 0,
  cost DECIMAL(10,4) DEFAULT 0,
  status VARCHAR(32),
  error_msg CLOB,
  produced_artifacts CLOB,   -- H2 无 JSON，用 CLOB 代替
  experience CLOB,
  retry_count INT DEFAULT 0,
  started_at TIMESTAMP,
  finished_at TIMESTAMP,
  duration_ms BIGINT,
  -- V5 F1 DORA 埋点列（case-20260818 T1）：PASS/FAIL/FAIL_WARN；NULL=埋点上线前历史
  gate_result VARCHAR(16),
  create_time TIMESTAMP,
  update_time TIMESTAMP,
  create_by VARCHAR(64),
  update_by VARCHAR(64),
  is_deleted INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS t_artifact (
  id BIGINT NOT NULL PRIMARY KEY,
  tenant_id BIGINT DEFAULT 0,
  case_id VARCHAR(128),
  role VARCHAR(64),
  stage VARCHAR(32),
  type VARCHAR(32),
  title VARCHAR(200),
  content CLOB,
  doc_key VARCHAR(200),
  frontmatter CLOB,
  derivation_id BIGINT,
  contract_key VARCHAR(200),
  create_time TIMESTAMP,
  update_time TIMESTAMP,
  create_by VARCHAR(64),
  update_by VARCHAR(64),
  is_deleted INT DEFAULT 0
);

-- M2 SP-6 新增：模型路由表（系统级，无 tenant_id）。测试上下文会装配 ModelRoutingServiceImpl，
-- 建表保证若路由被调用不会因"表不存在"失败。预置 sonnet 档位（DerivationEngine 默认 tier）。
-- 幂等：MERGE INTO（H2 upsert）避免 spring.sql.init always 模式重跑时主键冲突。
CREATE TABLE IF NOT EXISTS t_model_routing (
  id BIGINT NOT NULL PRIMARY KEY,
  tier VARCHAR(32),
  provider VARCHAR(32),
  model VARCHAR(64),
  priority INT DEFAULT 0,
  enabled INT DEFAULT 1,
  api_key_env VARCHAR(64),
  base_url VARCHAR(256),
  role_hint VARCHAR(256),
  create_time TIMESTAMP,
  update_time TIMESTAMP,
  create_by VARCHAR(64),
  update_by VARCHAR(64),
  is_deleted INT DEFAULT 0
);
MERGE INTO t_model_routing (id, tier, provider, model, priority, enabled) KEY(id) VALUES
  (1007, 'opus',   'glm', 'glm-4-plus',  10, 1),
  (1008, 'sonnet', 'glm', 'glm-4',       10, 1),
  (1009, 'haiku',  'glm', 'glm-4-flash', 10, 1);

-- ============ V6（case-20260820-L2治理收口）：L2 治理收口四表 + 权限 seed ============
-- 生产结构以 V6__l2_governance_close.sql 为准，此处为 H2 简化版：
--   DATETIME→TIMESTAMP、JSON/TEXT→CLOB、无 ENGINE/无 ON UPDATE/无行级 COMMENT。
--   F3 试用到期拦截零 DDL（复用 t_tenant 既有列），H2 侧无对应动作。
-- 幂等：建表 IF NOT EXISTS；seed 用 MERGE INTO KEY(id)（同上 t_model_routing 先例，
--   避免 spring.sql.init always 模式重跑时主键冲突）。
CREATE TABLE IF NOT EXISTS t_standard (
  id BIGINT NOT NULL PRIMARY KEY,
  tenant_id BIGINT DEFAULT 0,
  standard_code VARCHAR(64),
  title VARCHAR(200),
  version VARCHAR(32),
  status VARCHAR(16),
  content CLOB,                 -- 生产 MEDIUMTEXT（≤20000 汉字超 TEXT 上限纠偏），H2 用 CLOB
  related_principle_codes CLOB, -- H2 无 JSON，用 CLOB 代替
  related_gate_names CLOB,
  deprecate_reason VARCHAR(500),
  create_time TIMESTAMP,
  update_time TIMESTAMP,
  create_by VARCHAR(64),
  update_by VARCHAR(64),
  is_deleted INT DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_std_tenant_code_version ON t_standard (tenant_id, standard_code, version);
CREATE INDEX IF NOT EXISTS idx_std_tenant_status ON t_standard (tenant_id, status);

CREATE TABLE IF NOT EXISTS t_template (
  id BIGINT NOT NULL PRIMARY KEY,
  tenant_id BIGINT DEFAULT 0,
  template_type VARCHAR(64),
  template_name VARCHAR(200),
  version VARCHAR(32),
  content CLOB,
  enabled INT DEFAULT 1,
  create_time TIMESTAMP,
  update_time TIMESTAMP,
  create_by VARCHAR(64),
  update_by VARCHAR(64),
  is_deleted INT DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_tpl_tenant_type_name ON t_template (tenant_id, template_type, template_name);

CREATE TABLE IF NOT EXISTS t_data_asset (
  id BIGINT NOT NULL PRIMARY KEY,
  tenant_id BIGINT DEFAULT 0,
  asset_name VARCHAR(128),
  system_name VARCHAR(128),
  asset_type VARCHAR(16),
  owner VARCHAR(64),
  sensitivity VARCHAR(16),
  description CLOB,
  tags CLOB,
  create_time TIMESTAMP,
  update_time TIMESTAMP,
  create_by VARCHAR(64),
  update_by VARCHAR(64),
  is_deleted INT DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_tenant_system_name ON t_data_asset (tenant_id, system_name, asset_name);

CREATE TABLE IF NOT EXISTS t_data_quality_rule (
  id BIGINT NOT NULL PRIMARY KEY,
  tenant_id BIGINT DEFAULT 0,
  rule_name VARCHAR(200),
  asset_id BIGINT,
  check_type VARCHAR(16),
  threshold DECIMAL(5,2),
  last_result VARCHAR(8),
  last_actual_value DECIMAL(10,4),
  last_check_time TIMESTAMP,
  last_check_remark VARCHAR(500),
  create_time TIMESTAMP,
  update_time TIMESTAMP,
  create_by VARCHAR(64),
  update_by VARCHAR(64),
  is_deleted INT DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_dqr_tenant_name ON t_data_quality_rule (tenant_id, rule_name);
CREATE INDEX IF NOT EXISTS idx_dqr_tenant_asset ON t_data_quality_rule (tenant_id, asset_id);

-- 权限表（H2 简化版：仅本 case seed 涉及列，生产结构以 V1 为准；
--   供 AC-RBAC 类集成测试装配，此前 V1~V5 权限 seed 未入 H2，本期起补齐）
CREATE TABLE IF NOT EXISTS t_permission (
  id BIGINT NOT NULL PRIMARY KEY,
  tenant_id BIGINT DEFAULT 0,
  permission_code VARCHAR(64),
  permission_name VARCHAR(128),
  module VARCHAR(32),
  resource_type VARCHAR(32),
  action VARCHAR(32),
  description VARCHAR(500),
  create_time TIMESTAMP,
  update_time TIMESTAMP,
  create_by VARCHAR(64),
  update_by VARCHAR(64),
  is_deleted INT DEFAULT 0
);
CREATE TABLE IF NOT EXISTS t_role_permission (
  id BIGINT NOT NULL PRIMARY KEY,
  role_id BIGINT,
  permission_id BIGINT,
  create_time TIMESTAMP
);

-- V6 权限 seed（与 V6__l2_governance_close.sql 同值：原子 1059~1070 + 授权 2134~2169）
MERGE INTO t_permission (id, tenant_id, permission_code, permission_name, module, resource_type, action) KEY(id) VALUES
  (1059, 0, 'standard:view',   '工程标准查看', 'standard', 'standard', 'view'),
  (1060, 0, 'standard:create', '工程标准创建', 'standard', 'standard', 'create'),
  (1061, 0, 'standard:edit',   '工程标准编辑', 'standard', 'standard', 'edit'),
  (1062, 0, 'template:view',   '模板库查看',   'template', 'template', 'view'),
  (1063, 0, 'template:create', '模板库创建',   'template', 'template', 'create'),
  (1064, 0, 'template:edit',   '模板库编辑',   'template', 'template', 'edit'),
  (1065, 0, 'asset:view',      '数据资产查看', 'asset',    'asset',    'view'),
  (1066, 0, 'asset:create',    '数据资产创建', 'asset',    'asset',    'create'),
  (1067, 0, 'asset:edit',      '数据资产编辑', 'asset',    'asset',    'edit'),
  (1068, 0, 'dqrule:view',     '质量规则查看', 'dqrule',   'dqrule',   'view'),
  (1069, 0, 'dqrule:create',   '质量规则创建', 'dqrule',   'dqrule',   'create'),
  (1070, 0, 'dqrule:edit',     '质量规则编辑', 'dqrule',   'dqrule',   'edit');

MERGE INTO t_role_permission (id, role_id, permission_id) KEY(id) VALUES
  -- platform_admin (role 1): 12 项全量
  (2134,1,1059),(2135,1,1060),(2136,1,1061),(2137,1,1062),(2138,1,1063),(2139,1,1064),
  (2140,1,1065),(2141,1,1066),(2142,1,1067),(2143,1,1068),(2144,1,1069),(2145,1,1070),
  -- tenant_admin (role 2): 12 项全量
  (2146,2,1059),(2147,2,1060),(2148,2,1061),(2149,2,1062),(2150,2,1063),(2151,2,1064),
  (2152,2,1065),(2153,2,1066),(2154,2,1067),(2155,2,1068),(2156,2,1069),(2157,2,1070),
  -- project_manager (role 3): 四域只读
  (2158,3,1059),(2159,3,1062),(2160,3,1065),(2161,3,1068),
  -- engineer (role 4): 四域只读
  (2162,4,1059),(2163,4,1062),(2164,4,1065),(2165,4,1068),
  -- executive (role 5): 四域只读
  (2166,5,1059),(2167,5,1062),(2168,5,1065),(2169,5,1068);
