# 代码评审报告 — GLM 接入（case-20260723-GLM接入）

> 结论：**有条件通过（代码侧通过；case 级 API Key 文档泄露需独立修复）**
>
> 评审人：team-reviewer（L1，独立于 Dev） | 评审日期：2026-07-23
> 审查依据：SE 修订方案 §9（`docs/设计规划文档/GLM接入-技术方案.md`）+ ES-001/ES-002 + LlmAdapter SPI
> 独立性声明：本报告所有结论基于 reviewer 自跑的 `git diff`、`mvn compile/test/package`、`dependency:tree`、门禁脚本复跑、grep 结果得出，未采信 Dev 变更报告里的任何"前后对比/自检结论"。

---

## 0. 总体结论

| 维度 | 结论 |
|---|---|
| 磁盘事实核对 | ✅ 5 文件改动全部真实落地，与 Dev 报告一致 |
| 反幻觉 5 条独立验证 | ✅ 全过（编译 SUCCESS / 11 单测全过 / 门禁 6/6 PASS / api-key 占位 / 无 zhipu-ai 残留） |
| 6 维度质量审查 | ✅ 鉴权/映射/异常/usage/测试/零依赖 全部达标 |
| 安全性审查（代码侧） | ✅ 代码中 api-key 不入日志、不拼进异常 message、测试用占位 key |
| 安全性审查（case 级） | 🔴 **真实 GLM API Key 明文出现在 2 份 case 产出文档**（SE 技术方案 §3.4 示例 + ops 新机配置记录），虽未 commit 但 `.gitignore` 第 37 行"取消忽略 docs"，`git add .` 后将泄露到仓库历史 |

**综合判定**：Dev 的 5 文件代码改动本身**质量达标、可放行**；但本 case 产出的 2 份文档含真实 API Key，属 case 级阻断安全问题，需在 case 归档/提交前由 SE/Ops 修复（删除明文 key 或替换为 `<your-glm-api-key>` 占位）。代码评审结论为**有条件通过**：代码通过，文档泄露问题挂单待修。

---

## 1. 磁盘事实核对（阶段 A，强制第一步）

### 1.1 真实改动清单（`git status` + `git diff --stat`）

```
 M eaiselp-adapter/pom.xml                                    ← +6（mockwebserver test 依赖）
 M eaiselp-adapter/src/main/java/com/eaiselp/adapter/defaultimpl/GlmLlmAdapter.java   ← +119/-11（重写）
 M eaiselp-runtime/src/main/resources/application.yml         ← +11（adapter.llm 配置段）
 M pom.xml                                                    ← +7（mockwebserver dependencyManagement）
?? eaiselp-adapter/src/test/java/com/eaiselp/adapter/defaultimpl/GlmLlmAdapterTest.java   ← 新增 273 行
```

5 文件全部真实落地，diff 行数与内容经 reviewer 逐行 Read 核对，与磁盘一致。**无虚报。**

### 1.2 Dev 报告 vs 磁盘 diff 一致性

| 报告称改了 | 磁盘实际 | 一致性 |
|---|---|---|
| parent pom 删 community-zhipu-ai、保留 mockwebserver | ✅ diff 仅含 mockwebserver dependencyManagement，无 zhipu 坐标 | 一致 |
| adapter pom 删 community-zhipu-ai 主依赖、保留 mockwebserver test | ✅ diff 仅含 mockwebserver test，无 zhipu 主依赖 | 一致 |
| application.yml 加 eaiselp.adapter.llm.* 配置 | ✅ diff 含 provider/glm.api-key/glm.base-url/model-mapping 四段 | 一致 |
| GlmLlmAdapter 重写为 RestClient + Jackson | ✅ 完整重写，占位逻辑已删除 | 一致 |
| GlmLlmAdapterTest 11 单测 MockWebServer | ✅ 新增 273 行，11 个 @Test | 一致 |

### 1.3 关键点独立 grep 核对

| 检查点 | 方法 | 结果 |
|---|---|---|
| RestClient 真用了 | `Select-String "RestClient"` 源码 | ✅ import L14 + getRestClient L65 + invoke L98 真实调用 |
| Bearer 鉴权 | `Select-String "Bearer"` | ✅ L71 `defaultHeader(AUTHORIZATION, "Bearer " + apiKey)` |
| 模型映射 | Read MODEL_MAPPING L44-48 | ✅ opus→glm-4-plus / sonnet→glm-4 / haiku→glm-4-flash / DEFAULT_MODEL=glm-4 |
| api-key 是占位非明文 | `git grep "api-key" yml` | ✅ runtime yml L30 `${GLM_API_KEY:}`；adapter yml L18 `${GLM_API_KEY:}` |
| 无 zhipu-ai SDK import 残留 | `git grep "zhipu"` 源码 | ✅ "zhipu" 仅出现在 javadoc 注释（方案选型说明），**无任何 SDK import 或类引用** |
| 无 langchain4j-zhipu 引用 | findstr 源码 | ✅ 干净 |

---

## 2. 6 维度质量审查（阶段 B）

### 维度 1：鉴权安全 ✅

- L71 `defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)`：标准 Bearer 鉴权，符合智谱 v4 规范（SE §9.3，非 JWT）。
- API Key 来源：`@Value("${eaiselp.adapter.llm.glm.api-key:}")`，yml 写 `${GLM_API_KEY:}` 占位，走环境变量注入，**不入库**（application.yml 会 commit，但占位无值）。
- `isAvailable()` L55 依据 apiKey 非空判断；`invoke` L80-82 apiKey 空时抛 `IllegalStateException`，**不连真实网络**。

### 维度 2：模型映射 ✅

`resolveModel` L142-146：
- `null`/空白 → DEFAULT_MODEL(`glm-4`)
- 以 `glm-` 开头 → 原样透传
- 其他 → `MODEL_MAPPING.getOrDefault(tier.toLowerCase(), glm-4)`

映射表与 SE §4 完全一致：opus→glm-4-plus / sonnet→glm-4 / haiku→glm-4-flash / 兜底 glm-4。大小写不敏感（`.toLowerCase()`）。测试 TC-1 全覆盖（含 OPUS/SONNET 大写、glm-4-air 透传、unknown 兜底）。

### 维度 3：异常处理 ✅

| 异常路径 | 实现 | 评估 |
|---|---|---|
| apiKey 空 | L80-82 `IllegalStateException` | ✅ |
| HTTP 4xx/5xx（含 429 限流） | L103-108 `onStatus` 读 body → 抛 RuntimeException（message 含 model + status + errBody） | ✅ message 含限流码 1113（测试 TC-3 断言） |
| 网络超时 | SimpleClientHttpRequestFactory 抛 SocketTimeoutException → 被 L134 `catch(Exception)` 包装为 RuntimeException | ✅ 测试 TC-4 断言 cause 链含 SocketTimeoutException 或 message 含 "timed out" |
| JSON 解析失败 | `objectMapper.readTree` 抛异常 → L134 catch 包装 | ✅ |
| onStatus 内抛的 RuntimeException | L131-133 `catch(RuntimeException)` 直接透传（不被外层 catch(Exception) 二次包装） | ✅ 分层正确 |
| choices/usage 缺失 | `JsonNode.path()` 链式取值，不 NPE | ✅ 测试 TC-5（usage 缺失→null）+ TC（choices 缺失→空 content） |

异常归一化符合 SE §9.5.3.3 要点。

### 维度 4：usage 映射 ✅

L118-121：
- `usage.prompt_tokens` → inputTokens
- `usage.completion_tokens` → outputTokens
- 字段缺失 → `null`（`usage.has(...)` 判断），**不伪造**（已废弃占位实现的 `prompt.length()/4`）

字段名用下划线 `prompt_tokens`/`completion_tokens`（SE §9.3 纠正了原方案 §2.5 的 SDK getter 名错误）。测试 TC-2 断言 inputTokens=128/outputTokens=256。

### 维度 5：测试质量 ✅

11 个 @Test，覆盖度：
1. `resolveModel_mapsTiersCorrectly`（TC-1）：opus/sonnet/haiku/unknown/null/空/大小写/glm-*透传，**全覆盖**
2. `invoke_success_mapsContentAndUsage`（TC-2）：成功路径 + **反向验证请求体**（model/messages/temperature/max_tokens/response_format 不存在）+ **鉴权头** `Bearer test-api-key-placeholder`
3. `invoke_success_defaultOptions`：options=null 时 temperature=0.7/max_tokens=4096 缺省值
4. `invoke_missingUsage_returnsNullTokens`（TC-5）：usage 缺失→null
5. `invoke_missingChoices_returnsEmptyContent`：choices 缺失→空 content 不 NPE
6. `invoke_http429_throwsWithStatusAndCode`（TC-3）：429 + error.code 1113 + model 名
7. `invoke_http500_throwsWithStatus`：500 错误路径
8. `invoke_readTimeout_throwsWrapped`（TC-4）：超时路径，`setBodyDelay(2s)` + readTimeout=300ms
9. `isAvailable_flipsWithApiKey`（TC-6）：apiKey 非空/空/null 翻转
10. `invoke_emptyApiKey_throwsIllegalState`：apiKey 空→IllegalStateException
11. `invoke_nullApiKey_throwsIllegalState`：apiKey null→IllegalStateException

**反向验证质量高**：TC-2 不仅断言响应，还用 `server.takeRequest()` 取出实际发出的请求，断言请求体字段 + 鉴权头 + 不含 response_format（R11）。这是手写 HTTP 相对 SDK 的最大红利，利用充分。

测试用 apiKey = `"test-api-key-placeholder"`（L46），**非真实 key**。MockWebServer 不连真实网络，零成本（R7）。

### 维度 6：零新增依赖 ✅

`mvn -pl eaiselp-adapter dependency:tree`：
- **zhipu：零匹配**（彻底无 zhipu-ai artifact）。
- `mockwebserver:4.12.0:test`（test scope 正确）。
- `okhttp:4.12.0:compile` —— 经核实是 **adapter 既有依赖**（minio 8.5.10 传递依赖，adapter pom L14，非本次新增）。
- RestClient/Jackson 随 spring-boot-starter-web 已在 classpath，**零新增 Maven 坐标**，符合 SE §9.2 方案 C 裁决。

---

## 3. 反幻觉 5 条独立验证（阶段 C，全部自跑）

| # | 检查项 | 方法 | 结果 |
|---|---|---|---|
| 1 | mvn clean package BUILD SUCCESS | `mvn -pl eaiselp-runtime -am package`（JDK 26） | ✅ Reactor 6/6 SUCCESS，runtime repackage 成功生成 `eaiselp-runtime.jar` |
| 2 | 11 单测全过 | `mvn -pl eaiselp-adapter test` | ✅ `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0` |
| 3 | 门禁 6/6 PASS | 复跑 `质量门禁-模块边界.ps1` G1-G6 逻辑（系统禁运行 .ps1，用 PowerShell `-Command` 内联逐条复现） | ✅ G1/G2/G3/G4/G5/G6 全 PASS |
| 4 | API Key 是占位非明文 | grep yml | ✅ `${GLM_API_KEY:}` |
| 5 | 无 zhipu-ai SDK import 残留 | grep 源码 | ✅ 干净 |

**门禁复跑说明**：当前系统 PowerShell 执行策略为 Restricted，禁止运行 .ps1 文件（`质量门禁-模块边界.ps1` 无法直接 `&` 调用）。reviewer 通过 `powershell -NoProfile -Command` 内联复现了脚本中的 G1-G6 全部断言逻辑（pluginManagement executions 检查 / library 模块 spring-boot-maven-plugin 检查 / @SpringBootApplication 检查 / @EnableDiscoveryClient 检查 / nacos-discovery 检查 / service 模块 repackage executions 检查），6 条全部 PASS。结论与脚本语义等价。

---

## 4. 安全性审查（阶段 D，API Key 场景）

### 4.1 代码侧 —— API Key 泄露风险 ✅

| 风险点 | 核查 | 结论 |
|---|---|---|
| api-key 是否泄露到日志 | 3 条 log 语句（L96/L105/L136）逐条审 | ✅ L96 只记 `prompt.length()`（长度非全文）；L105 记 HTTP 错误 body（服务端响应，不含 key）；均**不打印 apiKey / prompt 全文** |
| 异常 message 是否含 api-key | L81/L106/L137 | ✅ 仅含 model 名 + status + 错误 body，**不含 api-key** |
| MockWebServer 测试的 api-key | 测试 L46 | ✅ `"test-api-key-placeholder"` 占位，非真实 key |
| 请求体是否含敏感信息 | 请求体只有 model/messages/temperature/max_tokens | ✅ 不含 key（key 在 header） |

### 4.2 case 级 —— 真实 API Key 文档泄露 🔴 阻断（非 Dev 代码引入）

reviewer 用 `Select-String` 全仓扫描 docs 发现：**真实 GLM API Key（`<redacted>`）明文出现在 2 份 case 产出文档共 5 处**：

| 文件:行 | 产出角色 | 状态 |
|---|---|---|
| `docs/设计规划文档/GLM接入-技术方案.md:281,283` | SE | `??` untracked |
| `docs/过程执行文档/ops-GLM接入-新机配置记录.md:74,87,122` | Ops | `??` untracked |

**风险**：`.gitignore` 第 37 行明确"取消忽略 docs"（手册与设计稿纳入 docs 管理）。这两个文档当前是 untracked，**一旦执行 `git add .` / `git add docs/`，真实 key 将随 commit 进入仓库历史**，即使事后删除也可从历史中找回。这违反 SE 自己在 §2.4/§9.7 R3 写的"严禁明文 key 进 yml / key 不进任何文档/git commit"约束。

**归属**：本问题**不是 Dev 的 5 文件代码改动引入**（是 SE 技术方案示例 + Ops 新机配置记录），故不阻断"Dev 代码评审通过"；但属 case 级安全问题，reviewer 作为门禁必须上报，**case 归档/提交前必须修复**。

**修复建议**：
1. SE 技术方案 §3.4（L281/283）的 `export GLM_API_KEY=<redacted-prefix>...` 示例改为 `export GLM_API_KEY=<your-glm-api-key>` 占位。
2. Ops 配置记录 L74/87/122 的明文 key 改为 `<your-glm-api-key>` 占位（key 已在新机环境变量持久化，文档无需留明文）。
3. **强烈建议**：该 key 已在多份文档中明文出现且可能已在团队内部流传，**应在智谱控制台 rotate（吊销重发）该 key**，因为无法保证这些文档未被复制/截图外传。
4. 长期：在仓库根加 pre-commit hook，扫描 commit 内容是否含 GLM key 特征（`<redacted-prefix>...` 或 `*.s7J8gzxCxCvbEw1U` 模式），从机制上防止再次泄露。

### 4.3 代码侧可改进项（建议级，非阻断）

L136 `log.error("[LlmAdapter-GLM] 调用失败 ...", model, resolvedModel, e.getMessage(), e)` 的第 4 个参数传入了整个异常对象 `e`，SLF4J 会打印**完整堆栈**。当前 `SimpleClientHttpRequestFactory`（底层 HttpURLConnection）的 `SocketTimeoutException` message 通常只含 "Read timed out"，不含请求头，故**当前无泄露**。但若 M1.x 切换到 Apache HttpClient / OkHttp requestFactory，某些异常实现会把请求头（含 Authorization）拼进异常 toString，则有间接泄露风险。建议见 D3。

---

## 5. 缺陷清单（分级）

| 编号 | 严重度 | 文件:行 | 问题 | 归因 | 修复建议 |
|---|---|---|---|---|---|
| D1 | 🔴阻断 | `docs/设计规划文档/GLM接入-技术方案.md:281,283` + `docs/过程执行文档/ops-GLM接入-新机配置记录.md:74,87,122` | 真实 GLM API Key 明文出现在 2 份 case 产出文档共 5 处；`.gitignore` 取消忽略 docs，`git add .` 后将泄露到仓库历史，违反 SE 自定 §2.4/R3 约束 | **SE + Ops 文档**（非 Dev 代码） | ① 文档明文 key 替换为 `<your-glm-api-key>` 占位；② 智谱控制台 rotate 该 key；③ 加 pre-commit hook 扫描 key 特征。**case 归档/提交前必须完成** |
| D2 | 🟡建议 | `GlmLlmAdapter.java:65-75` | Dev 偏离 SE §9.5.3.2：`getRestClient` 每次调用新建 RestClient + SimpleClientHttpRequestFactory（底层 HttpURLConnection 无连接池复用），而非 SE 方案的单例字段缓存。Dev 注释说明了理由（测试需按用例注入不同 readTimeout 覆盖超时路径，TC-4 需 300ms 而成功用例需更长）。权衡合理，M1.0 单并发无性能影响，但 M1.x 高并发时每次新建 RestClient 有开销 | Dev 代码 | M1.x 优化为"按 timeoutMs 分桶缓存 RestClient"（Dev 注释已自述此 TODO）。不阻断 |
| D3 | 🟡建议 | `GlmLlmAdapter.java:136` | `log.error(..., e)` 打印完整堆栈。当前 SimpleClientHttpRequestFactory 的超时异常 message 不含 header，无泄露；但若 M1.x 换 requestFactory（OkHttp/Apache）可能间接泄露 Authorization 头到日志 | Dev 代码 | 可选改为只打 `e.getMessage()` + 类名；或保持现状但在切 requestFactory 时复查。不阻断 |
| D4 | 🟢可选 | `GlmLlmAdapter.java:51` | `new ObjectMapper()` 每实例新建。Spring Boot 默认已配 ObjectMapper Bean，可 `@Autowired` 复用主序列化栈（SE §9.4 提及"与项目主序列化栈一致"）。当前 adapter 内自建实例不影响功能 | Dev 代码 | 可 `@Autowired ObjectMapper` 复用。不阻断 |

**无阻断级代码缺陷**（D1 是文档/运维问题，非 Dev 代码）。

---

## 6. 门禁执行记录

| 规则集 | G1 | G2 | G3 | G4 | G5 | G6 | 结论 |
|---|---|---|---|---|---|---|---|
| 模块边界（QG-MODULE） | PASS | PASS | PASS | PASS | PASS | PASS | **6/6 PASS**（本次改动未触碰任何 module pom 的 plugin/边界配置） |

> 执行方式：系统 PowerShell 执行策略 Restricted 禁止运行 .ps1，reviewer 用 `powershell -NoProfile -Command` 内联复现 G1-G6 断言逻辑逐条验证，语义等价。

---

## 7. 本次经验沉淀

1. **PowerShell 执行策略 Restricted 环境下门禁脚本的可执行性盲区**：当前系统禁止运行 .ps1，导致 `质量门禁-模块边界.ps1` 无法直接 `&` 调用（之前 case 声称"门禁 PASS"可能基于脚本能跑的前提）。应对：门禁脚本既要能用 `-File` 跑，也要保证其断言逻辑可被 `-Command` 内联复现（脚本设计上已满足——纯 cmdlet + 正则）。reviewer 本次通过内联复现 G1-G6 全部断言完成独立验证。**经验**：后续在 Restricted 环境，reviewer/QA 应直接内联脚本核心断言跑，不依赖 .ps1 文件能否执行。

2. **"零新增依赖"不能只看本次 pom diff，要 dependency:tree 全量核对**：本次 adapter `dependency:tree` 出现 `okhttp:4.12.0:compile`，初看像新增，实则 minio 8.5.10 的传递依赖（既有）。若只看 git diff 会误判。**经验**：依赖审查以 `dependency:tree` 为准，且需追到引入来源（`mvn dependency:tree -Dverbose` 或看既有 pom 的 minio 声明），区分"本次新增"与"既有传递"。

3. **代码评审 vs case 级安全评审的边界**：Dev 代码本身可能完全合规（api-key 走占位、不入日志），但同一 case 的其他角色产出（SE 技术方案示例、Ops 配置记录）可能泄露真实密钥。reviewer 做安全性审查时**不能只扫 Dev 改的文件，要全仓扫 case 相关文档**（grep key 特征）。本次 D1 就是从 docs 全仓扫描发现，而非 Dev 代码。**经验**：API Key 类 case 的安全审查范围 = 代码 + 该 case 全部产出文档（SE/Ops/过程文档），缺一不可。

4. **Dev 合理偏离 SE 方案的处理**：Dev 把 RestClient 从单例改成按 timeout 新建（D2），偏离了 SE §9.5.3.2，但 Dev 在代码注释里写明了理由（测试需注入不同 readTimeout）。这是良性偏离——SE 方案的测试覆盖点（§9.5.5 点 6 超时）要求不同用例不同 timeout，单例缓存反而无法满足。**经验**：reviewer 审"偏离方案"时，先看偏离是否破坏了方案要达成的目标（本案：测试覆盖未打折扣，反而更充分），若不破坏则判建议级而非阻断，避免"机械对方案"扼杀合理工程判断。

---

**报告完结。** 代码评审结论：**有条件通过**——Dev 5 文件代码达标可放行；D1（API Key 文档泄露）为 case 级阻断，需 SE/Ops 在归档/提交前修复并 rotate key。
