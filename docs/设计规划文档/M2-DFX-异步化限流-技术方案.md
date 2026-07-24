# M2-DFX：LLM 异步化 + 限流技术方案

| 项 | 值 |
|---|---|
| 文档版本 | v1.0 |
| 编写角色 | SE（team-se） |
| 阶段 | M2 / DFX（高并发 + 安全） |
| 关联模块 | eaiselp-runtime、eaiselp-common、eaiselp-data |
| 状态 | 待 Dev 实施 |

---

## 1. 背景与目标

### 1.1 问题（磁盘核对）
当前 `DerivationEngine.derive()`（`DerivationEngine.java:35`）是**全同步**链路：
`校验 → 选模型 → 装配 prompt → 调 LLM（60s 超时）→ 提取 → 落库 → 埋点`，全部在 Tomcat 工作线程内串行执行。

`RuntimeController.derive()`（`RuntimeController.java:21`）直接 `return R.ok(engine.derive(...))`——HTTP 线程被独占 0~60 秒。

**后果**：Tomcat 默认 `max-threads=200`，10 个并发派生请求各占线程 30 秒以上，瞬时即可把可用线程耗尽，导致整个 runtime 服务（含健康检查、其他接口）不可用。

### 1.2 目标
1. **异步化**：`POST /derive` 立即返回 `taskId`（202 Accepted），LLM 调用转入后台线程池；前端轮询 `GET /derive/{taskId}` 获取状态与结果。
2. **限流**：按 IP / 租户 / 用户多维限流，防暴力破解与 token 烧刷，超限返回 429。
3. **线程池隔离**：LLM 慢任务与 Tomcat 请求线程隔离，互不拖垮。
4. **连接池调优**：DB（HikariCP）与 LLM HTTP 客户端超时显式化。
5. **本机可验证**：Dev 改完可独立 mvn package + curl 验证四项关键路径。

---

## 2. 磁盘事实核对（方案依据，非转述）

| 文件 | 关键事实 | 对方案的影响 |
|---|---|---|
| `RuntimeController.java:13` | `@RequestMapping("/api/runtime")`——**实际路径是 `/api/runtime/derive`，无 `/v1` 版本前缀** | 方案沿用 `/api/runtime/derive`；`/v1` 是否引入为独立决策项（见 §10 决策点 D-1），不阻塞本次实施 |
| `DerivationEngine.java:48-49` | LLM 调用仅传 `timeoutMs=60000`（read/total） | connect 超时无独立配置 → §6.2 列为 gap，需 Adapter 层补 |
| `DerivationEngine.java:61-68` | 落库已下沉到独立 Bean `DerivationPersistenceService`（@Transactional，M1.2 决策，防 this 自调失效） | 异步方法须跨 Bean 调它，**不可在异步方法内部直接 this 调**——@Async 同样吃自调用失效的亏 |
| `schema.sql:121` | `t_derivation.status VARCHAR(32) DEFAULT 'running'`，且自带 `error_msg/started_at/finished_at/duration_ms/retry_count` | **无需改 schema**，直接复用；状态机：`pending → running → success/failed` |
| `schema.sql:111` | `t_derivation.id BIGINT NOT NULL`（无 AUTO_INCREMENT） | 应用层雪花 ID；taskId 直接用此 id，无映射（见 §5.1） |
| `application.yml:1-43` | 无任何线程池/Tomcat/HikariCP/限流配置 | 全部新增（§7） |
| `application.yml:34-42` | LLM adapter 走 `t_model_routing` 解耦层（M2 SP-6） | 异步化不触碰模型路由层，正交 |

---

## 3. 整体架构

```
┌─────────────┐  POST /api/runtime/derive      ┌──────────────────────────┐
│   前端/调用方 │ ─────────────────────────────▶ │  Tomcat 工作线程 (200)    │
└─────────────┘                                 │  RuntimeController       │
   ▲                                            │  ① 限流拦截器(429)        │
   │ 轮询 GET /derive/{id}                      │  ② RateLimit @注解        │
   │ (2~3s/次)                                  │  ③ 生成 id→insert pending │
   │                                            │  ④ asyncRunner.deriveAsync│  ← 立即返回
   │ 200 + {status,result?}                     │     (提交即返回 id)        │
   │                                            └─────────────┬────────────┘
   │                                                          │ @Async
   │                                                          ▼
   │                                          ┌──────────────────────────────┐
   │                                          │ runtime-llm 线程池            │
   │                                          │ core=5 max=20 queue=50        │
   │                                          │ ⑤ 更新 running                │
   │                                          │ ⑥ engine.derive() 同步逻辑复用│
   │                                          │ ⑦ 更新 success/failed         │
   │                                          └──────────────┬───────────────┘
   │                                                         │
   │              ┌──────────────────────────────────────────┴──────────┐
   │              │  任务状态双写                                        │
   │              │  • ConcurrentHashMap（运行态/近期完成，内存查询）       │
   │              │  • t_derivation（最终态/历史，DB 查询）                 │
   │              └─────────────────────────────────────────────────────┘
```

**核心设计取舍**：
- 复用现有同步 `derive()` 不改其内部逻辑（7 步不动），只在**外层包异步壳 + 状态机**。改动面最小，回归风险最低。
- taskId = `t_derivation.id`（雪花），提交时先 insert 一条 `pending` 记录。前端轮询统一用这一个 id，内存/DB 主键一致，无映射层。

---

## 4. 详细设计

### 4.1 LLM 异步化

#### 4.1.1 接口契约变更（给 BA/前端）

**POST `/api/runtime/derive`**（变更：返回值）

请求体不变（`{role, task, caseId, stage}`）。

响应：
- 202 Accepted（原 200 改 202，语义更准）
- body：`R.ok({"taskId": 183624..., "status": "pending"})`
- 角色未注册仍 `R.fail`（同步校验，不进异步）

**GET `/api/runtime/derive/{taskId}`**（新增）

响应（统一 200，靠 status 字段区分）：
```json
{
  "code": 200,
  "data": {
    "taskId": 183624,
    "status": "running",          // pending / running / success / failed / not_found
    "role": "team-po",
    "caseId": "case-001",
    "result": null,                // 仅 success 时非空，结构同原 DerivationResult
    "error": null,                 // 仅 failed 时非空
    "createdAt": "...",
    "startedAt": "...",
    "finishedAt": "..."
  }
}
```
- `not_found`：内存无且 DB 无 → 返回 200 + status=not_found（便于前端判断，不抛 404）

#### 4.1.2 状态机

```
insert(pending) ──提交成功──▶ [内存map] pending
        │
        │ @Async 线程拿到任务
        ▼
     running ──engine.derive() 正常──▶ success  ──内存map保留(TTL)+DB更新
        │
        └────engine.derive() 抛 Throwable──▶ failed ──内存map保留(TTL)+DB更新(error_msg)
```

幂等：GET 查询顺序 = **内存 map → DB**。运行中查内存；已完成且内存被 TTL 清理则查 DB（status=success/failed + error_msg + 结果字段）。

#### 4.1.3 异步执行器（独立 Bean，防自调用失效）

> 关键约束：`@Async` 与 `@Transactional` 一样吃 **this 自调用失效**的亏。现有 `DerivationEngine.derive()` 内部已把落库下沉到 `DerivationPersistenceService`（M1.2 决策，`DerivationEngine.java:26`）。异步壳必须同样走**独立 Bean**，不可在 Controller 内部 this 调本类的 @Async 方法。

新增 `DerivationAsyncRunner`（独立 @Component，被 Controller 注入后跨 Bean 调用）：

```java
@Slf4j
@Component
public class DerivationAsyncRunner {

    private final DerivationEngine engine;              // 复用，不改
    private final CapabilityLoader capabilityLoader;
    private final DerivationTaskService taskService;    // 新增：状态机 + DB

    @Async("runtimeLlmExecutor")                        // 显式指定 Bean 名
    public void deriveAsync(Long taskId, AgentDefinition agent,
                            String task, String caseId, DerivationContext ctx) {
        // 异步方法本身不加 @Transactional（异步边界不持事务）
        taskService.markRunning(taskId);                // pending→running，更新内存+DB
        try {
            DerivationEngine.DerivationResult r = engine.derive(agent, task, caseId, ctx);
            // engine.derive 内部已落库(success)；此处同步内存态
            taskService.markSuccess(taskId, r);
        } catch (Throwable t) {                         // 含 Error，与 derive() 落库容错策略一致
            log.error("[AsyncDerive] 失败 taskId={}", taskId, t);
            taskService.markFailed(taskId, t);
        }
    }
}
```

Controller 改造（仅 derive 方法体，其余不动）：

```java
@PostMapping("/derive")
public R<Map<String,Object>> derive(@RequestBody DeriveRequest req) {
    AgentDefinition agent = capabilityLoader.getAgent(req.getRole());
    if (agent == null) return R.fail("角色未注册或未加载: " + req.getRole());
    DerivationContext ctx = DerivationContext.builder()
            .task(req.getTask()).stage(req.getStage()).build();
    // 提交：先建 pending 记录拿 id，再异步执行
    Long taskId = taskService.createPending(req.getRole(), req.getCaseId(), req.getStage());
    asyncRunner.deriveAsync(taskId, agent, req.getTask(), req.getCaseId(), ctx);
    return R.ok(Map.of("taskId", taskId, "status", "pending"));   // 立即返回
    // 注：HTTP 状态码 202 由 ResponseEntity 包装，见 §4.1.4
}
```

#### 4.1.4 202 Accepted 的返回方式

Spring 默认 `R.ok` 走 200。要返回 202，把签名改为 `ResponseEntity<R<...>>`：
```java
return ResponseEntity.accepted().body(R.ok(Map.of(...)));
```
GET 接口保持 200。

#### 4.1.5 派生线程池满的处理

`queue=50 + max=20` → 最多缓存 70 个任务。第 71 个提交时线程池拒绝。
- **拒绝策略**：`AbortPolicy`（默认）→ 抛 `RejectedExecutionException`。
- Controller 捕获后返回 **503** + `"当前派生任务排队已满，请稍后重试"`，前端退避重试。
- **不用** `CallerRunsPolicy`：异步化后提交者是 Tomcat 线程，CallerRuns 会让 Tomcat 线程跑去调 LLM，**正好摧毁线程池隔离的初衷**。这是 SE 明确裁断，不容 Dev 自由发挥。

### 4.2 限流（Bucket4j）

#### 4.2.1 依赖
`eaiselp-common/pom.xml` 加：
```xml
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j-jdk11-core</artifactId>
    <version>8.10.1</version>
</dependency>
```
纯 Java、无外部依赖、JDK11+。M2 内存桶，M3 可换 `bucket4j-redis`。

#### 4.2.2 注解（common 模块）
```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    String name() default "default";        // 桶名，用于日志/监控
    KeyType key() default KeyType.USER;     // IP / TENANT / USER
    int capacity() default 100;             // 桶容量（令牌上限）
    int refillPerMin() default 100;         // 每分钟补充令牌数
    String message() default "请求过于频繁，请稍后再试";
    enum KeyType { IP, TENANT, USER }
}
```

#### 4.2.3 使用示例（Dev 在各 Controller 贴注解）
```java
// auth 登录：5 次/分/IP
@PostMapping("/login")
@RateLimit(name="login", key=KeyType.IP, capacity=5, refillPerMin=5,
           message="登录尝试过于频繁，请 1 分钟后再试")
public R<...> login(...) { ... }

// runtime 派生：10 次/分/租户
@PostMapping("/derive")
@RateLimit(name="derive", key=KeyType.TENANT, capacity=10, refillPerMin=10,
           message="派生请求过于频繁，请稍后再试")
public ResponseEntity<R<...>> derive(...) { ... }

// 其他通用：100 次/分/用户
@GetMapping("/xxx")
@RateLimit(key=KeyType.USER, capacity=100, refillPerMin=100)
public R<...> xxx(...) { ... }
```

#### 4.2.4 拦截器 + Key 解析（common 模块）
```java
public class RateLimitInterceptor implements HandlerInterceptor {
    private final BucketRegistry registry;   // ConcurrentHashMap<String,Bucket> + TTL

    @Override
    public boolean preHandle(req, resp, handler) {
        if (!(handler instanceof HandlerMethod hm)) return true;
        RateLimit rl = hm.getMethodAnnotation(RateLimit.class);
        if (rl == null) return true;

        String key = resolveKey(rl.key(), req);          // ip/tenant/user 组合
        Bucket bucket = registry.getOrCreate(name+":"+key,
                            rl.capacity(), rl.refillPerMin());
        if (bucket.tryConsume(1)) return true;
        throw new RateLimitedException(rl.message());    // 全局异常处理 → 429
    }
}
```

**Key 解析策略（关键，防绕过）**：
- `IP`：优先 `X-Forwarded-For` 首段（反向代理场景），回退 `req.getRemoteAddr()`。**注意 X-Forwarded-For 可伪造**，生产须由网关/Nginx 覆写，应用信任第一跳——此项 Dev 标 TODO，M2 dogfooding 直连可接受，M3 上网关后须收敛。
- `TENANT`：从已解析的 JWT claims 取 `tenantId`（runtime 已有 JWT 校验，`application.yml:28-32`）。
- `USER`：从 SecurityContext / JWT claims 取 `userId`。
- **登录接口特殊性**：登录时用户尚未认证、无 JWT，故登录限流**只能用 IP**（已在示例中体现）。其他接口从 JWT 取 tenant/user。

#### 4.2.5 429 响应
全局异常处理器（common，确认 `GlobalExceptionHandler` 是否已存在；若已有追加 case，无则新建）：
```java
@ExceptionHandler(RateLimitedException.class)
public ResponseEntity<R<Void>> onRateLimited(RateLimitedException e) {
    return ResponseEntity.status(429)
            .header("Retry-After", "60")
            .body(R.fail(429, e.getMessage()));
}
```
- 加 `Retry-After: 60` 响应头，引导前端退避。

#### 4.2.6 桶内存清理
`BucketRegistry` 内 ConcurrentHashMap 无限增长会 OOM。M2 措施：
- 桶带最后访问时间，定时任务（`@Scheduled(fixedDelay=5*60*1000)`）清理 30 分钟未访问的桶。
- 硬上限（如 10 万桶），超限告警。M3 换 Redis backend 自然解决。

### 4.3 线程池隔离

#### 4.3.1 Tomcat（`application.yml`，runtime 模块）
```yaml
server:
  port: 8081
  tomcat:
    threads:
      max: 200                # 工作线程上限
      min-spare: 20           # 预热保活
    accept-count: 100         # 满载时排队连接数
    max-connections: 8192     # TCP 连接上限
    connection-timeout: 10s   # 连接建立超时（非请求处理超时）
```
**注意**：异步化后 Tomcat 线程不再被 LLM 阻塞，200 线程足以扛住前端轮询 + 接口请求。这是异步化的收益。

#### 4.3.2 runtime-llm 线程池（独立 Bean）
```java
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("runtimeLlmExecutor")
    public ThreadPoolTaskExecutor runtimeLlmExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(5);
        ex.setMaxPoolSize(20);
        ex.setQueueCapacity(50);
        ex.setThreadNamePrefix("runtime-llm-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy()); // §4.1.5
        ex.setWaitForTasksToCompleteOnShutdown(true);   // 优雅停机
        ex.setAwaitTerminationSeconds(60);              // 给在飞 LLM 调用最多 60s 收尾
        ex.initialize();
        return ex;
    }
}
```
**为什么 core=5**：LLM 是外部慢 IO + 烧 token，core 设低防突发烧光配额；max=20 是突发上限；queue=50 缓冲。**核心数与 LLM provider 的 QPS 限额挂钩**——若 GLM 账号限 5 QPS，core>5 也只会被 provider 限速，徒增超时。Dev 实施时确认 provider QPS 限额，必要时下调 core（见 §10 决策点 D-2）。

#### 4.3.3 auth 线程池（SE 裁断：本期不做）
任务背景提到 "auth 线程池隔离"，但：
- auth 当前是 DB 查询（`t_user` 校验 + BCrypt），响应快（毫秒级），不调外部慢 IO。
- Tomcat 200 线程足以承载 auth 流量，隔离无收益。
- **SE 决策：本期不为 auth 单独配线程池**（避免过度设计）。若 M3 auth 引入外部 OAuth/SSO，再隔离。此项已记入 §10 决策点 D-3。

### 4.4 连接池调优

#### 4.4.1 HikariCP（`application.yml`）
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000        # 拿连接等 30s
      idle-timeout: 600000             # 空闲 10min 回收
      max-lifetime: 1800000            # 连接最长 30min（防 MySQL wait_timeout 踢）
      leak-detection-threshold: 60000  # 60s 未归还告警（定位泄漏）
```
**为什么 20**：异步化后并发 DB 写入 = runtime-llm 池 max（20）+ Tomcat 池部分读，20 连接足够；过多连接反而拖累 MySQL。MySQL `max_connections` 默认 151，多模块共享需留余量。

#### 4.4.2 LLM HTTP 超时（gap，需 Adapter 层补）
现状：`DerivationEngine.java:48` 仅传 `timeoutMs=60000`（read/total）。**connect 超时无独立配置**。
要求：
- connect = 10s（建连失败快速失败，不占线程池槽位）
- read = 60s（LLM 长输出容忍）
实施位置：`GlmLlmAdapter`（及其他 Adapter）内部 HTTP 客户端配置。
**Dev 须 grep 现有 Adapter 的 HTTP 客户端构造处**（RestTemplate/WebClient/OkHttp），补 `.connectTimeout(Duration.ofSeconds(10))`。若现有时长不可配，列为 Adapter 改造子任务（见 §8 清单 T-9）。

---

## 5. 数据结构设计

### 5.1 DerivationTaskState（内存 POJO）
```java
@Data @Builder
public class DerivationTaskState {
    private Long taskId;                 // = t_derivation.id
    private String status;               // pending/running/success/failed
    private String role;
    private String caseId;
    private DerivationEngine.DerivationResult result;   // success 时
    private String error;                // failed 时
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private volatile long lastAccessAt;  // TTL 清理依据
}
```

### 5.2 DerivationTaskService（状态机 + 双写）
职责：
- `createPending(role, caseId, stage) -> Long`：雪花生成 id → insert `t_derivation(status=pending, started_at=now)` → 内存 map put → 返回 id。
- `markRunning(id)`：内存 + DB `status=running`。
- `markSuccess(id, result)`：内存 result 填充；DB `status=success` 由 `engine.derive` 内部 `persist()` 完成（已有逻辑，**勿重复写**），此处仅同步内存。
- `markFailed(id, throwable)`：内存 error 填充；DB `update status=failed, error_msg=...`（engine.derive 落库容错是 try-catch 不重抛，失败时 DB 不会自动标 failed，须 taskService 显式补写）。
- `getTask(id) -> state`：内存查 → miss 则查 DB（按 status+error_msg+result 字段构造 state）→ 仍 miss 返回 `not_found`。
- 内存 map：`ConcurrentHashMap<Long, DerivationTaskState>`，软上限 1 万条（防 OOM），LRU 淘汰；`@Scheduled` 清理 1 小时前已完成项（M2，M3 换 Redis）。

### 5.3 t_derivation 落库要点（Dev 注意）
- **不要**改 schema.sql（status 默认 running 不影响，代码层显式 insert pending 覆盖）。
- `engine.derive()` 内部 `persistenceService.persist(result)` 已写 success 行；**createPending 与 persist 操作的是不同字段/不同时机**：
  - createPending：插入 `id, role, case_id, stage, status=pending, started_at`（占位）。
  - engine.derive 落库：写入 `model, tokens, cost, status=success, finished_at, duration_ms, produced_artifacts, experience`。
  - **冲突点**：engine.derive 的 persist 是 insert 还是 update？Dev 须 Read `DerivationPersistenceService` 确认。若为 insert，则 createPending 不能预占行（改方案：createPending 只写内存 + 一条 pending 占位行，engine.derive persist 改为 update-by-id，或 taskService 在 markSuccess 时统一 upsert）。**此为实施前必须 Read 确认的依赖点**（见 §10 D-4）。

---

## 6. 接口设计（汇总，给 BA 对齐契约）

| 方法 | 路径 | 限流 | 响应 | 变更 |
|---|---|---|---|---|
| POST | `/api/runtime/derive` | 10/分/租户 | 202 + `{taskId,status:pending}` | **变更**（原同步 200 + result） |
| GET | `/api/runtime/derive/{taskId}` | 100/分/用户 | 200 + TaskState | **新增** |
| POST | `/api/auth/login`（auth 模块） | 5/分/IP | 原响应 | 加注解 |

> **路径前缀说明**：磁盘现状为 `/api/runtime`（无 `/v1`）。本方案沿用现状。若编排者要求统一 `/api/v1/*`，属全局路由决策，应单独立项统一改所有 Controller，不在本次 DFX 范围内混入（见 §10 D-1）。

---

## 7. 配置变更（application.yml 全量增量）

runtime 模块 `application.yml` 追加：
```yaml
server:
  port: 8081
  tomcat:                                    # §4.3.1
    threads: { max: 200, min-spare: 20 }
    accept-count: 100
    max-connections: 8192
    connection-timeout: 10s

spring:
  datasource:
    hikari:                                  # §4.4.1
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
      leak-detection-threshold: 60000

eaiselp:
  async:                                     # §4.3.2（可选外置，便于调参不重打包）
    llm:
      core: 5
      max: 20
      queue: 50
  ratelimit:                                 # §4.2（可选外置）
    bucket-ttl-minutes: 30
    bucket-max-size: 100000
```
AsyncConfig / BucketRegistry 用 `@Value` 或 `@ConfigurationProperties` 读上述，便于不重打包调参。

---

## 8. 改动文件清单（给 Dev，约 13 个文件）

**runtime 模块：**
1. `RuntimeController.java` —— derive 改异步返回 taskId（ResponseEntity 202）；新增 GET /derive/{taskId}。
2. `DerivationAsyncRunner.java`（新）—— @Async 方法独立 Bean。
3. `DerivationTaskService.java`（新）—— 状态机 + 内存 map + DB 双写 + TTL 清理。
4. `DerivationTaskState.java`（新）—— 内存 POJO。
5. `AsyncConfig.java`（新）—— runtimeLlmExecutor Bean + @EnableAsync。
6. `application.yml` —— §7 全量增量。
7. （可选）`RuntimeControllerTest.java` —— 原同步断言需改为异步断言。

**common 模块：**
8. `RateLimit.java`（新）—— 注解。
9. `RateLimitInterceptor.java`（新）—— 拦截器。
10. `BucketRegistry.java`（新）—— Bucket4j 桶管理 + TTL。
11. `RateLimitedException.java`（新）—— 异常。
12. `GlobalExceptionHandler.java`（追加 case 或新建）—— 429 + Retry-After。
13. `WebMvcConfig.java`（追加注册或新建）—— 注册 RateLimitInterceptor。
14. `common/pom.xml` —— 加 bucket4j 依赖。

**adapter 模块：**
15. `GlmLlmAdapter.java`（及其他 Adapter）—— 补 connect 超时 10s（T-9，§4.4.2 gap）。

**不改**：schema.sql、DerivationEngine.java（同步 7 步逻辑零改动，这是本方案最低回归风险的关键）。

---

## 9. 本机验证步骤（给 Dev/QA，必须全过）

### 9.1 准备
```bash
cd D:\AI\mywork\platform
mvn clean package -DskipTests
set GLM_API_KEY=<你的key>
set MYSQL_PASSWORD=<你的密码>
java -jar eaiselp-runtime\target\eaiselp-runtime-*.jar
```

### 9.2 验证点 1：POST 立即返回（不等 LLM）
```bash
curl -i -X POST http://localhost:8081/api/runtime/derive \
  -H "Content-Type: application/json" \
  -d '{"role":"team-po","task":"写登录PRD","caseId":"case-001","stage":"stage_1"}'
```
**期望**：1 秒内返回，HTTP **202**，body 含 `taskId` 和 `status:pending`。
**若 30 秒才返回 = 异步化失败**，查 @EnableAsync 是否生效、Bean 名是否对得上。

### 9.3 验证点 2：GET 状态流转
```bash
# 用上一步返回的 taskId
curl http://localhost:8081/api/runtime/derive/183624
```
**期望**：多次轮询可见 `pending → running → success`（failed 也算通过，证明异常路径通了）。success 时 result 非空。

### 9.4 验证点 3：并发 20 不死
```bash
# Windows PowerShell
1..20 | ForEach-Object -Parallel {
  curl -s -X POST http://localhost:8081/api/runtime/derive `
    -H "Content-Type: application/json" `
    -d '{"role":"team-ops","task":"部署","caseId":"c1","stage":"s1"}'
} -ThrottleLimit 20
```
**期望**：前若干返回 202（线程池未满），超 70 个起返回 **503**（排队满，符合 §4.1.5），**服务进程不崩、健康检查仍可访问**：
```bash
curl http://localhost:8081/actuator/health   # 或其他已知存活接口
```

### 9.5 验证点 4：限流 429
```bash
# 登录接口连发 6 次（限 5/分/IP）——auth 模块若已起则验，未起则验 derive（10/分/租户）
for /L %i in (1,1,6) do curl -s -o nul -w "%%{http_code}\n" -X POST http://localhost:8081/api/auth/login -H "Content-Type: application/json" -d "{\"username\":\"admin\",\"password\":\"wrong\"}"
```
**期望**：前 5 个返回 200/401，第 6 个返回 **429**，响应头含 `Retry-After: 60`。
**若都返回 200 = 拦截器没注册**，查 WebMvcConfig 是否 addInterceptor。

### 9.6 验证点 5：DB 最终态
```sql
SELECT id, role, status, error_msg, duration_ms, started_at, finished_at
FROM t_derivation WHERE id = <taskId>;
```
**期望**：status = success 或 failed（不是 pending 残留）；failed 时 error_msg 有内容。

---

## 10. 关键决策点（SE 裁断 / 待编排者确认）

| 编号 | 决策 | SE 建议 | 性质 |
|---|---|---|---|
| D-1 | 路径是否加 `/v1` 版本前缀 | 沿用现状 `/api/runtime`，版本化单独立项统一改 | 待编排者确认 |
| D-2 | runtime-llm core 是否下调 | 实施前确认 GLM 账号 QPS 限额，限 5 QPS 则 core=5 不动，限更低则下调 | Dev 实施时确认 |
| D-3 | auth 是否独立线程池 | **本期不做**（auth 是快 DB 查询，隔离无收益） | SE 已裁断 |
| D-4 | createPending 与 engine.derive 落库冲突 | **Dev 实施前必须 Read `DerivationPersistenceService` 确认 insert/update 语义**，按 §5.3 处理 | Dev 必做核对 |
| D-5 | 线程池拒绝策略 | AbortPolicy + 503（**禁用 CallerRunsPolicy**，见 §4.1.5） | SE 已裁断 |
| D-6 | 失败任务 DB 标记 | engine.derive 落库容错不重抛，taskService.markFailed 显式补写 status=failed | SE 已裁断 |

---

## 11. 风险与权衡

| 风险 | 影响 | 缓解 |
|---|---|---|
| 内存 map 重启丢失运行中任务 | 重启后 GET 查不到运行态 | 内存 miss 回落 DB 查；运行中被打断的任务 DB 仍是 running——加超时扫描（>10min 的 running 标 failed），M3 Redis + 启动恢复 |
| t_derivation 残留 pending/running | DB 脏数据 | 定时任务扫超时 pending/running → failed（M2 可后置，dogfooding 可接受） |
| @Async 自调用失效 | 异步不生效（变同步） | 异步壳独立 Bean `DerivationAsyncRunner`，Controller 跨 Bean 调用（§4.1.3） |
| X-Forwarded-For 伪造绕过 IP 限流 | 限流被绕过 | M2 dogfooding 直连可接受；M3 上网关后由网关覆写 XFF，应用只信第一跳（§4.2.4） |
| GET 轮询打爆 | 反向把异步收益抵消 | GET 也限流（100/分/用户）+ 前端约定 2~3s 退避轮询，成功后停 |
| BucketRegistry OOM | 内存涨 | TTL 清理 + 硬上限（§4.2.6），M3 换 Redis |
| 线程池满频繁 503 | 用户体验差 | 前端退避重试 + 监控告警；core/max 可经 yml 调参不重打包 |
| connect 超时未配 | 建连慢占线程槽 | T-9 补 Adapter（§4.4.2） |

---

## 12. M3 演进（不在本期实施）
- 任务状态：ConcurrentHashMap → Redis（`bucket4j-redis` + 任务 state hash）。
- 启动恢复：扫描 DB 中 running/pending 超时项，标 failed 或重试。
- 多实例：runtime 水平扩容，任务状态走 Redis 共享，负载均衡分发。
- 限流：单机桶 → 集群桶（Redis backend，全局限流）。
- 队列：必要时引入 MQ（LLM 任务解耦，削峰填谷）。

---

## 本次经验沉淀
1. **异步化最低回归风险姿势**：不动既有同步核心逻辑（derive 7 步零改），只在**外层包异步壳 + 状态机**。新逻辑隔离在新增 Bean，老逻辑保持可单独同步调用（便于回退与测试）。
2. **@Async 与 @Transactional 同源陷阱**：都吃 this 自调用失效。异步方法必须独立 Bean 跨类调用——这与本项目 M1.2 的 `DerivationPersistenceService` 下沉决策同构，是 Spring AOP 自调用失效的第二次踩点，应纳入 CLAUDE.md 的"Spring 坑位清单"。
3. **taskId 即 DB 主键的设计**：异步任务 id 直接用业务表主键（雪花），省一层映射，内存/DB/前端三方主键统一，GET 查询内存 miss 自然回落 DB——比"UUID taskId + 单独 task 表"简洁得多。
4. **线程池拒绝策略不能凭直觉**：异步化后 CallerRunsPolicy 会让 Tomcat 线程跑 LLM，摧毁隔离初衷。拒绝策略必须结合"提交者是谁"判断，SE 须明裁。
5. **任务背景与磁盘事实常有出入**（本次路径 `/api/v1/runtime/derive` vs 实际 `/api/runtime/derive`），SE 方案必须以磁盘为准，并把差异点显式记入决策表供编排者裁决，而非盲目照抄背景。
