# 代码评审报告 — M2+M3 代码集中补审查（P0 三项）

> 结论：**不通过**（1 项阻断级密码泄漏缺陷，2 项建议级 detail 注入风险）
> 评审范围：commit 5020820（LLM 异步化+限流）/ 7df9468（安全加固）/ 56162b3（审计日志+用户管理）
> 评审日期：2026-08-03
> 评审员：team-reviewer（L1 独立代码评审）

---

## 0. 磁盘事实核对（强制第一步）

按"磁盘 diff 为唯一事实来源"原则，逐 commit 核对真实改动与报告的改动点：

### commit 7df9468（安全加固）
- `git show 7df9468` 显示改动 2 文件：`AuthServiceImpl.java`(+12/-5)、`CorsConfig.java`(+19/-4)。
- 报告改动点（CorsConfig 白名单、AuthServiceImpl 防枚举恒定时延+BCrypt cost=12）**全部在磁盘 diff 中落地**，与 Read 实读内容一致。
- 核对结论：**改动真实落地，无虚报**。

### commit 56162b3（审计日志+用户管理）
- `git show --stat 56162b3` 显示改动 20 文件（+893/-2）。
- 报告改动点（AuditServiceImpl/AuditLogger/UserController/UserServiceImpl/GovernanceLog/IGNORE_TABLES/schema.sql 等）**全部在磁盘 diff 中落地**。
- Read 实读 UserServiceImpl(199行)、UserController(182行)、AuditServiceImpl(86行)、AuditLogger(47行)、GovernanceLog(64行)、EaiselpTenantHandler(diff) 均与 diff 一致。
- 核对结论：**改动真实落地，无虚报**。

### commit 5020820（LLM 异步化+限流）
- `git show --stat 5020820` 显示 **193 文件 +25313 行**——这是项目 init commit（commit message 自述".git 目录被 Dev 临时 git init 误删，本次重新 init 恢复"），无父 commit，故 diff 为全量新增。
- Read 实读 DerivationPersistenceService(148行)、DerivationAsyncRunner(125行)、DerivationTaskService(309行)、RateLimitInterceptor(106行)、BucketRegistry(106行)、AsyncConfig(66行)、RuntimeWebMvcConfig(75行)、RuntimeController(126行)、JwtUtil(79行) 均存在且内容完整。
- **异步化核心逻辑（ThreadLocal 分支、@Async 独立 Bean、ConcurrentHashMap 状态机、AbortPolicy、order=0）全部真实落地**，与报告一致。
- 注：因 init commit 特性，DerivationEngine.guessType 的 team-* 硬编码（G11 WARN）随全量代码带入，**非本次新增违规**（commit message 自述"DerivationEngine 零改动"，属 M1 既有 SP-4 技术债）。
- 核对结论：**改动真实落地，无虚报**。

---

## 1. 缺陷清单

| 编号 | 严重度 | 文件:行 | 问题 | 修复建议 |
|---|---|---|---|---|
| D1 | 🔴阻断 | `UserServiceImpl.java` page 方法（约 51-59 行）+ `UserController.java:58` page 端点 | **密码哈希泄漏**：`UserController.page()` 返回 `R<IPage<User>>`，`UserServiceImpl.page()` 直接返回 `userMapper.selectPage()` 结果，**未清空 password 字段**。`User` 实体的 `password` 字段无 `@JsonIgnore`/`@JsonProperty(access=WRITE_ONLY)` 序列化保护，Jackson 默认全字段序列化，**BCrypt 密码哈希会随列表 API 返回前端**。对比：同文件 `get()` 在 controller 层有 `u.setPassword(null)` 双保险，`create()` 在 service 层清空，唯独 `page()` 漏了。 | 二选一（推荐 a）：a) `User.java` password 字段加 `@JsonIgnore`（全局根治，所有 User 序列化都不带 password，一劳永逸）；b) `UserServiceImpl.page()` 在返回前遍历 records 执行 `u.setPassword(null)`。 |
| D2 | 🟡建议 | `CaseController.java:83-84` create 审计 | **detail JSON 注入**：`"{\"title\":\"" + c.getTitle() + "\",...}"`，`c.getTitle()` 源自用户请求 `req.getTitle()`，**未经 safeJson 转义**。若 title 含双引号 `"` 或反斜杠 `\`，会破坏 detail JSON 结构（虽 detail 是 JSON 文本列，注入危害有限，但破坏 JSON 完整性导致后续解析失败）。CaseController 未定义 safeJson 方法（对比 UserController/AuthController/CaseStateController 均有）。 | CaseController 加 safeJson 私有方法并对 title/layer 转义；或抽到 AuditService 提供统一 detail 构造工具避免各 controller 重复手拼 JSON。 |
| D3 | 🟡建议 | `RuntimeController.java:90-91, 97-99` derive_rejected/derive_create 审计 | 同 D2：`req.getRole()`、`req.getCaseId()` 直接拼入 detail JSON 未经转义。role 来自请求体（虽通常受 capability 校验约束，但未在审计层兜底转义）。 | 同 D2，对 role/caseId 调用 safeJson 转义。 |
| D4 | 🟢可选 | `BucketRegistry.java:66-82` getOrCreate | **参数 refillPerMin 未生效**：方法签名 `(bucketId, capacity, refillPerMin)` 接收 refillPerMin，但实际只用 `capacity` 构造 `Bandwidth.classic(capacity, Refill.intervally(capacity, ...))`，注释说"兼容 refillPerMin != capacity 的灵活配法"，但代码并未实现（当 refillPerMin != capacity 时行为与注释不符）。当前所有 @RateLimit 调用 capacity==refillPerMin（10/10、100/100），暂无实际影响。 | 若需支持 capacity != refillPerMin 的突发令牌桶，应用两个 Bandwidth（一个限容量、一个限 refill 速率）；若不需要，删除 refillPerMin 参数避免误导。 |
| D5 | 🟢可选 | `CorsConfig.java:18` | **内网 IP 硬编码到默认配置**：默认值含 `http://172.16.180.166:8080`、`http://172.16.180.87:8080`（部署机 IP）。虽为 dev 默认值（生产可由环境变量覆盖）且明确标注"开发期默认"，但内网 IP 入库代码默认值不够规范。 | 默认值只保留 localhost/127.0.0.1，部署机 IP 走环境变量注入（`@Value("${eaiselp.cors.allowed-origins:http://localhost:8080,...}")` 默认值不含具体内网 IP）。 |

---

## 2. 三项审查分项结论

### 2.1 commit 5020820：LLM 异步化 + 限流 —— **有条件通过**

**合规项（符合规范）：**
- `DerivationPersistenceService` ThreadLocal 分支正确：`DerivationTaskIdHolder.get()` 非空走 `updateById`（异步路径），为空走 `save`（同步零回归），符合 SE §5.3 D-4。
- `DerivationAsyncRunner` `@Async("runtimeLlmExecutor")` 独立 Bean，跨线程显式 `TenantContext.set(tenantId)` 注入租户上下文，finally 必清 `DerivationTaskIdHolder.clear()` + `TenantContext.clear()` 防线程池线程复用串号，符合 ES-003 §9.3 P11。
- `DerivationTaskService` ConcurrentHashMap 状态机：createPending（配额强校验→INSERT pending→内存 put）、markRunning/markSuccess/markFailed、getTask（内存→DB miss 回落→not_found）、@Scheduled 清理 + 软上限 1 万防 OOM，实现完整。
- `RateLimitInterceptor` order=0 放在 JWT(order=1) 前（`RuntimeWebMvcConfig` 显式 `.order(0/1/2)`），登录接口无 JWT 时限流仍生效，符合 SE §4.2.4。429 + Retry-After 由 GlobalExceptionHandler 统一处理。
- `AsyncConfig` runtimeLlmExecutor：core=5/max=20/queue=50/**AbortPolicy**（禁用 CallerRunsPolicy，注释明确"防 Tomcat 线程跑 LLM 摧毁隔离"），符合 SE §4.1.5。
- `RuntimeController` POST /derive 异步返回 202 + taskId，GET /derive/{taskId} 轮询，符合 SE §4.1。
- `JwtUtil` 密钥从 `SecurityProperties` 配置驱动（`${JWT_SECRET:dev-placeholder}`），有 ≥32 字节长度校验，**非明文硬编码**（默认占位符明确标注 dev-placeholder）。

**遗留问题：**
- D3（derive 审计 detail 未转义，🟡建议级）。
- RuntimeController 路径 `/api/runtime` 无 v1 前缀——属 M1 遗留，G14 WARN 不阻断（代码注释引用 SE §10 D-1 决策点，已知技术债）。
- DerivationEngine.guessType team-* 硬编码（G11 WARN）——init commit 带入的 M1 既有代码，SP-4 待重构，非本次新增。

### 2.2 commit 7df9468：安全加固 —— **通过**

**合规项（符合规范）：**
- `CorsConfig` 从 `allowedOriginPatterns("*")` 改为 `allowedOrigins(origins)` 白名单（配置驱动 `${eaiselp.cors.allowed-origins:...}`），**不含 `*`**。allowCredentials(true) + 具体 origin 数组合规（Spring 要求 allowCredentials=true 时不能用 `*`，此处用具体 origin 满足要求）。
- `AuthServiceImpl` 防枚举恒定时延真实执行：`user==null` 时 `passwordEncoder.matches(req.getPassword(), DUMMY_HASH)` 跑一次 dummy BCrypt，与用户存在路径耗时一致（防时间侧信道枚举）。
- BCrypt cost=12：`new BCryptPasswordEncoder(12)`，DUMMY_HASH 也是 `$2a$12$...`（cost 一致，恒定时延有效）。
- 统一错误码 BAD_CREDENTIAL + "用户名或密码错误"，不区分用户存在性。

**遗留问题：**
- D5（CORS 默认值含内网 IP，🟢可选级）。

### 2.3 commit 56162b3：审计日志 + 用户管理 —— **不通过（D1 阻断）**

**合规项（符合规范）：**
- `AuditServiceImpl` 上下文同步读（LoginUser ThreadLocal + RequestContextHolder 取 IP）+ `AuditLogger` `@Async("runtimeAuditExecutor")` 异步写，独立 Bean 避免 this 自调用代理失效，符合 @Async 最佳实践。resolveClientIp 支持 X-Forwarded-For/X-Real-IP 链路解析。
- `AuditLogger` 拒绝策略 **CallerRunsPolicy**（与 LLM 线程池的 AbortPolicy 区分——审计日志是合规要求，队列满时由调用线程同步写，宁可慢也不丢日志），符合 reliability-governance。
- `GovernanceLog` **不继承 BaseEntity**（append-only 表只有 id/tenant_id/create_time，无 update_time/is_deleted），独立实体设计正确。
- `IGNORE_TABLES` **已加 `t_governance_log`**（审查要点核对通过），t_governance_log 走显式 tenant_id 记录（AuditServiceImpl 从 LoginUser 取 tenant_id 写入）。
- `UserController` 6 API 路径 `/api/v1/users`（符合 P13 接口版本化）；@RequirePermission 正确（读类 user:view，创建 user:create，更新/禁用/分配角色 user:update）；tenantId 从 LoginUser 取（不从请求参数取，防客户端伪造，符合 G13）。
- `UserServiceImpl` BCrypt 加密（strength=12 与 AuthServiceImpl 一致）；角色双写（t_user_role 关联表 + t_user.roles 冗余字符串）；assignRoles 覆盖式（先删旧按 tenant_id+user_id 再插新）；disable 只改 status 不动其他字段；update 显式带 tenantId 防越权改其他租户用户。
- `login_failure` 审计不暴露具体原因到 detail（防枚举）。

**阻断问题：**
- **D1（密码泄漏）**：`UserController.page()` / `UserServiceImpl.page()` 未清空 password，BCrypt 哈希随列表 API 返回前端。这是 P0 安全级缺陷，必须修复。

**遗留问题：**
- D2（CaseController.create 审计 detail 未转义，🟡建议级）。

---

## 3. 独立验证结果

| 验证项 | 命令 | 结果 |
|---|---|---|
| 编译 | `mvn clean package -DskipTests`（JDK 26） | **BUILD SUCCESS**（全 10 模块通过） |
| 模块边界门禁 | `质量门禁-模块边界.ps1` | **6/6 PASS**（G1-G6 全通过） |
| 平台承载门禁 | `质量门禁-平台承载规范.ps1` | **0 BLOCK**（G12/G13 PASS；G11/G14/G15 WARN 属已知技术债） |
| G11 team-* 硬编码 grep | `findstr /S team-* eaiselp-*/src/main/java` | 非注释命中仅在 DerivationEngine.guessType（M1 既有 SP-4 技术债，init commit 带入，非本次新增） |
| G13 @InterceptorIgnore | 门禁脚本 | **PASS**（无 @InterceptorIgnore 绕过租户拦截器） |

---

## 4. 安全审查结论

| 审查项 | 结论 | 证据 |
|---|---|---|
| JWT 密钥 | ✅ **占位非明文** | `SecurityProperties` + application.yml `${JWT_SECRET:dev-placeholder-secret-...}`，运维通过环境变量注入生产密钥；JwtUtil.init 有 ≥32 字节校验 |
| 密码不返回前端 | ❌ **page 接口泄漏** | D1：UserServiceImpl.page 未清空 password，User 实体无 @JsonIgnore。get/create 已清空，唯独 page 漏 |
| 审计日志 detail 防注入 | ⚠️ **部分缺失** | UserController/AuthController/CaseStateController 有 safeJson；CaseController.create（D2）、RuntimeController.derive（D3）未转义 |
| CORS 白名单不含 * | ✅ **通过** | CorsConfig 用 allowedOrigins(具体 origin 数组)，无 `*`；allowCredentials(true) 合规 |
| 防枚举恒定时延 | ✅ **真实执行** | AuthServiceImpl user==null 跑 passwordEncoder.matches(pwd, DUMMY_HASH)，BCrypt cost=12 与正常路径一致 |
| BCrypt cost=12 | ✅ **通过** | AuthServiceImpl + UserServiceImpl 均 new BCryptPasswordEncoder(12) |
| 多租户隔离（G13） | ✅ **通过** | 无 @InterceptorIgnore；UserController tenantId 从 LoginUser 取；UserServiceImpl 按 tenantId 过滤/显式带 tenantId |

---

## 5. 总评审结论

**不通过** —— 存在 1 项阻断级缺陷（D1 密码泄漏），必须修复后重审。

**修复要求（Dev 必做）：**
1. 🔴 **D1（阻断）**：修复 `UserController.page()` 密码泄漏。推荐方案：`User.java` password 字段加 `@JsonIgnore`（全局根治）。

**建议修复（不阻断但应修）：**
2. 🟡 D2/D3：CaseController.create、RuntimeController.derive 的审计 detail 拼接加 safeJson 转义（或抽到 AuditService 统一工具）。

**修复后重审范围**：仅需重验 D1（密码不返回前端），D2/D3 可在下一迭代处理。

**通过项**：commit 7df9468（安全加固）单独判**通过**；commit 5020820（LLM 异步化+限流）判**有条件通过**（D3 建议级不阻断）；commit 56162b3（审计日志+用户管理）因 D1 判**不通过**。

---

## 本次经验沉淀

1. **密码字段的序列化保护必须根治在实体层而非 Controller 层**：本案 UserController 的 get/create/disable 都有 setPassword(null) 双保险，唯独 page 漏了——这种"逐接口手动清空"的防御方式天然脆弱，只要有一个读接口漏清就会泄漏。正确做法是在 `User.java` password 字段加 `@JsonIgnore`（或 `@JsonProperty(access = Access.WRITE_ONLY)`），让 Jackson 在序列化层全局忽略，从源头杜绝。Reviewer 审查用户实体时，应优先检查 password 字段是否有 Jackson 注解保护，而非逐接口核对 setPassword(null)。

2. **审计 detail 的 JSON 拼接应统一工具化而非各 Controller 手拼**：本案 4 个 Controller（User/Auth/CaseState/Runtime）都有手拼 detail JSON 的代码，其中 3 个定义了 safeJson 私有方法、1 个（CaseController）忘了定义，导致一致性问题。手拼 JSON 是 detail 注入的温床。建议 AuditService 提供 `detail(key, value)` 链式构造器或接受 Map→内部 Jackson 序列化，从接口层消除各 Controller 重复手拼。

3. **init commit 的 G11/G14 WARN 需区分"本次新增"与"既有技术债"**：5020820 是项目 init commit（.git 被误删重建），diff 显示全量代码新增，但其中 DerivationEngine.guessType 的 team-* 硬编码是 M1 既有代码（SP-4 待重构），非本次新增违规。Reviewer 遇到 init commit 时，不能把全量 diff 的 WARN 都算到本次 commit 头上，需结合 commit message（"DerivationEngine 零改动"）和 git log -- <file> 历史判断代码真实来源。
