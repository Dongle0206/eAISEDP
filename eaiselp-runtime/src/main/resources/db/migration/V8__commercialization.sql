-- V8: 商用化闭环——订阅套餐 + 计量出账 + SLA 轻量（case-20260823-商用化，PRJ-006 收口）
-- 覆盖 PRD §4 功能点：
--   F1.1 套餐定义管理 → 新表 t_plan（平台级商品资产：code/名称/月价/超量单价/功能开关 JSON/
--                        SLA 档/上下架 + 审计；seed 官方三档 starter/pro/enterprise）
--   F1.2 套餐应用     → t_tenant 加 plan_code 列（裁决 Q1：加列绑定而非独立关系表；变更历史不建
--                        独立表——tenant_edition_change 审计 + 账单内套餐快照已构成完整追溯链）
--   F2.1 月度账单生成 → 新表 t_invoice（租户级；uk(tenant_id, period) 防重出账；计费口径 B1~B6）
--   F2.2 账单流转     → t_invoice.status draft→issued→paid + issued_time/paid_time（AC-F2.8）
--   F2.3 费用中心     → 零 DDL：t_invoice 租户侧读 API + t_quota 既有水位列
--   F3.1 SLA 承诺     → 零 DDL：t_plan.sla_level（承诺文案前端集中一处，纯展示不参与计算）
--   F3.2 事故登记     → 新表 t_incident（租户级轻量台账，无状态机）
--
-- 计费口径（PRD §4.4.1 B1~B6 唯一口径 + 编排者裁决 Q4/Q6 变更）：
--   B1 月末快照 / B2 试用期不出账 / B3 转正赠送 / B5 千 token ceil 超量 / B6 仅最终应缴 HALF_UP 2 位。
--   Q6 裁决覆盖 B4 整月计费：月中新建即付费租户首月按剩余天数折算（月价 ÷ 当月天数 × 剩余天数
--   含当天，HALF_UP 2 位）——由 t_invoice.prorotation_amount 列承载（非折算场景 0.00=整月计费）；
--   trial 转正赠送当月不出账口径不变（B2/B3）。total_amount = (proration_amount>0 ?
--   proration_amount : plan_price) + overage_amount（BillingCalculator 纯静态锁口径，裁决附带 #4）。
--   超量单价取 DECIMAL(12,6) 而非提示稿 (14,2)：B6/AC-F2.5 构造例 0.335 元/千 token 需 3 位以上
--   小数，(14,2) 会截断为 0.34 使断言 101.00 失败——按 PRD §4.1.1 契约执行（见设计文档 §7 R2）。
--
-- 租户隔离（P11）：
--   t_invoice / t_incident 为租户级业务表，不进 EaiselpTenantHandler.IGNORE_TABLES（拦截器自动
--   过滤；platform_admin 跨租户读写走既有平台管理通道，U1/U2/TenantController 先例）。
--   t_plan 为平台级全局表（套餐是平台商品资产，同 t_permission/t_role_permission 先例）：
--   tenant_id 恒 0 仅作系统级惯例占位列，**须由 Dev 将 "t_plan" 加入 EaiselpTenantHandler
--   IGNORE_TABLES 常量清单**（迁移只落 DDL，Java 常量属代码改动，Dev 任务传导，见设计文档 §7 R6）。
--   三域均不限层（PRD §1.4 场景 C），新 API 不注册 LayerGuardInterceptor。
--
-- 幂等说明（#20 部署失败教训，V4 r4 / V5 r2 / V6 / V7 惯例）：3 张新表 CREATE TABLE IF NOT EXISTS
--   + seed INSERT IGNORE（唯一键兜底）；本版含一处既有表 ALTER（t_tenant 加 plan_code）——照抄
--   V4 r4 的 information_schema 动态判断存储过程模式，列缺失才 ADD（半污染库重放自动收敛，
--   1060 不致命）；一处存量回填 UPDATE 以 WHERE plan_code IS NULL 守卫（重放对已回填行 no-op）。
--   重放 WARN（1050 表已存在 / 1062 重复 seed 被 IGNORE / 1305 DROP 不存在过程）均为预期无害告警。
--
-- ID 区间顺延（V7 用毕 permission id 1080 / role_permission id 2202）：
--   权限原子 id 1081~1089（9 条）；角色授权行 id 2203~2220（18 行）。
--   V9 起从 1090 / 2221 续排。

-- ============ F1.1: 订阅套餐（平台级商品资产，PRD §4.1） ============
-- code 为领域字典枚举（starter/pro/enterprise/custom），应用层校验非法 400，DB 不加约束（P6）；
--   标准三档同 code 在架至多一份为应用层校验（重复创建 400，PRD §4.1.1），custom 可多份（逐租户
--   定制，以名称区分，AC-F1.2）——故 code 不设 uk（DB 强约束会阻断合法第二份 custom），全局唯一
--   落在 name 上（PRD 契约：uk(name)，V4/V6/V7 uk(tenant,name) 惯例的平台级变体）。
-- sla_level 必填枚举 bronze/silver/gold（应用层校验空/枚举外 400，AC-F3.1）；承诺文案纯展示，
--   前端集中一处定义（P6），不参与任何计算/赔付（§7-4/7-5）。
-- 上下架用 enabled 位（1=在架可被应用 / 0=下架不可应用，存量绑定租户不受影响——AC-F1.4），
--   对齐 V4 t_architecture_principle / t_quality_gate_rule 的 enabled 先例；与逻辑删 is_deleted
--   正交：仅逻辑删不物理删——账单套餐快照自洽不依赖套餐行存续（§4.1.1）。
-- 金额上限校验提示（L3 收口 S1 教训镜像，裁决附带 #2）：DECIMAL(14,2) 上限 99,999,999,999.99
--   （千亿级），Service 保存前校验金额上限防脏数据；DECIMAL(12,6) 上限 999,999.999999。
CREATE TABLE IF NOT EXISTS `t_plan` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '平台级全局表惯例占位（恒 0，同 t_permission；表须进 EaiselpTenantHandler.IGNORE_TABLES——见头注释）',
  `code` VARCHAR(32) NOT NULL COMMENT '套餐编码: starter/pro/enterprise/custom（领域字典枚举，应用层校验非法 400；标准档同 code 在架唯一为应用层校验、custom 可多份——AC-F1.2，故不设 uk）',
  `name` VARCHAR(200) NOT NULL COMMENT '套餐名（全局唯一，uk_plan_name——PRD §4.1.1 契约；custom 多份以名称区分）',
  `monthly_price` DECIMAL(14,2) NOT NULL COMMENT '月价（元，B1 月末快照取值；≥0 应用层校验负值 400，=0 合法=免费定制档 AC-F1.3；上限 99,999,999,999.99，Service 校验上限——L3 S1 教训镜像）',
  `token_overage_price` DECIMAL(12,6) NOT NULL COMMENT 'token 超量单价（元/千 token，B5 超量费因子；≥0 应用层校验；6 位小数支撑低单价——B6 构造例 0.335 需 3 位以上小数，(14,2) 截断破坏 AC-F2.5，按 PRD 契约取 (12,6)；上限 999,999.999999，Service 校验上限）',
  `features` JSON DEFAULT NULL COMMENT '功能开关 JSON {max_users,max_cases,token_limit,case_limit,derivation_limit,storage_limit_mb,layer_overrides:{strategy_enabled,program_project_enabled},model_tiers:[...]}（服务端结构校验必需键与值域，未知键忽略——向前兼容；model_tiers 值域=t_model_routing 档位名 opus/sonnet/haiku/reasoning/structured/mechanical/code，ES-003 §2.5）',
  `sla_level` VARCHAR(16) NOT NULL COMMENT 'SLA 档位: bronze/silver/gold（必填，应用层校验枚举外 400——AC-F3.1；三档承诺文案前端集中一处，纯展示）',
  `edition_override` VARCHAR(16) DEFAULT NULL COMMENT 'custom 套餐生效档位（code=custom 时必填，D-9；其余套餐 NULL）',
  `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '上下架: 1=在架(默认,可被套餐应用) 0=下架(不可应用；存量绑定租户不受影响，次月账单仍按快照生成——AC-F1.4)',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_plan_name` (`name`),
  KEY `idx_plan_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订阅套餐表（平台级商品资产，F1.1，seed 官方三档）';
-- 索引说明：
--   uk_plan_name(name) —— PRD 契约"套餐名全局唯一"（§4.1.1，uk(tenant,name) 的平台级变体）：
--     同名再建被拒 + seed 幂等兜底（INSERT IGNORE 重放收敛）。不含 code：code 全局唯一会阻断
--     合法第二份 custom（AC-F1.2 明示 custom 可多份、名称不同即合法）；标准三档同 code 在架
--     唯一属应用层校验（PRD 契约原文），DB 不越权代管。
--   idx_plan_code(code) —— code 前缀查询路径（任务契约）：t_tenant.plan_code 引用解析与套餐
--     应用下拉的主查询 WHERE code=? AND enabled=1 AND is_deleted=0 等值命中；查询主键路径
--     以 code 为检索键（id 为雪花无业务语义）。
--   不加 (code, enabled) 复合：套餐目录量级 ≤ 几十行（商品目录非流水表），code 等值已收敛到
--     个位数行，enabled 残扫零成本；按 V6/V7"按需加列不按需加索引"先例保持最克制索引面。

-- ============ Seed: 官方三档套餐（PRD §4.1.2，seed 值仅占位满足"同名默认档存在"，
--            定价模板由运营在页面上改） ============
-- id 用保留小区间 101~103：避开 V1 权限 seed（1001~1031）与模型路由 seed（1001~1009）所在的
--   1000+ 段，亦避开应用层雪花 ID（19 位永不碰撞）；INSERT IGNORE + uk_plan_name 幂等。
-- pro 档 token_limit=5,000,000 对齐 AC-F2.2 构造例口径（月价 999/配额 5M/单价 8 元/千 token），
--   使 QA 数值断言可直接绑定默认 pro 档构造。
-- model_tiers 取路由表真实档位名（opus/sonnet/haiku；PRD 示例 high/mid/low 为示意写法，
--   值域以 ES-003 §2.5 为准——见设计文档 §7 R5）。
INSERT IGNORE INTO `t_plan` (`id`, `tenant_id`, `code`, `name`, `monthly_price`, `token_overage_price`, `features`, `sla_level`, `enabled`, `create_by`) VALUES
(101, 0, 'starter',    '入门版', 299.00, 12.000000, '{"max_users":10,"max_cases":20,"token_limit":1000000,"case_limit":20,"derivation_limit":200,"storage_limit_mb":10240,"layer_overrides":{"strategy_enabled":1,"program_project_enabled":1},"model_tiers":["haiku"]}', 'bronze', 1, 'system-seed'),
(102, 0, 'pro',        '专业版', 999.00,  8.000000, '{"max_users":50,"max_cases":100,"token_limit":5000000,"case_limit":100,"derivation_limit":1000,"storage_limit_mb":51200,"layer_overrides":{"strategy_enabled":1,"program_project_enabled":1},"model_tiers":["sonnet","haiku"]}', 'silver', 1, 'system-seed'),
(103, 0, 'enterprise', '企业版', 4999.00, 6.000000, '{"max_users":200,"max_cases":500,"token_limit":20000000,"case_limit":500,"derivation_limit":5000,"storage_limit_mb":204800,"layer_overrides":{"strategy_enabled":1,"program_project_enabled":1},"model_tiers":["opus","sonnet","haiku"]}', 'gold', 1, 'system-seed');

-- ============ F2.1: 月度账单（租户级，计费口径 B1~B6 + 裁决 Q6，PRD §4.4） ============
-- uk(tenant_id, period) 是出账幂等的核心（PRD §4.4.1 账期与幂等 / AC-F2.7）：定时任务（每月 1 日
--   02:00，裁决 Q2）与 platform_admin 手动补生成并发触发时，唯一键保证同租户同账期无第二行；
--   重跑口径——已存在 draft 重算覆盖、issued/paid 不覆盖仅 WARN（应用层，§7-1 作废重开范围外）。
-- 套餐快照自洽（B1）：plan_code/plan_price 为列（列表展示与汇总直接用列），名称/超量单价/SLA 档
--   等五字段快照入 detail JSON——套餐后续改价/下架/逻辑删均不影响已生成账单（AC-F1.4）。
-- 金额三列 + 月价快照全 DECIMAL(14,2)（裁决附带 #2）：上限 99,999,999,999.99（千亿级），Service
--   保存前校验金额上限（L3 收口 S1 教训镜像）；应缴服务端 BigDecimal 生成（禁 float/double），
--   客户端提交金额字段一律忽略（V7 防伪造先例，§6-2）。
-- draft 对租户不可见（AC-F2.9，应用层过滤）；平台内部成本 t_derivation.cost 禁入本表任何字段
--   与 API（信息隔离 §6-3）。
CREATE TABLE IF NOT EXISTS `t_invoice` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `period` CHAR(7) NOT NULL COMMENT '账期键 yyyy-MM（自然月 [当月1日00:00, 次月1日00:00) Asia/Shanghai；恒 7 字符定长 CHAR(7)，值域与 t_quota.period 同构同源）',
  `plan_code` VARCHAR(32) NOT NULL COMMENT '出账时生效套餐编码快照（B1 月末快照=账期结束时刻租户生效套餐；月中升降级本期按期末档计价）',
  `plan_price` DECIMAL(14,2) NOT NULL COMMENT '套餐月价快照（元，B1；上限 99,999,999,999.99，Service 校验上限——L3 S1 教训镜像）',
  `usage_tokens` BIGINT NOT NULL DEFAULT 0 COMMENT '本期成功派生 token 合计 Σ(input_tokens+output_tokens)（成功终态计、失败/运行中不计；账期归属 started_at 缺失 fallback create_time——B5；用量源 t_derivation 逐笔聚合，t_quota.token_used 仅实时水位不作账单口径）',
  `overage_tokens` BIGINT NOT NULL DEFAULT 0 COMMENT '超额 token = max(0, usage_tokens - 本期 token 配额基准)（B5，不足配额时 0）',
  `overage_amount` DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '超量费 = ceil(overage_tokens/1000) × 套餐超量单价快照（计费单位千 token 向上取整，不足 1 千按 1 千仅对超额部分——B5；边界 1000 恰不进位/1001 进一位 AC-F2.3；构造例 AC-F2.2 = 8,016.00；上限 99,999,999,999.99，Service 校验上限——L3 S1 教训镜像）',
  `proration_amount` DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '首月按剩余天数折算的实收月费（裁决 Q6：月价 ÷ 当月天数 × 剩余天数含当天，HALF_UP 2 位——覆盖 PRD B4 整月口径；仅"账期内首月新建即付费"租户非 0，其余 0.00=整月计费；trial 转正赠送当月不出账 B2/B3 不变；上限 99,999,999,999.99，Service 校验上限——L3 S1 教训镜像）',
  `total_amount` DECIMAL(14,2) NOT NULL COMMENT '应缴 = (proration_amount>0 ? proration_amount : plan_price) + overage_amount，仅最终应缴 HALF_UP 2 位（B6：中间计算 BigDecimal 全精度；构造例 100.995 → 101.00 AC-F2.5）；服务端生成，客户端提交金额一律忽略；上限 99,999,999,999.99，Service 校验上限——L3 S1 教训镜像',
  `detail` JSON DEFAULT NULL COMMENT '明细与套餐快照 JSON：套餐五字段(code/name/monthly_price/token_overage_price/sla_level) + tokenUsed/tokenLimit/tokenOverageTokens/ktokenBilled/derivationCount/caseCount/storageUsedMb + Q6 折算参数(当月天数/剩余天数)；平台内部成本 t_derivation.cost 禁入（信息隔离 §6-3）',
  `status` VARCHAR(16) NOT NULL DEFAULT 'draft' COMMENT 'draft/issued/paid（状态机单向顺次 draft→issued→paid，人工标记；跳变/回退/paid 后再流转一律 400，DB 不加约束——AC-F2.8；draft 对租户不可见 AC-F2.9）',
  `issued_time` DATETIME DEFAULT NULL COMMENT '标记 issued 时间（AC-F2.8 流转审计落点，操作者走 t_governance_log）',
  `paid_time` DATETIME DEFAULT NULL COMMENT '标记 paid 时间（同上）',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_invoice_tenant_period` (`tenant_id`, `period`),
  KEY `idx_invoice_period_status` (`period`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='月度账单表（租户级，B1~B6+Q6 折算口径，F2.1/F2.2/F2.3）';
-- 索引说明：
--   uk_invoice_tenant_period(tenant_id, period) —— 双重职责（任务契约 uk 幂等防重）：① 出账幂等
--     兜底（AC-F2.7 无第二行）；② 租户费用中心历史账单主查询 WHERE tenant_id=? ORDER BY
--     period DESC——拦截器改写后 tenant_id 等值打头 + uk 第二列反向扫描完全命中，无需额外排序
--     索引（时间倒序 = period 倒序，period 即账期时间键）。
--   idx_invoice_period_status(period, status) —— 平台账单列表查询路径（任务契约"平台按 period
--     列表"）：platform_admin 跨租户通道不走租户拦截器，故此索引不含 tenant_id 前缀；WHERE
--     period=? [AND status=?] 等值命中（period 打头为平台列表必带筛选；status 基数 3 低区分度，
--     按惯例放联合末尾不单独建索引）；叠加租户筛选时回表（≤200 租户 × 单账期 ≤200 行，§6-1 量级）。
--   不加 (tenant_id, status)：租户费用中心仅展示 issued/paid（draft 隔离 AC-F2.9），status
--     IN('issued','paid') 走 uk 前缀扫描后过滤即可——租户内月账单 ≤ 数十行，残扫零成本。

-- ============ F3.2: 服务事故台账（租户级，轻量无状态机，PRD §4.8） ============
-- 恢复态为展示口径（recovery_time 非空=已恢复），无状态机（PRD §4.8 轻量裁决）；登记/编辑入口
--   tenant_admin + project_manager，只读全员（§4.9 矩阵 incident:view 五角色）。
-- sla_breach 为登记时人工判定的台账标记（1=违约），纯记录不触发任何计算/赔付（§7-4/7-5 范围外）。
CREATE TABLE IF NOT EXISTS `t_incident` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `title` VARCHAR(200) NOT NULL COMMENT '事故标题（必填，应用层校验）',
  `occurred_time` DATETIME NOT NULL COMMENT '发生时间（必填；列表默认倒序键）',
  `recovery_time` DATETIME DEFAULT NULL COMMENT '恢复时间（可空——空=未恢复，非空=已恢复为展示口径，AC-F3.2 补记语义）',
  `impact_desc` VARCHAR(1000) NOT NULL COMMENT '影响描述（必填；宽度同 V7 evidence_note 先例——描述常含多条线索）',
  `root_cause` VARCHAR(1000) DEFAULT NULL COMMENT '根因（可空，恢复后补记——AC-F3.2）',
  `sla_breach` TINYINT NOT NULL DEFAULT 0 COMMENT 'SLA 违约标记: 0=未违约(默认) 1=违约（登记时人工判定，纯台账不触发计算/赔付——§7-4/7-5）',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_incident_tenant_time` (`tenant_id`, `occurred_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='服务事故台账表（租户级，轻量无状态机，F3.2）';
-- 索引说明：
--   idx_incident_tenant_time(tenant_id, occurred_time) —— 列表默认 occurred_time 倒序主查询
--     （AC-F3.3：8-20→8-10→8-01）WHERE tenant_id=? ORDER BY occurred_time DESC——拦截器改写后
--     tenant_id 等值打头 + 第二列反向扫描完全命中。
--   无 uk：事故台账无自然唯一键（同刻同名事故合法登记，V1 t_derivation/t_checkpoint 无 uk 同例），
--     幂等由雪花主键天然保证；恢复状态筛选（AC-F3.3"未恢复"）= recovery_time IS NULL 判空叠加
--     在主查询扫描上——判空不可索引等值优化，租户内台账轻量（P95 < 500ms 水位内），不建。

-- ============ F1.2: t_tenant 加 plan_code 列（裁决 Q1：加列绑定，引用 t_plan.code） ============
-- 可空 = 未绑套餐（trial 租户不绑，沿用既有拦截语义——AC-G2）；绑定/换绑仅经套餐应用（U2 扩展）
--   一次性事务生效（AC-F1.5），逻辑外键应用层校验（全库无物理外键风格，V1/V4 一致）。
-- 幂等（V4 r4 照抄）：ADD COLUMN 列已存在报 1060 致命失败，information_schema 动态判断缺失才
--   执行（存储过程 + CALL + DROP 自清理）；t_tenant 在 IGNORE_TABLES 中按 id 直查（V4 注释先例）。
DROP PROCEDURE IF EXISTS `add_col_tenant_plan`;
DELIMITER $$
CREATE PROCEDURE `add_col_tenant_plan`()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_tenant' AND COLUMN_NAME = 'plan_code'
  ) THEN
    ALTER TABLE `t_tenant`
      ADD COLUMN `plan_code` VARCHAR(32) DEFAULT NULL COMMENT '绑定套餐编码（引用 t_plan.code，逻辑外键应用层校验；NULL=未绑套餐——trial 不绑；绑定/换绑经套餐应用 U2 扩展一次性事务生效 AC-F1.5）';
  END IF;
END$$
DELIMITER ;
CALL `add_col_tenant_plan`();
DROP PROCEDURE IF EXISTS `add_col_tenant_plan`;

-- ============ 存量回填：非 trial 标准档租户绑定同名默认套餐（PRD §4.1.2 / AC-G2） ============
-- 口径：绑定关系空（plan_code IS NULL）且 edition 命中标准档（starter/pro/enterprise）时绑定
--   同名默认套餐；trial/未知 edition 不绑（沿用既有拦截语义，AC-F1.8 回归零影响）。
-- 幂等：WHERE plan_code IS NULL 守卫——重放对已回填行 no-op；人工换绑后的租户（plan_code 非空）
--   永不被迁移覆盖。置于 t_plan seed 之后执行：回填值依赖同名默认套餐行已存在。
-- t_tenant 在 IGNORE_TABLES 中，UPDATE 不受租户拦截器改写（按 id 直查通道，V4 先例）。
UPDATE `t_tenant`
SET `plan_code` = `edition`
WHERE `plan_code` IS NULL
  AND `edition` IN ('starter', 'pro', 'enterprise')
  AND `is_deleted` = 0;

-- ============ Seed: 商用化权限原子 9 条 + 模板角色授权（PRD §4.9 权威矩阵 / AC-G1） ============
-- 权限 code 前缀锁定 PRD §4.9 契约：plan/bill/cost/incident（提示稿 invoice:view/edit、fee:view
--   为候选名，以 PRD 矩阵 code 为准——bill:view/bill:manage/cost:view，见设计文档 §7 R1）。
--   bill:manage 含状态流转 + 手动补生成端点（裁决 Q2）；cost:view 覆盖费用中心三块（本期用量/
--   历史账单/超量明细）；incident:edit 含补记恢复/根因（AC-F3.2）。
-- 角色矩阵（PRD §4.9 逐格）：
--   platform_admin  9 项全量（套餐/账单 platform_admin 专属——1081~1085 仅 role 1）
--   tenant_admin    4 项 = cost:view（费用中心 US-4）+ incident ×3（US-5）
--   project_manager 3 项 = incident view/create/edit（US-5；无 cost:view——费用是管理层信息，裁决 Q5）
--   engineer        1 项只读（incident:view——知识传播 US-7）
--   executive       1 项只读（incident:view——服务质量总览 US-6；费用中心不可见，裁决 Q5）
-- 权限/授权均为系统级（tenant_id=0 共享，t_permission/t_role_permission 在 IGNORE_TABLES），
--   对存量与新建租户一体生效。幂等：INSERT IGNORE（uk_permission_code / uk_role_perm 兜底）。
INSERT IGNORE INTO `t_permission` (`id`, `tenant_id`, `permission_code`, `permission_name`, `module`, `resource_type`, `action`, `description`) VALUES
(1081, 0, 'plan:view',      '套餐查看', 'plan',     'plan',     'view',    NULL),
(1082, 0, 'plan:create',    '套餐创建', 'plan',     'plan',     'create',  NULL),
(1083, 0, 'plan:edit',      '套餐编辑', 'plan',     'plan',     'edit',    '含上下架（enabled 切换，AC-F1.4）'),
(1084, 0, 'bill:view',      '平台账单查看', 'bill', 'bill',     'view',    '跨租户账单列表（platform_admin 专属通道）'),
(1085, 0, 'bill:manage',    '账单状态流转', 'bill', 'bill',     'manage',  'draft→issued→paid 人工标记 + 手动补生成端点（裁决 Q2，AC-F2.7/F2.8）'),
(1086, 0, 'cost:view',      '费用中心查看', 'cost', 'cost',     'view',    '本期用量/历史账单/超量明细（tenant_admin，US-4；executive 不可见——裁决 Q5）'),
(1087, 0, 'incident:view',  '事故查看', 'incident', 'incident', 'view',    NULL),
(1088, 0, 'incident:create','事故登记', 'incident', 'incident', 'create',  NULL),
(1089, 0, 'incident:edit',  '事故编辑', 'incident', 'incident', 'edit',    '含补记恢复时间/根因（AC-F3.2）');

-- 授权行（id 2203 起：V7 用毕 2202；引用 role_id 1~5 模板角色与 permission_id 1081~1089）

-- platform_admin (role_id=1)：9 项全量（套餐+账单专属 1081~1085 仅此角色）
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2203,1,1081),(2204,1,1082),(2205,1,1083),                    -- plan ×3
(2206,1,1084),(2207,1,1085),                                  -- bill ×2（流转+补生成）
(2208,1,1086),                                                -- cost:view（平台侧费用核查）
(2209,1,1087),(2210,1,1088),(2211,1,1089);                    -- incident ×3

-- tenant_admin (role_id=2)：4 项（费用中心 + 事故全操作，US-4/US-5；无套餐/平台账单权限）
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2212,2,1086),                                                -- cost:view
(2213,2,1087),(2214,2,1088),(2215,2,1089);                    -- incident ×3

-- project_manager (role_id=3)：3 项（事故登记协作，US-5；无 cost:view——裁决 Q5 费用是管理层信息）
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2216,3,1087),(2217,3,1088),(2218,3,1089);                    -- incident view/create/edit

-- engineer (role_id=4)：1 项只读（事故知识传播，US-7）
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2219,4,1087);                                                -- incident:view

-- executive (role_id=5)：1 项只读（事故总览，US-6；费用中心不可见——裁决 Q5）
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2220,5,1087);                                                -- incident:view
