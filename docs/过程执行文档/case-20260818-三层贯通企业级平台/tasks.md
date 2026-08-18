# 任务清单 — eAISEDP 三层贯通骨架（PRJ-002）

| 字段 | 值 |
|---|---|
| Case | case-20260818-三层贯通企业级平台 |
| 产出日期 | 2026-08-18 |
| 产出者 | team-ba（业务分析员） |
| 上游输入 | 需求设计说明.md v1.0（10 功能点/45 AC）/ 技术方案说明.md（模块清单/D-1~D-4 决策/30 端点/前端 9 页面）/ 数据库设计说明.md（V4 r2 六表+seed） |
| 编排者裁决 | ① V4 直改（未发布过，权威数据基线为 V4 r2，**SE 方案中 V5 相关内容作废**）；② 灵活接入原则编号 **P13**（V4 r2 seed 已按 P13 落盘，无需改）；③ 9 个开放问题按 PRD 默认方案执行（Q2 未绑定项目继承租户全局原则 / Q5 KPI 不自动回写 / Q9 场景B 不加 external_ref） |
| 落盘产物 | 本文件（Dev 认领的唯一任务来源） |
| 汇总 | **42 个任务，合计 26 人天，关键路径约 5.5 人天，建议 4~5 条并行线** |

---

## 0. 拆解说明（Dev 认领前必读）

1. **权威基线对账（三份上游文档的漂移已按编排者裁决收敛，遇冲突以下表为准）**：
   | 冲突点 | SE 方案 | DBA V4 r2 | 任务清单采用 |
   |---|---|---|---|
   | 迁移载体 | V5__three_tier_governance.sql | 直改 V4 r2 | **V4 r2**（裁决①） |
   | 留痕列名 | t_orchestration.**injection_json** | t_orchestration.**injected_json** | **injected_json**（实体字段 injectedJson，以已落盘 V4 r2 为准） |
   | 项目-原则绑定 | 无行级覆盖位 | t_project_principle.**enabled** 项目级覆盖位 | **含覆盖位**，注入语义按 DBA §3 契约（绑定行全 enabled=0 → 注入空集，不回退租户默认） |
   | 门禁 seed | 3 条（含 human_approval pre_deploy 规则 c） | 2 条（仅 reviewer/qa） | **T02 显式裁决项**（见高风险清单）：删 team-ops 硬编码后 pre_deploy 检查点行为依赖规则 c 承接 |
   | 权限 seed | 14 条 1032~1045（放 V5） | 未包含 | **T01 增补进 V4**（裁决①允许直改） |
2. 全部新增后端代码归 `com.eaiselp.runtime.hierarchy` 包；L1 编排代码（OrchestrationService/ContextAssembler）**禁止 import hierarchy 实体/服务**，只认 `String governanceContext` 与编排包内 `GateRuleSnapshot`（P3 单向依赖，SE §1）。
3. 每任务独立可编译可验证；顺序即依赖序，标"可并行"的同阶段任务互不依赖。
4. 所有新表/新查询走 MyBatis-Plus 租户拦截器（不进 IGNORE_TABLES）；非标准 SQL（子查询/updateText）禁止，汇总重算形态固定为"两条标准 count + 一条 LambdaUpdateWrapper set"（SE 经验沉淀 3 编码规约）。
5. 前端仓库在 `D:\AI\mywork\eaiselp-web-separate`（独立于 platform 仓库），jQuery + menu.js 增量接入，不重构框架。
6. 估时单位：人天（d）。AC 映射引用 PRD §5 编号，QA 据此直接写用例。

---

## 1. 任务清单

### 阶段 A：数据基线（串行，1.0d）

| # | 任务 | 类型 | 模块 | 依赖 | 估时 | 验收AC映射 | 说明 |
|---|---|---|---|---|---|---|---|
| T01 | V4 r2 增补权限 seed：t_permission 14 条（id 1032~1045：strategy:create/edit、program:edit、project:view/create/edit、principle:view/create/edit、gate:view/create/edit、tenant:layer:edit、case:delete）+ t_role_permission 授权行（id 2058 起），INSERT IGNORE 幂等 | DB迁移 | migration | — | 0.5 | AC-RBAC.1/2/3 | SE §10 矩阵逐行落 SQL；engineer 零授权、executive 无 program:edit、project_manager 无 program/principle/gate:edit 与 tenant:layer:edit；存量租户无角色新建动作（模板角色授权走 V1 机制核对） |
| T02 | 【裁决项】V4 r2 增补门禁 seed 第 3 条：human_approval / pre_deploy / block / enabled=1（规则 c，等价现状 team-ops 检查点） | DB迁移 | migration | — | 0.25 | AC-F6.5、AC-F6.3 | **PRD Q1 默认仅 2 条，但 SE §6.5 等价表证明：删 team-ops 硬编码（:267）后若无规则 c，升级后 pre_deploy 审批默认消失=行为变化**。建议采纳 SE 方案补第 3 条；若编排者维持 2 条，则 T21 需保留该行为变化的发布说明。已列高风险清单待确认 |
| T03 | V4 r2 本地验证：迁移执行、幂等重放（IF NOT EXISTS/INSERT IGNORE）、seed 断言（原则=租户数×6、门禁=租户数×2或3、开关列 DEFAULT 1）、本地库若已手跑旧草案的 flyway repair 演练 | DB验证 | migration | T01,T02 | 0.25 | AC-F5.3、AC-F10.4 | 产出验证记录；DBA §5 回滚 SQL 演练一遍 |

### 阶段 B：实体与 L1 数据结构（串行 1.5d，完成后 C/D/E/F 全面铺开）

| # | 任务 | 类型 | 模块 | 依赖 | 估时 | 验收AC映射 | 说明 |
|---|---|---|---|---|---|---|---|
| T04 | hierarchy 实体 6 个 + Mapper 6 个：Strategy（既有骨架补全）/Program/Project/ArchitecturePrinciple/QualityGateRule/ProjectPrinciple + 对应 6 个 BaseMapper 空接口 | 实体+Mapper | hierarchy | T03 | 0.5 | AC-ISO.1 | Project 的 progress/case_total/case_done 加 `@TableField(updateStrategy=NEVER)` 防手工覆盖（AC-F3.2）；ProjectPrinciple 含 enabled 覆盖位（DBA D2）；全表不进 IGNORE_TABLES；主键 ASSIGN_ID 雪花 |
| T05 | 既有实体/状态对象改造：Case.java + Long projectId（legacy programId/subproject 保留只读不写，Q6）；Tenant.java + strategyEnabled/programProjectEnabled；OrchestrationRecord.java + injectedJson（**注意 DBA 列名 injected_json**）；OrchestrationState.java + governanceContext/injection/gateRules，StepResult + gateRuleId/gateType/checkpointId（全可空）；编排包新增 record GateRuleSnapshot | 实体 | eaiselp-data + orchestration | T03（与 T04 可并行） | 0.5 | AC-F4.3、AC-F6.2 | StepResult 新字段可空是旧 steps_json 反序列化兼容的前提（R2），重启恢复旧记录无 gateRules 快照按步骤既有 gate 属性执行 |
| T06 | DerivationContext + governanceContext 字段；ContextAssembler.assemble 在"项目约定"之后、"上游产出"之前渲染 `## 架构原则与项目约束（必须遵循）` 章节；null/blank 整体省略 | L1改造 | context | T05 | 0.5 | AC-F7.1 | 标题字样是 AC-F7.1 断言锚点，一字不差；只拼接不取数（无状态组件保持零数据源依赖） |

### 阶段 C：基础 Service 层（T07~T12 六任务可并行，依赖 T04/T05）

| # | 任务 | 类型 | 模块 | 依赖 | 估时 | 验收AC映射 | 说明 |
|---|---|---|---|---|---|---|---|
| T07 | StrategyService：CRUD、生命周期流转（draft→active→achieved/archived 校验，achieved/archived 后仅 KPI 可改否则 400）、有关联 active 项目群拒删、board 聚合数据（KPI + 关联群列表 + 双层进度均值 ⌊Σ/n⌋） | Service | hierarchy | T04 | 1 | AC-F1.1/1.2/1.3、AC-F8.5 | 聚合不落库、单条 listMaps 批查防 N+1（R11） |
| T08 | ProgramService：CRUD（strategy_id 可空）、charter 编辑、删除时成员项目 program_id 置空后逻辑删、aggregateProgress（成员项目 progress 算术平均向下取整） | Service | hierarchy | T04 | 1 | AC-F2.1/2.2/2.3、AC-F8.5 | 置空用 LambdaUpdateWrapper 标准形态；二次确认由前端承担 |
| T09 | ProjectService：CRUD（program_id 可空、进度三列服务端强制忽略）、原则绑定全量替换（审计 bind_principle）、删除时 Case.project_id 置空后逻辑删、详情聚合（含已绑定原则 id/code 列表） | Service | hierarchy | T04 | 1 | AC-F3.1/3.2/1.3 | 绑定全量替换=删旧插新（uk 幂等）；进度字段双保险=实体 NEVER + Service 忽略 |
| T10 | PrincipleService：CRUD、code 租户内唯一校验（uk 冲突→409）、启停、删除同步清理 t_project_principle 绑定 | Service | hierarchy | T04 | 0.5 | AC-F5.1/5.2 | 清绑定 WHERE principle_id=? 反向删（关联表方案的意义所在，DBA D2） |
| T11 | QualityGateRuleService：CRUD、启停、loadEnabledSnapshot(tenantId)（WHERE enabled=1 AND is_deleted=0 ORDER BY priority，命中 idx(tenant_id,enabled,priority)）；规则级 maxRetries=null 时取 yml eaiselp.orchestration.gate-max-retries 兜底 | Service | hierarchy | T04 | 0.5 | AC-F6.1/6.2/6.4 | 快照不可变（record）；每次编排一条 SELECT，不做运行期缓存（D-3） |
| T12 | TenantLayerService：开关读写（t_tenant 直查）、ConcurrentHashMap 本地缓存写后主动失效（CacheAside 无 TTL）、guard(layer) 断言输出业务码 43001/43002、列读取失败降级为全开（DBA §5 兼容兜底） | Service | hierarchy | T05 | 0.5 | AC-F10.1~10.4 | t_tenant 在 IGNORE_TABLES 按 id 直查天然无越权面；写后失效保证管理员改完立即可见 |

### 阶段 D：两大核心机制（T13~T18；机制间可并行）

| # | 任务 | 类型 | 模块 | 依赖 | 估时 | 验收AC映射 | 说明 |
|---|---|---|---|---|---|---|---|
| T13 | GovernanceInjectionService（核心①）：resolve(caseId) 链路（projectId 空/项目已删→InjectionResult.empty+warn；有绑定行→绑定 ∩ 双 enabled=1；**绑定行全 enabled=0→空集不回退**（DBA §3 契约，SE §4.4 未含，以 DBA 为准）；无绑定行→租户全部 enabled=1）+ render（项目约束/逐条原则 must 级拦截提示）+ 单条 2000/总量 8000 截断（must>should>may→code 排序丢弃，truncated 留痕） | Service | hierarchy | T04、T10 | 1 | AC-F7.1~7.4 | ≤4 条标准 MP 查询（getOne/selectById/selectList/selectBatchIds），≤100ms；隔离靠拦截器（AC-F7.3） |
| T14 | 支撑组件：CaseDoneEvent（POJO，放 hierarchy/support，casestate↔hierarchy 解耦桥）+ AsyncConfig 注册 progressExecutor（core=1,max=2,queue=50）+ ProjectProgressListener（@Async @EventListener，catch Throwable 只记 ERROR） | 支撑组件 | hierarchy/support + config | T05 | 0.5 | AC-F8.1、AC-F8.4 | 异步事件解耦保证汇总失败不打断 transit（D-2）；不用 AFTER_COMMIT（transit 单条 update 自动提交，全量重算幂等兜底） |
| T15 | ProjectProgressService（核心②）：recalculate(projectId) 全量重算（两条 count + 一条 LambdaUpdateWrapper set 三列，total=0→progress=0，⌊done×100/total⌋）+ recalculateAsync 入口 | Service | hierarchy | T14 | 0.5 | AC-F8.1/8.2/8.3 | 幂等=读 DB 真值重写，事件重复/并发最后写胜出=正确值（R6）；禁子查询 UPDATE（拦截器风险 R4） |
| T16 | CaseStateServiceImpl.transit 尾部发布 CaseDoneEvent（updated==true 且目标态 done 且 projectId!=null；try-catch 包裹发布动作） | L1改造 | casestate | T14 | 0.25 | AC-F8.1、AC-F8.4 | casestate 只 import 事件 POJO 与 ApplicationEventPublisher，不 import hierarchy 服务（依赖方向） |
| T17 | CaseController 扩展 + CaseProjectService：create 加可选 projectId、page 加 projectId 过滤；新增 POST /api/v1/cases/{caseId}/project（挂接）、DELETE /api/v1/cases/{caseId}/project（解除，新旧项目都重算）、DELETE /api/v1/cases/{caseId}（逻辑删+重算）；挂接/创建带 projectId 前调 layerService.guardL2()（L2 关→43002，**存量已关联 Case 不受影响**）；写操作全审计（case_link/unlink/delete）；挂接前 projectMapper.selectById 走拦截器（跨租户 null→404 不泄露存在性） | Controller | controller | T12、T15、T01 | 1 | AC-F4.1/4.2、AC-F8.2/8.3、AC-F10.2 | Q7 最小更新裁决：仅挂接/解除两动作+删除，不开放通用编辑；case:delete 新权限原子由 T01 提供；AC-ISO.2 覆盖 |
| T18 | TenantProvisionService：新租户初始化灌 6 原则 + 2~3 门禁规则（雪花 ID）；TenantController.register 末尾调用，失败仅 warn 不阻塞注册 | Service+接线 | hierarchy/support + controller | T03 | 0.5 | AC-F5.3、AC-ISO.3 | 杜绝 tenant_id=0 全局行方案（会被拦截器过滤等于没灌，R8）；seed 内容与 T02 裁决结果联动 |

### 阶段 E：门禁规则化改造（高风险串行线：T19→T20→T21；T22 并行）

| # | 任务 | 类型 | 模块 | 依赖 | 估时 | 验收AC映射 | 说明 |
|---|---|---|---|---|---|---|---|
| T19 | applyGateRulesToSteps：stage 边界探测（post_dev=最后 code 步骤后/post_test=最后 test 步骤后/pre_deploy=首个 deploy 步骤前，基于规划后步骤产物类型）；llm_review 附着优先（stage 边界向前找最近 role==gateRole 步骤，追加 GATE 判定指令+记 gateRuleId）插入兜底（index 重排，artifactType=review）；human_approval 在边界标"审批闸"不新增步骤、同 stage 多条合并一次等待；auto_check 登记收尾检查清单；边界不存在→跳过+WARN | L1改造 | orchestration | T05、T11 | 1 | AC-F6.1、AC-F6.5 | FAST_PIPELINE 默认 reviewer 恰在边界前→附着→token 不翻倍；智能规划精简掉 reviewer→插入→治理强制生效；R3 合并等待防线程池饿死；R10 边界容错 |
| T20 | 门禁执行期三类型分派（while 循环内）：llm_review 复用 parseGateResult/extractGateReason/findRerunTarget，FAIL+block→打回重做（超规则级 max_retries→该步 failed+后续 skipped+不部署），FAIL+warn→FAIL_WARN 记录放行，max_retries=0→首败即终判；human_approval 改造 awaitApproval（入参 ruleIds+stage，检查点 operation=orchestration_gate_{stage}_{orchId}，APPROVED 继续/REJECTED、TIMEOUT 跳过其后 deploy 步骤，服务异常降级放行+ERROR）；auto_check 在 gitService.commitWorkspace 之前执行（check_key→检查器 Map 注册表分发，本期仅 code_validation 对接 CodeValidationService；block→终判失败不 commit/push/不触发 CI；**无 auto_check 规则时验证仍执行只记录**，升级等价） | L1改造 | orchestration | T19 | 1 | AC-F6.1/6.4/6.5/6.6 | 复用既有 GATE:PASS/FAIL 协议与检查点页面/30 分钟超时；"忘输出判定视为 PASS"现状语义保留 |
| T21 | 【高风险·编排者点名独立任务】**删除 OrchestrationService 硬编码**：GATE_ROLES 常量（:91）、isGateStep（:139）、`"team-ops".equals(role)` 检查点硬编码（:267）——切换至 T19/T20 数据驱动链路；按 SE §6.5 升级等价性表逐行验证；确认代码中不存在任何按角色常量集合的固定门禁与隐藏兜底 | L1重构 | orchestration | T19、T20、T02 | 0.5 | **AC-F6.3**、AC-F6.2 | AC-F6.3 验收对象是**代码形态**（零规则不拦截且无内置集合），必须在 T19/T20 新链路就绪并验证等价后再删旧路径；gate-max-retries yml 保留为兜底默认（PRD F6.4） |
| T22 | runAsync 注入点接线（D-1）：TenantContext.set 之后、智能规划之前调 governanceInjectionService.resolve 一次→state.governanceContext/injection；解析失败降级"无注入"+warn 绝不中断；persistState 顺带写 t_orchestration.injected_json（三处留痕之二）；每步 DerivationContext.builder().governanceContext(...) 复用同一份；runFromStep 断点续跑从 state 重建天然复用；规则快照 state.setGateRules(loadEnabledSnapshot) 挂本任务或 T19（同一入口块） | L1改造 | orchestration | T06、T13 | 0.5 | AC-F7.1（清单+日志两处留痕）、AC-F4.3、AC-F6.2 | 注入摘要日志 `[Inject] caseId/projectId/原则codes/含项目约束/字符数/截断`（三处留痕之三）；projectId 空→resolve 直通 empty，不产生章节不发布事件 |

### 阶段 F：治理 Controller 层（T23~T28 可并行，依赖对应 Service + T01 权限 seed）

| # | 任务 | 类型 | 模块 | 依赖 | 估时 | 验收AC映射 | 说明 |
|---|---|---|---|---|---|---|---|
| T23 | StrategyController + DTO：GET 分页（默认 status=active）/POST/GET {id}/PUT {id}（achieved/archived 后仅 KPI 可改）/POST {id}/transit/DELETE {id}（有关联拒删 400）/GET {id}/board；@RequirePermission(strategy:*)；写操作审计 strategy_create/update/transit | Controller | hierarchy | T07、T01 | 0.5 | AC-F1.1~1.4 | 契约按 SE §8.2 JSON；P13 路径前缀 /api/v1/ |
| T24 | ProgramController + DTO：GET 分页（含项目数/进度均值）/POST（strategyId 可空）/GET {id}（章程全文+项目列表+进度均值）/PUT/DELETE；@RequirePermission(program:*)；审计 program_update/delete | Controller | hierarchy | T08、T01 | 0.5 | AC-F2.1~2.3 | |
| T25 | ProjectController + DTO：GET 分页（programId 过滤）/POST（进度字段服务端强制 0/0/0）/GET {id}（含原则列表）/PUT（进度三列忽略）/PUT {id}/principles（全量替换）/DELETE；@RequirePermission(project:*)；审计 project_update/bind_principle | Controller | hierarchy | T09、T01 | 0.5 | AC-F3.1~3.3 | |
| T26 | PrincipleController + DTO：GET 全量/POST（code 冲突 409）/PUT/PUT {id}/enabled（启停即时影响新编排注入）/DELETE（清绑定）；@RequirePermission(principle:*)；审计 principle_update/enabled | Controller | hierarchy | T10、T01 | 0.5 | AC-F5.1/5.2 | |
| T27 | QualityGateRuleController + DTO：GET（priority 排序）/POST/PUT/PUT {id}/enabled/DELETE；@RequirePermission(gate:*)；审计 gate_update/enabled | Controller | hierarchy | T11、T01 | 0.5 | AC-F6.1/6.2/6.4 | 启停仅对新编排生效、在跑走快照（快照语义） |
| T28 | TenantLayerController（GET layers 登录即可无权限注解 / PUT layers 挂 tenant:layer:edit，审计 layer_update）+ LayerGuardInterceptor（order=2 注册于 RuntimeWebMvcConfig，PermissionInterceptor 顺延 order=3 逻辑零改动）：/api/v1/strategies/**→strategy_enabled 关→43001；/api/v1/programs/**、/api/v1/projects/**→program_project_enabled 关→43002；不拦 /api/v1/cases/** 与原则/门禁/编排接口；HTTP 200+业务码禁止 500 | Controller+拦截器 | hierarchy + config | T12、T01 | 0.5 | AC-F10.1/10.2、AC-ISO.4 | LayerGuard 在权限拦截器之前：关层未授权用户先见业务码（语义优先且不泄露权限布局）；开关按 t_tenant 行级天然隔离 |

### 阶段 G：前端（eaiselp-web-separate，T29 先行，其余依赖对应 API + T29；同阶段可并行）

| # | 任务 | 类型 | 模块 | 依赖 | 估时 | 验收AC映射 | 说明 |
|---|---|---|---|---|---|---|---|
| T29 | menu.js 增量：ROLE_MENUS 挂载（executive+战略看板/战略管理；tenant_admin+项目群/项目/原则/门禁/分层开关；project_manager+项目群只读/项目/原则只读/门禁只读；engineer 零新增）；build(roleCodes, layers) 第二参数开关过滤（strategyEnabled=false 过滤 strategy-*，programProjectEnabled=false 过滤 program-*/project-*，原则/门禁/开关菜单不随层过滤）；登录后拉 GET /api/v1/tenant/layers 存 sessionStorage；公共 43001/43002 空态渲染 | 前端 | web/menu.js | T28 | 0.5 | AC-RBAC.1/3、AC-F10.1/10.2 | COMMON_MENUS 不动（AC-RBAC.3 兼容）；menu.js 只认 5 角色码（R9 本期不新增 pgm 角色） |
| T30 | strategy-list.html：状态筛选（默认 active）/新建编辑（title≤200 必填、description、horizon、owner、KPI 多条维护目标值/当前值/单位）/生命周期操作（激活/达成/归档） | 前端 | web/pages | T23、T29 | 1 | AC-F1.1/1.2、AC-F1.4 | KPI 当前值人工维护（Q5 裁决）；achieved/archived 后表单仅 KPI 可编辑 |
| T31 | strategy-board.html：active 战略卡片（KPI 表 + 关联项目群进度），点击群卡片→program-detail.html?id= | 前端 | web/pages | T23、T29 | 0.5 | AC-F9.1、AC-F8.5 | 战略看板是三级下钻链入口；空态文案"从战略目标开始，或直接创建项目群（可不关联战略）" |
| T32 | program-list.html：列表（状态/起止/项目数/进度均值）/新建编辑（name 必填、charter、strategy_id 可空下拉含"不关联"、pgm_manager、状态）/删除二次确认 | 前端 | web/pages | T24、T29 | 1 | AC-F2.1/2.2/2.3 | strategy_enabled=false 时表单不出战略选择（AC-F10.2） |
| T33 | program-detail.html?id={id}：章程全文展示 + 项目列表（进度条/case 计数）+ 进度均值 + 下钻 project-detail.html?id= | 前端 | web/pages | T24、T29 | 0.5 | AC-F2.2、AC-F9.1 | URL 含 id 可收藏直达 |
| T34 | project-list.html：列表（状态/优先级/进度条/case_done/case_total）/新建（program_id 可空"独立项目"）/编辑入口 | 前端 | web/pages | T25、T29 | 1 | AC-F3.1 | 进度三列只读展示（AC-F3.2） |
| T35 | project-detail.html?id={id}：进度 + 原则多选绑定（列本租户启用原则）+ Case 列表（复用 case-list?projectId= 过滤形态）；空态"创建项目，或直接创建 Case（可不关联项目）" | 前端 | web/pages | T25、T29 | 1 | AC-F3.2/3.3、AC-F9.1/9.2 | 原则绑定变更需进审计（后端 T25 已挂） |
| T36 | principle-list.html：code/title/principle_type/enforce_level/enabled 开关；新建编辑（code 租户唯一冲突提示 409）；内容 ≤2000 字符前端提示 | 前端 | web/pages | T26、T29 | 0.5 | AC-F5.1/5.2 | project_manager 只读（编辑按钮按角色码隐藏） |
| T37 | gate-rule-list.html：name/gate_type/stage/applies_to/fail_action/max_retries/enabled；新建编辑启停；**stage 边界依赖帮助文案**（规则挂载依赖对应产物类型步骤存在，R10） | 前端 | web/pages | T27、T29 | 0.5 | AC-F6.1~6.4 | 三类型字段联动表单（llm_review 显 gate_role / auto_check 显 check_key / human_approval 无角色） |
| T38 | layer-config.html：strategy_enabled / program_project_enabled 两开关 + 说明文案（关层=菜单隐藏+API 拒绝+数据保留可逆）；编辑按钮按 tenant:layer:edit 角色显示 | 前端 | web/pages | T28、T29 | 0.25 | AC-F10.1~10.4 | |
| T39 | case-list.html 改造：创建对话框"所属项目"下拉（默认"不关联"，数据 GET /api/v1/projects 简版）；列表项目名列 + 按项目过滤；program_project_enabled=false 时以上全部隐藏（读 layers） | 前端改造 | web/pages | T17、T29 | 0.5 | AC-F4.1/4.3、AC-F9.2、AC-F10.1 | 场景C 界面完全看不到项目字样（US-5） |
| T40 | case-detail.html 改造：新增"所属项目"字段 + 挂接/解除按钮（二次确认）；编排详情区新增两张卡片：**注入清单**（injection：原则 codes/项目约束/字符数/截断）与**门禁命中**（步骤 gateResult/gateReason 现有数据）；legacy program_id/subproject 只读区展示 | 前端改造 | web/pages | T17、T22、T29 | 0.5 | AC-F4.2、AC-F7.1、AC-F6.2 | 注入清单卡片是 AC-F7.1 三处留痕的前端可见落点；数据源 GET /api/runtime/orchestrate/{id} 增量字段 injection |

### 阶段 H：集成验证与交付（收尾串行）

| # | 任务 | 类型 | 模块 | 依赖 | 估时 | 验收AC映射 | 说明 |
|---|---|---|---|---|---|---|---|
| T41 | 全链路联调与回归：SE §6.5 升级等价性表逐行验证；PRD §6.3 回归全集（Case 全生命周期/智能规划降级/门禁打回/检查点审批/Git-CI 落地/断点续跑/多租户/RBAC/审计）；双租户隔离专项（AC-ISO 四条）；三场景专项（场景A 全层/B 关 L3/C 关 L3+L2 各走一遍主链路） | 集成验证 | 全部 | T21、T22、T17、T28、T29~T40 全部 | 1 | AC-F6.3、AC-ISO.1~4、AC-F10.1~10.4 | 配合 team-qa 用例执行；产出联调记录与缺陷清单 |
| T42 | 部署与文档同步：deploy 目录脚本核对（V4 迁移随发布执行、本地手跑过草案的环境 flyway repair 指引写入新机初始化手册）、README 新功能与页面说明、docs\运维文档\生产上线安全检查清单.md（新增权限矩阵 14 原子、业务码 43001/43002、新端点鉴权核对表）、发布说明（**门禁默认行为变化点：team-security/team-performance 不再默认带门禁；team-ops 检查点转数据驱动——按 T02 裁决结果措辞**）、changelog/kanban 更新 | 交付 | deploy + docs | T41 | 0.5 | AC-F10.4、AC-RBAC.4 | 工程标准-002 文档体系要求 |

---

## 2. 汇总视图

- **任务总数**：42（DB 3 / 实体与 L1 数据结构 3 / 基础 Service 6 / 核心机制 6 / 门禁改造 4 / Controller 6 / 前端 12 / 收尾 2）
- **总估时**：26 人天
- **五条并行线**（供排期）：
  1. **门禁线（关键路径）**：T02→T03→T04→T11→T19→T20→T21→T41→T42 ≈ 5.5d
  2. 注入线：T03→T04→T10→T13→T22（T06 并行小任务）
  3. 汇总线：T03→T05→T14→T15→T16/T17
  4. CRUD 线：T03→T04/T05→T07~T12→T23~T28
  5. 前端线：T29（layers API 就绪后）→T30~T40（对应 API 就绪即可开工，可 mock 提前）
- **关键路径**（最长依赖链）：**T02 → T03 → T04 → T11 → T19 → T20 → T21 → T41 → T42 ≈ 5.5 人天**（T01 与 T02 并行；门禁线占关键路径的原因：规则化改造必须"先建新链路→验证等价→删硬编码→全量回归"四步串行，不可压缩）

---

## 变更影响评估

### 1. 被修改的既有文件（后端，均在 D:\AI\mywork\platform）

| 文件（绝对路径） | 涉及任务 | 变更内容 |
|---|---|---|
| eaiselp-runtime\src\main\resources\db\migration\V4__three_tier_model.sql | T01、T02 | 增补权限 seed 14 条 + 授权行；增补（待裁决）human_approval 规则 c |
| eaiselp-runtime\src\main\java\com\eaiselp\runtime\context\DerivationContext.java | T06 | +governanceContext 字段 |
| eaiselp-runtime\src\main\java\com\eaiselp\runtime\context\ContextAssembler.java | T06 | +渲染"架构原则与项目约束"章节 |
| eaiselp-runtime\src\main\java\com\eaiselp\runtime\orchestration\OrchestrationService.java | T19、T20、T21、T22 | 删 GATE_ROLES(:91)/isGateStep(:139)/team-ops(:267) 硬编码；runAsync 注入解析+规则快照；门禁分派重构 |
| eaiselp-runtime\src\main\java\com\eaiselp\runtime\orchestration\OrchestrationState.java | T05 | +governanceContext/injection/gateRules；StepResult +3 字段 |
| eaiselp-runtime\src\main\java\com\eaiselp\runtime\orchestration\OrchestrationRecord.java | T05 | +injectedJson（列 injected_json） |
| eaiselp-runtime\src\main\java\com\eaiselp\runtime\casestate\CaseStateServiceImpl.java | T16 | transit 尾部发布 CaseDoneEvent |
| eaiselp-runtime\src\main\java\com\eaiselp\runtime\controller\CaseController.java | T17 | create/page 扩展 + 挂接/解除/删除端点 |
| eaiselp-runtime\src\main\java\com\eaiselp\runtime\controller\TenantController.java | T18 | register 后调 TenantProvisionService |
| eaiselp-runtime\src\main\java\com\eaiselp\runtime\config\RuntimeWebMvcConfig.java | T28 | 注册 LayerGuardInterceptor(order=2)，权限拦截器调 order=3 |
| eaiselp-runtime\src\main\java\com\eaiselp\runtime\config\AsyncConfig.java（若无则新建） | T14 | 注册 progressExecutor |
| eaiselp-data 模块 Case.java / Tenant 实体 | T05 | +projectId / +两开关列字段 |
| eaiselp-runtime\...\hierarchy\Strategy.java（既有骨架） | T04 | 补全字段映射 |

不改动（明确排除）：EaiselpTenantHandler.java（新表全租户级，无需进 IGNORE_TABLES）、checkpoint.html（human_approval 复用现有审批页）、eaiselp-gateway/adapter/auth 等其他模块。

### 2. 被修改的既有文件（前端，D:\AI\mywork\eaiselp-web-separate，独立仓库）

| 文件 | 涉及任务 |
|---|---|
| assets\js\menu.js | T29（ROLE_MENUS 增量 + build(layers) 开关过滤） |
| pages\case-list.html | T39 |
| pages\case-detail.html | T40 |

新建：9 个页面（strategy-board/strategy-list/program-list/program-detail/project-list/project-detail/principle-list/gate-rule-list/layer-config）。

### 3. 需更新的文档与部署物

| 文档 | 更新内容 | 涉及任务 |
|---|---|---|
| deploy\新机初始化手册.md / deploy\双机协同开发流程.md | V4 直改说明、本地已手跑旧草案的 flyway repair 步骤、前端仓库与后端同步发布顺序 | T42 |
| README.md | 三层贯通功能概览、9 新页面入口、分层开关说明 | T42 |
| docs\运维文档\生产上线安全检查清单.md | 新增 14 权限原子矩阵、30 端点 @RequirePermission 核对表、业务码 43001/43002 处置口径、跨租户 404 校验项 | T42 |
| 发布说明（随交付） | 门禁默认行为变化点：team-security/team-performance 不再默认带门禁；team-ops 检查点转数据驱动（按 T02 裁决措辞） | T42 |
| docs\过程跟踪文档\changelog.md / kanban.md | 按工程标准-002 落盘 | T42 |

### 4. 兼容性影响结论

- 升级不改变 L1 既有行为（AC-F10.4、SE §6.5 等价表），唯一两个显式行为变化点已进发布说明；V4 为纯增量（新表+可空/默认列+seed），回滚脚本见数据库设计说明 §5。
- menu.js COMMON_MENUS 与既有 5 角色授权零改动（AC-RBAC.3），engineer 无感知。

---

## 本次经验沉淀

1. **任务拆解的第一步是"多份上游文档的裁决后对账"，不是照抄模块清单**。本次 SE 方案（V5/3 条规则/injection_json/绑定无覆盖位）与 DBA 落盘（V4 直改/2 条规则/injected_json/绑定有 enabled 覆盖位）在编排者裁决生效后仍有四处漂移——若直接按 SE §3 模块清单拆任务，Dev 会拿着互相矛盾的契约开工。做法：拆解前逐项核对已落盘产物（V4 实际 SQL、实际代码行号），把每个冲突收敛成"任务清单采用 X，依据 Y"的显式决策表，并为无法自行收敛的（规则 c 增补）单列裁决任务进高风险清单。教训：**当架构裁决改变了某份文档的前提时，BA 的职责是把裁决传播到每一个受影响的任务，而不是默认文档已经自洽**。

2. **"删除硬编码"必须拆成"建新链路→执行分派→删旧路径"三个串行任务，且删除任务排在最后**。直接拆成"改造 OrchestrationService 门禁"一个大任务，会诱导 Dev 在同一提交里边建边删，等价性验证无从下手，AC-F6.3（代码形态验收）与回归保护互相挤压。三段式让每步都有独立验证点：T19/T20 可在旧逻辑仍在的情况下用新规则跑对照，T21 只做删除与切换、风险面最小。教训：**高风险重构的粒度标准不是"功能内聚"而是"每一步都可独立证明没有变坏"**。

3. **任务清单要给排期者"并行线视图"，而不只是一张依赖表**。42 个任务的原始表很难直接看出 26 人天为何关键路径只有 5.5 天。本次显式给出五条并行线（门禁/注入/汇总/CRUD/前端），前端线还能在 API 契约冻结后 mock 提前——这让编排者能直接推导人力分配与里程碑，而不是自己从依赖列里重新推导。教训：BA 交付的任务清单除了"做什么"外，必须回答"怎么并行、瓶颈在哪"，否则排期决策成本被转嫁给编排者。
