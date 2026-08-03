# 代码评审报告 — M2+M3 集中补审查第二轮（P1 四项）

> 结论：**不通过**
> 审查人：team-reviewer（L1 独立评审，与 Dev 异模型、未读 Dev 设计说明）
> 审查日期：2026-08-03
> 编译/测试门禁：`mvn clean package -DskipTests` + `mvn test` 均 BUILD SUCCESS（20/20 用例通过）
> 评审依据：项目 CLAUDE.md/编码规范未单独注入，按通用维度 + 代码自洽性审查

## 磁盘事实核对（强制第一步，已执行）

- 工作树状态：`git status` → clean（四项均已提交，无未提交改动）。
- 四个 commit 均真实存在，且每个 commit 的 `--stat` 文件清单与编排者给定的审查范围一致：
  - `8036fe9`（Case 状态机）：7 文件 +573 行，覆盖 CaseStatus/IllegalStateTransitionException/CaseStateService(+Impl)/CheckpointService(+Impl)/CaseStateController。
  - `e0cf875`（监控告警）：8 文件 +245 行，覆盖 PlatformMetrics/DerivationAsyncRunner/docker-compose/prometheus.yml/两模块 pom+application.yml。
  - `37c3081`（熔断+备份）：4 文件 +253 行，覆盖 CircuitBreaker/LlmCircuitBreaker/GlmLlmAdapter/backup-db.bat。
  - `1913806`（SPI）：11 文件 +532 行，覆盖 4 SPI 接口 + 4 Stub + AdapterFactory 扩展 + DefaultAdapterFactory + AdapterController。
- 报告所称改动与磁盘 diff **完全一致**，无虚报、无漏报、无夹带。
- 反幻觉自检：本报告所有"改动后代码"引用均来自本人独立 `git show` + Read，未抄 Dev 报告；审查结论仅依据 diff + 上下文文件复核得出。

## 缺陷清单

| 编号 | 严重度 | 文件:行 | 问题 | 修复建议 |
| --- | --- | --- | --- | --- |
| D1 | 阻断 | eaiselp-adapter/.../resilience/CircuitBreaker.java:98-105（tripOpen） | **HALF_OPEN 探针失败后冷却窗口不重置，熔断器在持续故障下形同虚设。** `tripOpen()` 用 `openSince.compareAndSet(0L, now)` 记录进入 OPEN 的时间戳，仅在 `openSince==0` 时成功。但 HALF_OPEN 探针失败 → recordFailure → tripOpen 这条路径下，`openSince` 仍是上一次 CLOSED→OPEN 时写入的旧值（非 0），CAS 必然失败，`openSince` 不更新。后果：当下游持续故障、OPEN 超时切 HALF_OPEN、探针失败切回 OPEN 后，下一次 `allowRequest()` 读到的仍是旧的 `openSince`（已远超 30s），**立即**又 OPEN→HALF_OPEN 放一个探针。如此每请求一个探针，30s 冷却期被完全绕过，熔断器在"半恢复失败"场景失去对下游的保护能力——这正是 P1「弹性容错」的核心诉求。注：首次 CLOSED→OPEN（openSince 由 0→T1）路径正常；仅 HALF_OPEN→OPEN 重 trip 路径失效。且本类无任何单元测试覆盖状态流转，缺陷无法被门禁发现。 | `tripOpen` 中刷新 `openSince` 不应依赖 CAS(0L→now)。改为：状态确非 OPEN 时，无条件 `openSince.set(System.currentTimeMillis())` 后再 `state.set(OPEN)`（幂等，多线程重复 set 同值无害）；或对 HALF_OPEN→OPEN 的重 trip 单独走 `openSince.set(now)`。并补 CircuitBreaker 单测覆盖 CLOSED→OPEN→HALF_OPEN→OPEN→（应冷却 30s）的完整时序。 |
| D2 | 建议 | eaiselp-runtime/.../casestate/CaseStateServiceImpl.java:60-67（transit） | `transit` 更新仅 `eq(case_id)` + SET status+update_by，**未带 CAS 守卫**（无 `WHERE status=current`）。两个并发合法流转（如同时 REVIEWING→TESTING 与 REVIEWING→DERIVING 返工）会双更新，后写覆盖前写，其中一个流转被静默丢失。幂等同态并发无碍，但分叉合法流转并发存在丢更新。Checkpoint 已正确用 `WHERE status='pending'` CAS 防双确认，transit 同属状态机但缺等价守卫，一致性有缺口。 | 视业务并发量决定：若需强一致，update 加 `.eq(Case::getStatus, current.dbValue())`，updated==false 时重查报"状态已被并发改变"；若可接受 last-write-wins，保留现状并在类注释显式声明该取舍。 |
| D3 | 建议 | eaiselp-runtime/.../controller/CaseStateController.java:128-135（resolveOperator） | 未取到 JWT 时回退 "anonymous" 写入 `update_by` 审计字段。正常路径 JwtAuthInterceptor 已挡 401，此分支几乎不可达；但若该路径被加入 JWT 白名单或拦截器顺序变化，审计字段会落 "anonymous"，削弱 GRC 可追溯。属防御性兜底的语义瑕疵，非安全漏洞。 | 回退值建议改为抛 IllegalStateTransitionException("操作人无法识别") 或返回 R.fail(401,...)，避免脏审计数据；至少在注释明确"正常不可达"。 |
| D4 | 建议 | deploy/backup-db.bat:31-34 | 用 `wmic os get localdatetime` 取时间戳。wmic 在 Windows 11 22H2+/Server 2022 起被标记弃用（默认仍可用，未来版本可能移除）。当前部署目标 win32 10.0.19045 可用，属前瞻性风险。脚本其余逻辑（docker exec mysqldump --single-transaction、errorlevel 检查、forfiles 按 KEEP_DAYS 轮转）正确。 | 后续可改用 PowerShell `Get-Date -Format yyyyMMdd_HHmmss` 取时间戳，去 wmic 依赖；或保持现状并在注释标注 wmic 依赖范围。 |

## 分项审查结论

### P1-1 Case 状态机 + 检查点（commit 8036fe9）— 通过（含 1 建议 D2/D3）

- `CaseStatus.canTransitionTo` 逻辑正确：幂等（self→self true）、终态锁死（DONE→任意 false）、防跨阶段跳跃（drafting 仅→deriving）、允许返工（reviewing/testing/deploying→deriving）。流转表用 EnumSet 不可变冻结，防篡改。fromDbValue/dbValue 与 t_case.status 列存小写串一致，未知值返回 null 兼容历史脏数据。未发现遗漏的合法流转（返工统一回 deriving 符合文档建模）。
- `CaseStateServiceImpl.transit` 用 LambdaUpdateWrapper 仅 SET status+update_by（精准更新，无全字段覆盖），按 case_id 定位，tenant_id 交 MP 拦截器；updated==false 给明确错误。唯一缺口见 D2（无 CAS 守卫）。
- `CheckpointServiceImpl.confirm/reject` 条件更新 `WHERE status='pending'`，CAS 原子防并发双确认/双拒绝，正确；非 pending 返回 false，Controller 映射 409，语义清晰。
- `CaseStateController` 5 API 齐备（transit/list/createCheckpoint/confirm/reject），均带 `@RequirePermission`（权限码 case:checkpoint:confirm / case:view 已在 schema.sql 权限表存在，未新增权限项）、状态变更类带 `@RateLimit(USER)`，operator 从 `LoginUser.get()` 取 username（防伪造）。`IllegalStateTransitionException extends BizException`，经 GlobalExceptionHandler 映射 R.fail(400,msg)。Checkpoint 实体字段与 diff 使用一致，schema status 默认 'pending'。唯一瑕疵 D3。
- 编译/测试：本 commit 文件在最终 mvn test 通过（无单独断言失败）。

### P1-2 监控告警（commit e0cf875）— 通过

- `PlatformMetrics` 4 指标定义正确：`eaiselp_derivation_total{role,status}`（counter）、`eaiselp_llm_duration_seconds{provider,model}`（Timer 直方图）、`eaiselp_case_active`（gauge，单 AtomicLong 强引用持有，构造时注册一次）、`eaiselp_token_consumed_total{type=input|output}`（counter）。线程安全：MeterRegistry 同名同 tag 去重 + AtomicLong 原子操作；decrement 用 `Math.max(0,v-1)` 防负。全埋点 try-catch(Throwable) 容错，不拖垮主流程。引用的 LlmResponse/DerivationResult 字段（model/inputTokens/outputTokens）均存在。
- `DerivationAsyncRunner` 埋点跨作用域正确：`startNs` + `status="failed"` 默认值在 try 外定义，成功路径覆盖，finally 内统一 recordDerivation + decrementActiveCase（异常路径也释放），incrementActiveCase 在 try 前。provider/model 预解析 + 成功路径以 engine 结果为准，口径一致。resolveProviderSafe/resolveModelSafe 失败回退不抛异常。新增 adapterFactory/metrics 字段经构造注入（@RequiredArgsConstructor），无破坏既有调用。
- Actuator 配置正确：exposure include 仅 health,info,metrics,prometheus（关闭 env/loggers 等敏感端点），health show-details=when_authorized，metrics.tags.application 统一打标。auth 与 runtime 两模块均加 actuator + micrometer-registry-prometheus 依赖与配置。
- docker-compose + prometheus.yml：prometheus 抓 runtime:8081 / auth:8085 的 /actuator/prometheus，用 host.docker.internal 访问宿主机应用（注释说明了容器化部署改 service 名），grafana admin/admin。配置自洽。

### P1-3 熔断降级 + 数据备份（commit 37c3081）— 不通过（D1 阻断）

- `CircuitBreaker` 三状态机 CAS 设计总体合理（AtomicReference<State> + CAS 切换 OPEN→HALF_OPEN 仅一个线程成功），CLOSED→OPEN（连续 5 次失败）、HALF_OPEN 成功→CLOSED、HALF_OPEN 失败→OPEN 路径正确。**但 HALF_OPEN→OPEN 重 trip 路径冷却时间戳不刷新（D1），导致持续故障下冷却失效，熔断器保护能力被削弱**，作为 P1 弹性容错核心项判定不通过。
- `LlmCircuitBreaker` 门面按 provider 维护独立 breaker（ConcurrentHashMap.computeIfAbsent 懒创建），provider 隔离正确；provider 空回退 "default"。
- `GlmLlmAdapter` 接入熔断正确：invoke 前判 `circuitBreaker.allowRequest("glm")` 拒则抛 RuntimeException("AI服务熔断中")；成功 recordSuccess、失败（RuntimeException 与 Exception 两路）recordFailure；`@Autowired(required=false)` 容器外单测跳过。recordSuccess/recordFailure 调用点在异常透传/包装之前，记账不丢。
- `backup-db.bat` 逻辑正确（D4 仅前瞻性建议）：docker exec mysqldump --single-transaction --routines --triggers --events、errorlevel 校验失败删半成品、forfiles 按 KEEP_DAYS 轮转。

### P1-4 企业适配器 SPI（commit 1913806）— 通过

- 4 SPI 接口（Ticket/CICD/IM/MCP）定义完整：均 `extends Adapter`，方法签名合理，DTO 用 @Data @Builder，未硬编码 provider 枚举（type/status/actionType 透传 provider 解释，符合 G11 零场景硬编码）。
- 4 Stub 默认实现 `@ConditionalOnProperty(...,havingValue="true",matchIfMissing=false)` 正确——默认不装配，企业接入配 enabled=true 或提供自研 Bean 覆盖；`isAvailable()` 返回 false（与 LocalGitAdapter 的 matchIfMissing=true 区分：核心三件默认装、企业四件默认不装，设计一致合理）。
- `DefaultAdapterFactory` 用 `@Autowired(required=false) private List<XxxAdapter>` 注入 4 类，`pick()` 过滤 isAvailable 返回首个或 null（区别于 Git/Llm/DocStore 的 orElseThrow），与 SPI default 方法抛 UnsupportedOperationException 的契约一致。@Autowired(required=false) 正确，未装配不报错。
- `AdapterFactory` 接口 4 个新方法用 default 抛 UnsupportedOperationException 保持向后兼容，其他 Factory 实现无需感知。
- `AdapterController` status 端点 `infoOptional` 统一兜底：catch UnsupportedOperationException / null 均输出 available=false 占位，不让 /status 整体 500。4 适配器状态正确并入视图。

## 总评审结论

**不通过（1 项阻断）**。四项中 P1-1 / P1-2 / P1-4 通过；P1-3（熔断降级）因 D1 阻断不通过。

编译门禁（mvn clean package -DskipTests）与测试门禁（mvn test，20/20）均绿，无编译/测试问题。阻断缺陷 D1 为运行时正确性缺陷（熔断器冷却窗口失效），现有测试覆盖不到，需 Dev 修复并补 CircuitBreaker 状态流转单测后重审。D2/D3/D4 为改进性建议，非阻断。

## 阻断清单（需 Dev 修复，本轮第 1 轮）

1. **D1** CircuitBreaker.tripOpen() 冷却时间戳 CAS 失效 —— HALF_OPEN 探针失败重 trip 后 openSince 不刷新，30s 冷却被绕过，持续故障下熔断器保护能力失效。修复：tripOpen 中状态非 OPEN 时无条件 `openSince.set(now)`；补 CLOSED→OPEN→HALF_OPEN→OPEN→冷却时序单测。

## 本次经验沉淀

1. **熔断器冷却时间戳必须随每次"进入 OPEN"刷新，不能用 CAS(0→now) 仅在首次生效**。HALF_OPEN 探针失败回 OPEN 是常见的"假恢复"路径，若此处沿用首次 trip 的旧时间戳，冷却窗口会被旧的远期时间戳绕过。评审熔断/限流类组件时，应专门追踪"重 trip"（OPEN→HALF_OPEN→OPEN）路径的时间戳/计数器是否重置，而非只看首次 trip。
2. **状态机 CAS 守卫要按"是否防重复操作"分级**：Checkpoint 的 confirm/reject 必须用 `WHERE status='pending'` CAS（防并发双确认/双拒绝，是业务正确性要求）；而 Case transit 的合法流转并发丢失是否需要 CAS，取决于业务对 last-write-wins 的容忍度——两者同属状态机但 CAS 需求不同，不能一刀切，评审时需逐个流转判断并发语义。
3. **条件装配 matchIfMissing 的语义即"默认是否启用"**：核心必需适配器（Git/Llm/DocStore）用 matchIfMissing=true 默认装配；企业可选能力（Ticket/CICD/IM/MCP）用 matchIfMissing=false 默认不装配、靠 enabled=true 显式开启。评审 `@ConditionalOnProperty` 时应核对该值是否与"该能力是否核心"的定位一致，避免把核心能力设成默认不装导致启动即缺 Bean，或把可选能力设成默认全装导致占位 Stub 干扰真实实现。
