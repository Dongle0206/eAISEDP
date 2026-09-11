# 任务清单 — case-20260823-商用化（订阅计划 + 计量出账 + SLA 轻量承诺）

| 字段 | 值 |
|---|---|
| 产出者 | team-ba（L1 需求分析员） |
| 日期 / 版本 | 2026-09-09 / v1.0 |
| 上游基线 | PRD v1.0（8 功能点 27 AC + 计费口径 B1~B6）；编排者裁决 Q1~Q6；SE 技术方案 v1.0（**§11 四批拆解序为唯一拆解基线；§0.3 消解表、§5 API 契约、§4.3 边界清单、§9.1 单测锚点 1~22 全量引用**）；DBA 数据库设计 + V8__commercialization.sql + schema-h2.sql（已落盘并真库验证）；CLAUDE.md（ES-001/002/003）；先例 case-20260821 tasks.md |
| 下游 | team-dev（认领执行）/ team-qa（27 AC 基线 + "给 QA 的断言标注"节）/ team-reviewer（公共验收约束为检查基线） |
| 配套契约 | 同目录 `api-contracts.md`（16 端点 = 15 新 + 1 既有扩展；前端任务直接引用编号 P1~P4 / U2 / I1~I4 / C1~C3 / N1~N4） |

**拆解声明**：

1. 本清单严格按 SE 技术方案 §11 四批拆解序组织（批A 口径锚定 → 批B 出账主线 → 批C 事故+前端 → 批D 收口），未另起炉灶。
2. **口径优先级（强制，QA/Dev 共同遵守）**：**SE §0.3 消解表 + 编排者裁决 > PRD 原文**。Q6 裁决（首月按剩余天数折算，剩余天数**含创建当天**）覆盖 PRD B4"整月不折算"——AC-F2.2 第二构造（8-20 新建即付费）的断言按消解表改写为 **proration_amount = 386.71**（999.00 × 12 ÷ 31，先乘后除 HALF_UP 2 位），PRD 冲突处视同 v1.1 勘误（PO 勘误跟踪中，不停等）。
3. **DB 层已由 DBA 交付完成**（T0，标"已完成"，真库验证过）：V8 迁移（三表 + t_tenant 加 plan_code + 权限 1081~1089 + 授权 2203~2220 + 回填）、schema-h2.sql V8 段均已落盘，Dev 零动作。
4. **V8 落盘 DDL 与 SE 方案 §3 的差异定稿**（BA 逐列磁盘核对；冲突以 V8 为准，实体/VO/契约字段名按 V8 列名映射——SE 方案相应条目视为被 DBA 终稿覆盖；已知两处纠偏——超量单价 (12,6)、权限 code bill/cost——V8 已按 PRD 落正，无代码影响）：

| # | 项 | SE 方案 §3 | V8 落盘（终稿） | 影响 |
|---|---|---|---|---|
| D-1 | t_plan 上下架列 | `status` VARCHAR(8)（active/disabled） | **`enabled` TINYINT（1=在架/0=下架）**（t_architecture_principle 先例，与 is_deleted 正交） | 实体/VO/契约字段一律 `enabled`（Boolean）；上下架 = P4 传 enabled=false；"标准档同 code 在架唯一"判据 = **enabled=1**；前端字典组名随之为 planEnabled |
| D-2 | t_invoice 列名体系 | plan_id + plan_snapshot JSON + usage_detail JSON + monthly_price / base_fee / overage_fee / amount_due | **plan_code + plan_price（列）+ usage_tokens / overage_tokens（列）+ overage_amount / proration_amount / total_amount（列）+ detail JSON 单列** | 实体/VO/契约出参按 V8 列名：`planCode/planPrice/usageTokens/overageTokens/overageAmount/prorationAmount/totalAmount/detail`；D-12 的 base_fee 语义由 proration_amount 承载（**base = proration_amount>0 ? proration_amount : plan_price**）；套餐五字段快照 + 用量明细 + 折算参数统一入 detail JSON（结构见 api-contracts.md 附 A） |
| D-3 | 账单套餐引用键 | plan_id（快照源 id） | **plan_code**（字符串快照） | 套餐引用/构造按 code（QA 绑 seed 102 用 plan_code='pro'，id 不敏感） |
| D-4 | t_incident 列 | 七字段（PRD §4.8） | **+ `sla_breach` TINYINT DEFAULT 0**（登记时人工违约判定，纯台账） | 实体/VO/入参补可选 `slaBreach`（缺省 0）；N2/N4 契约含该字段；不触发任何计算（§7-4/7-5） |
| D-5 | 套餐 seed id | 雪花段建议 | **101~103** | 无代码影响（绑定按 code） |
| D-6 | 金额上限注释 | 999,999,999,999.99 | 99,999,999,999.99（少一位 9，**注释笔误**） | **Service 校验上限按 DECIMAL(14,2) 真实域上限 999,999,999,999.99**（数学事实）；QA 越界构造用 1e12 断言 400 指名非 500。DECIMAL(12,6) 上限 999,999.999999 两稿一致 |

5. **启动类零改动**（编排者约束 + SE §2.1 零改动清单）：`@MapperScan` 已含 `com.eaiselp.data.mapper`、scanBasePackages 已含 `com.eaiselp.data`（磁盘核实）——subscription 子包零启动配置；`LayerGuardInterceptor`（五新前缀零注册）/ `GlobalExceptionHandler`（BizException 既有链路承载 40004/400）/ `AsyncConfig`（不动，新增独立 Config）零改动。**唯一既有代码改动三处**：`EaiselpTenantHandler.IGNORE_TABLES` 追加 t_plan（T2）、`TenantController` U2 入参扩展（T4）、`RuntimeController` 派生挂点（T6）。

**仓库根约定**（下文产物路径均为相对路径）：
- 后端仓库：`D:\AI\mywork\platform\`
- 前端仓库：`D:\AI\mywork\eaiselp-web-separate\`

---

## 公共验收约束（Reviewer 检查基线，适用于 T1~T11/T19 全部后端任务）

1. **Controller 薄层**：参数接收→Service→`R<T>`；请求 DTO 内嵌 static class；除判空外不写业务校验（TenantController/StandardController 先例）。
2. **金额全链 BigDecimal（D-4，禁 float/double）**：monthlyPrice/tokenOveragePrice 入参 DTO BigDecimal 承载；≥0（负值 400 指名）+ **域上限镜像**（(14,2) ≤ 999,999,999,999.99、(12,6) ≤ 999,999.999999，超限 BizException 指名非 DB 溢出 500）；HALF_UP 仅两处终点（overage_amount 落库前、total_amount），中间全精度，落库值 scale 恒 2。
3. **金额防伪造链**：`usageTokens/overageTokens/overageAmount/prorationAmount/totalAmount/status/issuedTime/paidTime` **不出现在任何入参 DTO**（toEntity 不映射——比"忽略"更强的防伪造，V7 先例）；应缴恒服务端 Calculator 生成。
4. **tenant_id 只从 TenantContext/LoginUser 取**（G13）；**唯一例外 = 出账路径**（D-5：定时线程/手动补生成 = SYSTEM 上下文 tenant 0，实体显式携带 tenant_id，Service 全程显式传 tenantId 禁依赖 ThreadLocal，代码注释锚定 D-5）；禁止 `@InterceptorIgnore`（G13 BLOCKER 红线）。
5. **uk 冲突统一形态**：`DuplicateKeyException` → `BizException(400, "...已存在: ...")`（uk_plan_name / uk_invoice_tenant_period；出账侧 DuplicateKey 兜底转"已存在"分支而非 400，D-7）。
6. **审计统一**（SE §8.1 清单）：plan_create / plan_update / tenant_edition_change（**沿用既有 action**，detail 扩展五类快照 old→new + 操作者）/ bill_transit / bill_generate / bill_generate_scheduled / incident_create / incident_update——逐条对照，写操作零漏挂。
7. **信息隔离红线（§8.4）**：`t_derivation.cost` 禁止进入 t_invoice 任何列/detail JSON、任何 VO 字段、任何页面展示位——Code Review 红线 + T19 序列化断言。
8. **风格一致性**：新端点一律 `/api/v1/`（G14）；代码无 team-* 字面量（G11）；5 新前缀（plans/invoices/cost-center/incidents + U2 扩展）**零注册** LayerGuardInterceptor；枚举（code/sla_level/status/enabled 语义）应用层校验非法 400 指名 + 合法值集，DB 不加约束（P6）；SLA 承诺文案仅前端 governance-dict.js 集中一处（D-17），后端零文案常量。

---

## 任务总览

- **总数 20**：T0 已完成（DBA 交付）+ 开发任务 19（后端 11：T1~T11 / 前端与配置 7：T12~T18 / 测试收口 1：T19；单测内嵌各任务，SE §9.1 编号 1~22 为锚点）。
- **并行组**（SE §11 四批序，前端线按 api-contracts.md 契约先行、后端联调收口）：
  - **批A（口径锚定）**：波1 零依赖即启——**T1（先行半日锁全部数值口径）** ∥ T2 ∥ T11（事故域零套餐依赖） ∥ T12（dict）；波2——T3（依赖 T2）；波3——T4 ∥ T6（依赖 T3）∥ T7（依赖 T1+T3，B3 用例直插审计行、不等 T4）；波4——T5（依赖 T4，短验收）
  - **批B（出账主线，依赖批A）**：T7 → T8 ∥ T9 ∥ T10
  - **批C（前端，依赖 T12 字典 + 契约，与批A/批B 全程并行）**：T13 ∥ T14 ∥ T15 ∥ T16 ∥ T17（五页零相互依赖）→ T18（menu，依赖五页文件存在）
  - **批D（收口）**：T19（依赖 T1~T11 全部后端 + T18）
- **关键路径**（两条并列同深，各 5 开发节点）：`T2 → T3 → T7 → T8 → T19`（出账线：拦截器 → 套餐 CRUD → 出账核心 → 定时任务 → 回归收口）∥ `T2 → T3 → T4 → T5 → T19`（U2 扩展线）。T1 与 T2/T3 并行先行，不占路径深度。
- **27 AC 全覆盖**，映射见各任务"验收"行；无孤儿 AC、无范围外遗留（范围外声明见文末）。

## 任务明细

### T0 [DB] V8 迁移 + H2 schema + 权限 seed —— **已完成（DBA 交付并真库验证，Dev 零动作）**
- 模块：eaiselp-runtime 数据层
- 类型：数据库迁移/Schema
- 依赖：无
- 验收（已达成）：t_plan（uk_plan_name/idx_plan_code）/ t_invoice（uk_invoice_tenant_period/idx_invoice_period_status）/ t_incident（idx_incident_tenant_time）三表 + t_tenant 动态加 plan_code + 守卫式回填 + seed（套餐 101~103 / 权限 1081~1089 / 授权 2203~2220）+ schema-h2.sql 同步（MERGE INTO 与 V8 逐值一致）；AC-G1/G2 数据侧
- 产物（已落盘）：`eaiselp-runtime/src/main/resources/db/migration/V8__commercialization.sql`；`eaiselp-runtime/src/test/resources/schema-h2.sql`（V8 段 318~430 行）
- 备注：V8 与 SE 方案 §3 的 6 处差异已在"拆解声明 4"定稿，**以 V8 为准**；R6（t_plan 进 IGNORE_TABLES 属 Java 侧）由 T2 承载

### T1 [后端] BillingCalculator 纯静态计算引擎 + 全边界单测（**先行任务第一位，锁全部数值口径**）
- 模块：eaiselp-data（`data/service/subscription/BillingCalculator.java`，新子包首个类，D-1/D-3）
- 类型：纯静态工具类 + 单测（final、私有构造、纯函数、零 Spring/ORM 依赖——RiskCalculator/BizCaseCalculator 先例）
- 依赖：无（批A 第一位；不依赖表/实体，半日级先行交付，R1/R3 风险缓解）
- 验收（AC-F2.2/F2.3/F2.4/F2.5 数值侧 + Q6 折算；单测锚点 §9.1-1，构造值照 SE §4.3 全表逐条）：
  - `overageTokens`：max(0, used−limit)；5,000,000−5,000,000=0（**恰等配额 → 0**）；4,999,999 → 0 → 应缴 999.00
  - `ktokenBilled`：整数运算 ceil（overage+999)/1000——1,000,000→**1000（恰不进位）**、1,000,001→**1001**、超额 1 token→1（ceil 下界）；1,001,500→1002
  - `overageFeeRaw/overageFee`：全精度乘法不预舍入；HALF_UP 2 位落库值——1002×8.00=8,016.00；3×0.335=1.005→**1.01**
  - `proratedBaseFee`：**先乘后除** monthlyPrice×remainingDays÷daysInMonth，HALF_UP 2 位——999.00×12÷31=**386.71**（Q6 锚点值，剩余天数含创建当天）；1 日创建→剩余=整月→折算=原价；31 日创建→999×1÷31=**32.23**；平年 2 月 28 天专项例；30/31/28 天月全覆盖
  - `amountDue`（V8 语义：base = proration>0 ? proration : planPrice）：999.00+8,016.00=**9,015.00**；99.99+1.005→100.995→**101.00**（进位）；386.71+超量照常（折算只作用基础费）；**恒等式落库形态 total = (proration>0 ? proration : planPrice) + overageAmount 恒成立**（D-4：base 恒 2 位，HALF_UP(base+overage 全精度) ≡ base+HALF_UP(overage)）
  - **上限镜像**：amount > 999,999,999,999.99 → BizException 400 指名"超出 DECIMAL(14,2) 域"，非 500
  - 零值：超量 0 → 0.00、免费档 0+0 → 0.00，**scale 恒 2**（与 B2"无行"语义区分）
  - 两断言族脱离 Spring/H2 可独立运行（eaiselp-data/src/test 已有基建，磁盘核实）；QA 数值断言复用 SE §4.3 构造值表，不自行发明
- 产物：`eaiselp-data/src/main/java/com/eaiselp/data/service/subscription/BillingCalculator.java`；`eaiselp-data/src/test/java/com/eaiselp/data/service/subscription/BillingCalculatorTest.java`

### T2 [后端][配置] 基础设施两处：EaiselpTenantHandler.IGNORE_TABLES 追加 t_plan + ResultCode 40004
- 模块：eaiselp-common（tenant/result）
- 类型：配置级代码增量（R6 承载 + D-10 错误码）
- 依赖：无（半日级，**T3 的前置**——否则 t_plan 查询被拦截器注入 tenant_id 条件，平台级行 tenant_id=0 查不到，P1 空列表/应用 40400）
- 验收：
  - `EaiselpTenantHandler.IGNORE_TABLES` 数组追加 `"t_plan"`（带注释引用 V8 头注释契约，t_permission 先例形态）；其余表零变化；**不使用 @InterceptorIgnore**（G13）
  - `ResultCode` 追加 `MODEL_TIER_FORBIDDEN = 40004`（"模型档位不在当前套餐范围内"——40003 用毕顺延，磁盘核实 40003 为当前末位）；其余码零变化
  - 既有 656 测试全量不动通过（两处均为纯增量）
- 产物：`eaiselp-common/src/main/java/com/eaiselp/common/tenant/EaiselpTenantHandler.java`（改，一处常量）；`eaiselp-common/src/main/java/com/eaiselp/common/result/ResultCode.java`（改，一行）

### T3 [后端] 三实体 + 三 Mapper + 套餐 CRUD（P1~P4，SubscriptionPlanService）
- 模块：eaiselp-data（entity/mapper 平铺 + service/subscription）
- 类型：Entity+Mapper+Service+Controller（Controller 落 eaiselp-runtime/controller/PlanController，D-1）
- 依赖：T2（IGNORE_TABLES）
- 验收（AC-F1.1/F1.2/F1.3/F1.4 + AC-F3.1 枚举校验侧；单测锚点 §9.1-11/12）：
  - P2 创建：code 枚举 starter/pro/enterprise/custom（非法 400 指名）；**标准三档同 code 在架（enabled=1 且 is_deleted=0）至多一份**（重复创建/下架复活到 active 态 → 400，AC-F1.2；custom 多份合法）；sla_level 枚举 bronze/silver/gold（空/枚举外 400，AC-F3.1）；金额 ≥0 + 域上限镜像（D-4/D-6）；**features 服务端结构校验**（必需键 max_users/max_cases/token_limit/case_limit/derivation_limit/storage_limit_mb/layer_overrides/model_tiers + 值域；未知键忽略向前兼容）；**custom↔edition_override 双向校验**（custom 必填 ∈{starter,pro,enterprise}，非 custom 必须为空，D-9）；uk_plan_name 冲突 400 统一形态
  - P1 列表：code/enabled/keyword(name LIKE) 筛选，默认 id DESC；features 结构化回显（VO）
  - P4 编辑（全量，含 enabled 上下架）：下架不可被应用（→ T4 拒绝，AC-F1.4）；**编辑改价/改配额不回写已生成账单与已绑定租户当期 t_quota**（商品目录语义，SE P4 冻结）；审计 plan_update detail 含 old→new
  - AC-F1.4 断言：disabled 套餐的存量绑定租户次月出账仍按快照（联调用例随 T7，本任务提供构造）
  - 审计 plan_create/plan_update；**无 DELETE 端点**（D-14，仅逻辑删列语义）
  - VO 字段名按 V8：`enabled`（Boolean，差异定稿 D-1），非 status
- 产物：`data/entity/Plan.java`、`Invoice.java`、`Incident.java`（三实体一次建齐，Invoice/Incident 供 T7~T11）；`data/mapper/PlanMapper.java`、`InvoiceMapper.java`、`IncidentMapper.java`；`data/service/subscription/SubscriptionPlanService.java` + impl；`dto/PlanVo.java`；`runtime/controller/PlanController.java`（`/api/v1/plans`，契约 P1~P4）；测试 `SubscriptionPlanServiceImplTest.java`

### T4 [后端] U2 扩展：套餐应用编排 SubscriptionApplyService（五类单事务）+ 审计扩展 + U1 出参扩展
- 模块：eaiselp-runtime（`runtime/subscription/SubscriptionApplyService.java`，D-1——层开关必须走 TenantLayerService，data→runtime 反向依赖禁止）
- 类型：Service 编排 + 既有 Controller 入参扩展（TenantController U2，零新端点）
- 依赖：T3（Plan 查询/features 解析）
- 验收（AC-F1.5/F1.6；单测锚点 §9.1-13/14）：
  - 入参分叉点唯一（D-8）：`{planCode?, edition?, expireTime?}`——**planCode 非空时 edition/expireTime 必须为空（同传 400 互斥）；三者全空 400；planCode 为空走既有 updateSubscription 路径逐行为零变化**（分叉仅"planCode 判空"一处）
  - planCode 非空 → `applyPlan` **单事务五类落库**（任一步失败全部回滚，Mockito doThrow 于 QuotaMapper 断言五类全保持旧值）：① t_tenant.edition（三档=code / custom=edition_override，D-9——SUPPORTED_EDITIONS 四值域保持不变）② t_tenant.plan_code 绑定 ③ expire_time 置空 ④ t_quota 当期四限 + monthly_cost=套餐月价覆写（行不存在按模板 upsert，uk(tenant,period)）⑤ 层开关 TenantLayerService.setLayerEnabled ×2（套餐说开就开/说关就关，写后缓存失效在事务内）
  - 模型档位经 plan_code 绑定即时生效（enforcement 行为断言在 T6/AC-F1.7，本任务不重复实现）
  - planCode 不存在 → 40400；存在但 enabled=0 → 400（AC-F1.4）
  - **审计 tenant_edition_change 沿用既有 action**，detail 扩展 old→new 五类快照（edition/expire_time/plan_code/配额四限+monthly_cost/层开关/model_tiers）+ 操作者（AC-F1.6；**B3 期初判定数据源**——detail 格式即 T7 的判定契约）
  - U2 出参（SubscriptionStatus）向前兼容扩展 planCode/planName/slaLevel 三字段；**U1 `GET /api/v1/tenant/subscription` 出参同步扩展**（F1.3 回显数据源）
  - custom 套餐应用 → edition=edition_override 非 trial 且出账正常（D-9 + PRD 4.2.1）
- 产物：`runtime/subscription/SubscriptionApplyService.java`；`runtime/controller/TenantController.java`（改：U2 入参扩展 + U1 出参扩展）；`TenantSubscriptionService.java`/`impl`（改：出参结构扩展）；测试 `SubscriptionApplyServiceTest.java`

### T5 [后端][测试] U2 兼容回归专项（AC-F1.8 单点分叉，独立任务不可并入）
- 模块：eaiselp-data / eaiselp-runtime（测试侧）
- 类型：回归测试（存量用例零改动通过为合并门禁，R6 风险缓解）
- 依赖：T4
- 验收（AC-F1.8；单测锚点 §9.1-15）：
  - **case-20260820 既有 TenantControllerTest 全量零改动通过**（含 400/40400/空串置空各分支）
  - trial 租户 40003/临期三档既有链路回归零影响
  - 互斥 400（planCode+edition 同传）；三者全空 400（D-8）
  - **U2 裸通道（不传 planCode）不动 t_tenant.plan_code 列**（D-13 前半——单字段语义零副作用断言）
- 产物：既有测试回归记录 + 新增分叉点用例（`TenantControllerTest` 增补或独立 `U2CompatTest`，Dev 按包惯例）

### T6 [后端] 派生入口模型档位 enforcement（40004 挂点）
- 模块：eaiselp-runtime（`RuntimeController`，与 assertTrialNotExpired 同挂点同形态，D-10）
- 类型：既有 Controller 校验挂点 + 防御放行
- 依赖：T2（40004）+ T3（features.model_tiers 解析）
- 验收（AC-F1.7；单测锚点 §9.1-16）：
  - 派生入口（/derive 与 /orchestrate 共用挂点）实时解析 `t_tenant.plan_code → t_plan.features.model_tiers`：请求 tier ∉ tiers → **BizException(40004)** + 审计 failure；∈ tiers → 放行
  - 未绑定套餐（plan_code NULL）/ trial 租户 → 不限（现状回归）；**plan 解析失败/套餐逻辑删 → 防御放行 + WARN**（不因计费配置锁死派生主链路，R7）
  - **model_tiers 值域 = 真实档位名**（opus/sonnet/haiku/reasoning/structured/mechanical/code，t_model_routing/ES-003 §2.5——PRD 的 high/mid/low 为示意写法，QA 构造按真实名，DBA R5）
  - 零缓存即时生效（与订阅判定同风格）
- 产物：`runtime/controller/RuntimeController.java`（改，挂点）；`data/service/subscription/SubscriptionPlanService.java`（增 model_tiers 读取）；测试（锚点 16）

### T7 [后端] 出账核心 InvoiceService（判定序 + 聚合 + 幂等）+ I3 手动补生成
- 模块：eaiselp-data（`service/subscription/InvoiceService` + impl，D-1——五数据源同模块自洽）
- 类型：Service 核心逻辑（读 t_derivation/t_quota/t_tenant/t_plan/t_governance_log，写 t_invoice）+ Controller I3（runtime）
- 依赖：T1（Calculator）+ T3（Plan 查询）；T4 建议先行（B3 集成用例；单测可直插 t_governance_log 行独立构造）
- 验收（AC-F2.1/F2.2/F2.3/F2.4/F2.5/F2.6/F2.7；单测锚点 §9.1-2~7）：
  - **判定序**（§4.2，先资格后折算）：① edition=trial → B2 跳过（**无行，非 0 元账单**）② plan_code 空 → D-13 跳过 + WARN（不出 0 元账单）③ 期初（当月 1 日 00:00）为 trial → B3 跳过（判定源 = t_governance_log action=tenant_edition_change 账期起始后首个 trial→非trial 变更；**fallback 双分支**：无审计+出账时 trial→不出账 / 无审计+付费+create_time<期初→正常出账，保守少收不误收 + WARN）④ plan 按出账时刻 plan_code 查（**B1 期末档**；查不到/逻辑删 → 跳过 + WARN）⑤ 用量聚合 ⑥ Q6 折算判定（期初租户不存在 create_time≥当月1日 且出账付费 → 折算；**B2/B3 优先于折算**）⑦ Calculator 计算 + 快照/明细组装 + uk 幂等落库
  - **用量聚合**（B5/D-15）：两段 UNION——started_at ∈ [期初,次月期初) 段 + started_at IS NULL AND create_time ∈ 同区间 段，各带 **status='success'**（成功终态字面值，V1 schema + DerivationEngine 磁盘事实）；input_tokens+output_tokens 逐笔 SUM；t_quota 水位仅快照展示不作口径
  - **幂等**（D-7/AC-F2.7）：uk(tenant_id,period) 查行——不存在 INSERT（DuplicateKeyException 兜底转已存在分支）；draft → 重算覆盖 UPDATE（补记用量后金额更新断言）；issued/paid → 不覆盖 + WARN；恒等式落库校验
  - **数值断言**（AC-F2.2/F2.3/F2.4/F2.5 集成侧）：9,015.00 构造（含失败 10,000 token 不计）/ ceil 1000 与 1001 / 恰等 0.00 + 4,999,999→999.00 / 100.995→101.00（overage_amount=1.01）/ **Q6 折算 8-20 新建 → proration_amount=386.71 + detail 折算参数**（§0.3-1 消解基线，非 PRD 整月）
  - **账期归属**（AC-F2.6）：[1日00:00, 次月1日00:00) 左闭右开；8-31 23:59:59→8 月、9-1 00:00:00→9 月、NULL+create_time 8-15→8 月；running/failed 不计（构造断言）
  - **B1/Q4**：期中 starter→pro → 按期末档整月计价；降级配额不回退、超量按新套餐单价（构造 used>旧配额断言新单价）
  - I3 `POST /api/v1/invoices/generate`（bill:manage）：`{period*, tenantId?}` 与定时**同一 Service 入口**；返回 `{processed, skippedTrial, skippedNoPlan, overwritten, failed:[{tenantId,reason}]}` 汇总；审计 bill_generate
  - **信息隔离**：t_derivation.cost 禁入任何列/JSON（§8.4）；出账全程显式 tenantId + SYSTEM 上下文（D-5 注释锚定）
- 产物：`data/service/subscription/InvoiceService.java` + impl；`dto/InvoiceVo.java`、`InvoiceDetailVo.java`；`runtime/controller/InvoiceController.java`（先交付 I3，I1/I2/I4 随 T9）；测试 `InvoiceServiceImplTest.java`（锚点 2~7）

### T8 [后端] 定时任务宿主 BillingGenerationTask + billingExecutor 线程池 + 测试开关
- 模块：eaiselp-runtime（`runtime/subscription/`）
- 类型：@Scheduled 宿主 + 独立线程池 Config + 配置（D-6/裁决 Q2/#3）
- 依赖：T7（同入口 generatePeriod）
- 验收（AC-F2.7 定时侧 + §8.2；单测锚点 §9.1-17）：
  - `@Scheduled(cron="0 0 2 1 * *", zone="Asia/Shanghai")`（每月 1 日 02:00 错峰）+ `@ConditionalOnProperty(eaiselp.billing.scheduler-enabled, matchIfMissing=true)`（**运营熔断开关**：改 yml 紧急停出账）
  - 方法体 AtomicBoolean CAS 防重入 → 提交 `billingExecutor`（core=1/max=1/queue=0/AbortPolicy，拒绝捕获=WARN"上一次出账仍在运行，跳过"）——**不占用 @Scheduled 默认单线程调度器（CapabilityLoader 共用）、不抢 orchestrationExecutor**（裁决 #3）
  - 全量流程：遍历 t_tenant（active，is_deleted=0）逐租户生成；单租户异常 try-catch 进 failed 清单不中断（第 1 租户 doThrow → 第 2 租户仍出账断言）；结束 summary 日志 + 审计 bill_generate_scheduled
  - `application-test.yml` 追加 `eaiselp.billing.scheduler-enabled: false`（CapabilityLoader auto-refresh 先例）；测试上下文无 BillingGenerationTask bean 断言
- 产物：`runtime/subscription/BillingGenerationTask.java`、`BillingScheduleConfig.java`；`eaiselp-runtime/src/test/resources/application-test.yml`（改）

### T9 [后端] 账单状态流转 + 平台账单列表（I1/I2/I4）
- 模块：eaiselp-data（InvoiceService 状态机扩展）+ eaiselp-runtime（InvoiceController 补全）
- 类型：Service 状态机 + Controller 查询/流转端点
- 依赖：T7（生成后有数据联调；契约先行）
- 验收（AC-F2.8 + AC-F2.10 平台侧；单测锚点 §9.1-8）：
  - I4 transit：`{target*}` ∈ {issued, paid}，**仅允许下一顺次**（draft→issued、issued→paid）；跳变（draft→paid）/回退（issued→draft）/paid 后再流转一律 **400**；落 issued_time/paid_time；审计 bill_transit（from→to+操作者+时间）
  - I1 列表（bill:view）：period/tenantId/status 筛选，默认 period DESC, id DESC，**平台跨租户通道**（platform_admin tenant 0 + D-5）；平台侧可见 draft
  - I2 详情：全字段 + detail JSON 解析回显；金额千分位由前端渲染
  - 状态机实现防伪造：status/issuedTime/paidTime 无入参绑定（公共约束 3）
- 产物：`InvoiceServiceImpl.java`（增 transit/查询）、`InvoiceController.java`（补 I1/I2/I4）；测试（锚点 8）

### T10 [后端] 租户费用中心（C1~C3，draft 隔离）
- 模块：eaiselp-data（CostCenterVo 聚合）+ eaiselp-runtime（CostCenterController）
- 类型：Service 聚合读 + Controller
- 依赖：T7（账单数据；契约先行）
- 验收（AC-F2.9/F2.10 + AC-F3.1 SLA 透传侧；单测锚点 §9.1-9/10）：
  - C1 summary：四维用量水位（token/case/derivation/storage，used/limit + token 维 alertThreshold，t_quota 当期行实时水位——**仅展示非账单口径**）+ 当前套餐卡（code/name/monthlyPrice/tokenOveragePrice/slaLevel 或 null=trial/未绑定；SLA 档透传，文案前端集中 D-17）
  - C2：本租户账单 IPage period DESC；**强制 status IN (issued, paid)——draft 对租户不可见**（拦截器隔离之外第二道语义过滤，构造 issued/paid/draft 三行 → 仅 2 行可见断言）
  - C3：本租户详情 + 超量明细展开（tokenUsed/tokenLimit/超量 token/ktokenBilled/折算参数四元组）；**draft 或他租户 → 404**
  - 信息隔离：CostCenterVo/InvoiceVo 序列化无 "cost" 字段（锚点 10 显式断言）
- 产物：`data/service/subscription/dto/CostCenterVo.java`；`runtime/controller/CostCenterController.java`（`/api/v1/cost-center`，契约 C1~C3）；测试（锚点 9/10）

### T11 [后端] 事故域 CRUD（N1~N4，IncidentService）
- 模块：eaiselp-data（service/subscription）+ eaiselp-runtime（IncidentController）
- 类型：Entity 已随 T3 建 + Service + Controller（轻量无状态机）
- 依赖：仅 T0（表）——**零套餐依赖，批A 即启**
- 验收（AC-F3.2/F3.3/F3.4/F3.5 代码侧；单测锚点随 §9.1-20 场景）：
  - N2 创建：title/occurredTime/impactDesc 必填（缺 400）；**recoveryTime ≥ occurredTime（倒挂 400，防脏数据）**；slaBreach 可选缺省 0（V8 增列 D-4）；审计 incident_create
  - N4 编辑：补记 recovery_time/root_cause 主场景（补记前"未恢复"/补记后"已恢复"由 VO `recovered` 布尔承载——recovery_time 非空）；审计 incident_update detail 含 old→new
  - N1 列表（incident:view 全五角色）：默认 occurred_time DESC（8-20→8-10→8-01 断言）；recovered=true/false 筛选（"未恢复"仅返回未恢复行）
  - 租户隔离由拦截器承载（AC-F3.5——他租户 404/空）；**无 DELETE 端点**（D-14）
- 产物：`data/service/subscription/IncidentService.java` + impl；`dto/IncidentVo.java`；`runtime/controller/IncidentController.java`（`/api/v1/incidents`，契约 N1~N4）；测试 `IncidentServiceImplTest.java`

### T12 [前端][配置] governance-dict.js 追加 5 组字典（含 SLA 承诺文案唯一集中处）
- 模块：eaiselp-web-separate
- 类型：前端公共资产增量（P6/D-17/G11 前端侧）
- 依赖：无（可与后端全程并行）
- 验收：追加 planCode(4)/planEnabled(2: 在架/下架——**字段名随 V8 enabled，非 SE §7.2 的 planStatus**，差异定稿 D-1)/slaLevel(3，**含 bronze/silver/gold 响应+可用性承诺文案——全站唯一集中处**，D-17：bronze 工作日≤8h/99.0%、silver 工作日≤4h/99.5%、gold 7×24≤2h/99.9%)/invoiceStatus(3: draft/issued/paid)/incidentRecovered(2) 文案+徽章样式映射；五新页禁止散落定义枚举文案与 SLA 承诺（Reviewer 对照检查）
- 产物：`assets/js/governance-dict.js`（改）

### T13 [前端] plan-list.html 套餐管理页
- 模块：eaiselp-web-separate
- 类型：新页面（骨架对齐 standard-list.html；platform_admin 菜单组）
- 依赖：T12（字典）；契约 P1~P4（后端联调随 T3）
- 验收（PRD §4.3/AC-F1.1~F1.4 前端侧）：列表（code/名称/月价/超量单价/SLA 档/在架徽章，code+状态筛选）+ 创建/编辑弹窗——**features 结构化表单**（数字输入 ×7 + 层开关 checkbox ×2 + 档位多选 chip【真实档位名 opus/sonnet/haiku/reasoning/structured/mechanical/code】，不裸编辑 JSON）+ **custom↔edition_override 联动**（custom 显示下拉、非 custom 隐藏提交 null）+ 上下架切换（P4 enabled）；金额输入前端先拦非数字/负数、后端 400 兜底；引 sanitize.js 字段级转义
- 产物：`pages/plan-list.html`

### T14 [前端] tenant-list.html 租户列表页（§0.3-3 磁盘事实增量，最小新建）
- 模块：eaiselp-web-separate
- 类型：新页面（**PRD F1.3 假设存在、磁盘 37 页实无——SE §0.3-3 消解：新建最小页，已向编排者/UX 报备**）
- 依赖：T12；契约 U2/U1 扩展 + P1（在架套餐下拉）
- 验收（PRD §4.3/US-2 前端侧）：租户列表（编码/名称/edition/plan_code/到期/status）+ 行内「订阅变更」弹窗（选在架套餐 P1 enabled=true → 确认应用 U2 传 planCode → 回显 U1 扩展快照 edition/到期/套餐/SLA 档）；trial 租户行红标临期提示（U1 daysLeft 消费）；后端零增量（数据走既有租户通道/U1）；引 sanitize.js
- 产物：`pages/tenant-list.html`

### T15 [前端] invoice-list.html 平台账单页
- 模块：eaiselp-web-separate
- 类型：新页面（platform_admin 菜单组）
- 依赖：T12；契约 I1~I4
- 验收（PRD §4.5/AC-F2.8 前端侧）：筛选（账期月选择器/租户下拉/状态）+ 列表（账期/租户/套餐/用量摘要/基础费【proration>0 显示折算价】/超量费/应缴**千分位**/状态徽章/流转按钮——"标记已出账"仅 draft、"标记已收款"仅 issued）+ 详情抽屉（detail 展开：套餐五字段快照 + 用量明细 + 折算参数四元组）+ 「补生成」入口（选账期确认弹窗，I3，展示 processed/skipped/failed 汇总）；引 sanitize.js
- 产物：`pages/invoice-list.html`

### T16 [前端] cost-center.html 费用中心
- 模块：eaiselp-web-separate
- 类型：新页面（tenant_admin 菜单）
- 依赖：T12；契约 C1~C3
- 验收（PRD §4.6/AC-F2.9/F3.1 前端侧）：四维用量卡（token/case/派生/存储 used/limit 水位条；token 维接近/超配额醒目提示按 alertThreshold）+ 当前套餐卡（名称/月价/超量单价/SLA 档徽章 + **承诺文案两行——governance-dict.js slaLevel 组渲染**）+ 历史账单列表（仅 issued/paid——后端双重过滤，前端不重复实现）+ 行展开超量明细（各维用量/配额/超额/计费千 token 数/折算参数）；金额千分位；引 sanitize.js
- 产物：`pages/cost-center.html`

### T17 [前端] incident-list.html 事故台账
- 模块：eaiselp-web-separate
- 类型：新页面（COMMON_MENUS 全角色）
- 依赖：T12；契约 N1~N4
- 验收（PRD §4.8/AC-F3.2/F3.3 前端侧）：列表（标题/发生时间/影响/恢复状态徽章 已恢复/未恢复/根因摘要/违约标记，默认发生时间倒序 + 恢复状态筛选）+ 登记/编辑弹窗（恢复时间/根因可后补——AC-F3.2 补记场景；恢复时间≥发生时间前端先拦）；**写按钮按角色隐藏**（incident 写仅 TA/PM/platform_admin，gov-write 先例），后端 403 兜底；引 sanitize.js
- 产物：`pages/incident-list.html`

### T18 [前端][配置] menu.js 菜单挂载
- 模块：eaiselp-web-separate
- 类型：配置
- 依赖：T13~T17（五页文件存在）
- 验收：ROLE_MENUS.platform_admin +3（套餐管理 plan-list / 平台账单 invoice-list / 租户管理 tenant-list）；tenant_admin +1（费用中心 cost-center）；COMMON_MENUS +1（事故台账 incident-list——incident:view 全角色只读，写按钮页内角色隐藏）；**layerHidden 零改动**（不限层 AC-G3）
- 产物：`assets/js/menu.js`（改）

### T19 [测试] RBAC/幂等/跨租户/不限层集成收口 + 656 存量全量回归
- 模块：eaiselp-runtime
- 类型：集成测试与回归（SE §9.1-18~22 + §9.2）
- 依赖：T1~T11（全部后端）+ T18
- 验收（AC-G1/G2/G3 + AC-F1.9/F2.10/F3.4/F3.5 集成侧）：
  - **权限注解反射断言矩阵**（锚点 18）：16 端点 → @RequirePermission 逐格对照 SE §6 表（P1/P3=plan:view；P2=plan:create；P4=plan:edit；I1/I2=bill:view；I3/I4=bill:manage；C1~C3=cost:view；N1/N3=incident:view；N2=incident:create；N4=incident:edit）；**U2 沿用 JWT roles 显式校验断言**（不新增权限原子）
  - **V8 幂等重放**（锚点 19/AC-G1/G2）：干净库 V1~V8 → 权限 9 行/授权 18 行/套餐 seed 3 行/回填绑定正确（trial 未绑、标准档已绑）；重放一次行数不变
  - **跨租户与 403 矩阵**（锚点 20）：租户 B 查 A 账单/事故 404/空；TA/PM/engineer 套餐 CRUD 与应用 403（AC-F1.9）；TA/PM/engineer 平台账单列表/标记收款 403（AC-F2.10）；engineer/executive 费用中心 403 + 事故写 403（AC-F3.4）；**executive 费用中心 403（裁决 Q5）**
  - **AC-G3 不限层**（锚点 21）：两层层开关全关 → 费用中心/事故/账单查询完整可用，无 43001/43002；LayerGuard 前缀零注册断言
  - **信息隔离断言**：InvoiceVo/detail JSON 序列化 grep "cost" 无命中（§8.4）
  - **656 存量测试全量回归零失败**（改动面：TenantController 入参扩展/RuntimeController 挂点/IGNORE_TABLES 加 t_plan/ResultCode 加 40004——全部向后兼容，R6 兜底验证）；回归执行记录由 QA 承接落 `docs/测试报告/`
- 产物：`eaiselp-runtime/src/test/java/com/eaiselp/runtime/controller/`（反射矩阵 + 集成测试类）

---

## 给 QA 的断言标注（27 AC 中需特殊注意的口径——消解表与 V8 差异定稿，防"实现正确但用例 FAIL"假缺陷）

| # | 主题 | QA 用例口径（权威） |
|---|---|---|
| 1 | **Q6 折算覆盖 B4（最大风险 R1）** | AC-F2.2 第二构造**不得按 PRD 原文断言整月 999.00**：8-20 新建即付费绑 pro → 8 月账单 `prorationAmount = 999.00 × 12 ÷ 31 = 386.71`（剩余天数=当月 31−20+1=12 **含创建当天**，先乘后除 HALF_UP 2 位）+ `totalAmount = 386.71 + 超量费`；detail 含折算参数（createdDate/remainingDays=12/daysInMonth=31/prorated=true）。**期中升降级仍按期末档整月计价**（B1 不变）；trial 转正赠送（B2/B3）优先于折算 |
| 2 | **恒等式与字段名（V8 定稿 D-2）** | 落库恒等式 **`totalAmount = (prorationAmount > 0 ? prorationAmount : planPrice) + overageAmount` 恒成立**（D-4 的 V8 形态：base 恒 2 位，HALF_UP(base+overage 全精度) ≡ base+HALF_UP(overage)）；断言字段名 `totalAmount/prorationAmount/overageAmount/usageTokens/overageTokens`（V8 列），**不是 SE 方案 §3.2 的 amountDue/baseFee/usageDetail**；detail 为单 JSON 列（结构见 api-contracts.md 附 A） |
| 3 | **(12,6) 单价精度** | token_overage_price DECIMAL(12,6)：AC-F2.5 的 0.335 必须以 6 位小数原样存取（0.335000），构造入参 0.335 → overageAmount=1.01 → totalAmount=101.00；若断言失败先查入参通道是否被舍成 0.34（提示稿 (14,2) 会截断使 101.00 变 101.01——DBA R2 已纠偏） |
| 4 | **t_plan IGNORE_TABLES（R6）** | Java 侧遗漏则 t_plan 查询被拦截器注入 tenant_id 条件 → 平台级行（tenant_id=0）查不到 → P1 空列表/套餐应用 40400。**QA 冒烟第一查：platform_admin 登录 GET /api/v1/plans 返回 3 条 seed**（101~103） |
| 5 | **model_tiers 真实档位名（DBA R5）** | AC-F1.7 构造**不用 PRD 示意名 high/mid/low**，用真实档位 opus/sonnet/haiku（seed pro = [sonnet,haiku]）：绑 pro 后请求 opus → 40004、sonnet → 放行；未绑定 trial → 任意放行；值域以 t_model_routing 为准 |
| 6 | **draft 隔离双语义** | C2 强制 status IN (issued,paid)（三行构造仅 2 行可见）；C3 直查 draft → **404**（拦截器隔离之外的第二道过滤）；平台侧 I1/I2 可见 draft |
| 7 | **B3 期初判定 fallback（R2）** | 审计缺失时保守分支：出账时 trial → 不出账；出账时付费 + create_time < 期初 → 正常出账（整月或折算）；均 WARN 可核查。**dogfooding 存量租户 V8 前无审计 → fallback 即主路径，上线首月重点核对** |
| 8 | **成功终态与账期归属** | 计入口径 status='success'（V1 schema+DerivationEngine 磁盘事实）；账期 [1 日 00:00, 次月 1 日 00:00) 左闭右开；started_at NULL → fallback create_time；running/failed 一律不计；t_quota.token_used 仅水位不作账单口径 |
| 9 | **上限镜像（D-4/D-6）** | totalAmount > 999,999,999,999.99 → **400 指名"超出 DECIMAL(14,2) 域"非 500**（V8 注释 99,999,999,999.99 少一位 9 属笔误，按数学域上限断言；构造 1e12）；出账任务中进 failed 清单不中断 |
| 10 | **U2 互斥与兼容** | planCode+edition 同传 → 400；三者全空 → 400；不传 planCode → 与 case-20260820 基线逐分支一致（存量用例零改动）；**U2 裸通道不动 plan_code**（D-13） |
| 11 | **enabled 字段（D-1）** | 套餐上下架字段是 `enabled`（1/0，Boolean 出参），非 status；"标准档同 code 在架唯一"判据 enabled=1（下架后可再建同 code，复活到在架时查重 400） |
| 12 | **executive 费用中心（裁决 Q5）** | executive 调 C1~C3 → 403（费用是管理层信息）；executive 事故只读 200 |

其余 AC（F1.1/F1.5/F1.6/F2.8/F3.1/F3.2/F3.3 等）按 PRD 原文 + api-contracts.md 直接写用例。

---

## 变更影响评估

- **架构变更：是（模块化单体内轻度扩展）**——eaiselp-data 新增 `service/subscription` 子包（entity 平铺 + 计算器 + 三服务）、eaiselp-runtime 新增 `subscription` 包（应用编排 + 定时宿主 + 独立 billingExecutor 线程池）；既有代码改动仅四处且全部向后兼容（EaiselpTenantHandler 常量追加 t_plan / TenantController U2 入参扩展 / RuntimeController 派生挂点 / ResultCode 加 40004）。依赖方向 runtime→data 既有（P3 无新方向）；启动类/@MapperScan/LayerGuard/GlobalExceptionHandler/AsyncConfig 零改动；新增平台内首个 @Scheduled 月度出账任务（无新中间件/新协议/部署拓扑不变）。
- **需求调整：是**——新增商用化功能域（套餐 F1/账单 F2/SLA+F3 全量，PRJ-006 收口、PRG-001 章程闭口）；对既有行为两处扩展：U2 订阅变更（向后兼容）、派生三入口新增 40004 校验（防御放行不误伤主链路）。
- **接口契约变更：是（纯新增，无破坏）**——新增 15 端点 + U1/U2 向前兼容扩展（新增字段不删不改，G14 不升版本）；新增唯一业务码 40004；权限 9 原子挂 @RequirePermission + U2 沿用 JWT 显式校验（与存量断言兼容）。
- **编码规范变更：否**——CLAUDE.md 与 ES-001/002/003 未修改；本 case 严格遵循（G13 禁 @InterceptorIgnore/G14 v1 前缀/G11 无角色硬编码/V8 幂等惯例）。
- **文档更新建议**：
  - [不需更新] `D:\AI\mywork\platform\CLAUDE.md` — 原因：模块清单/分层规范/门禁无变化（team-standards 治理，无规范级变更）
  - [不需更新] `D:\AI\mywork\platform\README.md` — 原因：服务级概览（服务清单/端口/快速开始）不变，非项目级架构变化
  - [需更新（轻微）] `D:\AI\mywork\eaiselp-web-separate\README.md` — 原因：governance-dict.js 描述行补 5 组新字典字样（随 T12 交付顺手完成；pages/ 为示例性列举非全量清单）
  - [需更新] `D:\AI\mywork\platform\docs\过程跟踪文档\changelog.md`（共享区） — 原因：ES-002 §8.2 工程级共享文档，case 收尾记录交付（Ops/PM 动作，非 Dev 任务）
  - [不需更新] `docs\架构文档\*`（ADR/工程标准/质量门禁） — 原因：无 ADR 级决策（包归属/表结构/口径均 SE 方案+裁决已承载）
  - [不需更新] Swagger/OpenAPI — 原因：M1 未引入 springdoc（CLAUDE.md §9.4，SP-2/3 落地）；api-contracts.md 即本 case 接口权威
  - [不需更新] PRD/裁决/技术方案/数据库设计 — 原因：本 case 过程产物已定稿；**PRD v1.1 勘误（B4 措辞 + AC-F2.2 第二构造 386.71）由 PO 承接跟踪，不停等交付**（SE §0.3-1 已请求，非 Dev/QA 任务）
- **README 更新清单（本次改动涉及的目录逐个判断）**：
  - [需更新（轻微）] `D:\AI\mywork\eaiselp-web-separate\README.md` — 原因：如上，dict 描述行
  - [不需更新] `eaiselp-data/`、`eaiselp-runtime/`、`eaiselp-common/` — 原因：无模块 README（模块说明由 CLAUDE.md §3 承载），按现状不新增
  - [不需更新] 根 `D:\AI\mywork\platform\README.md` — 原因：非项目级变化

## 范围外（显式声明，防镀金；均承 PRD §7 + 裁决，不设"转后续"暗债）

支付网关/在线支付/自动扣款（§7-1，paid=人工标记）；发票税务（§7-2）；多币种汇率（§7-3）；SLA 自动可用率计算与监控集成（§7-4）；SLA 违约赔付/服务积分（§7-5——sla_breach 仅人工台账标记）；按量后付费（§7-6——整月月价+token 超量+Q6 首月折算是唯一计费模型）；case/派生/存储超量计费（§7-7，Q3 演进）；欠费自动停服（§7-8——人工经 U2/订阅变更）；租户自助购套餐/下单（§7-9）；账单 PDF 导出/邮件推送/作废重开（§7-10）；executive 费用中心（裁决 Q5 本期不开）；跨时区部署（D-16 单时区约定）；**平台内部成本 t_derivation.cost 永久禁入租户可见面**（§6-3 禁令，非范围项）。

**平台级技术债报备（非本 case 范围，需编排者+用户确认排期）**：无新增债务立项；既有 V7+ 债务"GlobalExceptionHandler 补 HttpMessageNotReadableException → 400 平台级兜底"仍挂账（先例 case-20260821 已报备）——本 case 金额入参 BigDecimal 承载继续规避（公共约束 2），不扩面实施。

---

## 本次经验沉淀

1. **计费类 case 的"SE 方案→DBA 落盘"差异比普通 CRUD 高一个量级，BA 必须逐列比对而非抽查**：本 case DBA 将 t_invoice 列名体系整体更名（base_fee/amount_due/usage_detail → proration_amount/total_amount/detail）+ 上下架 status→enabled——若 Dev 按 SE §5 出参、QA 按 PRD 字段断言、契约按方案旧名，三方错位且编译期不可见。规则：凡含计算列/状态列的表，BA 逐列产出差异定稿表（本清单拆解声明 4），并同步改写契约字段名与 QA 恒等式断言（total = base + overage 的 V8 形态）。
2. **"裁决覆盖 PRD"在 BA 侧要三落点，缺一即口径分裂**：任务验收行锁锚点值（T1/T7 的 386.71 构造）、QA 标注改断言（AC-F2.2 第二构造禁按原文整月）、契约字段随 DBA 承载列定名（prorationAmount）——消解表只在 SE 方案里孤悬时，Dev 与 QA 仍会各自发明"剩余天数含不含创建当天"这类细节（先例 case-20260821 经验 3 的计费版）。
3. **"迁移管不了代码"的断点要在拆解时独立成任务并置顶冒烟**：平台级表进 IGNORE_TABLES 属 Java 常量（DBA 只能注释标注），遗漏症状（查询空/40400）离根因（拦截器注入 tenant_id）很远——本次将 T2 独立为半日级前置任务并把"platform_admin 查 seed 返 3 行"设为 QA 冒烟第一查；同模式的还有测试开关（application-test.yml scheduler-enabled）与菜单挂载，均须显式任务承载，不靠 Dev 记性。
