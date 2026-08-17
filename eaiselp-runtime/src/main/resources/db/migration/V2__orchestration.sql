-- V2: 编排任务持久化表（#15 编排状态重启不丢）
-- 幂等：IF NOT EXISTS 防重复执行
CREATE TABLE IF NOT EXISTS `t_orchestration` (
  `id` BIGINT NOT NULL COMMENT '编排任务ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `case_id` VARCHAR(128) DEFAULT NULL,
  `requirement` TEXT,
  `tier` VARCHAR(16) DEFAULT 'fast',
  `status` VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT 'pending/running/awaiting_approval/done/failed',
  `current_role` VARCHAR(64) DEFAULT NULL,
  `pending_checkpoint_id` BIGINT DEFAULT NULL,
  `approval_message` VARCHAR(1000) DEFAULT NULL,
  `steps_json` JSON DEFAULT NULL COMMENT '步骤列表快照（含状态/指令/错误）',
  `validation_json` JSON DEFAULT NULL COMMENT '产出验证结果摘要',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `finished_at` DATETIME DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_orch_case` (`case_id`),
  KEY `idx_orch_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='编排任务表（重启恢复）';
