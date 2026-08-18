-- V3: 租户自配 LLM Key（#24：租户自己的 token 费自己付）
-- llm_provider: glm/deepseek（默认 glm）
-- llm_api_key: 租户自己的 API Key（派生时优先用租户 Key，平台不垫付）
-- 注意：MySQL 8.0 的 ALTER TABLE ADD COLUMN 不支持 IF NOT EXISTS（MariaDB 才支持），
--       Flyway schema history 保证本脚本只执行一次，无需幂等语法。
ALTER TABLE `t_tenant` ADD COLUMN `llm_provider` VARCHAR(32) DEFAULT 'glm' COMMENT '租户LLM厂商';
ALTER TABLE `t_tenant` ADD COLUMN `llm_api_key` VARCHAR(256) DEFAULT NULL COMMENT '租户自配LLM API Key';
