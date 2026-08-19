# API 接口契约 — case-20260818-L2治理核心（五组端点 + 埋点数据契约）

| 字段 | 值 |
|---|---|
| Case | case-20260818-L2治理核心 |
| 依据 | 需求设计说明.md §4/§5（口径唯一权威 §4.1.2）、技术方案说明.md §8（D-4 前缀裁决）、tasks.md §0（漂移收敛 C1~C7，**列名/错误码/origType/deprecateReason 均按收敛口径**） |
| 产出者 | team-ba（需求分析员） |
| 产出日期 | 2026-08-19 |
| 消费方 | team-dev（实现）/ team-qa（断言）/ 前端（mock 联调可凭本文件先行） |

---

## 0. 通用约定

1. **统一响应壳 `R<T>`**：HTTP 恒 200（平台既有语义），业务结果看 `code`：

```json
{ "code": 200, "message": "ok", "data": { } }
{ "code": 400, "message": "同对项目同类型依赖已存在（blocks 与 depends_on 同向视为同一强依赖）：id=9001", "data": null }
```

2. **认证与权限**：全部端点需 JWT（Authorization: Bearer）；方法级 `@RequirePermission("<code>")`，无权限 → `{"code":403,"message":"无权限"}`。权限原子=V5 seed 1046~1058（milestone/dependency/dora/adr/radar × view/create/edit，dora 仅 view）。
3. **租户隔离**：tenant_id 由拦截器自动过滤（t_governance_log 例外由后端手写）；跨租户资源 id 一律查 null → `{"code":404,"message":"资源不存在"}`，不泄露存在性（AC-ISO.2）。
4. **层开关**：L2 组前缀（milestones / project-dependencies / metrics / programs / projects）在 `program_project_enabled=false` 时统一返回 `{"code":43002,"message":"项目群/项目层未启用"}`（AC-SWITCH.1）；**adrs / tech-radar / principles 不注册 LayerGuard，任何开关组合下恒可用**（AC-SWITCH.2）。
5. **分页**：`page`（默认 1）/`size`（默认 20），响应为 MyBatis-Plus IPage 形态：`{"records":[],"total":0,"size":20,"current":1,"pages":0}`。
6. **审计**：全部写操作（含状态流转与环移动）写 t_governance_log，action 命名 `<resource>_<verb>`；detail 为 safeJson 转义 JSON，状态流转含变更前后值（AC-RBAC.3）。
7. **日期格式**：DATE=`yyyy-MM-dd`，DATETIME=`yyyy-MM-dd HH:mm:ss`。
8. **环检测错误码=400**（PRD AC-F3.3 验收基线；编排简报"409"为口径笔误，不采用——tasks.md §0 C5）。

---

## 1. DORA 效能看板（/api/v1/metrics，注册 L2 组）

### GET /api/v1/metrics/dora — 看板聚合（缓存 5min，D-1）

**权限**：`dora:view`（tenant_admin/project_manager/executive）。

**Query 参数**：

| 参数 | 必填 | 取值 | 非法处理 |
|---|---|---|---|
| scope | 是 | `project` / `program` / `all` | 未知值 → 400 `"scope 非法，应为 project/program/all"` |
| scopeId | scope≠all 时必填 | 项目 id / 项目群 id | 缺失 → 400 `"scope=project/program 时 scopeId 必填"`；不存在 → 404 |
| periodDays | 否（默认 30） | `7` / `30` / `90` | 非三档 → 400 `"periodDays 非法，应为 7/30/90"` |

**成功响应**（字段与 AC-F1.1~F1.6 断言一一对应；示例=AC 构造值）：

```json
{
  "code": 200, "message": "ok",
  "data": {
    "scope": "program", "scopeId": 1, "periodDays": 30,
    "deploymentFrequency": {
      "value": 0.1, "unit": "次/天", "band": "high", "sampleCount": 3
    },
    "leadTime": {
      "p50Hours": 36.0, "p90Hours": 48.0,
      "display": "P50 36h / P90 48h",
      "sampleCount": 2, "excludedCount": 1,
      "exclusionNote": "另有 1 条历史数据不可回溯，已排除"
    },
    "changeFailureRate": {
      "value": 0.333, "percentDisplay": "33.3%", "sampleCount": 3,
      "proxyNote": "门禁终判失败口径（代理指标）",
      "parseErrorCount": 0
    },
    "timeToRestore": {
      "p50Minutes": 45, "avgMinutes": 47, "sampleCount": 2,
      "approximateCount": 1
    },
    "gateReworkRate": {
      "value": 0.15, "note": "门禁打回率（参考值，非四指标）"
    },
    "emptyState": null
  }
}
```

**字段语义（前后端展示与 QA 断言照此，口径唯一权威=PRD §4.1.2）**：

| 字段 | 语义 | AC |
|---|---|---|
| deploymentFrequency.value | 周期内首次流转 done 的去重 Case 数 ÷ periodDays（无数据天计 0） | F1.1（3/30=0.1） |
| deploymentFrequency.band | 按分档表渲染档位（elite/high/medium/low），前端分档常量集中一处 | F1.1 |
| leadTime.p50Hours / p90Hours | P50=线性插值（PERCENTILE.INC）；P90=向上取整序位 ceil(0.9N)；样本 [24h,48h]→36/48 | F1.2 |
| leadTime.excludedCount | t_case.status=done 但无 case_transit 审计的历史 Case 数（**严禁 update_time 冒充**）；>0 时前端显示 exclusionNote | F1.5 |
| changeFailureRate.value | 两源分子（llm_review 终判 FAIL 步骤 ∪ auto_check 阻断，防双计）÷ 终态编排 Case 去重数；FAIL_WARN 不计 | F1.3 |
| changeFailureRate.parseErrorCount | steps_json 解析失败 Case 数；>0 → 该卡降级"该项暂不可用"（**不 500 整页**） | §6.3 |
| timeToRestore.p50Minutes | 有埋点：max(PASS finished_at)−min(FAIL finished_at)；终判失败 Case 不入样本 | F1.4（45min） |
| timeToRestore.approximateCount | 无 gate_result 埋点的近似样本数（末条 finished_at−首条 started_at，系统性偏大）；>0 → 前端"≈"角标 | F1.5 |
| emptyState | `null`=有数据；`"先创建项目并关联 Case"`=无项目；`"暂无统计数据，完成 Case 后自动生成"`=有项目无数据 | F1.6 |

**空态响应示例**（scope=all 且租户无项目）：

```json
{ "code": 200, "data": { "scope": "all", "scopeId": null, "periodDays": 30,
  "deploymentFrequency": null, "leadTime": null, "changeFailureRate": null,
  "timeToRestore": null, "gateReworkRate": null,
  "emptyState": "先创建项目并关联 Case" } }
```

**错误**：400（参数非法，见上表）/ 403（无 dora:view，engineer）/ 404（scopeId 不存在）/ 43002（L2 关）。
**边界语义**：project_id 为空的 Case 不计入任何维度（Case 池 IN 条件天然排除，AC-F1.6 的 C9）；缓存 key=`tenantId|scope|scopeId|periodDays`，TTL 300s（延迟上界恰满足"≤5 分钟"）。

---

## 2. 里程碑（/api/v1/milestones，注册 L2 组）

**实体字段**（API 语义名 ↔ V5 列，tasks.md C4）：`milestoneCode`↔milestone_code、`ownerType`(program|project)↔owner_type、`ownerId`↔owner_id、`title`、`description`、`targetDate`、`owner`↔owner(负责人)、`status`(planned|achieved|delayed)、`achievedDate`、`blocker`、`subprojects`（群级涉及项目多选，仅展示）。

### GET /api/v1/milestones — 列表（milestone:view）

Query：`ownerType`/`ownerId`（两级归属过滤，命中 idx_ms_tenant_owner）、`status`、`page`、`size`。

```json
{ "code": 200, "data": { "records": [{
    "id": 5001, "milestoneCode": "MS-0001", "ownerType": "project", "ownerId": 202,
    "title": "接口联调完成", "description": null, "targetDate": "2026-08-18",
    "owner": "张三", "status": "planned", "achievedDate": null,
    "blocker": null, "subprojects": null,
    "overdue": true, "createTime": "2026-08-10 09:00:00" }],
  "total": 1, "size": 20, "current": 1, "pages": 1 } }
```

`overdue`：targetDate<今天 且 status=planned → true（展示层黄角标，**系统不改状态**，AC-F2.3）。

### POST /api/v1/milestones — 创建（milestone:create）

```json
// body
{ "ownerType": "project", "ownerId": 202, "title": "接口联调完成",
  "description": "双方接口冻结", "targetDate": "2026-08-18",
  "owner": "张三", "blocker": null, "subprojects": null }
// 200 —— milestoneCode 服务端生成（MS-+租户自增序，uk 兜底重试 3 次）
{ "code": 200, "data": { "id": 5001, "milestoneCode": "MS-0001", "status": "planned",
  "achievedDate": null, "ownerType": "project", "ownerId": 202 } }
```

**错误**：400（title 空/超 200 字符、ownerType 非 program|project）；404（ownerId 归属对象不存在：`"归属项目不存在: 202"`）。审计 `milestone_create`。

### GET /api/v1/milestones/{id} — 详情（milestone:view）

返回全字段（同列表行结构）。跨租户/不存在 → 404。

### PUT /api/v1/milestones/{id} — 编辑（milestone:edit）

body 同创建（不含 status/achievedDate——状态变更只走 transit）。legacy program_id/milestone_id 无请求字段（只读不写）。审计 `milestone_update`。

### DELETE /api/v1/milestones/{id} — 逻辑删（milestone:edit）

`{ "code": 200, "data": null }`。审计 `milestone_delete`。

### POST /api/v1/milestones/{id}/transit — 状态流转（milestone:edit，AC-F2.2/F2.3）

```json
// 确认达成（达成日期默认当天，可改）
{ "target": "achieved", "achievedDate": "2026-08-18" }
// 200
{ "code": 200, "data": { "id": 5001, "status": "achieved", "achievedDate": "2026-08-18" } }

// 撤销误确认 → achieved 清空达成日期回 planned
{ "target": "planned" }
{ "code": 200, "data": { "id": 5001, "status": "planned", "achievedDate": null } }
```

**状态机**（非法流转 400，MilestoneStatus）：`planned→achieved`（achievedDate 必填）/ `planned→delayed` / `delayed→achieved`（achievedDate 必填）/ `achieved→planned`（撤销清日期）；流转到自身=幂等合法。

**错误**：400 `"非法状态流转: achieved→delayed"`；400 `"达成日期必填"`（target=achieved 缺 achievedDate）；404。审计 `milestone_transit`，撤销时 detail 含 `{"from":"achieved","to":"planned","clearedAchievedDate":true}`。

### GET /api/v1/programs/{id}/milestone-timeline — 群聚合时间线（milestone:view，挂 programs 前缀→天然 43002）

群直属 + 成员项目全部里程碑合并，按 targetDate 排序，`ownerLevel` 标签区分层级（AC-F2.6）：

```json
{ "code": 200, "data": [{
  "id": 5001, "milestoneCode": "MS-0001", "ownerType": "project", "ownerId": 202,
  "ownerLevel": "project", "ownerName": "项目B", "title": "接口联调完成",
  "targetDate": "2026-08-18", "status": "delayed", "statusColor": "red",
  "overdue": false, "achievedDate": null, "owner": "张三" }] }
```

`statusColor`：planned=blue / achieved=green / delayed=red（前端枚举常量集中定义）。

---

## 3. 跨项目依赖（/api/v1/project-dependencies，注册 L2 组）

**归一化存储（D-5 + C1）**：入参三值 blocks/depends_on/relates_to；落库一律 `dependency_type=depends_on`（blocks 换向：from=被阻塞方、to=阻塞方）或 `relates_to`；原始 blocks 表述以 `[orig:blocks]` 前缀写入 note 列，响应 `origType` 由其解析还原。列表统一**依赖方视角**。

### POST /api/v1/project-dependencies — 登记（dependency:create，AC-F3.1/F3.3）

```json
// body（"A 阻塞 B"快捷录入，fromProjectId=A=101，toProjectId=B=202）
{ "fromProjectId": 101, "toProjectId": 202, "dependencyType": "blocks",
  "remark": "接口未就绪" }
// 200 —— 换向归一后的存储形态：from=B(202) 依赖 to=A(101)
{ "code": 200, "data": { "id": 9001,
  "fromProjectId": 202, "toProjectId": 101,
  "dependencyType": "depends_on", "origType": "blocks",
  "fromProjectName": "项目B", "toProjectName": "项目A",
  "displayName": "项目B → 项目A（硬阻塞）", "remark": "接口未就绪",
  "createTime": "2026-08-19 10:00:00" } }
```

**错误**：

| 场景 | 响应 |
|---|---|
| 自依赖 from=to | 400 `"禁止自依赖：from 与 to 不能相同"` |
| 类型非法 | 400 `"dependencyType 非法，应为 blocks/depends_on/relates_to"` |
| 成环（AC-F3.3，**400 非 409**，C5） | 400 `"依赖成环，禁止登记：项目A→项目C→项目B→项目A"`（附 WARN 日志） |
| 同对同类型重复（AC-F3.1 第二次登记） | 400 `"同对项目同类型依赖已存在（blocks 与 depends_on 同向视为同一强依赖）：id=9001"` |
| 删后重登（C2 复活语义） | 200（id 复用，审计 detail 含 `"revived":true`；无逻辑删行命中才走上表 400） |
| 端点项目不存在 | 404 `"项目不存在: xxx"` |

审计 `dependency_create`（detail 含归一化前后方向与 origType）。

### GET /api/v1/project-dependencies — 边列表（dependency:view）

Query：`projectId`（作为 from 或 to 命中均可返回，行内方向字段自明）、`type`、`page`、`size`。行结构同 POST 响应 data。

### PUT /api/v1/project-dependencies/{id} — 编辑（dependency:edit）

body：`{ "dependencyType": "depends_on", "remark": "..." }`（方向不可改——改向=删旧建新）。**编辑同样过环预检**。审计 `dependency_update`。

### DELETE /api/v1/project-dependencies/{id} — 逻辑删（dependency:edit，C7 seed 口径）

`{ "code": 200, "data": null }`。审计 `dependency_delete`。

### GET /api/v1/project-dependencies/board — blocked 看板（dependency:view，AC-F3.2/F3.4）

**展示层实时判定，不落库**——被依赖项目置 delivered/closed 后刷新即自动解除：

```json
{ "code": 200, "data": {
  "stats": { "totalProjects": 3, "blockedCount": 1, "edgeCount": 2 },
  "projects": [{
    "projectId": 202, "projectName": "项目B", "status": "in_progress",
    "blocked": true,
    "blockedSources": ["被 项目A 阻塞：项目A 未交付"],
    "waitingFor": [{ "edgeId": 9001, "toProjectId": 101, "toProjectName": "项目A",
                     "dependencyType": "depends_on", "origType": "blocks",
                     "displayName": "受阻", "remark": "接口未就绪" }],
    "responsibleFor": []
  }] } }
```

**判定语义**：项目 P blocked ⟺ 存在强依赖边（dependency_type='depends_on'）P→Q 且 Q.status ∉ {delivered, closed}；relates_to 边不参与判定（AC-F3.4）。

### GET /api/v1/project-dependencies/cycle-check?from=&to= — 新边环预检（dependency:view）

前端登记表单实时提示用（查询语义，成环也返回 200）：

```json
// 会成环
{ "code": 200, "data": { "wouldCycle": true,
  "cyclePathIds": [101, 202, 101], "pathDisplay": "项目A→项目B→项目A" } }
// 不会成环
{ "code": 200, "data": { "wouldCycle": false, "cyclePathIds": [], "pathDisplay": null } }
```

仅强边（depends_on）参与 DFS；relates_to 不进图（AC-F3.4）。防御上界：节点>10000/边>50000 → 400 `"依赖规模超限，请先清理依赖数据"`。

### GET /api/v1/project-dependencies/full-check — 全图体检（dependency:view）

```json
{ "code": 200, "data": { "cycleCount": 0, "cycles": [] } }
// 有历史脏数据时：
{ "code": 200, "data": { "cycleCount": 1, "cycles": [
  { "pathIds": [101, 202, 101], "pathDisplay": "项目A→项目B→项目A" } ] } }
```

环序列按最小节点旋转规范化去重；正常情况为空（可见可治）。

---

## 4. ADR 库（/api/v1/adrs，**不注册 LayerGuard，不限层**）

**字段**（C4）：`adrCode`↔adr_code（租户内唯一，缺省 `ADR-NNN` 服务端生成可自定义）、`title`、`status`(proposed|accepted|deprecated|superseded)、`context`/`decision`/`consequences`↔context_text/decision_text/consequence_text（五段式必填）、`relatedPrincipleCodes[]`↔related_principle_codes(JSON)、`decisionDate`、`author`、`supersededBy`；**无 deprecateReason 列**（C3：transit 必填校验+审计 detail 承载+响应回显）。

### GET /api/v1/adrs — 列表筛选（adr:view，AC-F4.4）

Query：`status`（缺省=`proposed,accepted` 双值）、`principleCode`（如 P11，JSON_CONTAINS 或内存过滤）、`keyword`（title LIKE）、`page`、`size`。

```json
{ "code": 200, "data": { "records": [{
  "id": 7001, "adrCode": "ADR-001", "title": "多租户隔离贯穿", "status": "accepted",
  "relatedPrincipleCodes": ["P11"], "decisionDate": "2026-08-01", "author": "admin" }],
  "total": 1, "size": 20, "current": 1, "pages": 1 } }
```

### POST /api/v1/adrs — 创建（adr:create，AC-F4.1）

```json
// body
{ "adrCode": "ADR-001", "title": "多租户隔离贯穿",
  "context": "…", "decision": "…", "consequences": "…",
  "relatedPrincipleCodes": ["P11"], "decisionDate": "2026-08-01", "author": "admin" }
// 200（状态默认 proposed）
{ "code": 200, "data": { "id": 7001, "adrCode": "ADR-001", "status": "proposed" } }
```

**错误**：400（五段式任一为空串：`"context 不能为空"`；关联原则不存在：`"原则 P99 不存在"`——整单拒绝指名）；同编号重复 → 400 `"ADR 编号已存在: ADR-001"`（uk_adr_tenant_code）。审计 `adr_create`。

### GET /api/v1/adrs/{id} — 详情（adr:view）

全字段 + `deprecateReason`（若曾流转 deprecated，从最近一次 adr_transit 审计回显，否则 null）+ `supersededBy`。跨租户 → 404。

### PUT /api/v1/adrs/{id} — 编辑（adr:edit）

body 同创建（status/supersededBy 不在此改——只走 transit）。审计 `adr_update`（detail 含变更摘要；版本对比=范围外，审计即历史）。

### DELETE /api/v1/adrs/{id} — 逻辑删（adr:edit）。审计 `adr_delete`。

### POST /api/v1/adrs/{id}/transit — 状态流转（adr:edit，AC-F4.2/F4.3）

**状态机**（AdrStatus，非法 400）：`proposed→accepted`；`accepted→deprecated`（必填 deprecateReason）；`accepted→superseded`（必填 supersededBy）；deprecated/superseded 终态无出边；自身幂等。

```json
// supersede（AC-F4.2/F4.3）
{ "target": "superseded", "supersededBy": "ADR-002" }
// 200 —— 离开 accepted 且关联原则非空 → principleSyncHints（提示而非自动）
{ "code": 200, "data": { "adrCode": "ADR-001", "status": "superseded",
  "supersededBy": "ADR-002",
  "principleSyncHints": [{ "code": "P11", "title": "多租户隔离贯穿" }] } }

// deprecated（废弃说明不落列，写审计 detail.deprecateReason，C3）
{ "target": "deprecated", "deprecateReason": "已被事件驱动架构取代" }
{ "code": 200, "data": { "adrCode": "ADR-003", "status": "deprecated",
  "deprecateReason": "已被事件驱动架构取代", "principleSyncHints": [...] } }
```

**错误**：400 `"非法状态流转: proposed→superseded"`（跳过 accepted）；400 `"superseded_by 必填"`；400 `"superseded_by 指向的 ADR 须为 accepted"`；400 `"superseded_by 不能指向自身"`；400 `"deprecateReason 必填"`；400 `"deprecated/superseded 为终态，不可回退"`。审计 `adr_transit`（detail 含 from/to/supersededBy/deprecateReason）。

**前端联动**（T27）：principleSyncHints 非空 → 提示条"该 ADR 关联原则 P11，请检查原则内容是否需要同步更新"+ 原则管理直达链接；**系统不自动修改原则内容**。

### GET /api/v1/principles/{id}/adrs — 原则关联 ADR 反查（principle:view，既有 PrincipleController 增量）

```json
{ "code": 200, "data": [{ "id": 7001, "adrCode": "ADR-001", "title": "多租户隔离贯穿",
  "status": "deprecated", "relatedPrincipleCodes": ["P11"] }] }
```

原则管理/编辑页展示"关联的 ADR 列表"（含状态，AC-F4.3 原则侧聚合）。

---

## 5. 技术雷达（/api/v1/tech-radar，**不注册 LayerGuard，不限层**）

**字段**（C4）：`name`↔tech_name（租户内唯一，一技术一当前态）、`quadrant`(techniques|tools|platforms|languages)、`ring`(adopt|trial|assess|hold)、`reason`（必填）、`reviewedAt`↔reviewed_at（必填）、`remark`。

### GET /api/v1/tech-radar — 列表（radar:view）

Query：`quadrant`、`ring`（可组合筛选）。含展示层派生标记：

```json
{ "code": 200, "data": [{ "id": 6001, "name": "Redis", "quadrant": "tools",
  "ring": "adopt", "reason": "缓存事实标准", "reviewedAt": "2026-02-01",
  "remark": null, "pendingReview": false }] }
```

`pendingReview`：reviewedAt < 今天−180 天 → true（"待复审"角标，**不阻塞任何操作**，AC-F5.4）。

### POST /api/v1/tech-radar — 创建（radar:create，AC-F5.1）

```json
{ "name": "Redis", "quadrant": "tools", "ring": "adopt",
  "reason": "缓存事实标准", "reviewedAt": "2026-08-01", "remark": null }
→ { "code": 200, "data": { "id": 6001, "name": "Redis", "ring": "adopt" } }
```

**错误**：400 `"quadrant 非法，应为 techniques/tools/platforms/languages"`（如传 hardware，AC-F5.1②）；400 `"ring 非法，应为 adopt/trial/assess/hold"`；400 `"reason/reviewedAt 必填"`；同名重复 → 400 `"技术项已存在: Redis，请编辑既有项"`（uk_radar_tenant_name，AC-F5.1①）。审计 `radar_create`。

### GET /api/v1/tech-radar/{id} — 详情（radar:view）。跨租户 → 404。

### PUT /api/v1/tech-radar/{id} — 编辑（radar:edit，**环移动审计**，AC-F5.3）

```json
{ "ring": "adopt", "reason": "生产验证稳定", "reviewedAt": "2026-08-19" }
→ { "code": 200, "data": { "id": 6001, "name": "Redis", "ring": "adopt" } }
```

改 ring 时审计 `radar_update` 新增（t_governance_log，环变更历史唯一留痕）：

```json
{ "action": "radar_update", "resource_type": "tech_radar", "resource_id": "6001",
  "detail": { "name": "Redis", "fromRing": "trial", "toRing": "adopt" } }
```

### DELETE /api/v1/tech-radar/{id} — 逻辑删（radar:edit）。审计 `radar_delete`。

---

## 6. 既有端点增量（唯一改造的存量出参，只加字段向后兼容）

### GET /api/v1/projects/{id} — 详情新增两块（AC-F2.4/F2.5/F3.5 展示入口）

```json
{ "code": 200, "data": {
  "id": 202, "name": "项目B", "status": "in_progress",
  "progress": 100, "caseTotal": 2, "caseDone": 2,
  "principles": [ ],
  "achievementHint": {
    "eligible": true, "milestoneIds": [5001],
    "message": "项目全部 Case 已完成，可达成里程碑" },
  "dependencies": {
    "waitingFor": [{ "edgeId": 9001, "toProjectId": 101, "toProjectName": "项目A",
                     "dependencyType": "depends_on", "origType": "blocks" }],
    "responsibleFor": [] } } }
```

**判定**：`eligible` = case_total>0 && case_done==case_total && 存在 status=planned 里程碑（数据源=既有汇总字段，零新口径）；case_total=0 或未全 done → `achievementHint:null` 或 `eligible:false`（AC-F2.5 不出现提示条）。对方项目已删除的边不出现在 dependencies。**两块计算异常均降级 null，不阻塞详情主渲染**（PRD §6.3）。

---

## 7. 埋点数据契约（t_derivation.gate_result，非 HTTP；AC-F1.4 断言载体）

| 项 | 契约 |
|---|---|
| 列 | `t_derivation.gate_result` VARCHAR(16) NULL（V5 已落盘；NULL=埋点上线前历史记录，RT 走近似口径加"≈"） |
| 值域 | `PASS` / `FAIL` / `FAIL_WARN` / `NULL`（LLM 未输出判定→不写保持 NULL；断点续跑 executeStep 路径不埋点同为 NULL） |
| 唯一写入点 | OrchestrationService.runAsync 门禁判定三支汇合处 markGateResult(derivationId, gateValue)（tasks.md T21；精准 UPDATE 依赖 T1 回填的 derivationId） |
| 失败语义 | 写入异常仅 ERROR 日志告警，**绝不影响编排主流程**（打回/重做/终判行为不变，PRD §6.3） |
| QA 验证 | AC-F1.4：门禁打回 Case 的 t_derivation 两行 `SELECT id,role,gate_result,finished_at FROM t_derivation WHERE case_id='...' ORDER BY id` 断言 FAIL/PASS 两值；RT=45min=PASS 行 finished_at−FAIL 行 finished_at |
| 历史数据 | **不回填**（D-2 裁决）：无法从 steps_json 复原中间轮次 FAIL 时间戳，回填只能造近似值，与"不冒充精确值"口径冲突；近似样本由看板 approximateCount 透明展示 |

---

## 8. 错误码总表（本期新增/复用面）

| code | 场景 | 出现端点 |
|---|---|---|
| 200 | 成功 | 全部 |
| 400 | 参数非法 / 枚举非法 / 状态机非法流转 / 必填缺失（supersededBy、deprecateReason、achievedDate）/ 唯一冲突（milestoneCode、adrCode、tech_name、依赖边）/ **环检测拒绝（含成环路径，非 409）** / 自依赖 / 依赖规模超限 | 全部写端点 + DORA 查询参数 |
| 401 | 未认证/ token 失效 | 全部（JWT 拦截器） |
| 403 | 无对应权限原子（AC-RBAC.1：engineer 八类编辑全 403、dora:view 403；AC-RBAC.2：adr/radar view 对 engineer 200） | 全部（权限拦截器） |
| 404 | 资源不存在 / 跨租户直达（不泄露存在性，AC-ISO.2） | 全部带 id 端点 |
| 43001 | 战略层未启用（既有语义，本期不涉及新端点） | /api/v1/strategies/** |
| 43002 | **L2 未启用**：milestones / project-dependencies / metrics（+挂靠的 programs 子端点）三新前缀注册进既有 L2 组（AC-SWITCH.1；ADR/雷达/原则端点**永不出此码**，AC-SWITCH.2） | L2 组前缀 |

> 兜底铁律（PRD §6.3）：统计源异常（steps_json 解析失败等）以 data 内 parseErrorCount 降级表达，**任何场景不得以 500 代替业务码**。
