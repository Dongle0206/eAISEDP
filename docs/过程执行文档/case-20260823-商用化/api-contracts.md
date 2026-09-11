# 接口契约 — case-20260823-商用化（16 端点 = 15 新 + 1 既有扩展）

| 字段 | 值 |
|---|---|
| 产出者 | team-ba（L1 需求分析员） |
| 基线 | SE 技术方案 §5（权威）；**DBA V8 落盘 DDL（字段名以 V8 列名为准——prorationAmount/totalAmount/enabled 等，见 tasks.md 拆解声明 4 差异定稿表）**；PRD §4 口径 + 编排者裁决 Q1~Q6 + §0.3 消解表 |
| 消费方 | team-dev（端点实现唯一契约）、前端任务 T13~T17（直接引用编号 P1~P4 / U2 / I1~I4 / C1~C3 / N1~N4）、team-qa（接口级断言） |
| 编号约定 | P=Plan 套餐 / U2=既有订阅端点扩展（沿 case-20260820 编号）/ I=Invoice 平台账单 / C=CostCenter 费用中心 / N=iNcident 事故；**与 tasks.md 任务号（T1~T19）不同域** |

## 0. 通用约定

- **前缀**：全部 `/api/v1/`（G14）；由 eaiselp-runtime 进程暴露（M1 模块化单体）；五个前缀（plans / tenant/{id}/subscription / invoices / cost-center / incidents）**均不注册**进 LayerGuardInterceptor（不限层，AC-G3）。
- **鉴权**：全部需 JWT（`Authorization: Bearer <token>`）；`@RequirePermission("<code>")` + PermissionInterceptor 承载（40301）。**唯一例外：U2 沿用 TenantController 既有 JWT roles 显式校验（platform_admin 专属，F3 先例，不新增权限原子）**。
- **tenant_id**：一律取自 TenantContext（JWT 解析），禁止请求参数传入（防伪造，ES-003 §9.3-4）；跨租户资源访问 → 40400。**例外通道：I1~I4 平台账单走 platform_admin（tenant 0 = SYSTEM）跨租户通道**（D-5，拦截器全放行）。
- **统一响应** `R<T>`：

```json
{ "code": 200, "message": "success", "data": { } }
```

- **分页** `IPage<Vo>`：请求 `page`（默认 1）/`size`（默认 20）；data = `{ "records": [...], "total": n, "size": 20, "current": 1, "pages": n }`。
- **错误码（唯一新增 40004）**：

| code | 语义 | 典型触发 |
|---|---|---|
| 40000 | 参数缺失/格式错误 | 必填缺失、时间格式非法、period 非 yyyy-MM |
| 400 | 业务校验失败 | code/sla_level 枚举非法（指名字段+合法值集）、金额负值/**超 DECIMAL 域上限**（指名，D-4 镜像）、features 结构校验失败、标准档同 code 在架重复、custom↔editionOverride 双向校验、uk 冲突（"...已存在: ..."）、U2 互斥/全空、账单非法流转（跳变/回退/paid 后流转）、事故恢复时间倒挂、套餐下架不可应用 |
| 40003 | 试用已到期 | 既有（trial 拦截链路，本 case 零改动） |
| **40004** | **模型档位不在当前套餐范围内** | **本 case 新增唯一业务码**：绑定套餐租户派生请求 tier ∉ features.model_tiers（D-10） |
| 40101 | 未登录/token 失效 | — |
| 40301 | 无权限 | @RequirePermission 不通过（矩阵见附 B） |
| 40400 | 资源不存在（含跨租户） | — |

- **金额约定**：单位元；月价/超量单价/账单三金额出参均 BigDecimal（VO 序列化数字）；**金额/单价入参 DTO BigDecimal 承载**（禁 float/double，提交非法值须 400 指名不得 50000）；千分位由前端渲染。**服务端字段防伪造**：usageTokens/overageTokens/overageAmount/prorationAmount/totalAmount/status/issuedTime/paidTime 不出现在任何入参模型（提交即被反序列化丢弃）。
- **period 账期键**：`yyyy-MM`（CHAR(7)，与 t_quota.period 同构）；账期区间 [当月 1 日 00:00, 次月 1 日 00:00) Asia/Shanghai；时间格式 `yyyy-MM-dd HH:mm:ss`。
- **写操作全部落审计**（t_governance_log，action 清单见 SE §8.1）；账单 createBy = 生成来源（定时任务固定 `billing-scheduler`，手动补生成为操作者账号）。

---

## 1. 套餐 — `/api/v1/plans`（PlanController；T3 交付 P1~P4；platform_admin 专属）

### P1: GET /api/v1/plans — 套餐分页列表
- 鉴权：plan:view（platform_admin）
- 入参：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| page / size | long | 否 | 默认 1/20 |
| code | String | 否 | starter/pro/enterprise/custom 筛选 |
| enabled | Boolean | 否 | true=在架 / false=下架（**V8 字段名，非 status**） |
| keyword | String | 否 | name LIKE |

- 出参：`IPage<PlanVo>`，默认 id DESC；features 结构化回显

```json
{ "code": 200, "message": "success", "data": {
  "records": [ { "id": 102, "code": "pro", "name": "专业版", "monthlyPrice": 999.00,
    "tokenOveragePrice": 8.000000, "slaLevel": "silver", "enabled": true,
    "editionOverride": null,
    "features": { "maxUsers": 50, "maxCases": 100, "tokenLimit": 5000000, "caseLimit": 100,
      "derivationLimit": 1000, "storageLimitMb": 51200,
      "layerOverrides": { "strategyEnabled": 1, "programProjectEnabled": 1 },
      "modelTiers": ["sonnet", "haiku"] },
    "createBy": "system-seed", "createTime": "2026-09-09 10:00:00" } ],
  "total": 3, "size": 20, "current": 1, "pages": 1 } }
```

- 错误码：40101 / 40301
- QA 冒烟锚点：platform_admin 登录返回 3 条 seed（101~103）——IGNORE_TABLES 遗漏即空列表（tasks.md QA 标注 4）

### P2: POST /api/v1/plans — 创建套餐（enabled 缺省 true）
- 鉴权：plan:create
- 入参（body JSON）：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| code | String | 是 | starter/pro/enterprise/custom；非法 → 400 指名（AC-F1.2） |
| name | String | 是 | ≤200；uk_plan_name 冲突 → 400 |
| monthlyPrice | BigDecimal | 是 | ≥0 且 ≤ 999,999,999,999.99；=0 合法（免费档，AC-F1.3） |
| tokenOveragePrice | BigDecimal | 是 | ≥0 且 ≤ 999,999.999999（**DECIMAL(12,6)，6 位小数**——0.335 合法原样存取） |
| slaLevel | String | 是 | bronze/silver/gold；空/枚举外 → 400（AC-F3.1） |
| editionOverride | String | 条件 | **custom 必填 ∈{starter,pro,enterprise}；非 custom 必须为空**（双向 400，D-9） |
| features | Object | 是 | 必需键 maxUsers/maxCases/tokenLimit/caseLimit/derivationLimit/storageLimitMb/layerOverrides{strategyEnabled,programProjectEnabled}/modelTiers[] + 值域校验（modelTiers 值域=真实档位名 opus/sonnet/haiku/reasoning/structured/mechanical/code）；**未知键忽略**（向前兼容）；缺必需键/值域非法 → 400 |
| enabled | Boolean | 否 | 缺省 true |

- 语义：**标准三档（starter/pro/enterprise）同 code 在架（enabled=true）至多一份 → 重复 400**（含 disabled 复活场景：目标态 true 时查重）；custom 可多份（名称不同即合法，AC-F1.2）；审计 plan_create
- 出参：`R<PlanVo>`
- 错误码：40000 / 400（枚举/金额/结构/uk/唯一性/custom 联动）/ 40301

### P3: GET /api/v1/plans/{id} — 套餐详情
- 鉴权：plan:view
- 出参：`R<PlanVo>` 全字段（平台级表，tenant 0 上下文直查）
- 错误码：40400 / 40301

### P4: PUT /api/v1/plans/{id} — 编辑套餐（全量，含上下架）
- 鉴权：plan:edit
- 入参：同 P2（全量；enabled 传 false 即下架）
- 语义：下架后不可被新租户应用（U2 → 400，AC-F1.4），**存量绑定租户不受影响**（次月出账仍按账单快照/期末档）；**编辑改价/改配额不回写已生成账单与已绑定租户当期 t_quota**（商品目录语义，下次应用才覆写）；审计 plan_update（detail 含 old→new 关键字段）
- 出参：`R<PlanVo>`
- 错误码：400（同 P2 校验族）/ 40400 / 40301
- 注：**无 DELETE 端点**（D-14，上下架覆盖全生命周期）

---

## 2. U2 扩展（既有端点，零新端点）— `PUT /api/v1/tenant/{id}/subscription`（TenantController；T4 交付，T5 兼容回归）

### U2: PUT /api/v1/tenant/{id}/subscription — 订阅变更（套餐应用 = 试用转正/升降级标准路径）
- 鉴权：**JWT roles 显式校验 platform_admin**（既有形态，不新增权限原子；TA/PM/engineer → 403，AC-F1.9）
- 入参（body JSON；**分叉点唯一 = planCode 是否为空**，D-8）：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| planCode | String | 否 | 套餐应用模式：**非空时 edition/expireTime 必须为空（同传 → 400 互斥）**；指向在架套餐（enabled=true 且未逻辑删），不存在 → 40400，下架 → 400 |
| edition | String | 否 | 既有单字段语义（仅 planCode 为空时合法） |
| expireTime | String | 否 | 同上（空串=置空既有语义） |

- 语义：**planCode 为空 → 既有 updateSubscription 路径逐行为零变化（AC-F1.8，存量用例零改动；且不动 plan_code 列，D-13）**；三者全空 → 400。
- planCode 非空 → `SubscriptionApplyService.applyPlan` **单事务五类落库**（任一步失败全部回滚，无半应用状态，AC-F1.5）：① t_tenant.edition（三档=code / custom=editionOverride）② t_tenant.plan_code 绑定 ③ expire_time 置空 ④ t_quota 当期四限 + monthly_cost=套餐月价覆写（行不存在按模板 upsert）⑤ 层开关按 layerOverrides 覆写 ×2（说开就开/说关就关）；模型档位经绑定即时生效（enforcement 见 40004）。审计 tenant_edition_change（沿用既有 action，detail 扩展 old→new 五类快照 + 操作者，AC-F1.6/B3 数据源）。
- 出参：`R<SubscriptionStatus>`（**向前兼容扩展三字段**，既有六字段零变化）

```json
{ "code": 200, "message": "success", "data": {
  "edition": "pro", "editionName": "专业版", "expireTime": null,
  "daysLeft": null, "expired": false, "trial": false,
  "planCode": "pro", "planName": "专业版", "slaLevel": "silver" } }
```

- 错误码：40000 / 400（互斥/全空/下架）/ 40400（租户或套餐不存在）/ 40301
- 关联：**U1 `GET /api/v1/tenant/subscription` 出参同步扩展** planCode/planName/slaLevel 三字段（向前兼容；tenant-list.html 回显数据源）

---

## 3. 平台账单 — `/api/v1/invoices`（InvoiceController；T7 交付 I3、T9 交付 I1/I2/I4；platform_admin 专属，tenant 0 跨租户通道 D-5）

### I1: GET /api/v1/invoices — 平台账单分页列表（跨租户）
- 鉴权：bill:view
- 入参：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| page / size | long | 否 | 默认 1/20 |
| period | String | 否 | yyyy-MM 筛选 |
| tenantId | Long | 否 | 叠加租户筛选 |
| status | String | 否 | draft/issued/paid |

- 出参：`IPage<InvoiceVo>`，默认 `period DESC, id DESC`；**平台侧可见 draft**；金额千分位前端渲染

```json
{ "code": 200, "message": "success", "data": {
  "records": [ { "id": 9001, "tenantId": 2, "period": "2026-08", "planCode": "pro",
    "planPrice": 999.00, "usageTokens": 6001500, "overageTokens": 1001500,
    "overageAmount": 8016.00, "prorationAmount": 0.00, "totalAmount": 9015.00,
    "status": "issued", "issuedTime": "2026-09-02 10:00:00", "paidTime": null,
    "createBy": "billing-scheduler", "createTime": "2026-09-01 02:00:05" } ],
  "total": 1, "size": 20, "current": 1, "pages": 1 } }
```

- 错误码：40101 / 40301（TA/PM/engineer/executive 调用——QA 断言点，AC-F2.10）

### I2: GET /api/v1/invoices/{id} — 账单详情（平台侧）
- 鉴权：bill:view
- 出参：`R<InvoiceDetailVo>` = InvoiceVo 全字段 + `detail` 解析（套餐五字段快照 + 用量明细 + 折算参数，结构见附 A）；**detail 任何位置不得出现 t_derivation.cost**（信息隔离红线）
- 错误码：40400 / 40301

### I3: POST /api/v1/invoices/generate — 手动补生成（与定时任务同一 Service 入口）
- 鉴权：bill:manage
- 入参（body JSON）：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| period | String | 是 | yyyy-MM（账期），格式非法 → 40000 |
| tenantId | Long | 否 | 缺省 = 全量该账期所有租户；指定则单租户补生成 |

- 语义：幂等口径 D-7——已存在 **draft → 重算覆盖**（用量补记后金额更新）；**issued/paid → 不覆盖 + WARN**；uk(tenant_id, period) 保证无第二行；判定序 B2/B3/D-13/Q6 与定时完全同源（AC-F2.7）；审计 bill_generate
- 出参：`R<GenerateSummaryVo>`

```json
{ "code": 200, "message": "success", "data": {
  "processed": 12, "skippedTrial": 3, "skippedNoPlan": 1, "overwritten": 2,
  "failed": [ { "tenantId": 7, "reason": "超出 DECIMAL(14,2) 域" } ] } }
```

- 错误码：40000 / 40301

### I4: POST /api/v1/invoices/{id}/transit — 账单状态流转（人工标记）
- 鉴权：bill:manage
- 入参：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| target | String | 是 | `issued` / `paid`；空 → 400 |

- 语义：**仅允许当前状态的下一顺次**（draft→issued、issued→paid）；draft→paid 跳变 / issued→draft 回退 / paid 后再流转一律 **400**（AC-F2.8）；流转落 issuedTime/paidTime；审计 bill_transit（from→to + 操作者 + 时间）
- 出参：`R<InvoiceVo>`（新状态）
- 错误码：400（三类非法流转）/ 40400 / 40301

---

## 4. 费用中心 — `/api/v1/cost-center`（CostCenterController；T10 交付 C1~C3；tenant_admin + platform_admin）

### C1: GET /api/v1/cost-center/summary — 本期用量与当前套餐
- 鉴权：cost:view（**executive 403——裁决 Q5**；engineer 403）
- 入参：无（tenant_id 取 TenantContext）
- 出参：`R<CostCenterVo>`——四维实时水位（t_quota 当期行，**仅展示非账单口径**）+ 当前套餐卡（trial/未绑定 → plan=null；SLA 档透传，**承诺文案前端 governance-dict.js 集中渲染**，D-17）

```json
{ "code": 200, "message": "success", "data": {
  "quota": {
    "period": "2026-09",
    "token": { "used": 3500000, "limit": 5000000, "alertThreshold": 80 },
    "case": { "used": 42, "limit": 100 },
    "derivation": { "used": 380, "limit": 1000 },
    "storage": { "usedMb": 20480, "limitMb": 51200 } },
  "plan": { "code": "pro", "name": "专业版", "monthlyPrice": 999.00,
    "tokenOveragePrice": 8.000000, "slaLevel": "silver" } } }
```

- 错误码：40101 / 40301（executive/engineer——QA 断言点）

### C2: GET /api/v1/cost-center/invoices — 本租户历史账单
- 鉴权：cost:view
- 入参：page / size
- 出参：`IPage<InvoiceVo>` 本租户 period DESC；**强制 status IN (issued, paid)——draft 对租户不可见**（AC-F2.9 第二道语义过滤；构造 issued/paid/draft 三行仅 2 行可见）
- 错误码：40101 / 40301

### C3: GET /api/v1/cost-center/invoices/{id} — 本租户账单详情 + 超量明细
- 鉴权：cost:view
- 出参：`R<InvoiceDetailVo>` 同 I2 结构（detail 展开：tokenUsed/tokenLimit/tokenOverageTokens/ktokenBilled/derivationCount/caseCount/storageUsedMb/折算参数）
- 语义：**draft 或他租户账单 → 404**（AC-F2.9/F2.10）；金额与平台侧同表同 VO 一致
- 错误码：40400 / 40301

---

## 5. 事故 — `/api/v1/incidents`（IncidentController；T11 交付 N1~N4；view 全五角色）

### N1: GET /api/v1/incidents — 事故分页列表
- 鉴权：incident:view（**全五角色**，COMMON_MENUS 只读）
- 入参：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| page / size | long | 否 | 默认 1/20 |
| recovered | Boolean | 否 | true=已恢复（recovery_time 非空）/ false=未恢复（AC-F3.3 筛选） |

- 出参：`IPage<IncidentVo>`，默认 `occurredTime DESC`（AC-F3.3 倒序断言：8-20→8-10→8-01）；拦截器保证租户隔离（AC-F3.5）

```json
{ "code": 200, "message": "success", "data": {
  "records": [ { "id": 7001, "title": "派生服务超时", "occurredTime": "2026-08-20 14:30:00",
    "recoveryTime": "2026-08-20 16:00:00", "recovered": true, "impactDesc": "三租户派生排队延迟",
    "rootCause": "上游模型服务限流", "slaBreach": 0,
    "createBy": "ta1", "createTime": "2026-08-20 17:00:00" } ],
  "total": 1, "size": 20, "current": 1, "pages": 1 } }
```

- 错误码：40101 / 40301

### N2: POST /api/v1/incidents — 登记事故
- 鉴权：incident:create（tenant_admin / project_manager / platform_admin；engineer、executive 403——AC-F3.4）
- 入参（body JSON）：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| title | String | 是 | ≤200 |
| occurredTime | String(datetime) | 是 | 必填；格式非法 → 40000 |
| impactDesc | String | 是 | ≤1000 |
| recoveryTime | String(datetime) | 否 | 空=未恢复；**非空时须 ≥ occurredTime（倒挂 → 400）** |
| rootCause | String | 否 | ≤1000（恢复后补记） |
| slaBreach | Boolean | 否 | 缺省 false（V8 增列：登记时人工违约判定，纯台账不触发计算） |

- 出参：`R<IncidentVo>`（recovered 布尔由服务端按 recoveryTime 判定）；审计 incident_create
- 错误码：40000 / 400（必填/倒挂）/ 40301

### N3: GET /api/v1/incidents/{id} — 事故详情
- 鉴权：incident:view
- 出参：`R<IncidentVo>` 全字段（拦截器保证租户隔离——他租户 404，AC-F3.5）
- 错误码：40400 / 40301

### N4: PUT /api/v1/incidents/{id} — 编辑事故（补记恢复/根因主场景）
- 鉴权：incident:edit（tenant_admin / project_manager / platform_admin；engineer、executive 403）
- 入参：同 N2（全量）；补记 recoveryTime/rootCause 后 recovered 翻转 true（AC-F3.2 补记前"未恢复"/补记后"已恢复"）；审计 incident_update（detail 含 old→new）
- 出参：`R<IncidentVo>`
- 错误码：400（必填/倒挂）/ 40400 / 40301
- 注：**无 DELETE 端点**（D-14，台账留痕）

---

## 附 A：t_invoice.detail JSON 结构（I2/C3 出参解析口径；DBA V8 单列承载，差异定稿 D-2）

```json
{
  "plan": { "code": "pro", "name": "专业版", "monthlyPrice": 999.00,
            "tokenOveragePrice": 8.000000, "slaLevel": "silver" },
  "tokenUsed": 6001500, "tokenLimit": 5000000,
  "tokenOverageTokens": 1001500, "ktokenBilled": 1002,
  "derivationCount": 380, "caseCount": 42, "storageUsedMb": 20480,
  "firstMonthProration": { "createdDate": "2026-08-20", "remainingDays": 12,
                            "daysInMonth": 31, "prorated": true }
}
```

- `plan` 五字段快照（B1，套餐改价/下架/逻辑删不影响已生成账单）；`firstMonthProration` **仅首月折算账期出现**（Q6/裁决 D-11——非折算账期无此键或 prorated=false）；**任何位置禁止出现 t_derivation.cost**（§8.4 信息隔离，QA 序列化 grep "cost" 断言）。
- **恒等式（QA 落库断言基线）**：`totalAmount = (prorationAmount > 0 ? prorationAmount : planPrice) + overageAmount` 恒成立。

## 附 B：权限原子 → 端点绑定矩阵（T19 反射断言基线，AC-G1/F1.9/F2.10/F3.4）

| 权限原子（seed id） | code | 绑定端点 | 角色 |
|---|---|---|---|
| 1081 / 1082 / 1083 | plan:view / create / edit | P1、P3；P2；P4 | 仅 platform_admin |
| 1084 / 1085 | bill:view / bill:manage | I1、I2；I3、I4 | 仅 platform_admin（bill:manage 含流转+补生成，裁决 Q2） |
| 1086 | cost:view | C1、C2、C3 | platform_admin + tenant_admin（**executive 403——裁决 Q5**；engineer 403） |
| 1087 / 1088 / 1089 | incident:view / create / edit | N1、N3；N2；N4 | view 全五角色；create/edit = platform_admin / tenant_admin / project_manager（engineer、executive 403） |
| —（不新增） | U2 = JWT roles 显式校验 | U2 | 仅 platform_admin（F3 先例，AC-F1.8 存量断言兼容） |

角色授权分布（V8 seed 2203~2220 已落盘）：platform_admin×9（2203~2211 全量）/ tenant_admin×4（2212~2215：cost:view + incident×3）/ project_manager×3（2216~2218：incident×3，无 cost:view——裁决 Q5）/ engineer×1（2219：incident:view）/ executive×1（2220：incident:view）。与 PRD §4.9 权威矩阵逐格一致。
