-- H2 兼容版（MySQL 模式），仅测试用，只建本 case 涉及的 2 张表
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
