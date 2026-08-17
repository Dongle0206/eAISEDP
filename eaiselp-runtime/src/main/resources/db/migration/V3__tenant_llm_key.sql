-- V3: 租户自配 LLM Key（#24：租户自己的 token 费自己付）
-- llm_provider: glm/deepseek（默认 glm）
-- llm_api_key: 租户自己的 API Key（派生时优先用租户 Key，平台不垫付）
ALTER TABLE `t_tenant` ADD COLUMN IF NOT EXISTS `llm_provider` VARCHAR(32) DEFAULT 'glm' COMMENT '租户 LLM 厂商';
ALTER TABLE `t_tenant` ADD COLUMN IF NOT EXISTS `llm_api_key` VARCHAR(256) DEFAULT NULL COMMENT '租户自配 LLM API Key';
