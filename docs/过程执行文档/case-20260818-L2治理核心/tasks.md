# 任务拆解 — case-20260818-L2治理核心（L2 治理核心 + 知识资产）

| 字段 | 值 |
|---|---|
| Case | case-20260818-L2治理核心（PRJ-003 主体 + PRJ-004 拆分项 ADR/雷达） |
| 上游输入 | 需求设计说明.md v1.0（5 功能点/34 AC，验收基线）/ 技术方案说明.md（模块清单·D-1~D-6·AC 可达表，行为权威）/ 数据库设计说明.md + **V5__l2_governance.sql（已落盘，数据权威）** / hierarchy·orchestration·engine 既有代码（本版逐一复核） |
| 产出者 | team-ba（需求分析员） |
| 产出日期 | 2026-08-19（替换 2026-08-18 初版，按编排者分批口径重组为 5 批 30 任务） |
| 下游 | team-dev（按批认领，配套 [api-contracts.md](./api-contracts.md)）/ team-qa（AC 用例基线=PRD §5）/ 编排者（派发与裁决） |
| 已裁决 | D-1 DORA 实时聚合+5min TTL；D-2 埋点不回填历史（≈角标）；D-5 blocks 归一化 depends_on；ADR/雷达不限层；里程碑/依赖/DORA 挂 L2 开关（LayerGuard 43002） |
| 权威约定 | 数据模型以 **DBA 落盘 V5 为权威**，行为语义以 **SE 方案为权威**；漂移按 §0 收敛表执行，Dev/QA 不得再各自发明 |

**技术栈锚点**：Spring Boot + MyBatis-Plus（ServiceImpl/LambdaWrapper，实体继承 `eaiselp-common` BaseEntity：ASSIGN_ID + tenantId/审计自动填充 + @TableLogic Integer deleted 置 1）；`R<T>` 统一返回（HTTP 200 + 业务码）；`@RequirePermission` 注解权限；租户拦截器自动注入 tenant_id（**t_governance_log 在 IGNORE_TABLES，SQL 必须手写 tenant_id 且不与业务表 JOIN**）；控制器先例=ProjectController（safeJson 审计、跨租户查 null→404 不泄露存在性）；前端独立仓 eaiselp-web-separate（jQuery+Bootstrap，bizXxx 传对象，菜单走 menu.js）。

---

## 0. 四源漂移收敛表（PRD/SE/V5/代码现状，Dev/QA 照此执行）

| # | 漂移点 | SE 技术方案 | V5 落盘 / 代码现状 | **收敛裁决（执行口径）** |
|---|---|---|---|---|
| C1 | 依赖归一化载体 | dependency_type 归一 depends_on + **orig_type 列**留原始类型 | **无 orig_type 列**；`note` VARCHAR(500) 注释预留"保留用户原始表述" | 行为按 D-5（编排者已裁决）：blocks 登记换向存 from=被阻塞方、dependency_type 归一为 **depends_on**（V5 注释里的 blocks 存储值应用层不写）；orig_type 语义由 **note 承载**——创建时原始类型=blocks 则 note 写结构化前缀 `[orig:blocks]`+用户备注，读取时解析还原（失败默认 depends_on 文案）。uk(tenant,from,to,dependency_type) 对归一化语义重复天然去重，AC-F3.1 由 DB 兜底 |
| C2 | 唯一键与逻辑删 | uk 五列含 is_deleted BIGINT（删除置=id） | **uk 四列不含 is_deleted**；全库 BaseEntity @TableLogic Integer（置 1） | 删后同对重登必撞 uk。收敛=**复活语义**：DuplicateKey 时经 Mapper 自定义 SQL（绕过 @TableLogic select 过滤）查同 (tenant,from,to,type) 逻辑删行，命中则 UPDATE 复活（is_deleted=0、刷新 note/update_by），审计 detail 标 `"revived":true`；无逻辑删行命中才报 400 唯一冲突。提请 DBA 在《数据库设计说明》记为已知边界（V6 可选优化） |
| C3 | ADR 废弃说明 | deprecate_reason VARCHAR(500) 列 | **无该列** | transit→deprecated 时 deprecateReason **仍为请求必填**（空=400，PRD §4.4.2 行为权威）；值写入 t_governance_log 审计 detail（adr_transit detail.deprecateReason）并在响应返回；详情接口从最近一次 transit 审计回显该字段。不加列不改 V5；V6 候选优化项提请 DBA |
| C4 | 列名/宽度 | ms_code/owner_name/name/review_date/remark/context·decision·consequences/related_principles/gate_result VARCHAR(8) | **milestone_code/owner/tech_name/reviewed_at/note/context_text·decision_text·consequence_text/related_principle_codes/gate_result VARCHAR(16)** | **实体与 SQL 一律按 V5 列名**（gate_result 宽度 DBA 纠偏正确：FAIL_WARN 9 字符）；API JSON 字段按语义命名（milestoneCode/owner/name/reviewedAt/remark/context/decision/consequences/relatedPrincipleCodes），实体映射处注释标注差异 |
| C5 | 环检测错误码 | SE §8.3=400 | PRD AC-F3.3="400 业务错误" | **400**（PRD 验收基线+SE 双源一致；编排简报提及"409"为口径笔误，不采用） |
| C6 | 里程碑前缀 | D-4：/api/v1/milestones 独立前缀注册 L2 组 | PRD §4.2.8 建议挂 programs/projects | 按 SE D-4：milestones / project-dependencies / metrics 三前缀统一注册 LayerGuard（语义等价，AC 断言 43002 与数据保留而非前缀）；群聚合时间线保留 `/api/v1/programs/{id}/milestone-timeline` 挂靠端点 |
| C7 | 依赖删除细粒度权限 | PRD §4.3.6"删除限依赖双方项目经理或 tenant_admin" | t_project 无负责人字段；V5 seed 只授 tenant_admin/project_manager（全体 pm） | 不可判定，按 V5 seed 执行（AC-RBAC.1 断言口径），记录为已知能力边界；项目负责人字段属 PRJ-003 后续 |
| C8 | Milestone 实体位置 | "新建 hierarchy/Milestone.java" | **eaiselp-data/entity/Milestone.java + MilestoneMapper.java 已存在**（V1 产物，已复核：映射 legacy milestoneId/programId/title/targetDate/status/subprojects/integrationPoints/blocker，继承 BaseEntity） | **改造既有文件**补 6 字段，不新建；ProjectDependency/Adr/TechRadarItem 三对按 SE 新建于 runtime.hierarchy（与 Project 同包） |

其余核对一致：gate_result VARCHAR(16)；权限 seed 1046~1058 + 授权行 2092~2133 与 PRD §4.6 角色矩阵逐行一致；idx_tenant_action_time 已落 V5（DORA 查询性能前提）；34 AC 口径无漂移。

---

## 1. 任务清单（30 任务 / 5 批 / 111h ≈ 14 人日）

> 类型图例：实体=数据层脚手架｜逻辑=纯算法/状态机（无 Mapper 依赖，单测友好）｜服务=Service 业务层｜接口=Controller｜改造=既有文件增量｜前端=eaiselp-web-separate｜回归=测试验证。依赖列写任务号；"—"=可立即开工。

### 批① 实体 + Mapper + 纯逻辑（9 任务全部并行，16h）

| # | 任务 | 类型 | 模块 | 依赖 | 估时 | AC 映射 | 说明 |
|---|---|---|---|---|---|---|---|
| T1 | gate_result 埋点数据链：`Derivation` 实体 +`gateResult` 字段（PASS/FAIL/FAIL_WARN/NULL）；`DerivationEngine.DerivationResult` +`derivationId` 字段；`DerivationPersistenceService.persist` 末尾回填 `result.setDerivationId(d.getId())`（异步 UPDATE 预占/同步 INSERT 两路径都回填） | 改造 | eaiselp-data / engine | — | 1h | AC-F1.4（前置） | 三处小改一任务闭环，纯增量零行为变化（Mapper 走 BaseMapper 无需动）；高风险接线在 T21 |
| T2 | `Milestone` 实体激活改造（**既有文件，C8**）：+ownerType/ownerId/milestoneCode/description/owner/achievedDate 六字段；legacy milestoneId/programId 标 `@TableField(updateStrategy=NEVER)` 只读不写；blocker/subprojects 语义沿用（PRD §4.2.2）；integrationPoints 保留映射不启用 | 实体 | eaiselp-data/entity/Milestone.java | — | 1h | AC-F2.1 | 死表无存量数据零迁移；status 列存 planned/achieved/delayed |
| T3 | `ProjectDependency` 实体+Mapper 新建：fromProjectId/toProjectId/dependencyType/note（V5 列名，API 语义名 remark，C1/C4）；继承 BaseEntity；Mapper 附两条自定义 SQL——①绕过 @TableLogic 查含逻辑删行（复活语义用，C2）②复活 UPDATE（手写 tenant_id 条件不依赖拦截器） | 实体 | runtime/hierarchy（新建 2 文件） | — | 1h | AC-F3.1/F3.5 | 自定义 SQL 是 C2 收敛的载体，随实体一并交付 |
| T4 | `Adr` 实体+Mapper 新建：adrCode/title/status/contextText/decisionText/consequenceText/relatedPrincipleCodes(JSON→String)/supersededBy/decisionDate/author；**无 deprecateReason 字段**（C3） | 实体 | runtime/hierarchy（新建 2 文件） | — | 1h | AC-F4.1 | JSON 数组以 String 承载，Jackson 序列化/解析在 Service 层 |
| T5 | `TechRadarItem` 实体+Mapper 新建：techName/quadrant/ring/reason/reviewedAt/remark（C4） | 实体 | runtime/hierarchy（新建 2 文件） | — | 1h | AC-F5.1 | 枚举校验在 Service（T13），实体保持先例风格不加校验注解 |
| T6 | `MilestoneStatus` 状态机枚举：planned→achieved（achievedDate 必填）/ planned→delayed / delayed→achieved / achieved→planned（撤销，清 achieved_date）；canTransitionTo + fromDbValue + requiredFieldsFor(target)；复刻 CaseStatus 先例（枚举纯逻辑不抛异常，上层抛 400） | 逻辑 | runtime/hierarchy | — | 2h | AC-F2.2/F2.3 | 自身幂等合法（同 CaseStatus 并发重试语义）；**系统永不自动置 achieved/delayed**——逾期仅 Vo.overdue（T10） |
| T7 | `AdrStatus` 状态机枚举：proposed→accepted / accepted→deprecated（必填 deprecateReason）/ accepted→superseded（必填 supersededBy、目标须 accepted 且≠自身）；deprecated/superseded 终态无出边；requiredFieldsFor(target) | 逻辑 | runtime/hierarchy | — | 2h | AC-F4.2 | 必填项规则内聚枚举，Service 只调用不重复定义 |
| T8 | `DependencyCycleDetector` 三色 DFS：`wouldCycle(strongEdges, from, to)` 新边预检（从 to 出发回 from，沿 parent 链还原环路径）+ `findCycles(strongEdges)` 全图体检（环序列按最小节点旋转规范化后 Set 去重）；防御：递归深度>max(1000,V)、节点>10000、边>50000 拒绝；附单测（两节点环/三节点环/空图/自环/去重） | 逻辑 | runtime/hierarchy | — | 4h | AC-F3.3/F3.4 | **只进 dependency_type='depends_on' 强边**（relates_to 豁免不进图）；路径还原是 400 提示的数据源 |
| T9 | 六个 DTO：`DoraBoardVo`（四指标卡+打回率+样本数+excludedCount/approximateCount/parseErrorCount+emptyState，字段名=api-contracts §1 契约）/ `DependencyBoardVo`（项目卡+blockedSources+waitingFor/responsibleFor+统计条）/ `MilestoneTimelineVo`（含 ownerLevel/statusColor/overdue）/ `AdrVo`（含 principleSyncHints）/ `TechRadarVo`（含 pendingReview）/ `ProjectDetailVo` 扩展（+achievementHint+依赖区块字段） | 实体 | runtime/hierarchy/dto | — | 3h | 全功能结构载体 | ProjectDetailVo 只加字段不删改（前端向后兼容）；异常降级 null 的字段在 Vo 注释标明 |

### 批② 五 Service（5 任务并行，31h；T14 最重单独专人）

| # | 任务 | 类型 | 模块 | 依赖 | 估时 | AC 映射 | 说明 |
|---|---|---|---|---|---|---|---|
| T10 | `MilestoneService(Impl)`：CRUD（milestoneCode 缺省服务端生成 `MS-`+租户内自增序、uk 兜底重试 3 次；ownerType/ownerId 归属存在性校验 404；title 必填≤200）+ transit 统一入口（状态机校验/achievedDate 默认当天/撤销清空）+ 逾期展示层标记（target_date<today 且 planned→Vo.overdue，不改库）+ 群聚合时间线（群直属+成员项目合并、ownerLevel 标签，命中 idx_ms_tenant_owner）+ isAchievableHint(projectId) 辅助方法（供 T22）+ 审计 milestone_create/update/delete/transit（detail 含 owner/title/状态前后值，撤销含 clearedAchievedDate:true） | 服务 | runtime/hierarchy | T2,T6,T9 | 6h | AC-F2.1~F2.3/F2.6 | legacy 两列一律不写；subprojects 群级多选仅展示不参与自动判定 |
| T11 | `DependencyService(Impl)`：创建归一化（blocks 换向→depends_on + note 前缀 `[orig:blocks]`，C1）+ 硬校验（from≠to 400/两端存在 404/类型枚举 400）+ 环预检（调 T8，成环 400+路径提示+WARN 日志，C5）+ DuplicateKey→复活/唯一冲突翻译（C2）+ blocked 看板聚合（一次查全部活跃边+涉项目状态快照内存判定；强依赖对端 status∉{delivered,closed}→blocked+阻塞链文案"被 Q 阻塞：Q 未交付"；relates_to 不计）+ 全图体检 + 审计 dependency_create/update/delete | 服务 | runtime/hierarchy | T3,T8,T9 | 5h | AC-F3.1~F3.4 | blocked 展示层实时不落库（交付即自动解除）；看板统计条 totalProjects/blockedCount/edgeCount |
| T12 | `AdrService(Impl)`：CRUD（五段式必填空串 400/adrCode 缺省 `ADR-`+自增序 uk 重试）+ transit（AdrStatus 校验/supersededBy 目标存在且 accepted 且≠自身/deprecateReason 必填）+ relatedPrincipleCodes 逐 code 存在性校验（不存在整单 400 指名"原则 P99 不存在"）+ principleSyncHints 组装（流转离开 accepted 且关联非空→[{code,title}]）+ 按状态（默认 proposed+accepted）/原则 code（JSON_CONTAINS 或内存过滤）/关键词筛选 + 按 code 反查 ADR 列表（供 T17 反查端点）+ 审计 adr_create/update/delete/transit（detail 含状态前后值、superseded_by 链、deprecateReason，C3） | 服务 | runtime/hierarchy | T4,T7,T9 | 5h | AC-F4.1~F4.4 | 废弃说明不落列：审计 detail+响应回显（C3）；**提示而非自动**——系统不写 t_architecture_principle |
| T13 | `TechRadarService(Impl)`：CRUD（quadrant/ring 四值枚举校验 400/reviewedAt 必填）+ 同名唯一冲突→400 提示"编辑既有项" + **环移动审计**（改 ring 时 radar_update detail 记 {"name","fromRing","toRing"}，唯一留痕）+ 待复审标记（reviewedAt<today−180d→Vo.pendingReview，不阻塞任何操作）+ 审计 radar_create/update/delete | 服务 | runtime/hierarchy | T5,T9 | 3h | AC-F5.1/F5.3/F5.4 | 雷达版本管理=范围外（审计留痕即闭环） |
| T14 | `DoraMetricsService`（**本 Case 最重任务，专人**）：scope 三态解析（project/program/all→项目 id 池；project_id 为空 Case 在 Case 池即被 IN 条件排除，AC-F1.6 的 C9）→ 四段两段式查询（**t_governance_log 手写 tenant_id+action='case_transit'+create_time 下界命中 idx_tenant_action_time**；t_case/t_orchestration/t_derivation 走拦截器；IN 分批 500；**不跨 IGNORE 边界 JOIN**）→ 内存计算：**DF**（done 审计 detail 解析 targetStatus='done' 按 Case 去重取最早/÷periodDays 自然日）；**LT**（doneTs−create_time；P50 线性插值 PERCENTILE.INC 语义/P90 向上取整序位 ceil(0.9N)；t_case.status=done 但无审计的 Case 排除计 excludedCount M，**严禁 update_time 冒充**）；**CFR**（分母=终态编排 Case 去重；分子两源合一防双计：①steps_json 存在 status=failed 且 gateResult=FAIL 步骤 ②auto_check 阻断=编排 failed 且 validation_json.allPassed=false 且无①；FAIL_WARN 不计；解析失败计 parseErrorCount 单卡降级"该项暂不可用"）；**RT**（样本框=周期内 done 且非 CFR 分子；按 caseId+role 分组：有埋点→max(PASS finished_at)−min(FAIL finished_at)；无埋点多行→末条 finished_at−首条 started_at 记 approximate=true"≈"角标，**不回填历史已裁决 D-2**）+ 打回率参考值 + **5min TTL 缓存**（ConcurrentHashMap，key=tenantId\|scope\|scopeId\|periodDays，未命中计算打结构化日志 tenantId/scope/四指标样本数/耗时） | 服务 | runtime/hierarchy | T1,T9 | 12h | AC-F1.1~F1.6 / AC-ISO.3 | 口径唯一权威=PRD §4.1.2、落地规则=SE §4.3；单测先锁构造值（0.1 次/天、36h/48h、33.3%、45min、M=1）；指标独立降级不 500 整页；缓存 TTL=300s 恰好满足"延迟≤5 分钟" |

### 批③ 五 Controller + LayerGuard 扩展 + 埋点接线（8 任务并行，23h）

| # | 任务 | 类型 | 模块 | 依赖 | 估时 | AC 映射 | 说明 |
|---|---|---|---|---|---|---|---|
| T15 | `MilestoneController`（/api/v1/milestones）：GET 分页（ownerType/ownerId/status 过滤）/ POST / GET{id} / PUT{id} / DELETE{id} 逻辑删 / POST {id}/transit + `ProgramController` 增 `GET /api/v1/programs/{id}/milestone-timeline` 挂靠端点；@RequirePermission milestone:view/create/edit；全写审计 | 接口 | runtime/controller（新建 1+改造 1） | T10 | 3h | AC-F2.1/F2.2/F2.6 | 复刻 ProjectController 先例（R<T>+safeJson+跨租户 404）；契约=api-contracts §2 |
| T16 | `DependencyController`（/api/v1/project-dependencies）：GET 列表（projectId/type 过滤、依赖方视角）/ GET board / POST（归一化创建）/ PUT{id} / DELETE{id}（seed 权限口径，C7）/ GET cycle-check?from=&to= / GET full-check；@RequirePermission dependency:view/create/edit | 接口 | runtime/controller | T11 | 3h | AC-F3.1~F3.4 | 环拒绝 400 携带成环路径（C5）；契约=api-contracts §3 |
| T17 | `AdrController`（/api/v1/adrs）：GET 筛选列表 / POST / GET{id}（含 deprecateReason 审计回显）/ PUT{id} / DELETE{id} / POST {id}/transit（响应携 principleSyncHints）+ `PrincipleController` 增 `GET /{id}/adrs` 反查（principle:view）；@RequirePermission adr:view/create/edit | 接口 | runtime/controller（新建 1+改造 1） | T12 | 3h | AC-F4.1~F4.4 | **不注册 LayerGuard**（不限层）；契约=api-contracts §4 |
| T18 | `TechRadarController`（/api/v1/tech-radar）：GET 列表（quadrant/ring 过滤，含 pendingReview）/ POST / GET{id} / PUT{id}（环移动审计在 T13）/ DELETE{id}；@RequirePermission radar:view/create/edit | 接口 | runtime/controller | T13 | 2h | AC-F5.1/F5.3 | **不注册 LayerGuard**；契约=api-contracts §5 |
| T19 | `DoraMetricsController`（/api/v1/metrics）：GET /dora?scope=project\|program\|all&scopeId=&periodDays=7\|30\|90（参数非法 400：scope 未知/scope≠all 缺 scopeId/periodDays 不在三档）；@RequirePermission dora:view | 接口 | runtime/controller | T14 | 2h | AC-F1.1~F1.6 | 薄控制器，聚合与缓存全在 T14；契约=api-contracts §1 |
| T20 | LayerGuard L2 组扩展：`LayerGuardInterceptor` 前缀硬编码改**集合判断**（L2 组含 programs/projects/**milestones/project-dependencies/metrics** 五前缀，L3 组 strategies 不变）+ `RuntimeWebMvcConfig.addPathPatterns` 增三前缀；order=3 与 OPTIONS 放行不变 | 改造 | hierarchy + config | 代码可先行（联测挂 T15/T16/T19） | 2h | AC-SWITCH.1 | 两处小改（F-25）；关闭→HTTP 200+43002 非 500；**先回归既有 strategy/programs/projects 三前缀行为不变**，再验三新前缀 |
| T21 | **埋点接线（独立高风险）**：`OrchestrationService.runAsync` 门禁判定块（L612-671）三支汇合处插 `markGateResult(result.getDerivationId(), gateValue)`——FAIL+warn→FAIL_WARN / FAIL+超限→FAIL / FAIL+打回→FAIL / gate=PASS→PASS / **gate=null→不写保持 NULL**；私有方法 try-catch 全吞仅 ERROR 日志；`executeStep` 断点续跑路径不接线（保持 NULL，RT 走近似分支口径自洽）；完成即跑编排冒烟（打回+终判两路径） | 改造 | orchestration | T1 | 4h | **AC-F1.4** / PRD §6.3 | 铁律：**埋点失败绝不影响编排主流程**（打回/重做/终判行为逐分支比对不变）；不采用 WHERE case_id+role LIMIT 1 竞态写法（SE §4.4）；**QA 造数约束：AC-F1.4 断言数据必须产生在本任务合并之后** |
| T22 | `ProjectServiceImpl` 改造：①deleteWithUnlinkCase 增依赖边联动逻辑删（from=id OR to=id 全部边，同 t_project_principle 先例）②detail() 增 achievementHint（case_total>0 && case_done==case_total && 存在 planned 里程碑→{eligible,milestoneIds,message}，调 T10 判定，复用 F8 汇总字段零新口径）与依赖区块数据（等待/责任两组、对方已删边过滤）——try-catch 降级 null **不阻塞详情主渲染** | 改造 | hierarchy + dto | T3,T10,T11 | 4h | AC-F2.4/F2.5/F3.5 | 空项目（total=0）eligible=false（AC-F2.5）；回归项目详情既有字段不受影响 |

### 批④ 前端（6 任务并行，28h；eaiselp-web-separate，jQuery 增量不重构）

| # | 任务 | 类型 | 模块 | 依赖 | 估时 | AC 映射 | 说明 |
|---|---|---|---|---|---|---|---|
| T23 | menu.js 增量：COMMON_MENUS 增 adr-list/radar-quadrant（恒显）；tenant_admin/project_manager 增 dora-board/milestone-board/dependency-board；executive 增同三个只读入口；layerHidden 前缀扩为 `/^(strategy-\|program-\|project-\|dora-\|milestone-\|dependency-)/`（adr-/radar- 不匹配→恒显）；**勿动既有判断结构** | 前端 | assets/js/menu.js | 可先行 | 1h | AC-SWITCH.1/.2、AC-RBAC.2 | 菜单只控入口；编辑按钮按权限码显隐在各页面实现（登录响应含权限码） |
| T24 | `pages/dora-board.html`：四指标卡（数值+单位+**分档徽标（前端常量集中一处定义，P6 裁决禁散落）**+样本数 N+口径 tooltip+≈/排除/暂不可用标注）+ 三态过滤（项目/项目群/全部）+ 周期切换（7/30/90 即时刷新）+ 打回率小字 + 两档空态（无项目→"先创建项目并关联 Case"；有项目无数据→"暂无统计数据，完成 Case 后自动生成"）+ 43002 友好提示 | 前端 | pages（新建） | T19（可凭 api-contracts §1 mock 提前） | 6h | AC-F1.1~F1.6 / AC-SWITCH.1 | approximateCount>0→"≈"角标；excludedCount>0→"另有 M 条历史数据不可回溯，已排除"；parseErrorCount>0→该卡"暂不可用"；CFR 卡明示"门禁终判失败口径（代理指标）" |
| T25 | 里程碑前端三件：`pages/milestone-board.html`（租户全量聚合时间线+owner 过滤+状态色 planned 蓝/achieved 绿/delayed 红+逾期黄角标+有权限者流转操作）+ project-detail.html 增时间线区块与**顶部达成提示条**（achievementHint 驱动→点击确认达成弹层，达成日期默认当天，确认后消失）+ program-detail.html 增群聚合时间线区块（层级标签） | 前端 | pages（新建 1+改造 2） | T15,T22 | 6h | AC-F2.1~F2.6 | **提示是唯一联动、确认永远人工**；无 milestone:edit 权限只渲染时间线不出操作钮；区块随 L2 菜单隐藏 |
| T26 | 依赖前端两件：`pages/dependency-board.html`（**独立页，SE 裁决不并入 project-list**——项目卡 waitingFor/responsibleFor 两组边+blocked 徽标+阻塞来源链+"仅看被阻塞"筛选+全图体检入口（成环路径展示）+统计条+穿透项目详情；登记表单含"A 阻塞 B"快捷录入，新建前先调 cycle-check 实时提示）+ project-detail.html 增依赖区块（"对方项目已删除"边过滤） | 前端 | pages（新建 1+改造 1） | T16,T22 | 5h | AC-F3.1~F3.5 | 换向归一在后端；列表统一依赖方视角 |
| T27 | `pages/adr-list.html`（列表：状态默认 proposed+accepted/关联原则/关键词筛选；五段式详情/编辑弹层；状态流转操作——superseded 条件必填指向、deprecated 条件必填说明；**principleSyncHints 提示条**+"请检查原则内容是否需要同步更新"+原则管理直达链接）+ `principle-list.html` 增"关联的 ADR 列表"区块（调反查端点，含状态） | 前端 | pages（新建 1+改造 1） | T17 | 5h | AC-F4.1~F4.4 | **恒显不限层**；系统不自动修改原则内容（前端无任何写原则入口） |
| T28 | `pages/radar-quadrant.html`：**纯 SVG 自包含**四象限图（4 扇区 techniques 上左/tools 上右/platforms 下右/languages 下左 × 4 同心环 adopt 最内→trial→assess→hold 最外；hold 红系视觉警示；技术项=扇区内点+标签；点击点→详情/编辑）+ 列表保底视图切换（名称/象限/环/评审日期/理由摘要，按象限/环筛选，两视图同数据，>80 项降级方案）+ 待复审角标 | 前端 | pages（新建） | T18 | 8h | AC-F5.1~F5.4 | **禁外部 CDN/在线图表服务（离线部署硬约束）**，jQuery 动态拼 `<svg>`；网络面板零外域请求为验收自检 |

### 批⑤ 回归（2 任务，10h）

| # | 任务 | 类型 | 模块 | 依赖 | 估时 | AC 映射 | 说明 |
|---|---|---|---|---|---|---|---|
| T29 | 后端回归：PRJ-002 全部 AC（开关/注入/汇总/下钻）+ Case 全生命周期 + **编排门禁四分支（warn 放行/打回/终判/正常 PASS）与 T21 改造前逐项等价**+ 埋点隔离（模拟 gate_result 写入异常→编排不受影响）+ V5 幂等重放（CREATE IF NOT EXISTS/INSERT IGNORE；**手工重放先修 V4 line200 `--（` 注释语法**，仅修重放副本不改仓内已发布文件）+ 存量功能零破坏 | 回归 | 全后端 | T20,T21,T22 | 5h | PRD §6.3 / AC-SWITCH.3 | T21 等价性证明是核心：四分支日志与库内步骤状态逐一比对；开关可逆（关 L2 数据保留，重开 DORA 数值一致） |
| T30 | 全链路联调：34 AC 冒烟（QA 用例基线=PRD §5 逐条）+ 三矩阵——**开关矩阵**（L2 开/关×ADR/雷达恒可用×数据保留可逆）、**权限矩阵**（PM 9 项/engineer 2 项/executive 5 项对 seed 逐格核）、**租户矩阵**（T1/T2 隔离+跨租户直达 404，含 DORA 手写 tenant_id 路径）+ 前端五页×菜单显隐三组合（L3 on/off×L2 on/off） | 回归 | 前后端 | 批④ 全部 | 5h | 全部 34 AC | DORA 数值断言照 PRD §4.1.2 公式（0.1 次/天/36h/48h/33.3%/45min/M=1/≈角标/C9 不计入）；环检测两节点+三节点+relates_to 豁免；AC-SWITCH.2 否定性用例（关 L2+L3 后 ADR/雷达全功能）；时间断言 ±1 分钟 |

---

## 2. 并行性与关键路径

```
批①（9 任务并行，16h）──┬── T2+T6+T9 ──→ T10(6h)──T15(3h)──T25(6h)──┐
                        ├── T3+T8+T9 ──→ T11(5h)──T16(3h)──T26(5h)──┤
                        ├── T4+T7+T9 ──→ T12(5h)──T17(3h)──T27(5h)──┼── T30(5h)
                        ├── T5+T9 ─────→ T13(3h)──T18(2h)──T28(8h)──┤
                        ├── T1 ──┬─────→ T14(12h)──T19(2h)──T24(6h)──┤   ★关键路径
                        │        └─────→ T21(4h)────────────→ T29(5h)─┘
                        ├── T3+T10+T11 → T22(4h) → T25/T26
                        └── T20(2h)、T23(1h)：随时插入（联测挂靠对应批）
```

- **并行分组**：批① 九任务零依赖全并行；批② 五 Service 并行（T14 建议专人+最先派发）；批③ 八任务并行且 T20/T21/T23 可与批② 同步开工（T21 仅依赖 T1）；批④ 六前端任务并行（T24~T28 可凭 api-contracts.md mock 提前，后端就绪后联调）；批⑤ 收口。
- **关键路径**：`T1→T14→T19→T24→T30` = 1+12+2+6+5 = **26h（≈3.3 人日）**。次长：里程碑链 T9→T10→T15→T25→T30=23h；依赖链 T8→T11→T16→T26→T30=22h；雷达前端 T28 单任务 8h 为前端最长。
- **里程碑建议**：批① 完成=数据层基线冻结；**T21 合并=T24 前 QA 可开始 AC-F1.4 造数**（gate_result 断言数据必须产生在埋点上线后——造数早于 T21 会把口径问题误判为代码 bug）。

## 3. 高风险清单（认领须带验收自检）

| # | 任务 | 风险 | 自检要点 |
|---|---|---|---|
| R1 | T21 埋点接线 | 触碰编排主循环：写入点错位（三支漏一支）、derivationId 回填不全、异常中断派生 | markGateResult 全 try-catch ERROR-only；四分支行为与改造前逐分支等价（T29 专项）；gate=null 保持 NULL；QA 造数时点≥T21 合并 |
| R2 | T14 DORA 聚合 | 口径错（去重/分位数/两源分子防双计）、跨 IGNORE_TABLES JOIN、大 steps_json 解析抛 500 | 逐条对照 PRD §4.1.2+SE §4.3；governance_log 手写 tenant_id；单卡 parseError 只降级该卡；缓存 key 四元组完整；单测先锁四组构造值 |
| R3 | T8+T11 环检测与复活 | 路径还原错/relates_to 误入图/复活语义漏判（@TableLogic select=false 过滤掉逻辑删行） | 两/三节点环用例断言路径；只进 depends_on 边；复活走 T3 自定义 SQL；"登记→删除→重登"用例锁定 |
| R4 | T20 LayerGuard | 改既有拦截器：误拦存量（/api/v1/cases、principles 等）或漏拦三新前缀 | 先回归既有三前缀 43001/43002，再验新前缀关层即 43002（HTTP 200 非 500） |
| R5 | T28 SVG 雷达 | 引入外部 CDN 违反离线部署约束 | 纯 jQuery 拼 SVG；网络面板零外域请求；两视图数据一致 |
| R6 | C1~C3 收敛点 | Dev 凭 SE 原文实现 orig_type 列/deprecate_reason 列/uk 删除位 → 实体与 V5 不符 | 实体生成后逐一对照 V5 列名；本表为唯一执行口径，SE 方案差异处以本文件 §0 为准 |
| R7 | T22/T25 既有页面改造 | project-detail 增量异常阻塞主渲染；menu.js 改错隐掉既有菜单 | try-catch 降级 null；layerHidden 仅增前缀不改结构；开关四组合截图核对 |

## 4. AC 覆盖核对（34/34 无漏映射）

| AC 组 | 承载任务 |
|---|---|
| F1.1~F1.6（6） | T14/T19/T24（数据链 T1/T21；F1.4 断言含 T21 埋点） |
| F2.1~F2.6（6） | T10/T15/T25（F2.4/F2.5 提示判定=T22；F2.6 群聚合=T10/T15/T25） |
| F3.1~F3.5（5） | T11/T16/T26（F3.3 算法=T8；F3.5 删除联动=T22） |
| F4.1~F4.4（4） | T12/T17/T27（原则反查=T17；原则页区块=T27） |
| F5.1~F5.4（4） | T13/T18/T28（四象限渲染=T28） |
| ISO.1~ISO.3（3） | 实体/Mapper+Service 拦截器天然隔离（T2~T5）；DORA 手写 tenant_id=T14；跨租户直达 404=五 Controller；T30 联调 |
| RBAC.1~RBAC.3（3） | V5 seed（1046~1058+2092~2133）+ T15~T19 @RequirePermission + T10~T13 审计全覆盖 + T30 矩阵逐格核 |
| SWITCH.1~SWITCH.3（3） | T20（三前缀注册）+ T23（layerHidden 扩展）+ T24~T26 随菜单隐藏 + T29/T30 可逆验证；SWITCH.2 由 T17/T18/T23/T27/T28 恒显保证 |

## 5. 变更影响评估（四维度）

**① 架构影响**
- 五新 Service 归 `runtime.hierarchy` 治理域，只向下依赖 eaiselp-data/eaiselp-common（P3 依赖方向不破坏）；orchestration 包唯一增量=对 DerivationService 的一个 UPDATE 调用（T21），编排不感知 DORA 存在。
- LayerGuardInterceptor 从硬编码分支改集合判断（T20）——形态演进语义零变化，后续新增 L2 前缀成本从"改 if 链"降为"加集合元素"。
- DORA 实时聚合+进程内 TTL 缓存（D-1）为平台首个治理数据缓存点；升级触发线已写死（单租户 done Case>2 万或 P95>800ms→日快照表 t_dora_snapshot 预案，计算逻辑下沉复用），预案不在本期范围。

**② 需求影响**
- 34 AC 全覆盖（§4 核对表）；PRD §4.2.8 里程碑前缀与 SE D-4 偏离已收敛（C6），群聚合时间线保留 programs 挂靠端点弥补下钻语义。
- 三处 PRD 行为在 V5 数据权威下调整实现载体（C1 orig_type→note、C3 deprecate_reason→审计、C7 依赖删除细粒度权限不可判定）——**验收口径不变**（400 错误/提示文案/审计留痕照 PRD），仅存储位置或执行主体变化；C7 为已知能力边界，QA 用例按 V5 seed 口径编写。
- 范围外红线重申（防镀金）：不回填历史埋点、不做 ADR diff 版本、不做依赖自动发现、DORA 趋势折线仅 P1 不阻塞验收。

**③ 接口影响**
- 新增 22 端点/五前缀（api-contracts.md 全量契约：入参/出参 JSON/错误码/环检测 400 路径/埋点数据契约）；**既有端点增量仅三处**且全部向后兼容——GET /api/v1/projects/{id}（+achievementHint+依赖区块，只加字段）、GET /api/v1/programs/{id}/milestone-timeline 与 GET /api/v1/principles/{id}/adrs（新增子资源），旧前端页面零感知。
- 错误码族：43002 语义复用不新增码；400 家族新增状态机/枚举/环路径/唯一冲突/复活冲突细分文案（api-contracts §8 总表）；无 5xx 新增面（DORA 单卡降级为 data 内 parseErrorCount，非错误码）。

**④ 规范影响**
- 领域字典枚举先例续用（MilestoneStatus/AdrStatus 复刻 CaseStatus 的 canTransitionTo+非法流转 400）；前端枚举文案（依赖类型/ADR 状态/雷达象限环/里程碑状态色/DORA 分档表）**集中一处常量定义**（P6 裁决），落公共 js 禁止散落多页面。
- 审计 action 新增 11 个（milestone_*/dependency_*/adr_*/radar_*），沿用 `<resource>_<verb>` 风格；环拒绝 WARN（含路径）、DORA 未命中结构化日志、埋点失败 ERROR 为 §6.6 新观测点。
- 逻辑删+uk 共存模式（C2 复活语义）首次成文——建议进工程标准"数据设计 checklist"（uk 是否含删除位→复活还是拒绝）。

**文档更新建议**
1. 《数据库设计说明》（DBA）：补记 C1/C2/C3 收敛裁决与 V6 候选优化（orig_type 列/deprecate_reason 列/依赖表 uk 删除位）；gate_result 不加索引的评估（§7 风险项）续记依赖表复活语义。
2. 《技术方案说明》（SE）：T30 回归通过后出一页勘误（orig_type/is_deleted 置=id/deprecate_reason/ms_code 等与 V5 差异处标注"以 tasks.md §0 为准"），防下期 Dev 误按方案原文实现。
3. PRD 不改版（验收口径未变）；本 tasks.md §0 为 Dev/QA 共同执行依据，QA 用例中 C5（400 非 409）与 C7（seed 口径）两处需特别对齐。
4. Swagger：五前缀端点随注解自生成（JWT 白名单已放行），上线核对 SPRINGDOC_ENABLED 生产策略。

---

## 本次经验沉淀

1. **四源对齐时"以落盘产物为权威"要显式裁决到列级并做一致性推演**：V5 与 SE 方案在依赖表上 4 处列级漂移中，"uk 不含删除位"不是简单改名——它直接推出"删后重登必撞唯一键"这一方案没写的实现缺口，拆任务时必须翻译成"复活语义+自定义 SQL 绕过 @TableLogic"的具体指令。收敛文档差异不能停在"标注谁权威"，要逐个语义点（唯一键/删除位/留痕列）问"这个语义在新结构下还成立吗，不成立时行为交给谁"。
2. **拆任务前用 5 分钟核实"方案说新建的文件是否已存在"**：SE 方案写"新建 hierarchy/Milestone.java"，而 eaiselp-data 的 Milestone.java/MilestoneMapper.java（V1 产物）一直存在——照方案拆会产出重复实体任务。实体/Mapper/拦截器这类锚点文件，BA 拆解时应 ls 真实目录把"新建/改造"类型标对。
3. **"先埋点后统计"类需求要把 QA 造数时点写进任务清单**：AC-F1.4 的 gate_result 断言数据必须产生在埋点上线（T21 合并）之后，造数早于此会把口径问题误判为代码 bug——代码依赖之外，数据产生时点的前置约束同样是任务清单的一等公民。
