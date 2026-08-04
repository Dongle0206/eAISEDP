# 代码评审报告 — M4-2 MCP + P2 修复 + M4-1 负载均衡

> 结论：**通过**
> 审查日期：2026-08-04
> 审查人：Reviewer（L1，独立于 Dev）
> 事实来源：磁盘 git diff / git show + Read 文件原文（非 Dev 报告转述）

---

## 0. 磁盘事实核对（强制第一步）

**核对结论：Dev 报告的改动全部真实落地，无虚报。**

| 改动项 | 报告声称 | 磁盘实际 | 一致性 |
|---|---|---|---|
| M4-2 HttpMCPAdapter | 新增 | untracked，磁盘存在，226 行，可读 | ✓ |
| M4-2 MCPController | 新增 | untracked，磁盘存在，143 行，可读 | ✓ |
| M4-2 application.yml | 新增 mcp.enabled/server-url | `git diff` 命中 +3 行 | ✓ |
| P2-D1 ObjectMapper | 改 buildFrontmatter/summarizeArtifacts | `git show 1dfebf0` 命中，磁盘文件一致 | ✓ |
| P2-D2 stage 语义 | setStage(pa.getType()) | 磁盘 L109 一致 | ✓ |
| M4-1 nginx.conf | host.docker.internal + 故障转移 | 磁盘 L15-22/L42/L51 一致 | ✓ |
| M4-1 start-ha.bat | 4 实例 -Dserver.port | 磁盘 L25/27/33/35 一致 | ✓ |

未发现"报告了但磁盘无"或"磁盘有但报告未提"的偏差。下文所有引用均来自本人 Read/git 输出，非 Dev 报告。

---

## 1. 审查范围逐项核验

### 1.1 M4-2 MCP — HttpMCPAdapter（`eaiselp-adapter/.../defaultimpl/HttpMCPAdapter.java`）

| 检查点 | 结论 | 证据 |
|---|---|---|
| JSON-RPC 2.0 格式 | ✓ 正确 | L197-201：`body` 含 `jsonrpc:"2.0"` / `method` / `params` / `id`，四要素齐全；`id` 用 `AtomicInteger.incrementAndGet()`（L71/L201），请求隔离不依赖服务端 |
| invokeTool 返回 result 解析 | ✓ 正确 | L136-140：`resp.path("result")`，缺失/null 返回 null，否则 `treeToValue(result, Object.class)`；params 包装为 `{name, arguments}`（L127-129），对齐 MCP `tools/call` |
| listTools inputSchema→ToolInfo.schema | ✓ 正确 | L168-176：读 `inputSchema` 字段，缺失置空 Map，`treeToValue(schemaNode, Map.class)` 填 `ToolInfo.schema`；name/description 也正确取自 `result.tools[].name/description` |
| error 处理 | ✓ 正确 | L217-223：JSON-RPC `error` 非空时抛 RuntimeException（不静默吞错），上层 catch 走降级返回 null/空表 |
| 条件装配 | ✓ 正确 | L57：`@ConditionalOnProperty(name="eaiselp.adapter.mcp.enabled", havingValue="true")`，默认 false 不装配；与 StubMCPAdapter 同开关（Stub `isAvailable()` 恒 false，工厂 `pick` 按 isAvailable 选 Http） |
| **server-url 注入（安全）** | ✓ 合规 | L61-62：`@Value("${eaiselp.adapter.mcp.server-url:}")` 注入，**无硬编码 URL**；空则 `isAvailable()` 返回 false（L77-79） |
| 无新增依赖 | ✓ | RestClient/Jackson 均来自既有 spring-boot-starter-web（与 DeepSeekLlmAdapter 一致），零新增 Maven 坐标 |
| team-* 硬编码（G11） | ✓ 0 命中 | findstr 全文搜 team-* 无匹配 |

### 1.2 M4-2 MCP — MCPController（`eaiselp-runtime/.../controller/MCPController.java`）

| 检查点 | 结论 | 证据 |
|---|---|---|
| 注入方式 | ✓ 合规 | L57：注入 `AdapterFactory`（非直接 `@Autowired MCPAdapter`），经 `adapterFactory.getMCPAdapter()` 取首个可用 Bean。与 ADR P3 单向依赖一致（runtime→adapter 工厂，不绑死具体实现） |
| @ConditionalOnProperty | ✓ 正确 | Controller 本身无该注解（合理：Controller 常驻，降级由 AdapterFactory 返回 null 处理）；适配器侧的 `@ConditionalOnProperty` 控制是否装配，Controller 容忍 null 走降级（L71/L98） |
| @RequirePermission | ✓ 正确 | L65/L89：`@RequirePermission("adapter:view")`，与适配器查看口径一致；注解类型为 `String[]`，单值字面量自动包装（Java 语法合法） |
| @RateLimit | ✓ 正确 | L66-68（list 60/min）、L90-92（invoke 30/min），`name/key/capacity/refillPerMin/message` 均与注解定义吻合；invoke 限流更紧（外部 HTTP 调用开销大），合理 |
| 降级处理 | ✓ | mcp==null/!isAvailable 时返回 `R.ok` 带 enabled=false 占位（L72/L99），不抛 500 |
| 接口版本（G14） | ✓ 实际合规 | 类级 `@RequestMapping("/api/v1/mcp")`（L48）+ 方法 `/tools` `/invoke`，完整路径 `/api/v1/mcp/tools`、`/api/v1/mcp/invoke` 均以 /api/v1/ 开头。门禁 WARN 是脚本逐注解解析不组合类前缀的已知局限（同 RuntimeController 等遗留模式），非真实违规 |
| 敏感信息暴露 | ✓ | 响应体仅 enabled/provider/tools/name/success/result/message，不暴露 server-url/api-key/内部异常栈 |

### 1.3 P2 修复 — DerivationPersistenceService（commit 1dfebf0，磁盘已核）

| 检查点 | 结论 | 证据 |
|---|---|---|
| buildFrontmatter 改 ObjectMapper | ✓ | 磁盘 L138-152：改用 `ObjectMapper OM`（L119 static final）+ `LinkedHashMap` 序列化，替代手工 StringBuilder 拼接；catch 返回 null（落库兜底，P12） |
| summarizeArtifacts 改 ObjectMapper | ✓ | 磁盘 L121-133：改用 `OM.writeValueAsString(list)`，替代手工拼接 `[{"type":...}]`，防 MySQL JSON 严格列报错 |
| stage 语义修复 | ✓ | 磁盘 L109：`a.setStage(pa.getType())`（产物类型 prd/review/test），不再用 `result.getStatus()`（派生状态 success，语义错误） |
| 测试回归 | ✓ | `mvn test` BUILD SUCCESS，DerivationEngineTest（3）+ PersistenceFailureTest（1）全绿 |

### 1.4 M4-1 负载均衡（nginx.conf / docker-compose-nginx.yml / start-ha.bat）

| 检查点 | 结论 | 证据 |
|---|---|---|
| upstream host.docker.internal | ✓ 正确 | nginx.conf L15-22：4 个 server 全用 `host.docker.internal:80xx`（非 127.0.0.1），容器内正确访问宿主机端口 |
| proxy_next_upstream 故障转移 | ✓ 正确 | L42/L51：`error timeout http_502 http_503`，两 location 均配，实例故障自动切换 |
| 健康探测 | ✓ | L54-58：`/actuator/health` 单独 location，access_log off（减噪） |
| max_fails/fail_timeout | ✓ | L15-22：`max_fails=3 fail_timeout=30s`，被动健康检查阈值合理 |
| start-ha.bat 4 实例 | ✓ 正确 | L25/27：auth `-Dserver.port=8085/8086`；L33/35：runtime `-Dserver.port=8081/8082`，端口覆盖正确，与 upstream 一致 |
| 实例错峰启动 | ✓ | L26/L32/L34 ping 等待，避免同时启动抢资源 |
| 旧实例清理 | ✓ | L18-20：先 netstat+taskkill 占用 8085/8086/8081/8082 的进程 |

---

## 2. 构建与门禁

### 2.1 编译打包
```
mvn clean package -DskipTests  →  BUILD SUCCESS（仅 Lombok/Unsafe deprecated 警告，无 error）
```

### 2.2 测试
```
mvn test  →  BUILD SUCCESS
  adapter：Tests run: 18, Failures: 0, Errors: 0
  runtime：Tests run: 4,  Failures: 0, Errors: 0
```
日志中的 ERROR 行为 GlmLlmAdapter/CircuitBreaker/PersistenceFailure 负向测试的预期日志（模拟 GLM 429/500、DB down），非测试失败。

### 2.3 门禁
| 门禁 | 结果 |
|---|---|
| G1-G6（模块边界） | **6/6 PASS** |
| G7-G10（产出物落盘） | G8 验证无 `$null` 字面文件 |
| G11-G15（平台承载） | BLOCK:0 / WARN:3 |

G11-G15 的 3 个 WARN 均非本次改动引入：
- **G11**（11 处 team-* 硬编码）：全在 `DerivationEngine.guessType()`（SP-4 待重构），HttpMCPAdapter/MCPController 0 命中（已单独 findstr 确认）。
- **G14**（MCPController `/tools` `/invoke`）：门禁脚本局限导致的误报（不组合类前缀 `/api/v1/mcp`），完整路径实际合规。
- **G15**（t_artifact 字段）：P2 已修复 frontmatter 填充逻辑，DB 层需 SP-4 补种子数据。

---

## 3. 缺陷清单

| 编号 | 严重度 | 文件:行 | 问题 | 修复建议 |
|---|---|---|---|---|
| — | — | — | 无阻断/建议级缺陷 | — |

本次审查未发现 🔴阻断或 🟡建议级缺陷。以下为 🟢可选观察项（不阻断合入）：

| 编号 | 严重度 | 文件:行 | 观察 | 备注 |
|---|---|---|---|---|
| O1 | 🟢可选 | HttpMCPAdapter.java:85-95 | `getRestClient()` 每次请求新建 RestClient + SimpleClientHttpRequestFactory，无连接池复用 | 当前注释已说明"MCP 调用低频，无开销"；如后续高频可改单例 + HttpClient 连接池。不阻断 |
| O2 | 🟢可选 | HttpMCPAdapter.java:68 | `ObjectMapper` 实例字段非 static final（DerivationPersistenceService 用了 static final） | ObjectMapper 线程安全，两种写法均可；建议统一风格但不强制 |
| O3 | 🟢可选 | MCPController.java:65 | invoke 与 list 用同一权限码 `adapter:view`；invoke 是写操作（调外部工具） | 可考虑后续拆 `adapter:view`（读）/`adapter:invoke`（写）细粒度权限，当前与既有适配器口径一致，不阻断 |

---

## 4. 结论

**通过。阻断缺陷数：0。**

- M4-2 MCP：JSON-RPC 2.0 协议映射正确，安全（server-url 注入不硬编码），工厂注入合理，权限/限流齐全。
- P2 修复：ObjectMapper + stage 语义两处真实落地，测试全绿。
- M4-1 负载均衡：host.docker.internal + proxy_next_upstream + 4 实例端口覆盖三处均正确。

3 项 🟢可选观察不影响合入，可由 Dev 自行判断是否在后续迭代优化。

---

## 本次经验沉淀

1. **门禁 G14 的类前缀盲区**：`质量门禁-平台承载规范.ps1` 的 G14 逐 `@*Mapping` 注解解析路径字符串，不与类级 `@RequestMapping` 前缀组合。当 Controller 采用"类 `/api/v1/x` + 方法 `/y`"的标准拆分写法时，门禁会对方法级 `/y` 误报 WARN。评审时需手工拼接类+方法路径核对完整路径是否合规，不能盲信门禁 WARN。MCPController 正是此模式，实际合规。

2. **JSON-RPC 2.0 适配器的正确性核对要点**：核对四要素（`jsonrpc:"2.0"` / `method` / `params` / `id`）是否齐全 + id 是否请求隔离（AtomicInteger 自增，避免并发 id 冲突）+ error 字段非空时是否抛错而非静默吞（防上层拿到"空结果"误判为工具无返回）。本次 HttpMCPAdapter 三点全对。

3. **Dev 报告"已修复"类改动须走 commit 历史核验**：P2 修复（commit 1dfebf0）在工作区已无 diff（已提交）。若只看 `git diff`（未暂存）会误判"改动未落地"，必须辅以 `git log --oneline` + `git show <commit>` 核对 HEAD 已提交内容。磁盘文件原文（Read）是最终事实来源。
