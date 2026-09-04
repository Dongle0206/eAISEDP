-- V7: L3 收口——GRC 风险合规 + 战略投资决策（case-20260821-L3收口）
-- 覆盖 PRD §4 功能点：
--   F1.1 风险登记册 → 新表 t_risk（计算列 risk_value/risk_level 由 Service 保存时重算落库——
--                      裁决 Q1，对齐 t_project.progress 算好落库先例：列表默认 risk_value 降序
--                      排序与看板等级分布直接用列，客户端提交值一律忽略覆盖，AC-F1.5 防伪造）
--   F1.2 合规检查   → 新表 t_compliance_check（手动登记制，结果为覆盖式单值当前态，历史唯一
--                      留痕 = t_governance_log 审计 detail——同 V6 质量规则先例，不建历史表）
--   F1.3 风险看板   → 零 DDL：t_risk 单聚合读 API（GROUP BY probability,impact 热力图 /
--                      risk_level 分布 / 高风险清单），无新表无物化——≤5000 行/租户（PRD §6.5）
--                      实时聚合毫秒级，物化表反而引入刷新一致性负担
--   F2.1 商业案例   → 新表 t_business_case（金额单位=元 DECIMAL(14,2)——裁决 Q2；计算列
--                      payback_years/roi_percent/rice_score 落库且可空，N/A 语义统一用 NULL
--                      表示——裁决 Q1；net_benefit 一并落库：投资组合汇总 Σ 直接用列）
--   F2.2 投资组合   → 零 DDL：t_business_case 单聚合读 API（rice_score 排序 + 投资口径汇总
--                      status IN(approved,executing,done) + 状态分布全量口径，裁决 Q8）
--
-- 幂等说明（对齐 V6 / V5 r2 / V4 r4，#20 部署失败教训强制）：本版对既有表零 ALTER / 零 UPDATE /
--   零存储过程，仅 3 张新表 CREATE TABLE IF NOT EXISTS + seed INSERT IGNORE（唯一键兜底）——
--   天然幂等，不涉及 DELIMITER（无 ADD COLUMN/ADD KEY 类非幂等 DDL，无 1060/1061 风险面），
--   任意污染库重放自动收敛。重放 WARN（1050 表已存在 / 1062 重复 seed 被 IGNORE）均为预期无害告警。
--
-- ID 区间顺延（V6 用毕 permission id 1070 / role_permission id 2169）：
--   权限原子 id 1071~1080（10 条）；角色授权行 id 2170~2202（33 行）。
--   V8 起从 1081 / 2203 续排。
--
-- 租户隔离：三张新表均为租户级业务表，不进 EaiselpTenantHandler.IGNORE_TABLES
--   （租户拦截器自动生效，聚合 API 同样经拦截器过滤，PRD §6-1）；全部索引按"拦截器改写后的
--   真实 SQL"设计（tenant_id 等值打头完全命中，唯一键含 tenant_id 的权衡见各表注释）。
--   三域均不限层（PRD §1.3 场景 C 可用），不注册进 LayerGuardInterceptor。

-- ============ F1.1: 风险登记册（租户级知识资产，不限层，PRD §4.1） ============
-- 计算列落库（裁决 Q1）：risk_value = probability × impact（值域 1~25）、risk_level 四段闭区间
--   [1,6]/[7,12]/[13,19]/[20,25] → low/medium/high/critical（PRD §4.1.2 唯一口径），create 与
--   update 均由 Service 重算覆盖，DB 不用生成列——生成列无法表达"客户端提交值一律忽略"的防伪造
--   语义且破坏纯 IF NOT EXISTS 幂等简洁性（V6 t_standard 同论证）。
-- 概率/影响 1~5 边界与状态机均应用层校验（非法 400，DB 不加约束——V5/V6 枚举先例）：
--   open→mitigating→closed（closed 必填 resolution_note，终态不可 reopen——裁决 Q3）；
--   mitigating→open 回退合法（缓解无效风险复燃，PRD §4.1.3 / Q2-①）；其余流转一律 400。
-- 关联对象存 id（裁决 Q4）：t_program/t_project/t_case 均无 code 列，V4 t_program.strategy_id
--   存 id 先例；JSON 数组而非关联表——纯展示级引用无行级生命周期诉求（V5/V6 JSON 论证先例）。
CREATE TABLE IF NOT EXISTS `t_risk` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `risk_name` VARCHAR(200) NOT NULL COMMENT '风险名（租户内唯一，同 V4 uk_gate_tenant_name / V6 uk_dqr_tenant_name 先例）',
  `category` VARCHAR(16) NOT NULL COMMENT '风险类别: strategy/compliance/operations/technical/security（领域字典枚举，应用层校验非法 400，DB 不加约束——PRD §0 P6 裁决）',
  `probability` TINYINT NOT NULL COMMENT '概率 1~5（应用层校验，0/6/非整数 400——AC-F1.3）',
  `impact` TINYINT NOT NULL COMMENT '影响 1~5（同上）',
  `risk_value` INT NOT NULL COMMENT '风险值=probability×impact（1~25），服务端保存时重算落库（裁决 Q1）；四段闭区间 [1,6]/[7,12]/[13,19]/[20,25] 边界 6/12/13/19/20 由等级映射消费（AC-F1.2）',
  `risk_level` VARCHAR(16) NOT NULL COMMENT '等级: low/medium/high/critical（服务端算落库；VARCHAR(16) 而非提示稿的 8——critical 恰 8 字符零余量，按 V5 FAIL_WARN 宽度纠偏教训放宽数字余量，枚举域 PRD 冻结）',
  `description` TEXT COMMENT '风险描述（背景/影响范围，PRD §4.1.1 三字段之一——DBA r2 补列，v1 漏建）',
  mitigation TEXT COMMENT '缓解措施（计划类自由文本，TEXT——同 V6 description 先例）',
  `contingency_plan` TEXT COMMENT '应急预案（同上）',
  `owner` VARCHAR(64) DEFAULT NULL COMMENT '风险责任人（自由填写，本期不联动 t_user——同 V6 t_data_asset.owner 先例）',
  `status` VARCHAR(16) NOT NULL DEFAULT 'open' COMMENT 'open/mitigating/closed（状态机应用层校验非法流转 400，DB 不加约束；closed 后全字段只读 AC-F1.4）',
  `resolution_note` VARCHAR(500) DEFAULT NULL COMMENT '处置说明（closed 必填——应用层校验缺失 400；宽度同 V6 deprecate_reason 先例）',
  `related_objects` JSON DEFAULT NULL COMMENT '关联对象数组 [{type: program|project|case, id: BIGINT}]（可空多选；存 id——裁决 Q4，type 非法或 id 租户内不存在 400 AC-F1.6；被关联对象逻辑删后展示层已删除占位，不影响风险行为）',
  `review_date` DATE DEFAULT NULL COMMENT '复评日期（可空；逾期=review_date < CURRENT_DATE 且 status 不为 closed，展示层红标不落库——裁决 Q9，到期当日不标次日标）',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_risk_tenant_name` (`tenant_id`, `risk_name`),
  KEY `idx_risk_tenant_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险登记册表（租户级知识资产，不限层，计算列落库，F1.1）';
-- 索引说明：
--   uk_risk_tenant_name(tenant_id, risk_name) —— PRD 契约 uk(tenant, 风险名)：同名再建被拒（AC-F1.1）
--     + 列表主查询（WHERE tenant_id=?）前缀命中。必须含 tenant_id：risk_name 为租户自由命名无全局
--     语义，跨租户同名是常态（V4 uk_gate_tenant_name 同权衡），丢 tenant_id 会误伤合法数据。
--   idx_risk_tenant_status(tenant_id, status) —— 列表状态筛选（页面筛选器 open/mitigating/closed
--     等值命中）与看板/逾期口径的"未 closed"过滤（应用层写 status IN(open,mitigating) 两等值分支
--     走索引范围，AC-F1.12~F1.14 主路径）；对齐 V4 t_strategy/t_program 与 V6 t_standard 的
--     (tenant_id, status) 惯例。status 基数 3 低，但作为联合索引等值前缀命中列表筛选主查询仍有效。
--   不加 (tenant_id, risk_value) 排序索引：列表默认 risk_value DESC + id DESC 在 ≤5000 行/租户量级
--     filesort 毫秒级（P95 < 500ms 水位内），按 V6"按需加列不按需加索引"先例待量级证明慢查询再评估。
--   不加 (tenant_id, probability, impact) 聚合索引：热力图 GROUP BY(probability,impact) 仅 25 格、
--     值低区分度，聚合成本在行读取而非分组；未 closed 子集扫描后临时表聚合毫秒级（AC-F1.12 路径）。
--   不加类别/等级/复评日期索引：基数 5/4 低区分度；review_date < CURRENT_DATE 为非等值比较且需
--     叠加 status 条件，租户内扫描过滤即可（AC-F1.7 逾期筛选）。

-- ============ F1.2: 合规检查（租户级知识资产，不限层，手动登记制，PRD §4.2） ============
-- 框架/结果为领域字典枚举（PRD §0 P6 裁决），应用层校验非法 400，DB 不加约束：
--   framework=djba2.0/iso27001/gdpr/custom（djba2.0 展示名=等保 2.0，前端集中一处映射）；
--   result=pass/fail/partial/na。custom 联动规则：framework=custom 时 framework_name 必填、
--   其余框架时必须为空（防脏数据，AC-F1.9）——均应用层校验。
-- 结果为覆盖式单值当前态：历史唯一留痕 = t_governance_log 审计 detail（含被覆盖旧值，AC-F1.10；
--   同 V6 质量规则 last_result 先例，不建历史表）。na 不豁免复检逾期（PRD §4.2 统一口径）。
CREATE TABLE IF NOT EXISTS `t_compliance_check` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `check_name` VARCHAR(200) NOT NULL COMMENT '检查项名（租户内唯一，uk 命名同 V6 uk_dqr_tenant_name 先例）',
  `framework` VARCHAR(16) NOT NULL COMMENT '合规框架: djba2.0/iso27001/gdpr/custom（应用层枚举校验非法 400，DB 不加约束；最长值 iso27001=8 字符）',
  `framework_name` VARCHAR(128) DEFAULT NULL COMMENT '自定义框架名（framework=custom 时必填、非 custom 时必须为空——应用层校验 400，AC-F1.9）',
  `clause_ref` VARCHAR(128) DEFAULT NULL COMMENT '条款引用（如 ISO27001 A.9.4.1；自由填写，平台不做条款符合性自动判定 PRD §7-6）',
  `description` TEXT COMMENT '检查描述',
  `result` VARCHAR(8) NOT NULL COMMENT '检查结果: pass/fail/partial/na（应用层枚举校验；partial=7 字符宽度对齐 V6 last_result VARCHAR(8) 先例）',
  `evidence_note` VARCHAR(1000) DEFAULT NULL COMMENT '证据说明（手动登记制，平台不做自动扫描取证 PRD §7-2；较 V6 last_check_remark 500 放宽——证据常含多条线索引用）',
  `check_date` DATE DEFAULT NULL COMMENT '检查日期（登记时可选，缺省由应用层取当天）',
  `recheck_date` DATE DEFAULT NULL COMMENT '复检日期（可空；逾期=recheck_date < CURRENT_DATE 展示层红标，na 不豁免——裁决 Q9/PRD §4.2）',
  `owner` VARCHAR(64) DEFAULT NULL COMMENT '检查责任人（自由填写）',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_check_tenant_name` (`tenant_id`, `check_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合规检查表（租户级知识资产，不限层，手动登记制，F1.2）';
-- 索引说明：
--   uk_check_tenant_name(tenant_id, check_name) —— PRD 契约 uk(tenant, 检查项名)：同名再建被拒
--     （AC-F1.8）+ 列表主查询前缀。含 tenant_id 权衡同 t_risk：检查项名为租户自由命名，跨租户
--     同名合法（如各租户都登记"访问控制条款核验"）。
--   不加任何二级索引（对齐 V6 t_data_asset 四维筛选不索引先例）：框架/结果筛选基数仅 4/4 区分度
--     极差；逾期筛选为非等值日期比较且不可索引优化；检查项 ≤5000 行/租户（PRD §6.5）uk 的
--     tenant_id 前缀扫描后过滤毫秒级（P95 < 500ms 水位内）。低频管理页操作，待量级证明慢查询
--     后再评估（见设计文档 §7 风险项）。

-- ============ F2.1: 商业案例（租户级知识资产，不限层，战略投资决策记录，PRD §4.4） ============
-- 计算列落库且可空（裁决 Q1）：payback_years/roi_percent/rice_score 由 Service 在 create/update
--   重算覆盖存储（客户端提交值一律忽略，AC-F2.4 Then 项）；N/A 统一以 NULL 表示（net_benefit≤0
--   或 onetime_cost=0 的回收期边界、onetime_cost=0 的 ROI 除零边界——PRD §4.4.1 唯一口径）。
--   rice_score 本无 N/A 路径（effort≥1 恒正），列保持可空仅为三计算列统一防御（迁移期/异常兜底）。
--   net_benefit 一并落库：年净收益=annual_benefit-annual_op_cost 为确定函数，落库使投资组合
--   汇总 Σ(net_benefit) 与 Σ(net_benefit×3) 直接用列（裁决 Q1"聚合直接用列"精神，裁决列举的
--   五列之外 DBA 增补，见设计文档 §2.3 说明）。
-- 金额单位=元、DECIMAL(14,2)（裁决 Q2，两位小数；前端展示万/亿缩写）；≥0 应用层校验负值 400
--   （AC-F2.5），=0 合法（触发 N/A/0.0 边界）。计算列宽度按最坏值预算（V6 经验"按最坏字节算"）：
--   payback 最坏 14,2 成本 ÷ 0.01 净收益 ≈ 1e14 → DECIMAL(16,1)；roi_percent 最坏 ≈ 3e16 →
--   DECIMAL(20,2)；rice_score 值域 [0.01,100.00] → DECIMAL(10,2)。
-- 状态机应用层校验（非法流转 400，DB 不加约束）：draft→approved→executing→done；draft→rejected
--   （必填 rejected_reason）；rejected/done 终态；不允许回退/reopen/批准后撤销（裁决 Q3）。
--   注：裁决 Q3 将五态线性串写，executing 的唯一合法出边=done 以 PRD §4.4.2 图/AC-F2.6 为准。
-- 编辑/删除限制（AC-F2.7）：draft 全字段可编辑；approved/executing 输入只读仅 decision_note
--   可更新；rejected/done 全只读；仅 draft 可逻辑删。每次合法流转与决策记录更新写审计。
CREATE TABLE IF NOT EXISTS `t_business_case` (
  `id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL DEFAULT 0,
  `case_name` VARCHAR(200) NOT NULL COMMENT '案例名（租户内唯一）',
  `description` TEXT COMMENT '案例描述',
  `related_strategy_ids` JSON DEFAULT NULL COMMENT '关联战略 id 数组（可空多选，存 t_strategy.id——其无 code 列，裁决 Q4；与 V5 ADR related_principle_codes 存 code 有意不同：被引实体无稳定业务键；存在性应用层校验无效 400 AC-F2.8；战略逻辑删后展示层已删除占位）',
  `onetime_cost` DECIMAL(14,2) NOT NULL COMMENT '一次性成本（单位元，裁决 Q2；≥0 应用层校验，=0 合法且触发回收期 0.0/ROI N/A 边界 AC-F2.2/F2.3）',
  `annual_op_cost` DECIMAL(14,2) NOT NULL COMMENT '年运营成本（元，≥0 应用层校验）',
  `annual_benefit` DECIMAL(14,2) NOT NULL COMMENT '量化收益/年（元，≥0 应用层校验）',
  `net_benefit` DECIMAL(14,2) NOT NULL COMMENT '年净收益=annual_benefit-annual_op_cost（可负，中间量落库——汇总 Σ 直接用列；两输入同宽 14,2 差值不越界）',
  `payback_years` DECIMAL(16,1) DEFAULT NULL COMMENT '投资回收期=onetime_cost÷net_benefit（年，1 位小数 HALF_UP，服务端算落库）；NULL=N/A（net_benefit≤0 含 0 防除零）；onetime_cost=0 且净>0 → 0.0 零成本（AC-F2.2 四例逐条对应）',
  `roi_percent` DECIMAL(20,2) DEFAULT NULL COMMENT 'ROI=(net_benefit×3-onetime_cost)÷onetime_cost×100（3 年口径，2 位小数 HALF_UP，负值合法展示）；onetime_cost=0 → NULL=N/A 防除零（AC-F2.3）',
  `reach` INT NOT NULL COMMENT 'RICE 触达 1~10（应用层校验 0/11/非整数 400——AC-F2.4）',
  `impact` INT NOT NULL COMMENT 'RICE 影响 1~10（应用层校验同上；与 t_risk.impact 同名不同义，表内自洽）',
  `confidence` DECIMAL(2,1) NOT NULL COMMENT 'RICE 信心 0.1~1.0（0.1 步进离散恰 10 档，0.05/0.15/两位小数非步进值 400——AC-F2.4；PRD 契约精度）',
  `effort` INT NOT NULL COMMENT 'RICE 投入 1~10（≥1 恒正，rice_score 无除零路径）',
  `rice_score` DECIMAL(10,2) DEFAULT NULL COMMENT 'RICE=reach×impact×confidence÷effort（2 位小数 HALF_UP，服务端重算落库；值域 [0.01,100.00]；组合视图降序排序直接用列，AC-F2.10）',
  `status` VARCHAR(16) NOT NULL DEFAULT 'draft' COMMENT 'draft/approved/rejected/executing/done（状态机应用层校验，DB 不加约束；rejected 必填 rejected_reason；详见上方流转说明）',
  `rejected_reason` VARCHAR(500) DEFAULT NULL COMMENT '拒绝原因（rejected 必填——应用层校验缺失 400 AC-F2.6；宽度同 V6 deprecate_reason 先例）',
  `decision_note` TEXT COMMENT '决策记录（自由文本随流转可更新；approved/executing 输入只读期仍可更新——执行期进展记录 AC-F2.7；旧值唯一留痕=审计 detail）',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` VARCHAR(64) DEFAULT NULL,
  `update_by` VARCHAR(64) DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_bizcase_tenant_name` (`tenant_id`, `case_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商业案例表（租户级知识资产，不限层，投资决策记录，计算列落库，F2.1）';
-- 索引说明：
--   uk_bizcase_tenant_name(tenant_id, case_name) —— PRD 契约 uk(tenant, 案例名)：同名再建被拒
--     （AC-F2.1）+ 列表主查询前缀。含 tenant_id 权衡同上两表：案例名为租户自由命名。前缀用
--     bizcase 而非 case：避免与 L1 既有 t_case 的索引名（idx_case_project，V4）语义混淆。
--   不加任何二级索引（对齐 V6 t_template 仅 uk 先例）：案例 ≤500 行/租户（PRD §6.5，三表中
--     量级最小），rice_score 降序排序、状态分布 GROUP BY、投资口径汇总 status IN(...) 全为
--     租户内全量扫描聚合，毫秒级；status 基数 5 低区分度，uk 的 tenant_id 前缀扫描即可，
--     P95 < 500ms 水位内——最克制索引面，待量级证明慢查询后再评估。

-- ============ Seed: L3 收口权限原子 10 条 + 模板角色授权（PRD §4.6 / AC-RBAC.5） ============
-- 权限 code 前缀锁定 PRD §4.6 契约：risk/compliance/bizcase × view/create/edit + bizcase:approve
--   （module/resource_type 同前缀；approve 独立原子而非应用层角色判断——裁决 Q7：审批高频
--   且需可配置可审计，403 断言直接走权限链，AC-F2.9）。
-- 角色矩阵（PRD §4.6 权威矩阵 + 裁决 Q5/Q6：team-grc/team-strategy 角色缺位由 tenant_admin 兼任）：
--   platform_admin  10 项全量（V1/V4/V5/V6 惯例）
--   tenant_admin    10 项全量（兼任风险管理员/合规检查员/战略投资经理/决策审批人，US-1/2/4/5）
--   project_manager 7 项 = risk×3（一线上报+缓解进展更新）+ compliance:view + bizcase view/create/edit
--                   （起草权）；无 compliance create/edit（防自查自登）、无 bizcase:approve
--                   （防自我批准）——裁决 Q6；compliance:view 按 PRD §4.6 矩阵（PM 合规只读）
--   engineer        3 项只读（三域 view，知识传播——V5/V6 engineer view 先例）
--   executive       3 项只读（三域 view——风险看板/投资组合是高管第一消费场景 US-3）
-- 权限/授权均为系统级（tenant_id=0 共享，t_permission/t_role_permission 在 IGNORE_TABLES），
--   对存量与新建租户一体生效，无需按租户 provisioning。
-- 幂等：INSERT IGNORE（uk_permission_code / uk_role_perm 兜底），重放安全。
INSERT IGNORE INTO `t_permission` (`id`, `tenant_id`, `permission_code`, `permission_name`, `module`, `resource_type`, `action`, `description`) VALUES
(1071, 0, 'risk:view',        '风险查看',     'risk',       'risk',       'view',    NULL),
(1072, 0, 'risk:create',      '风险创建',     'risk',       'risk',       'create',  NULL),
(1073, 0, 'risk:edit',        '风险编辑',     'risk',       'risk',       'edit',    '含状态流转（open→mitigating→closed，closed 必填处置说明；mitigating→open 回退合法，PRD §4.1.3）'),
(1074, 0, 'compliance:view',  '合规检查查看', 'compliance', 'compliance', 'view',    NULL),
(1075, 0, 'compliance:create','合规检查创建', 'compliance', 'compliance', 'create',  NULL),
(1076, 0, 'compliance:edit',  '合规检查编辑', 'compliance', 'compliance', 'edit',    '含检查结果覆盖式更新（历史唯一留痕=审计 detail，AC-F1.10）'),
(1077, 0, 'bizcase:view',     '商业案例查看', 'bizcase',    'bizcase',    'view',    NULL),
(1078, 0, 'bizcase:create',   '商业案例创建', 'bizcase',    'bizcase',    'create',  NULL),
(1079, 0, 'bizcase:edit',     '商业案例编辑', 'bizcase',    'bizcase',    'edit',    'draft 全字段可编辑；approved/executing 仅决策记录可更新；非 draft 不可逻辑删（AC-F2.7）'),
(1080, 0, 'bizcase:approve',  '商业案例审批', 'bizcase',    'bizcase',    'approve', '批准/拒绝流转（draft→approved/rejected）；仅 platform_admin/tenant_admin——创建与审批分离（裁决 Q7，AC-F2.9）');

-- 授权行（id 2170 起：V6 用毕 2169；引用 role_id 1~5 模板角色与 permission_id 1071~1080）

-- platform_admin (role_id=1)：10 项全量
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2170,1,1071),(2171,1,1072),(2172,1,1073),                    -- risk ×3
(2173,1,1074),(2174,1,1075),(2175,1,1076),                    -- compliance ×3
(2176,1,1077),(2177,1,1078),(2178,1,1079),(2179,1,1080);      -- bizcase ×4（含 approve）

-- tenant_admin (role_id=2)：10 项全量（裁决 Q5：GRC/Strategy 角色缺位由其兼任）
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2180,2,1071),(2181,2,1072),(2182,2,1073),                    -- risk ×3
(2183,2,1074),(2184,2,1075),(2185,2,1076),                    -- compliance ×3
(2186,2,1077),(2187,2,1078),(2188,2,1079),(2189,2,1080);      -- bizcase ×4（含 approve）

-- project_manager (role_id=3)：7 项（裁决 Q6：risk 一线上报 + bizcase 起草；两处利益冲突隔离——
--   无 compliance create/edit 防自查自登、无 bizcase:approve 防自我批准；compliance:view 按 PRD §4.6）
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2190,3,1071),(2191,3,1072),(2192,3,1073),                    -- risk view/create/edit
(2193,3,1074),                                                 -- compliance:view
(2194,3,1077),(2195,3,1078),(2196,3,1079);                     -- bizcase view/create/edit（无 approve）

-- engineer (role_id=4)：3 项只读（三域 view——风险/案例知识传播，PRD US-7）
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2197,4,1071),                                                 -- risk:view
(2198,4,1074),                                                 -- compliance:view
(2199,4,1077);                                                 -- bizcase:view

-- executive (role_id=5)：3 项只读（三域 view——风险看板/投资组合第一消费场景，US-3；无任何写）
INSERT IGNORE INTO `t_role_permission` (`id`, `role_id`, `permission_id`) VALUES
(2200,5,1071),                                                 -- risk:view
(2201,5,1074),                                                 -- compliance:view
(2202,5,1077);                                                 -- bizcase:view
