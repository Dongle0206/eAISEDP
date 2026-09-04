# 接口契约 — case-20260821-L3收口（20 新端点：风险 7 + 合规 5 + 商业案例 8）

| 字段 | 值 |
|---|---|
| 产出者 | team-ba（L1 需求分析员） |
| 基线 | SE 技术方案 §5（权威）；DBA V7 落盘 DDL（列名以 V7 为准——roi_percent 等，见 tasks.md 拆解声明 3）；PRD §4 口径 + §5 AC |
| 消费方 | team-dev（端点实现唯一契约）、前端任务 T6~T8/T14~T17（直接引用编号 R1~R7/C1~C5/B1~B8）、team-qa（接口级断言） |

## 0. 通用约定

- **前缀**：全部 `/api/v1/`（G14）；由 eaiselp-runtime 进程暴露（M1 模块化单体）；三域前缀均**不注册**进 LayerGuardInterceptor（不限层，AC-SWITCH.1）。
- **鉴权**：全部需 JWT（`Authorization: Bearer <token>`），无对外白名单接口；`@RequirePermission("<code>")` + PermissionInterceptor 承载（40301）。
- **tenant_id**：一律取自 TenantContext（JWT 解析），**禁止**请求参数传入（防伪造，ES-003 §9.3-4）；跨租户资源访问 → 40400。
- **统一响应** `R<T>`：

```json
{ "code": 200, "message": "success", "data": { } }
```

- **分页** `IPage<Vo>`：请求参数 `page`（默认 1）/`size`（默认 20）；响应 data = `{ "records": [...], "total": n, "size": 20, "current": 1, "pages": n }`。
- **统一错误码（无新增，裁决 Q9）**：

| code | 语义 | 典型触发 |
|---|---|---|
| 40000 | 参数缺失/格式错误 | 必填缺失、类型不符 |
| 400 | 业务校验失败 | uk 冲突（"...已存在: ..."）、枚举非法（指名字段+合法值集）、P/I/R/I/E 非整数或越界、confidence 非 0.1 步进、金额为负、状态机非法流转（跳级/终态出边/缺必填原因或说明）、编辑受限（终态只读/已批准输入不可改）、关联对象 type 非法或 id 不存在 |
| 40101 | 未登录/token 失效 | — |
| 40301 | 无权限 | @RequirePermission 不通过（如 PM 调 B7） |
| 40400 | 资源不存在（含跨租户） | — |

- **数值入参承载**：body JSON 数值字段（probability/impact/reach/impact/effort/confidence/三金额）服务端 DTO 以 BigDecimal 承载——提交 `1.5`/`0.05` 等可正常到达 Service 并被 400 指名拒绝（**不得 50000**，D-4）。
- **金额单位**：元，DECIMAL(14,2)（裁决 Q2）；前端展示层可做万/亿缩写。
- **计算字段防伪造**：riskValue/riskLevel/netBenefit/paybackYears/roiPercent/riceScore 不出现在任何入参模型（提交即被反序列化丢弃）；create/update 服务端重算覆盖落库（AC-F1.5/F2.4）。
- **`overdue` 布尔**：服务端统一判定（风险 `review_date < today && status != closed`；合规 `recheck_date < today`，**na 不豁免**），前端红标直消费 VO 字段（D-10 防时钟偏差）。
- **写操作全部落审计**（t_governance_log，action 见 SE §8.1）。

---

## 1. 风险登记册 — `/api/v1/risks`（Controller：RiskController；T2 交付 R1~R5、T10 交付 R6、T12 交付 R7）

### R1: GET /api/v1/risks — 风险分页列表
- 鉴权：risk:view
- 入参：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| page / size | long | 否 | 默认 1/20 |
| category | String | 否 | strategy/compliance/operations/technical/security |
| level | String | 否 | low/medium/high/critical |
| status | String | 否 | open/mitigating/closed |
| overdueOnly | Boolean | 否 | 1=仅逾期（SQL 口径 `review_date < CURRENT_DATE AND status <> 'closed'`，AC-F1.7） |
| keyword | String | 否 | risk_name LIKE |

- 出参：`IPage<RiskVo>`，**默认排序 `riskValue DESC, id DESC`（写死，QA 断言用）**

```json
{ "code": 200, "message": "success", "data": {
  "records": [ { "id": 3011, "riskName": "数据泄露", "category": "security", "probability": 4,
    "impact": 5, "riskValue": 20, "riskLevel": "critical", "mitigation": "加密与最小权限",
    "contingencyPlan": "应急响应预案A", "owner": "张三", "status": "open",
    "resolutionNote": null, "reviewDate": "2026-08-01", "overdue": true,
    "createBy": "admin", "createTime": "2026-08-21 10:00:00" } ],
  "total": 1, "size": 20, "current": 1, "pages": 1 } }
```

- 错误码：40101 / 40301

### R2: POST /api/v1/risks — 创建风险（status 固定 open）
- 鉴权：risk:create
- 入参（body JSON）：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| riskName | String | 是 | ≤200；uk(tenant, risk_name) 冲突 → 400（AC-F1.1） |
| category | String | 是 | 枚举，非法 → 400 指名 |
| probability | BigDecimal | 是 | **1~5 整数**：0/6/1.5/负数 → 400 指名（AC-F1.3） |
| impact | BigDecimal | 是 | 同上 |
| description | String | 否 | — |
| mitigation | String | 否 | 缓解措施 |
| contingencyPlan | String | 否 | 应急预案 |
| owner | String | 是 | 自由 VARCHAR ≤64（缺失 → 400，AC-F1.1 Then） |
| relatedObjects | Array | 否 | 元素 `{type, id}`；type∈program/project/case；逐条存在性校验，非法 type/不存在 id → 400 指名；空数组合法（AC-F1.6） |
| reviewDate | String(yyyy-MM-dd) | 否 | 复评日期，可空 |

- 出参：`R<RiskVo>`（status=open；riskValue/riskLevel 服务端算——入参模型无此二字段，提交即丢弃）
- 错误码：40000 / 400（uk/枚举/边界/关联校验）/ 40301

### R3: GET /api/v1/risks/{id} — 风险详情
- 鉴权：risk:view
- 出参：`R<RiskVo>` 全字段 + `relatedObjects: [{ "type": "project", "id": 88, "name": "PJ1", "deleted": false }]`（悬空/已逻辑删 → `"deleted": true` 占位，不 400 不静默丢，AC-F1.6）
- 错误码：40400（不存在/跨租户）

### R4: PUT /api/v1/risks/{id} — 编辑风险（open/mitigating 全字段，编辑即重算）
- 鉴权：risk:edit
- 入参：同 R2（全量）
- 语义：open/mitigating 可编辑，**P/I 变更即触发重算覆盖**（AC-F1.5）；**closed 编辑任意字段 → 400"终态只读"**（AC-F1.4 Then）；非 closed 状态 resolutionNote 置 NULL
- 出参：`R<RiskVo>`（重算后）
- 错误码：400（终态只读/uk/边界/关联）/ 40400 / 40301

### R5: DELETE /api/v1/risks/{id} — 逻辑删除
- 鉴权：risk:edit；审计 risk_delete
- 出参：`R<Void>`；删除后列表不可见
- 错误码：40400 / 40301

### R6: POST /api/v1/risks/{id}/transit — 状态流转
- 鉴权：risk:edit
- 入参：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| target | String | 是 | `mitigating` / `closed` / `open`；空 → 400"target 不能为空" |
| resolutionNote | String | 条件 | target=closed 必填（空 → 400，AC-F1.4） |

- 语义（**§0.3-1 消解：mitigating→open 回退合法=200**；裁决"单向"=终态不可回退）：open→mitigating 200；mitigating→closed（必填说明）200；mitigating→open 200；**open→closed 跳级 400；closed 任何出边 400**；自流转幂等；每次合法流转审计 risk_transit（detail 含 from→to+操作者+resolutionNote）
- 出参：`R<RiskVo>`（新状态）
- 错误码：400（非法流转/说明缺失）/ 40400 / 40301

### R7: GET /api/v1/risks/dashboard — 风险看板聚合（纯只读）
- 鉴权：risk:view（无独立权限原子；写语义请求 405/404 天然——AC-F1.15）
- 入参：无
- 出参：`R<RiskDashboardVo>`——**cells 恰 25 格（仅未 closed 计入，AC-F1.12；X=影响 Y=概率由 cells 元组自描述，前端轴写死）+ 等级分布 + 高风险清单（level∈{high,critical} 未 closed，riskValue DESC, id DESC，overdue 透传，AC-F1.14）**

```json
{ "code": 200, "message": "success", "data": {
  "cells": [ { "probability": 1, "impact": 1, "riskValue": 1, "riskLevel": "low", "count": 0 },
             { "probability": 1, "impact": 2, "riskValue": 2, "riskLevel": "low", "count": 0 } ],
  "levelDistribution": { "low": 1, "medium": 0, "high": 0, "critical": 2 },
  "highRisks": [ { "id": 3012, "riskName": "权限蔓延", "probability": 5, "impact": 4,
    "riskValue": 20, "riskLevel": "critical", "owner": "李四", "status": "mitigating",
    "reviewDate": "2026-08-01", "overdue": true } ] } }
```

- 错误码：40101 / 40301

---

## 2. 合规检查 — `/api/v1/compliance-checks`（Controller：ComplianceCheckController；T3 交付 C1~C5）

### C1: GET /api/v1/compliance-checks — 检查项分页列表
- 鉴权：compliance:view
- 入参：page/size；framework（djba2.0/iso27001/gdpr/custom）；result（pass/fail/partial/na）；overdueOnly（**na 不豁免**，AC-F1.11）；keyword（check_name LIKE）
- 出参：`IPage<ComplianceCheckVo>`，默认 id DESC（PRD 未锁排序，QA 不做排序断言）

```json
{ "code": 200, "message": "success", "data": {
  "records": [ { "id": 4021, "checkName": "访问控制条款核验", "framework": "iso27001",
    "frameworkName": null, "clauseRef": "A.9.4.1", "description": "口令与双因素策略核查",
    "result": "pass", "evidenceNote": "截图归档", "checkDate": "2026-08-20",
    "recheckDate": "2026-09-01", "owner": "王五", "overdue": true,
    "createBy": "admin", "createTime": "2026-08-20 10:00:00" } ],
  "total": 1, "size": 20, "current": 1, "pages": 1 } }
```

- 错误码：40101 / 40301

### C2: POST /api/v1/compliance-checks — 创建检查项（手动登记制）
- 鉴权：compliance:create
- 入参（body JSON）：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| checkName | String | 是 | ≤200；uk(tenant, check_name) 冲突 → 400（AC-F1.8） |
| framework | String | 是 | 枚举 djba2.0/iso27001/gdpr/custom，非法（如 "iso27001:2022"）→ 400 |
| frameworkName | String | 条件 | **custom 时必填（空 → 400）；非 custom 时必须为空（填了 → 400 防脏数据）**（AC-F1.9 四例） |
| clauseRef | String | 否 | ≤128（如 "ISO27001 A.9.4.1"） |
| description | String | 否 | — |
| result | String | 是 | 枚举 pass/fail/partial/na |
| evidenceNote | String | 否 | ≤1000 |
| checkDate | String(yyyy-MM-dd) | 否 | 缺省服务端取当天 |
| recheckDate | String(yyyy-MM-dd) | 否 | 可空 |
| owner | String | 否 | ≤64 |

- 出参：`R<ComplianceCheckVo>`
- 错误码：40000 / 400（uk/枚举/custom 联动）/ 40301

### C3: GET /api/v1/compliance-checks/{id} — 检查项详情
- 鉴权：compliance:view
- 出参：`R<ComplianceCheckVo>` 全字段
- 错误码：40400

### C4: PUT /api/v1/compliance-checks/{id} — 编辑（result 覆盖式更新）
- 鉴权：compliance:edit
- 入参：同 C2（全量）
- 语义：result 变更为**覆盖式单值当前态**（不建历史表）；旧值唯一留痕 = 审计 compliance_update detail 含 **oldResult→newResult + 证据**（AC-F1.10）；非法 result → 400
- 出参：`R<ComplianceCheckVo>`
- 错误码：400（枚举/uk/custom 联动）/ 40400 / 40301

### C5: DELETE /api/v1/compliance-checks/{id} — 逻辑删除
- 鉴权：compliance:edit；审计 compliance_delete
- 出参：`R<Void>`
- 错误码：40400 / 40301

---

## 3. 商业案例 — `/api/v1/business-cases`（Controller：BusinessCaseController；T4 交付 B1~B5、T11 交付 B6/B7、T13 交付 B8）

### B1: GET /api/v1/business-cases — 案例分页列表
- 鉴权：bizcase:view
- 入参：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| page / size | long | 否 | 默认 1/20 |
| status | String | 否 | draft/approved/rejected/executing/done |
| strategyId | Long | 否 | 关联战略筛选（related_strategy_ids **JSON 内存过滤**，分页后过滤——V6 principleCode 口径；战略反向展示复用，D-8） |
| keyword | String | 否 | case_name LIKE |

- 出参：`IPage<BusinessCaseVo>`，**默认排序 `riceScore DESC, id DESC`（AC-F2.10）**（记录结构同 B3，列表可不含长文本字段）
- 错误码：40101 / 40301

### B2: POST /api/v1/business-cases — 创建案例（status 固定 draft）
- 鉴权：bizcase:create
- 入参（body JSON）：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| caseName | String | 是 | ≤200；uk(tenant, case_name) 冲突 → 400（AC-F2.1） |
| description | String | 否 | — |
| relatedStrategyIds | Long[] | 否 | 逐 id 存在性校验（t_strategy.id），无效 → 400 指名；空数组合法（AC-F2.8；存 id——裁决 Q4） |
| onetimeCost | BigDecimal | 是 | ≥0（负 → 400，AC-F2.5）；=0 合法触发边界态 |
| annualOpCost | BigDecimal | 是 | ≥0 |
| annualBenefit | BigDecimal | 是 | ≥0 |
| reach | BigDecimal | 是 | **1~10 整数**：0/11/1.5 → 400 指名（AC-F2.4） |
| impact | BigDecimal | 是 | 同上 |
| confidence | BigDecimal | 是 | **0.1 步进离散恰 10 档（0.1~1.0）**：0.05/0.15/0.85/两位小数/0/1.1 → 400 |
| effort | BigDecimal | 是 | 1~10 整数（≥1 恒正，rice 无除零） |

- 出参：`R<BusinessCaseVo>`（status=draft + 四计算字段回显；构造值回归：{100, 20, 60, R5,I3,C0.8,E6} → `{netBenefit:40, paybackYears:2.5, roiPercent:20.00, riceScore:2.00}`，AC-F2.1）
- 错误码：40000 / 400（uk/金额负值/区间/步进/战略校验）/ 40301

### B3: GET /api/v1/business-cases/{id} — 案例详情
- 鉴权：bizcase:view
- 出参：`R<BusinessCaseVo>` 全字段 + `relatedStrategies: [{ "id": 1, "title": "数字化转型", "deleted": false }]`（战略逻辑删 → deleted 占位，计算与流转不受影响，AC-F2.8）

```json
{ "code": 200, "message": "success", "data": {
  "id": 5031, "caseName": "数据中台建设", "description": "...",
  "relatedStrategyIds": [1, 5],
  "relatedStrategies": [ { "id": 1, "title": "数字化转型", "deleted": false } ],
  "onetimeCost": 100.00, "annualOpCost": 20.00, "annualBenefit": 60.00,
  "netBenefit": 40.00, "paybackYears": 2.5, "roiPercent": 20.00, "riceScore": 2.00,
  "reach": 5, "impact": 3, "confidence": 0.8, "effort": 6,
  "status": "draft", "rejectedReason": null, "decisionNote": null,
  "createBy": "pm1", "createTime": "2026-08-21 10:00:00" } }
```

- 注：`paybackYears`/`roiPercent` 为 **null = N/A**（payback：net≤0 不可投；roi：onetime=0 除零防御）；onetime=0 且 net>0 → paybackYears=**0.0** 非 null（AC-F2.2/F2.3 边界）；roiPercent 为百分数值（20.00 = 20.00%，前端拼 %）；**字段名 roiPercent 来自 V7 列 roi_percent**（tasks.md 拆解声明 3 定稿）
- 错误码：40400

### B4: PUT /api/v1/business-cases/{id} — 编辑案例（draft 专属）
- 鉴权：bizcase:edit
- 入参：同 B2（全量；入参模型无四个计算字段——提交 rice_score=999 无绑定入口，AC-F2.4）
- 语义：**draft → 200 且计算字段重算**；**approved/executing 改输入 → 400"已批准，输入不可改，请复盘或新建案例"**；rejected/done 任何编辑 → 400（AC-F2.7）
- 出参：`R<BusinessCaseVo>`（重算后）
- 错误码：400（编辑受限/uk/边界/校验）/ 40400 / 40301

### B5: DELETE /api/v1/business-cases/{id} — 逻辑删除（仅 draft）
- 鉴权：bizcase:edit；审计 bizcase_delete
- 语义：draft 可删；**非 draft → 400**（已进入决策/执行流的案例是合规资产，只能留痕，AC-F2.7 Then）
- 出参：`R<Void>`
- 错误码：400（非 draft 不可删）/ 40400 / 40301

### B6: PUT /api/v1/business-cases/{id}/decision-note — 更新决策记录
- 鉴权：bizcase:edit
- 入参：`{ "decisionNote": String* }`（空 → 400）
- 语义：draft/approved/executing 可更新（执行期进展记录）→ 200；rejected/done → 400；审计 bizcase_decision_note（detail 含旧值→新值，AC-AUDIT.1）
- 出参：`R<BusinessCaseVo>`
- 错误码：40000 / 400（终态 400）/ 40400 / 40301

### B7: POST /api/v1/business-cases/{id}/transit — 状态流转（**bizcase:approve，创建与审批分离**）
- 鉴权：**bizcase:approve**（§0.3-2：四类流转目标统一挂 approve 原子；PM 无该原子 → 403，AC-F2.9）
- 入参：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| target | String | 是 | `approved` / `rejected` / `executing` / `done`；空 → 400 |
| rejectedReason | String | 条件 | target=rejected 必填（空 → 400，AC-F2.6） |

- 语义：draft→approved 200；draft→rejected（必填原因）200；approved→executing 200；executing→done 200；**draft→executing 跳级 400；approved→rejected 400（批准后不可撤销）；rejected/done 终态出边 400**；自流转幂等；审计 bizcase_transit（detail 含 from→to+操作者+rejectedReason/decisionNote 快照+计算字段）
- 出参：`R<BusinessCaseVo>`（新状态）
- 错误码：400（非法流转/原因缺失）/ 40400 / **40301（PM 调用——QA 断言点）**

### B8: GET /api/v1/business-cases/portfolio — 投资组合聚合（纯只读）
- 鉴权：bizcase:view（无独立权限原子）
- 入参：page/size（cases 清单分页）
- 出参：`R<PortfolioVo>`——**cases 全量（含 rejected/done，`riceScore DESC, id DESC`，AC-F2.10）+ summary 四项投资口径（status∈{approved,executing,done}；draft/rejected 不计，AC-F2.11；空集 COALESCE 0）+ statusDistribution 全量五态（与汇总口径有意不同，同用例双断言 AC-F2.12）**

```json
{ "code": 200, "message": "success", "data": {
  "cases": { "records": [ { "id": 5032, "caseName": "AI 助手", "status": "approved",
      "riceScore": 100.00, "paybackYears": 2.5, "roiPercent": 20.00,
      "onetimeCost": 100.00, "netBenefit": 40.00 } ],
    "total": 1, "size": 20, "current": 1, "pages": 1 },
  "summary": { "totalOnetimeCost": 100.00, "totalAnnualOpCost": 20.00,
    "totalAnnualNetBenefit": 40.00, "totalThreeYearNetBenefit": 120.00 },
  "statusDistribution": { "draft": 1, "approved": 1, "rejected": 1, "executing": 0, "done": 0 } } }
```

- 注：summary 直接对落库列 Σ（net_benefit/onetime_cost/annual_op_cost，D-2）；totalAnnualNetBenefit **可为负**正常展示；totalThreeYearNetBenefit = 3×Σnet（与 ROI 3 年口径一致）
- 错误码：40101 / 40301

---

## 附：权限原子 → 端点绑定矩阵（T18 反射断言基线，AC-RBAC.1/2/3）

| 权限原子（seed id） | code | 绑定端点 |
|---|---|---|
| 1071 / 1072 / 1073 | risk:view / create / edit | R1、R3、R7；R2；R4、R5、R6 |
| 1074 / 1075 / 1076 | compliance:view / create / edit | C1、C3；C2；C4、C5 |
| 1077 / 1078 / 1079 | bizcase:view / create / edit | B1、B3、B8；B2；B4、B5、B6 |
| 1080 | bizcase:approve | **仅 B7**（裁决 Q7） |

角色授权分布（V7 seed 已落盘）：platform_admin×10 全量 / tenant_admin×10 全量（GRC/Strategy 兼任）/ PM×7（risk×3 + compliance:view + bizcase view/create/edit——**无 compliance create/edit、无 approve**，两处利益冲突隔离）/ engineer×3 / executive×3（三域 view）。
