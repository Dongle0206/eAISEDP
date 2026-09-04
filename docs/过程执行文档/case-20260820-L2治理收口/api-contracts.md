# 接口契约 — case-20260820-L2治理收口（25 新端点 + 登录响应扩展）

| 字段 | 值 |
|---|---|
| 产出者 | team-ba（L1 需求分析员） |
| 基线 | SE 技术方案 §5（权威）；DBA V6 表结构；PRD §5 AC |
| 消费方 | team-dev（端点实现唯一契约）、前端任务 T7~T10/T14/T15/T21/T23（直接引用编号）、team-qa（接口级断言） |

## 0. 通用约定

- **前缀**：全部 `/api/v1/`（G14）；由 eaiselp-runtime 进程暴露（M1 模块化单体）。
- **鉴权**：全部需 JWT（`Authorization: Bearer <token>`），无对外白名单接口；标注 `@RequirePermission("<code>")` 的端点另受权限拦截器（40301）约束；U1/U2 为应用层角色显式校验（无权限原子）。
- **tenant_id**：一律取自 TenantContext（JWT 解析），**禁止**请求参数传入（防伪造，ES-003 §9.3-4）；跨租户资源访问 → 40400。
- **统一响应** `R<T>`：

```json
{ "code": 200, "message": "success", "data": { } }
```

- **分页** `IPage<Vo>`：请求参数 `page`（默认 1）/`size`（默认 20）；响应 data = `{ "records": [...], "total": n, "size": 20, "current": 1, "pages": n }`。
- **统一错误码**：

| code | 语义 | 典型触发 |
|---|---|---|
| 40000 | 参数缺失/格式错误 | 必填缺失、类型不符 |
| 400 | 业务校验失败 | uk 冲突（"...已存在: ..."）、枚举非法（指名字段+合法值集）、状态机非法流转、版本未变更、编辑受限、关联对象不存在/已停用、threshold 越界（ADR 先例 R.fail(400,...)） |
| 40101 | 未登录/token 失效 | — |
| 40301 | 无权限 | @RequirePermission / U1/U2 角色校验不过 |
| 40400 | 资源不存在（含跨租户） | — |
| 40003 | **试用已到期**（本 case 新增，Q9 定稿） | 登录/派生入口到期拦截；message 必含"试用已到期"+升级指引 |

- **写操作全部落审计**（t_governance_log，action 见 SE §8.1）。

---

## 1. 工程标准 — `/api/v1/standards`（Controller：StandardController，T2/T12/T13 交付）

### AC-S1: GET /api/v1/standards — 标准分页列表
- 鉴权：standard:view
- 入参：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| page / size | long | 否 | 默认 1/20 |
| status | String | 否 | 逗号多值；**缺省 `draft,published`**；显式传 `deprecated` 可查废弃（AC-F1.2） |
| principleCode | String | 否 | 关联原则筛选（JSON 内存过滤，分页后过滤同 ADR §4.2 口径；AC-F1.6） |
| gateName | String | 否 | 关联门禁筛选（JSON 内存过滤；**打回解析与规则页"已关联标准"一律配合 status=published 使用**，AC-F1.7/F1.8 翻译口径） |
| keyword | String | 否 | title LIKE |

- 出参：

```json
{ "code": 200, "message": "success", "data": {
  "records": [ { "id": 1932, "standardCode": "STD-0002", "title": "接口设计规范",
    "version": "v1.0", "status": "published", "relatedPrincipleCodes": ["P3","P11"],
    "relatedGateNames": ["接口文档完备性检查"], "deprecateReason": null,
    "createBy": "admin", "createTime": "2026-08-20 10:00:00" } ],
  "total": 1, "size": 20, "current": 1, "pages": 1 } }
```

- 错误码：40101 / 40301

### AC-S2: POST /api/v1/standards — 创建标准（status 固定 draft）
- 鉴权：standard:create
- 入参（body JSON）：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| standardCode | String | 否 | 缺省服务端生成 `STD-NNNN`（续推+冲突重试 ≤3 次）；自定义编号 uk 冲突 → 400 |
| title | String | 是 | ≤200 |
| version | String | 是 | 同编号内不可重复（uk 冲突 400，AC-F1.1） |
| content | String | 是 | markdown，建议 ≤20000 字符 |
| relatedPrincipleCodes | String[] | 否 | 逐 code 存在性校验，无效 → 400 指名（AC-F1.6） |
| relatedGateNames | String[] | 否 | 逐 name 存在且 enabled 校验，不存在/已停用 → 400（§4.5 翻译，AC-F1.3） |

- 出参：`R<StandardVo>`（同 S1 记录结构，status=draft）
- 错误码：40000 / 400（uk 冲突、原则/门禁校验）/ 40301

### AC-S3: GET /api/v1/standards/{id} — 标准详情
- 鉴权：standard:view
- 入参：路径 id
- 出参：`R<StandardVo>` 全字段（content 全文 + **deprecateReason 列直读回显**）+ `referencedByGates`（被引用门禁列表：relatedGateNames 解析 name→规则当前信息；悬空 name → 条目标记 `"deleted": true` 占位）
- 错误码：40400（不存在/跨租户）

### AC-S4: PUT /api/v1/standards/{id} — 编辑标准（draft 专属）
- 鉴权：standard:edit
- 入参：同 S2（全量）
- 出参：`R<StandardVo>`
- 错误码：400（**published/deprecated 编辑任意字段 → 400"发布后不可编辑，请升版"**，AC-F1.5；uk/关联校验同 S2）/ 40400 / 40301

### AC-S5: DELETE /api/v1/standards/{id} — 逻辑删除
- 鉴权：standard:edit
- 入参：路径 id
- 出参：`R<Void>`；删除后列表不可见、审计留痕；门禁侧关联条目展示"已删除"占位由查询侧实现（D-9，不影响门禁运行）
- 错误码：40400 / 40301

### AC-S6: POST /api/v1/standards/{id}/transit — 状态流转
- 鉴权：standard:edit
- 入参：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| target | String | 是 | `published` / `deprecated`；空 → 400"target 不能为空" |
| deprecateReason | String | 条件 | target=deprecated 必填（空 → 400，AC-F1.2） |

- 出参：`R<StandardVo>`（新状态）
- 语义：draft→published（**触发自动取代事务**：FOR UPDATE 锁旧 published → 置 deprecated（原因=「被 {code} {新版本} 取代」）→ 新版 published，双审计，AC-F1.4）；published→deprecated / draft→deprecated（必填原因）；其余流转（deprecated→任何、published→draft）→ 400
- 错误码：400（非法流转/原因缺失）/ 40400 / 40301

---

## 2. 模板库 — `/api/v1/templates`（Controller：TemplateController，T3 交付）

### AC-T1: GET /api/v1/templates — 模板分页列表
- 鉴权：template:view
- 入参：page/size；templateType（精确匹配，**含自定义值**，AC-F1.10）；keyword（name LIKE）；includeDisabled（默认 0=仅 enabled=1，AC-F1.12）
- 出参：

```json
{ "code": 200, "message": "success", "data": {
  "records": [ { "id": 2201, "templateType": "PRD", "templateName": "需求文档模板",
    "version": "v1", "enabled": 1, "placeholderCount": 2,
    "createTime": "2026-08-20 10:00:00" } ],
  "total": 1, "size": 20, "current": 1, "pages": 1 } }
```

- 错误码：40101 / 40301

### AC-T2: POST /api/v1/templates — 创建模板
- 鉴权：template:create
- 入参：`{ templateType*, templateName*, version*, content* }`——type **开放字典不校验枚举**（P6 裁决）
- 出参：`R<TemplateVo>`
- 错误码：400（uk(tenant,type,name) 冲突 → "已存在"，AC-F1.9）/ 40000 / 40301

### AC-T3: GET /api/v1/templates/{id} — 模板详情
- 鉴权：template:view
- 出参：`R<TemplateVo>` 全字段 + `placeholders: ["case_id","project_name"]`（实时从 content 提取 `\{\{[A-Za-z0-9_]+\}\}`，去重排序，不落库；无占位符 → 空数组合法，AC-F1.11）
- 错误码：40400

### AC-T4: PUT /api/v1/templates/{id} — 编辑（原地升版）
- 鉴权：template:edit
- 入参：同 T2
- 语义：**version 必须 ≠ 当前值**（相同 → 400"版本必须变更"，不比较大小）；旧版本仅存审计 detail（template_update 含 oldVersion，AC-F1.12）
- 错误码：400（版本未变更/uk 冲突）/ 40400 / 40301

### AC-T5: DELETE /api/v1/templates/{id} — 逻辑删除
- 鉴权：template:edit；审计 template_delete
- 错误码：40400 / 40301

### AC-T6: PUT /api/v1/templates/{id}/enabled — 启停
- 鉴权：template:edit（形态对齐 gate-rules `PUT /{id}/enabled` 先例）
- 入参：`{ "enabled": 0|1 }`
- 出参：`R<TemplateVo>`；审计 template_status
- 错误码：40000 / 40400 / 40301

---

## 3. 数据资产 — `/api/v1/data-assets`（Controller：DataAssetController，T4/T5 交付）

### AC-A1: GET /api/v1/data-assets — 资产分页列表
- 鉴权：asset:view
- 入参：page/size；assetType（枚举筛选）；sensitivity（四档筛选）；tag（单选命中，JSON_CONTAINS）；keyword（assetName+systemName LIKE）
- 出参：

```json
{ "code": 200, "message": "success", "data": {
  "records": [ { "id": 3301, "assetName": "t_order", "systemName": "ERP",
    "assetType": "table", "sensitivity": "sensitive", "owner": "张三",
    "tags": ["客户数据","日报"], "createTime": "2026-08-20 10:00:00" } ],
  "total": 1, "size": 20, "current": 1, "pages": 1 } }
```

- 错误码：40101 / 40301

### AC-A2: POST /api/v1/data-assets — 创建资产
- 鉴权：asset:create
- 入参：`{ assetName*, systemName*, assetType*, owner?, sensitivity*, description?, tags? }`
- 校验：assetType ∈ database/table/api/report/file、sensitivity ∈ public/internal/sensitive/confidential，非法 → 400 且 message **指名字段与合法值集合**（AC-F2.2）；uk(tenant,system,name) 冲突 → 400（AC-F2.1）
- 错误码：400 / 40000 / 40301

### AC-A3: GET /api/v1/data-assets/{id} — 资产详情（含质量规则聚合）
- 鉴权：asset:view
- 出参：`R<DataAssetVo>` 全字段 + 聚合区：

```json
"rules": { "count": 2, "items": [ { "ruleId": 4401, "ruleName": "订单表完整性",
  "checkType": "completeness", "threshold": 99.50,
  "lastResult": "fail", "lastActualValue": 97.2,
  "lastCheckTime": "2026-08-20 09:00:00" } ] }
```

（AC-F2.6 Then"资产详情展示该规则及 fail 结果"）
- 错误码：40400

### AC-A4: PUT /api/v1/data-assets/{id} — 编辑资产
- 鉴权：asset:edit；入参同 A2；审计 asset_update
- 错误码：400（枚举/uk）/ 40400 / 40301

### AC-A5: DELETE /api/v1/data-assets/{id} — 逻辑删除（联动清理）
- 鉴权：asset:edit
- 语义：**关联质量规则同步逻辑删**（审计 asset_delete detail 含 `ruleIds` 数组可辨识，AC-F2.7）；被删资产 id 再用于新建规则 → 400（Q2 端校验承载）
- 错误码：40400 / 40301

---

## 4. 质量规则 — `/api/v1/data-quality-rules`（Controller：DataQualityRuleController，T5 交付）

### AC-Q1: GET /api/v1/data-quality-rules — 规则分页列表
- 鉴权：dqrule:view
- 入参：page/size；checkType；lastResult（pass/fail）；assetId；keyword（ruleName LIKE）
- 出参：`R<IPage<DataQualityRuleVo>>`——记录含 id/ruleName/assetId/**assetName**（关联资产摘要）/checkType/threshold（展示附 %）/lastResult/lastActualValue/lastCheckTime/lastCheckRemark
- 错误码：40101 / 40301

### AC-Q2: POST /api/v1/data-quality-rules — 创建规则
- 鉴权：dqrule:create
- 入参：`{ ruleName*, assetId*, checkType*, threshold* }`
- 校验：assetId 存在且**未逻辑删**（不存在/已删 → 400，AC-F2.5/F2.7）；checkType ∈ completeness/accuracy/consistency/timeliness；threshold ∈ [0,100]（100.5/−1 → 400；**边界 0 与 100 合法**）；uk(tenant,ruleName) 冲突 → 400
- 错误码：400 / 40000 / 40301

### AC-Q3: GET /api/v1/data-quality-rules/{id} — 规则详情
- 鉴权：dqrule:view；出参全字段+关联资产摘要
- 错误码：40400

### AC-Q4: PUT /api/v1/data-quality-rules/{id} — 编辑规则
- 鉴权：dqrule:edit；入参同 Q2；审计 dqrule_update
- 错误码：400 / 40400 / 40301

### AC-Q5: DELETE /api/v1/data-quality-rules/{id} — 逻辑删除
- 鉴权：dqrule:edit；审计 dqrule_delete
- 错误码：40400 / 40301

### AC-Q6: POST /api/v1/data-quality-rules/{id}/check-results — 登记最近检查结果
- 鉴权：dqrule:edit
- 入参：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| result | String | 是 | `pass` / `fail`（登记人判定，平台不按阈值自动判定） |
| actualValue | Decimal | 否 | 实测值 |
| checkTime | DateTime | 否 | 缺省当前时刻 |
| remark | String | 否 | 如"字段缺失" |

- 语义：**覆盖式更新** last_* 四列（单值当前态，不落历史行）；审计 dqrule_check_result detail 含旧值→新值+登记人（AC-F2.6"旧值不覆盖审计"）
- 出参：`R<DataQualityRuleVo>`（更新后）
- 错误码：40000 / 40400 / 40301

---

## 5. 租户订阅 — TenantController 扩展（T22 交付；**无新增权限原子**，应用层角色显式校验）

### AC-U1: GET /api/v1/tenant/subscription — 当前租户订阅状态查询
- 鉴权：JWT + **tenant_admin 或 platform_admin**（JWT claims roles 显式校验，不可伪造）；其他角色 → 40301（AC-F3.5）
- 入参：无（tenant_id 取自 TenantContext）
- 出参：

```json
{ "code": 200, "message": "success", "data": {
  "edition": "trial", "editionName": "试用版",
  "expireTime": "2026-09-19 12:00:00",
  "daysLeft": 5, "expired": false, "trial": true } }
```

- 语义：daysLeft/expired 与登录口径同源（TenantSubscriptionService，PRD §4.3.1 唯一口径）
- 错误码：40101 / 40301

### AC-U2: PUT /api/v1/tenant/{id}/subscription — 修改租户订阅（恢复路径，platform_admin 专属）
- 鉴权：JWT + **仅 platform_admin**（tenant_admin → 40301，AC-F3.6）
- 入参（body JSON，null=不变，支持单字段更新）：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| edition | String | 否 | ∈ trial/pro/enterprise/starter，非法 → 400 |
| expireTime | String | 否 | yyyy-MM-dd HH:mm:ss；可置空（=Q4"未设置"语义） |

- 出参：`R<订阅状态>`（同 U1 结构，修改后回显新值）
- 语义：审计 `tenant_edition_change`（detail 含 oldEdition→newEdition、old→new expireTime、操作者）；无缓存，**下次登录即生效**（AC-F3.6）
- 错误码：400（edition 非法）/ 40400（租户不存在）/ 40301

---

## 6. 登录响应扩展（T17/T19 交付，既有端点契约非破坏性扩展）

### AC-L1: POST /api/v1/auth/login — trialTip 扩展字段
- 鉴权：否（登录端点）；**防枚举顺序不变**：凭据校验通过后才做到期校验（错密码+到期租户 → 仍 40001）
- 成功响应（新增可空字段，非 trial/无临期时字段不出现）：

```json
{ "code": 200, "message": "success", "data": {
  "token": "...", "user": { },
  "trialTip": { "daysLeft": 3, "level": "warning", "expireTime": "2026-09-22 12:00:00" } } }
```

- trialTip.level：`normal`（4≤N≤7 蓝）/ `warning`（2≤N≤3 黄）/ `critical`（N=1 红，红色优先）；N=ceil((expire−now)/24h)；(expire−now)>7×24h → trialTip=null
- 到期失败响应（**不签发 token、不更新 last_login_at**）：

```json
{ "code": 40003,
  "message": "试用已到期，请联系平台管理员升级（platform_admin 可通过订阅管理接口延期/转正）",
  "data": null }
```

- 关联拦截：`POST /api/runtime/derive`、`POST /api/runtime/orchestrate` 到期同样 40003（不预占 pending、不烧 token；这两个为既有端点，无契约变化仅增错误码）

---

## 7. 前端页面 ↔ 端点引用映射（Dev/QA 联调索引）

| 页面 | 消费端点 |
|---|---|
| standard-list.html（T7） | S1~S6 + 既有原则列表（选择器）/ 既有 gate-rules enabled 列表（选择器，§4.5 翻译口径） |
| template-list.html（T8） | T1~T6 |
| asset-list.html（T9） | A1~A5（A3 含 Q1 聚合） |
| quality-rule-list.html（T10） | Q1~Q6 + A1（资产选择器） |
| gate-rule-list.html 改造（T14） | S1（gateName+status=published）只读区 |
| case-detail.html 改造（T15） | 既有 `GET /api/v1/gate-rules/{id}` → S1（gateName=）两跳解析 |
| login.html/提示条（T21） | 既有 login + trialTip 字段 / 40003 特判 |
| llm-key.html 改造（T23） | U1 |
