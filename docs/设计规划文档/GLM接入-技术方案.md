# GLM 接入技术方案

> **case-20260723-GLM接入与端到端验证** | 产出人：team-se | 阶段：1.8 技术方案设计
> 状态：待 Dev 落地 | 上游：M1.0 端到端验证最后一步

---

## 0. 背景与目标

把 `GlmLlmAdapter` 从 M1.0 占位实现（返回 `"[M1.0 占位]..."` + `prompt.length()/4` 伪 token）改为真实 GLM API 调用，打通 `DerivationEngine → LlmAdapter → 智谱开放平台` 的端到端链路。

**目标产物**：Dev 按本方案改造后，`POST /api/runtime/derive` 能拿到 GLM 真实生成内容，`t_derivation` / `t_artifact` 落库，`inputTokens/outputTokens` 为 GLM 真实 usage。

---

## 1. 现状分析（磁盘事实，SE 已 Read）

### 1.1 调用链（model 参数透传路径）

```
AgentDefinition.model = "opus"|"sonnet"|"haiku"   (frontmatter 第 4 行，22 角色已实测)
        │
        ▼
DerivationEngine.derive()
   L41: String model = agent.getModel() != null ? agent.getModel() : "sonnet";
   L46: LlmResponse resp = llm.invoke(model, fullPrompt, options);   ← 透传原始档位名
        │
        ▼
GlmLlmAdapter.invoke(model="opus"|"sonnet"|"haiku", prompt, options)   ← 当前占位，GLM 不认识这些名字
```

**结论**：映射必须在 `GlmLlmAdapter.invoke` 内部完成。`DerivationEngine` 不改动（最小侵入，符合 SPI 边界）。

### 1.2 关键文件现状

| 文件 | 行 | 现状 |
|---|---|---|
| `GlmLlmAdapter.java` | 25-38 | `invoke` 占位：返回 `[M1.0 占位]...`，token=`prompt.length()/4` |
| `GlmLlmAdapter.java` | 17-18 | `@Value` 注入 api-key / base-url，已有结构 |
| `GlmLlmAdapter.java` | 22 | `isAvailable()` = apiKey 非空 |
| `GlmLlmAdapter.java` | 41-44 | `listModels()` 已列 glm-4-plus/glm-4/glm-4-flash/glm-4-long |
| `LlmAdapter.java` | 12-28 | SPI：`LlmOptions`(temperature/maxTokens/stop/timeoutMs) + `LlmResponse`(content/inputTokens/outputTokens/model/durationMs/finishReason) |
| `DerivationEngine.java` | 44-47 | 调 `invoke(model, prompt, options.timeoutMs=60000)`；**LLM 调用无 try-catch**，异常会冒泡 |
| `DerivationEngine.java` | 61-66 | 仅落库有 try-catch(Throwable)，失败只 log 不重抛 |
| `application.yml` | 23-26 | 仅有 `eaiselp.system.path`，**无 `eaiselp.adapter.llm.*` 配置段** |
| parent `pom.xml` | 40 | `<langchain4j.version>0.31.0</langchain4j.version>` |
| parent `pom.xml` | 94-98 | dependencyManagement 只管 `dev.langchain4j:langchain4j`，**不含 community 模块** |
| parent `pom.xml` | 157-166 | 已配阿里云 maven 镜像，拉 community artifact 无障碍 |

---

## 2. 关键技术决策（SE 裁决）

### 2.1 SDK vs 手写 HTTP —— 用 SDK（langchain4j-community-zhipu-ai）

**裁决：用官方 SDK `dev.langchain4j:langchain4j-community-zhipu-ai:0.31.0`**，不手写 HTTP。

理由：
1. 智谱鉴权较复杂（API Key 拆 id.secret 后生成 JWT），手写易错；SDK 已封装。
2. SDK 自带 token usage 解析、finishReason 映射、超时/重试、异常归一化。
3. 已与现有 `langchain4j:0.31.0` 同版本同生态，无版本冲突。
4. Maven Central 已发布 0.31.0（经核实，类 `dev.langchain4j.community.model.zhipuai.ZhipuAiChatModel`）。

手写 HTTP 仅作为 SDK 不可用时的降级备选（M1.0 不走）。

### 2.2 模型映射位置 —— 放 adapter 层 invoke 内部

**裁决：映射逻辑放在 `GlmLlmAdapter` 内（私有方法 `resolveModel`）**，runtime 层零改动。

理由：
1. 符合 model-registry 体系设计——"能力档位→具体模型"解耦，adapter 正是该映射的落地处。
2. `DerivationEngine` 透传 `opus/sonnet/haiku`，adapter 翻译成 `glm-4-*`，调用方无感。
3. 映射表用 `@ConfigurationProperties` 绑定 yml，模型换代改 yml 不改代码（M1.0 给默认值，yml 可覆盖）。

### 2.3 异常处理 —— 抛 RuntimeException 向上冒泡，adapter 不吞异常

**裁决**：GLM 调用失败时抛 `RuntimeException`（包装原始异常 + model 上下文），不在 adapter 层吞掉。

理由：
1. `DerivationEngine` 当前对 LLM 调用无 try-catch，异常冒泡到 controller——由上层（runtime facade/controller）决定降级策略，符合分层职责。
2. 占位实现不能伪装成功（否则端到端验证失真）。M1.0 要的就是"真实失败/成功"信号。
3. api-key 未配置时，`invoke` 入口抛 `IllegalStateException`（不调真实 API）。

### 2.4 API Key 配置 —— 环境变量占位，yml 不落明文

**裁决**：`application.yml` 写 `${GLM_API_KEY:}`（占位），新机用环境变量或 `-DGLM_API_KEY=xxx` 注入。**严禁明文 key 进 yml**（`application.yml` 会被 git commit，仅 `application-prod.yml`/`application-local.yml` 被 .gitignore 排除）。

### 2.5 token usage 映射

GLM 返回的 `TokenUsage` 直接映射：
- `usage.inputTokenCount()` → `LlmResponse.inputTokens`
- `usage.outputTokenCount()` → `LlmResponse.outputTokens`
- `usage` 为 null 时（异常路径）字段置 null（不再用 `length()/4` 伪造）。

---

## 3. 改动清单（精确到文件:行）

### 3.1 parent `pom.xml`（补充 dependencyManagement）

**文件**：`D:\AI\mywork\platform\pom.xml`

在 L98 后（`langchain4j` 的 dependencyManagement 项之后）追加：

```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-community-zhipu-ai</artifactId>
    <version>${langchain4j.version}</version>
</dependency>
```

**原因**：统一管理 community 模块版本，子模块引用时不写 version。`${langchain4j.version}` 已是 0.31.0，版本一致。

**可独立编译**：是（仅 dependencyManagement，不引入实际依赖）。

---

### 3.2 adapter `pom.xml`（引入 SDK + 测试依赖）

**文件**：`D:\AI\mywork\platform\eaiselp-adapter\pom.xml`

在 L12（`langchain4j` 之后）追加主依赖：

```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-community-zhipu-ai</artifactId>
</dependency>
```

在 `<dependencies>` 末尾追加测试依赖（MockWebServer，用于集成测试，见 3.5）：

```xml
<dependency>
    <groupId>com.squareup.okhttp3</groupId>
    <artifactId>mockwebserver</artifactId>
    <version>4.12.0</version>
    <scope>test</scope>
</dependency>
```

**原因**：SDK 主依赖 + 测试用 mock HTTP 服务。`spring-boot-starter-test`（parent 全局已含）覆盖 JUnit5/Mockito。

**可独立编译**：是。

---

### 3.3 `GlmLlmAdapter.java` 改造（核心）

**文件**：`D:\AI\mywork\platform\eaiselp-adapter\src\main\java\com\eaiselp\adapter\defaultimpl\GlmLlmAdapter.java`

#### 3.3.1 新增 import（L3-10 区域追加）

```java
import dev.langchain4j.community.model.zhipuai.ZhipuAiChatModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
```

#### 3.3.2 类注解 + 映射配置（替换 L12-18）

```java
@Slf4j
@Component
@Configuration
@ConfigurationProperties(prefix = "eaiselp.adapter.llm")
@ConditionalOnProperty(name = "eaiselp.adapter.llm.provider", havingValue = "glm", matchIfMissing = true)
public class GlmLlmAdapter implements LlmAdapter {

    @Value("${eaiselp.adapter.llm.glm.api-key:}") private String apiKey;
    @Value("${eaiselp.adapter.llm.glm.base-url:https://open.bigmodel.cn/api/paas/v4}") private String baseUrl;

    /** 档位→GLM 模型映射，yml 可覆盖；默认值见 3.4 */
    private Map<String, String> modelMapping;
    @Value("${eaiselp.adapter.llm.model-mapping.default:glm-4}") private String defaultModel;

    /** ZhipuAiChatModel 实例缓存（线程安全，按 model 名复用，避免反复建 OkHttpClient） */
    private final Map<String, ZhipuAiChatModel> modelCache = new ConcurrentHashMap<>();

    // setter 供 @ConfigurationProperties 绑定 model-mapping（map 下划线→驼峰不适用，用 @ConfigurationProperties + setter）
    public void setModelMapping(Map<String, String> modelMapping) { this.modelMapping = modelMapping; }
```

> 说明：`@ConfigurationProperties(prefix="eaiselp.adapter.llm")` 绑定 `model-mapping.*`。Spring Boot 松绑定会把 `model-mapping` → `modelMapping`。

#### 3.3.3 替换 invoke 方法（L24-38）

```java
@Override
public LlmResponse invoke(String model, String prompt, LlmOptions options) {
    long start = System.currentTimeMillis();
    if (!isAvailable()) {
        throw new IllegalStateException("GLM API Key 未配置 (eaiselp.adapter.llm.glm.api-key / 环境变量 GLM_API_KEY)");
    }
    final String resolvedModel = resolveModel(model);
    log.info("[LlmAdapter-GLM] 调用: tier={}, resolved={}, prompt长度={}", model, resolvedModel, prompt.length());
    try {
        ZhipuAiChatModel zhipu = getOrCreateModel(resolvedModel, options);
        Response<AiMessage> response = zhipu.generate(UserMessage.from(prompt));
        TokenUsage usage = response.tokenUsage();
        String content = response.content() != null ? response.content().text() : "";
        return LlmResponse.builder()
                .content(content)
                .inputTokens(usage != null ? usage.inputTokenCount() : null)
                .outputTokens(usage != null ? usage.outputTokenCount() : null)
                .model(resolvedModel)
                .durationMs(System.currentTimeMillis() - start)
                .finishReason(response.finishReason() != null ? response.finishReason().name() : "stop")
                .build();
    } catch (Exception e) {
        log.error("[LlmAdapter-GLM] 调用失败 model={}, resolved={}, err={}", model, resolvedModel, e.getMessage(), e);
        throw new RuntimeException("GLM 调用失败 [model=" + resolvedModel + "]: " + e.getMessage(), e);
    }
}

/** 档位名→GLM 模型名。已是 glm-* 直接透传；未知档位用 default。 */
private String resolveModel(String tier) {
    if (tier == null || tier.isBlank()) return defaultModel;
    if (tier.toLowerCase().startsWith("glm-")) return tier;   // 调用方已传具体模型名，直接用
    if (modelMapping != null && modelMapping.containsKey(tier.toLowerCase())) {
        return modelMapping.get(tier.toLowerCase());
    }
    return defaultModel;
}

/** 按 resolvedModel 缓存 ZhipuAiChatModel 实例。callTimeout/readTimeout 必须显式设（issue #2496）。 */
private ZhipuAiChatModel getOrCreateModel(String resolvedModel, LlmOptions options) {
    long timeoutMs = options != null && options.getTimeoutMs() != null ? options.getTimeoutMs() : 60000L;
    return modelCache.computeIfAbsent(resolvedModel, m -> ZhipuAiChatModel.builder()
            .apiKey(apiKey)
            .model(m)
            .callTimeout(Duration.ofMillis(timeoutMs))
            .readTimeout(Duration.ofMillis(timeoutMs))
            // .baseUrl(baseUrl)  // ⚠ 见风险点 R2：0.31 SDK 是否支持自定义 baseUrl 需 Dev 实测确认；不支持则注释，用 SDK 默认（即智谱官方地址，与配置一致）
            .build());
}
```

#### 3.3.4 `listModels()` / `validateModel()` 保持不变（L40-44）

无需改动，已正确列出 4 个 GLM 模型。

**可独立编译**：是（改完后 `mvn -pl eaiselp-adapter compile` 应通过）。

---

### 3.4 `application.yml`（补配置段）

**文件**：`D:\AI\mywork\platform\eaiselp-runtime\src\main\resources\application.yml`

在 L26（`eaiselp.system` 之后）追加：

```yaml
  adapter:
    llm:
      provider: glm
      glm:
        api-key: ${GLM_API_KEY:}                                                      # 严禁明文，走环境变量
        base-url: https://open.bigmodel.cn/api/paas/v4
      model-mapping:                                                                  # 档位→GLM 模型，模型换代只改这里
        opus: glm-4-plus
        sonnet: glm-4
        haiku: glm-4-flash
        default: glm-4
```

> `provider: glm` 与 `GlmLlmAdapter` 的 `@ConditionalOnProperty(matchIfMissing=true)` 配合，确保该 Bean 装配。

**新机启动注入**（Ops 执行）：
```bash
# 环境变量（替换为真实 Key，不要写入版本库）
export GLM_API_KEY=<your-glm-api-key>
# 或 JVM 参数
java -DGLM_API_KEY=<your-glm-api-key> -jar eaiselp-runtime.jar
```

> ⚠️ **安全约束（R3）**：真实 API Key 严禁出现在任何文档/代码/日志/commit message 中。
> 文档只写 `<your-glm-api-key>` 占位符，真实值仅通过环境变量或 `-D` JVM 参数注入。
> 智谱控制台 rotate key 步骤：登录 https://open.bigmodel.cn → API Keys → 删除旧 Key → 新建 Key。

**可独立编译**：是（yml 不影响编译）。

---

### 3.5 单元测试 `GlmLlmAdapterTest.java`（新增）

**文件**：`D:\AI\mywork\platform\eaiselp-adapter\src\test\java\com\eaiselp\adapter\defaultimpl\GlmLlmAdapterTest.java`

**决策：方案 C（不依赖真实 API、不依赖 SDK 内部 baseUrl 能力）**——把 `getOrCreateModel` / 实际 HTTP 调用点做成可 spy 的 protected 方法，单测验证 adapter 自己的逻辑（映射、异常包装、usage 提取、builder 参数），真实 HTTP 集成测试单独标 `@EnabledIfEnvironmentVariable(named="GLM_API_KEY")` 手动触发，CI 不跑（不烧钱）。

测试覆盖点（Dev 必须写）：
1. `resolveModel("opus")` → `glm-4-plus`；`"sonnet"`→`glm-4`；`"haiku"`→`glm-4-flash`；`null`→`glm-4`（default）。
2. `invoke` 在 `apiKey` 为空时抛 `IllegalStateException`。
3. `isAvailable()` 随 apiKey 空非空翻转。
4. yml 覆盖映射：构造一个注入 `modelMapping={opus:glm-4-long}` 的实例，验证 `resolveModel("opus")`→`glm-4-long`。
5. 异常路径：mock 内部调用点抛异常，验证 `invoke` 包装成 `RuntimeException` 且 message 含 model 名。
6. usage 映射：构造 mock `Response<AiMessage>` 返回 `TokenUsage(100,200)`，验证 `inputTokens=100/outputTokens=200`。

集成测试（可选，标 `@EnabledIfEnvironmentVariable`）：
- 用 MockWebServer 起本地 HTTP，返回伪造 GLM 响应 JSON，验证 SDK 能解析（**仅当确认 0.31 SDK 支持自定义 baseUrl 指向 localhost 时才写**，否则跳过，留真实 key 手测）。

**可独立编译**：是（test 源码，不影响主构建）。

---

## 4. 模型映射表（SE 裁决）

| 能力档位 | 用途 | 角色数 | GLM 模型 | 理由 |
|---|---|---|---|---|
| **opus** (reasoning) | 复杂推理/架构/战略 | 8 | **glm-4-plus** | 智谱最强模型，对标 Claude Opus 的深度推理能力；EA/SE/PO/Reviewer 等高判断密度角色 |
| **sonnet** (structured) | 结构化产出/标准任务 | 12 | **glm-4** | 标准模型，平衡质量与成本；BA/Dev/QA/DBA 等大多数执行角色 |
| **haiku** (mechanical) | 机械/快速/低频 | 1 (team-ops) | **glm-4-flash** | 最快且免费，适合 ops 这类轻量触发；长上下文场景预留 glm-4-long（按需配 yml） |
| **default** | 档位缺失兜底 | — | glm-4 | 未知/空 model 兜底到标准档，安全 |

> 22 角色 model 字段已实测：opus(8)=ea/grc/pgm/po/reviewer/se/security/strategy/ux... sonnet(12)=analyst/ba/dba/dev/mle/performance/pm/qa/sre/standards/steward/vsm，haiku(1)=ops。

---

## 5. 改动顺序（按依赖，每步可独立编译）

| 步 | 改动 | 依赖 | 编译验证 |
|---|---|---|---|
| 1 | parent pom 加 zhipu-ai dependencyManagement (3.1) | 无 | `mvn -N validate` |
| 2 | adapter pom 加 zhipu-ai + mockwebserver (3.2) | 步1 | `mvn -pl eaiselp-adapter dependency:resolve` |
| 3 | application.yml 加配置段 (3.4) | 无 | 启动期校验 |
| 4 | GlmLlmAdapter 改造 (3.3) | 步2 | `mvn -pl eaiselp-adapter compile` |
| 5 | GlmLlmAdapterTest 单测 (3.5) | 步4 | `mvn -pl eaiselp-adapter test` |
| 6 | runtime 整体打包验证 | 步1-5 | `mvn -pl eaiselp-runtime -am package` |
| 7 | 端到端验证（QA/Ops） | 步6 注入 GLM_API_KEY 启动 | 见第 7 节 |

---

## 6. 风险点

| ID | 风险 | 等级 | 应对 |
|---|---|---|---|
| R1 | **0.31 SDK callTimeout=null 崩溃**（langchain4j issue #2496） | 高 | builder 必须显式设 `callTimeout`/`readTimeout`（已在骨架 `getOrCreateModel` 设置） |
| R2 | **0.31 SDK 是否支持自定义 baseUrl 未确认** | 中 | M1.0 用 SDK 默认（即智谱官方地址，与配置一致，不影响功能）；`baseUrl` 配置项保留为 @Value 注入，待 Dev 实测确认后启用；MockWebServer 集成测试视此能力决定是否写 |
| R3 | **API Key 泄露** | 高 | yml 只写 `${GLM_API_KEY:}` 占位，明文走环境变量；.gitignore 已排除 prod/local yml；key 不进任何文档/git commit |
| R4 | **GLM 限流 (QPM)** | 中 | M1.0 单并发验证不触发；后续 M1.x 若并发，在 adapter 层加限流/重试（本方案不含，留待后续） |
| R5 | **长上下文超 token 限制** | 中 | glm-4-plus 上下文 128K，单角色 prompt 一般 < 8K，M1.0 无虞；超长场景配 yml 映射到 glm-4-long(1M 上下文) |
| R6 | **LLM 调用异常未被 DerivationEngine 捕获** | 中 | 现状如此（仅落库有 try-catch）。adapter 抛 RuntimeException 会冒泡到 controller。**M1.0 接受此行为**（要真实失败信号）；若需降级，应由 runtime controller 层补 try-catch（不在本次范围，提示编排者评估） |
| R7 | **测试烧钱** | 低 | 单测方案 C 不调真实 API（0 成本）；集成测试标 `@EnabledIfEnvironmentVariable` 手动触发 |
| R8 | **community 模块包路径** | 低 | 0.31 为 `dev.langchain4j.community.model.zhipuai`（注意 community 中段，非 `dev.langchain4j.model.zhipuai`），import 别写错 |

---

## 7. 端到端验证步骤（给 QA / Ops）

**前置**：按第 5 节完成改造，新机 172.16.180.166:8081 用 `GLM_API_KEY` 环境变量启动 runtime。

```
1. POST http://172.16.180.166:8081/api/runtime/derive
   Body: {"role":"team-po","task":"写登录功能 PRD","caseId":"case-glm-test-001"}

2. 验收返回内容：
   - 不再是 "[M1.0 占位]..."
   - 是 GLM 真实生成的 PRD 文本（含 markdown 结构）

3. 验收 token 真实性：
   - inputTokens / outputTokens 是 GLM 真实 usage（通常 input 几百~几千，output 几百~几千）
   - 不再等于 prompt.length()/4 这种整除伪值

4. 查 MySQL：
   - SELECT * FROM eaiselp.t_derivation WHERE case_id='case-glm-test-001';  → 1 条记录
   - SELECT * FROM eaiselp.t_artifact WHERE case_id='case-glm-test-001';    → 1 条记录（type=prd）

5. 看日志：
   - [LlmAdapter-GLM] 调用: tier=opus, resolved=glm-4-plus, prompt长度=...
   - [Derive] 完成: role=team-po, in=<真实>, out=<真实>, 耗时=...
```

**异常路径验证**（可选）：
- 不配 GLM_API_KEY 启动 → 调用 derive 应返回 500 + `IllegalStateException: GLM API Key 未配置`。

---

## 8. 自检结论

| 检查项 | 结论 |
|---|---|
| GlmLlmAdapter 现状 Read 过？ | ✅ 45 行全读，占位实现确认（L25-38） |
| DerivationEngine 怎么传 model 搞清楚？ | ✅ L41 取 `agent.getModel()` 兜底 sonnet，L46 透传给 invoke；LLM 调用无 try-catch |
| AgentDefinition.getModel() 来源？ | ✅ L10 `private String model`，frontmatter 第 4 行 `model: opus/sonnet/haiku`（22 角色实测） |
| langchain4j 0.31 ZhipuAi 支持查了？ | ✅ `langchain4j-community-zhipu-ai:0.31.0` 存在，类 `ZhipuAiChatModel`，已知 issue #2496 必须设 timeout |
| parent pom 版本管理方式？ | ✅ dependencyManagement 管 langchain4j（L94-98），需补 community 模块 |
| API Key 泄露风险？ | ✅ yml 占位 + 环境变量，.gitignore 已排 prod/local yml |
| 改动是否侵入 runtime？ | ✅ 零侵入——DerivationEngine/AgentDefinition 不改，映射全在 adapter 层 |

---

## 本次经验沉淀

- **SPI 适配层的"能力档位→具体模型"映射应放在 adapter invoke 内部**，而非 runtime/engine 层。这样 runtime 透传语义档位（opus/sonnet/haiku）无感，模型换代只动 adapter + yml，符合 model-registry 解耦设计。本次 22 角色定义零改动即完成 GLM 接入，验证了该边界划分的正确性。
- **引入第三方 LLM SDK 前必须核实三件事**：①Maven 坐标（community 模块的命名中段易错，`langchain4j-community-zhipu-ai` 非 `langchain4j-zhipu-ai`）；②已知 issue（#2496 callTimeout 必须显式设，否则 NPE 崩溃）；③是否支持自定义 baseUrl（决定 MockWebServer 集成测试可行性）。三件缺一都会让 Dev 卡住。
- **API Key 类敏感配置统一用 `${ENV_VAR:}` 占位 + 环境变量注入**，绝不可依赖 .gitignore 的文件级排除（application.yml 默认会 commit）。本次新机启动用 `-DGLM_API_KEY=xxx` 注入。
- **单测防烧钱策略**：把 SDK 调用点抽成可 spy 的 protected 方法，单测验证 adapter 自身逻辑（映射/异常/usage 提取），真实 API 集成测试标 `@EnabledIfEnvironmentVariable` 手动触发，CI 零成本。

---

## §9 方案修订（2026-07-23，Dev 阻断后 SE 复核）

> **触发**：Dev 落地 §3.1/§3.2（S1/S2）时用 Maven Central 目录 + javap 反编译三重验证阻断，证明原方案 §2.1/§3/§8 的核心坐标错误。本段为 SE 复核后的**权威修订**，与上文冲突处以本段为准。

### 9.1 承认原方案错误

原方案 §2.1「用 SDK `dev.langchain4j:langchain4j-community-zhipu-ai:0.31.0`」及 §8 自检表「`langchain4j-community-zhipu-ai:0.31.0` 存在」**为误判**。Dev 三重验证成立：

1. `langchain4j-community-zhipu-ai` 这个 artifact **在 0.31.0 不存在**——community 命名从 1.0.0-alpha1 才起。0.31.0 只有旧 artifact `langchain4j-zhipu-ai`。
2. 旧 artifact `langchain4j-zhipu-ai:0.31.0` 的包是 `dev.langchain4j.model.zhipu`（非我写的 `community.model.zhipuai`），且 ChatModel builder **无 callTimeout 方法**（我 §3.3.3 的 issue #2496 修复方案在 0.31.0 无法编译）。
3. community `1.0.0-alpha1` 有 callTimeout，但 POM 强制 `langchain4j-core:1.0.0-alpha1`，与项目现有 `langchain4j:0.31.0` 冲突，要全量升级。

SE 复核结论：Dev 证据确凿，原方案作废。同时附带纠正：原方案 §2.1 称「智谱鉴权较复杂（API Key 拆 id.secret 后生成 JWT）」是基于**旧版认知的错误**——智谱 v4 已统一为 `Authorization: Bearer <api-key>`（见 9.3），鉴权极简，手写无难度。

### 9.2 A/B/C 三方案裁决（选 C）

| 方案 | 描述 | SE 评估 | 结论 |
|---|---|---|---|
| **A** | 换旧 artifact `langchain4j-zhipu-ai:0.31.0` | 包名 `dev.langchain4j.model.zhipu`，**builder 无 callTimeout**，超时失控（R1 NPE 风险无法用 §3.3.3 方案规避）；且旧 artifact 已停止维护，后续模型迭代（如 glm-4.6）无更新 | **否决** |
| **B** | 升 community `1.0.0-alpha1` | 有 callTimeout，但强制 `langchain4j-core:1.0.0-alpha1` 与项目 `langchain4j:0.31.0` 冲突，**需全量升级 langchain4j 主栈**（runtime/capability 等所有引用 0.31 的模块）。alpha 版本非稳定，回归面大，违反 M1.0「最小改动打通端到端」目标 | **否决**（留待 M1.x 单独评估） |
| **C** | 手写 HTTP，不依赖 SDK | 改动仅限 adapter 一个文件 + 两处 pom 回退 + yml 配置；鉴权极简（Bearer api-key）；adapter 已依赖 `spring-boot-starter-web`（Spring 6.1 `RestClient` 零新增依赖）；超时/重试/限流自控；无版本冲突 | **裁决采纳** |

**裁决理由（为什么 C 最优，非盲从编排者倾向）**：
1. **零依赖冲突**：adapter 现有依赖 `spring-boot-starter-web` 已含 Spring 6.1 `RestClient`（同步、流式 API），JSON 用 `spring-boot-starter-web` 间接带入的 Jackson（源码 grep 确认项目无自定义 ObjectMapper，用 Spring Boot 默认配置即可）。**无需新增任何 Maven 坐标**，彻底消除 A/B 的版本地狱。
2. **鉴权无难度**：SE 已核实智谱 v4 是标准 `Authorization: Bearer <api-key>`（[智谱 HTTP API 文档](https://docs.bigmodel.cn/cn/guide/develop/http/introduction)），原方案"JWT 易错"的顾虑不存在。
3. **改动可控**：只动 `GlmLlmAdapter.java`（核心）+ 两处 pom 回退 + `application.yml`，runtime/engine/SPI 零侵入。
4. **自控超时**：A 方案根本无法设 callTimeout 是致命缺陷，C 方案 RestClient 显式配 connect/read 超时。
5. **限流可见**：智谱 429 错误体格式已知（`{"error":{"code":"1113","message":"..."}}`，限流码 1112/1113/1304，见 [错误码文档](https://docs.bigmodel.cn/cn/faq/api-code)），可精准识别并冒泡（M1.0 不做自动重试，上层决定）。

### 9.3 智谱 GLM-4 v4 API 调用细节（SE 已核实，Dev 直接用）

**端点**：`POST https://open.bigmodel.cn/api/paas/v4/chat/completions`

**请求头**（标准 Bearer，非 JWT）：
```
Content-Type: application/json
Authorization: Bearer <完整 API Key，原样使用，不拆分不签名>
```

**请求体**（JSON）：
```json
{
  "model": "glm-4",
  "messages": [{"role": "user", "content": "<prompt 全文>"}],
  "temperature": 0.7,
  "max_tokens": 4096
}
```
> temperature 默认 GLM-4 系列建议 0.7（来自 options.getTemperature()，null 时用 0.7）；max_tokens 来自 options.getMaxTokens()，null 时 4096。不要传 `response_format`（与 LangChain 兼容性踩坑，见 9.7 R9）。

**成功响应体**（JSON）：
```json
{
  "id": "xxx",
  "choices": [
    {"index": 0, "message": {"role": "assistant", "content": "回答内容"}, "finish_reason": "stop"}
  ],
  "usage": {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150}
}
```

**错误响应体**（JSON，HTTP 4xx/5xx，含 429 限流）：
```json
{"error": {"code": "1113", "message": "请求过于频繁，请稍后再试"}}
```
限流相关业务码：`1112`(并发超限) / `1113`(QPM/TPM 超限) / `1304`(资源繁忙)。

**usage 映射**（GLM 字段 → LlmResponse 字段）：
- `usage.prompt_tokens` → `inputTokens`（Integer）
- `usage.completion_tokens` → `outputTokens`（Integer）
- `usage` 缺失时置 null（不伪造）。
- **注意字段名差异**：智谱用 `prompt_tokens`/`completion_tokens`（下划线），非原方案 §2.5 写的 `inputTokenCount`（那是 SDK 的 getter）。手写 JSON 映射必须用下划线字段名。

### 9.4 技术选型（HTTP 客户端 + JSON）

| 选型 | 选择 | 理由（基于磁盘事实） |
|---|---|---|
| HTTP 客户端 | **Spring 6.1 `RestClient`** | adapter 已依赖 `spring-boot-starter-web`（pom L11），Spring Boot 3.2.5（parent L32）自带 `RestClient`，**零新增依赖**。同步阻塞式，与现有调用链（invoke 同步返回 LlmResponse）契合，无需引入异步复杂度。源码 grep 确认项目无 RestClient/OkHttp/RestTemplate 现成封装，故在本 adapter 内自建单例 `RestClient`。 |
| JSON 序列化 | **Jackson `ObjectMapper`**（经 spring-boot-starter-web 间接可用） | Spring Boot 默认 Jackson，与项目主序列化栈一致（未来 controller 返回也走 Jackson）。不引 Hutool JSON（虽然 hutool-all 经 eaiselp-common 在 classpath，但 JSON 处理统一走 Spring 栈更一致，避免两套 JSON 库并存）。 |
| HTTP 客户端单例 | 类内 `private final RestClient restClient`，构造时 `RestClient.builder().baseUrl(...).defaultHeader(Authorization).requestFactory(超时配置).build()` | RestClient 线程安全，单例复用连接池。baseUrl + Authorization 在 builder 设一次，invoke 只发 body。 |

**超时配置**（用 Spring 的 `SimpleClientHttpRequestFactory` 或 `JdkClientHttpRequestFactory`）：
```java
ClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
((SimpleClientHttpRequestFactory) factory).setConnectTimeout(connectTimeoutMs);  // 默认 10000ms
((SimpleClientHttpRequestFactory) factory).setReadTimeout(readTimeoutMs);          // 来自 options.timeoutMs，默认 60000ms
```

### 9.5 修订后改动清单（覆盖原 §3，与上文冲突以此为准）

#### 9.5.1 parent `pom.xml`（**回退** Dev 已加的错误坐标）

**文件**：`D:\AI\mywork\platform\pom.xml`

**删除 L99-104**（Dev 按 §3.1 加的 `langchain4j-community-zhipu-ai` dependencyManagement 项）。方案 C 不需要任何 SDK 依赖管理。

**保留 L105-111**（mockwebserver dependencyManagement）——测试仍用 MockWebServer 模拟智谱 HTTP 响应，验证 adapter 自身逻辑（9.6）。

> 编译验证：`mvn -N validate`。

#### 9.5.2 adapter `pom.xml`（**回退** SDK 主依赖）

**文件**：`D:\AI\mywork\platform\eaiselp-adapter\pom.xml`

**删除 L13-17**（Dev 按 §3.2 加的 `langchain4j-community-zhipu-ai` 主依赖）。

**保留 L21-26**（mockwebserver test 依赖，version 由父 POM 管）。

**保留 L12**（`dev.langchain4j:langchain4j`）——这个是项目原有依赖，不删（其他地方可能用 langchain4j-core 的 message 类，SE 不擅自删既有依赖；Dev 若编译后确认 adapter 内不再引用 langchain4j 任何类，可在本 case 范围外另议，本 case 不动）。

> 方案 C 下 adapter 不再需要 langchain4j-zhipu-ai 的任何类，故无需新增任何坐标。HTTP 用 RestClient（已随 spring-boot-starter-web 在 classpath），JSON 用 Jackson（已随 spring-boot-starter-web 在 classpath）。
> 编译验证：`mvn -pl eaiselp-adapter dependency:resolve`。

#### 9.5.3 `GlmLlmAdapter.java` 改造（**重写**，覆盖原 §3.3）

**文件**：`D:\AI\mywork\platform\eaiselp-adapter\src\main\java\com\eaiselp\adapter\defaultimpl\GlmLlmAdapter.java`

##### 9.5.3.1 import（替换原 §3.3.1，不引 langchain4j-zhipu-ai）

```java
import com.eaiselp.adapter.spi.LlmAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
```

##### 9.5.3.2 类结构 + RestClient 单例（替换原 §3.3.2）

```java
@Slf4j
@Component
@ConditionalOnProperty(name = "eaiselp.adapter.llm.provider", havingValue = "glm", matchIfMissing = true)
public class GlmLlmAdapter implements LlmAdapter {

    @Value("${eaiselp.adapter.llm.glm.api-key:}") private String apiKey;
    @Value("${eaiselp.adapter.llm.glm.base-url:https://open.bigmodel.cn/api/paas/v4}") private String baseUrl;

    /** 档位→GLM 模型映射（保留原 §3.4 映射表逻辑，但移除 @ConfigurationProperties 改为内部默认 + yml 可选覆盖）。
     *  M1.0 简化：用静态默认 Map + 私有 resolveModel，yml 覆盖留待 M1.x（原 §3.4 的 @ConfigurationProperties map 绑定增加 Spring 复杂度，M1.0 先内联默认值打通端到端）。 */
    private static final Map<String, String> MODEL_MAPPING = Map.of(
            "opus", "glm-4-plus",
            "sonnet", "glm-4",
            "haiku", "glm-4-flash"
    );
    private static final String DEFAULT_MODEL = "glm-4";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestClient restClient;  // 延迟初始化（依赖 @Value 注入的 baseUrl/apiKey）

    @Override public String getType() { return "llm"; }
    @Override public String getProvider() { return "glm"; }
    @Override public boolean isAvailable() { return apiKey != null && !apiKey.isEmpty(); }

    /** 首次调用时构造 RestClient（apiKey/baseUrl 此时已注入）。线程安全，后续复用。
     *  抽成 protected 便于单测 spy/mock（见 9.6）。 */
    protected RestClient getRestClient(long timeoutMs) {
        if (restClient == null) {
            synchronized (this) {
                if (restClient == null) {
                    ClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    ((SimpleClientHttpRequestFactory) factory).setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
                    ((SimpleClientHttpRequestFactory) factory).setReadTimeout((int) Math.min(timeoutMs, 120000L));
                    restClient = RestClient.builder()
                            .baseUrl(baseUrl)
                            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .requestFactory(factory)
                            .build();
                }
            }
        }
        return restClient;
    }
```

##### 9.5.3.3 invoke 方法（替换原 §3.3.3，**手写 HTTP 全实现**）

```java
@Override
public LlmResponse invoke(String model, String prompt, LlmOptions options) {
    long start = System.currentTimeMillis();
    if (!isAvailable()) {
        throw new IllegalStateException("GLM API Key 未配置 (eaiselp.adapter.llm.glm.api-key / 环境变量 GLM_API_KEY)");
    }
    final String resolvedModel = resolveModel(model);
    long timeoutMs = options != null && options.getTimeoutMs() != null ? options.getTimeoutMs() : 60000L;
    double temperature = options != null && options.getTemperature() != null ? options.getTemperature() : 0.7;
    int maxTokens = options != null && options.getMaxTokens() != null ? options.getMaxTokens() : 4096;

    // 构造请求体（用 Map 拼，Jackson 序列化，避免写 POJO 类）
    Map<String, Object> requestBody = Map.of(
            "model", resolvedModel,
            "messages", new Object[]{Map.of("role", "user", "content", prompt)},
            "temperature", temperature,
            "max_tokens", maxTokens
    );

    log.info("[LlmAdapter-GLM] 调用: tier={}, resolved={}, prompt长度={}, timeoutMs={}", model, resolvedModel, prompt.length(), timeoutMs);
    try {
        String jsonResp = getRestClient(timeoutMs).post()
                .uri("/chat/completions")
                .body(requestBody)
                .retrieve()
                // 4xx/5xx 不直接抛，先读 body 提取 error.code/message，再包装成 RuntimeException
                .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(), (req, resp) -> {
                    String errBody = new String(resp.getBody().readAllBytes());
                    log.error("[LlmAdapter-GLM] HTTP错误 model={}, status={}, body={}", resolvedModel, resp.getStatusCode().value(), errBody);
                    throw new RuntimeException("GLM HTTP 错误 [model=" + resolvedModel
                            + ", status=" + resp.getStatusCode().value() + "]: " + errBody);
                })
                .body(String.class);

        // 解析响应（健壮：choices/usage 任一缺失不 NPE）
        JsonNode root = objectMapper.readTree(jsonResp);
        JsonNode choices = root.path("choices");
        String content = choices.isArray() && choices.size() > 0
                ? choices.get(0).path("message").path("content").asText("") : "";
        String finishReason = choices.isArray() && choices.size() > 0
                ? choices.get(0).path("finish_reason").asText("stop") : "stop";
        JsonNode usage = root.path("usage");
        Integer inputTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null;
        Integer outputTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : null;

        return LlmResponse.builder()
                .content(content)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .model(resolvedModel)
                .durationMs(System.currentTimeMillis() - start)
                .finishReason(finishReason)
                .build();
    } catch (RuntimeException e) {
        // onStatus 抛出的 RuntimeException 直接透传（已含 model 上下文）
        throw e;
    } catch (Exception e) {
        // Jackson 解析异常、网络 IO 异常等
        log.error("[LlmAdapter-GLM] 调用失败 model={}, resolved={}, err={}", model, resolvedModel, e.getMessage(), e);
        throw new RuntimeException("GLM 调用失败 [model=" + resolvedModel + "]: " + e.getMessage(), e);
    }
}

/** 档位名→GLM 模型名。已是 glm-* 直接透传；未知档位用 default。 */
private String resolveModel(String tier) {
    if (tier == null || tier.isBlank()) return DEFAULT_MODEL;
    if (tier.toLowerCase().startsWith("glm-")) return tier;
    return MODEL_MAPPING.getOrDefault(tier.toLowerCase(), DEFAULT_MODEL);
}
```

> **异常处理要点**：
> - HTTP 4xx/5xx（含 429 限流）→ onStatus 内读 body 提取 `error.code/message`，抛 RuntimeException 冒泡（message 含 model + status + 原始 body，便于排查）。
> - 网络超时 → SimpleClientHttpRequestFactory 抛 `java.net.SocketTimeoutException`，被外层 catch(Exception) 包装成 RuntimeException。
> - JSON 解析失败 → `objectMapper.readTree` 抛异常，被外层 catch 包装。响应体 choices/usage 缺失用 `JsonNode.path()` 链式取值，不 NPE。
> - API Key 未配置 → 入口抛 IllegalStateException（不调真实 API）。

##### 9.5.3.4 listModels() / validateModel() 保持不变（原 L40-44）

无需改动，已正确列 4 个 GLM 模型。

#### 9.5.4 application.yml（保持原 §3.4 不变）

**文件**：`D:\AI\mywork\platform\eaiselp-runtime\src\main\resources\application.yml`

Dev 的 S3（加配置段）**继续有效，无需调整**（SE 已 Read 确认现状无该段）。配置内容同原 §3.4：

```yaml
  adapter:
    llm:
      provider: glm
      glm:
        api-key: ${GLM_API_KEY:}                                                      # 严禁明文，走环境变量
        base-url: https://open.bigmodel.cn/api/paas/v4
      model-mapping:                                                                  # 档位→GLM 模型（M1.0 暂由 adapter 内联，本段预留 M1.x 启用 @ConfigurationProperties 覆盖）
        opus: glm-4-plus
        sonnet: glm-4
        haiku: glm-4-flash
        default: glm-4
```

> 注：§9.5.3.2 把 model-mapping 简化为 adapter 内联 `MODEL_MAPPING` 常量，yml 的 `model-mapping` 段在 M1.0 **暂不被读取**（adapter 用内联默认值）。保留 yml 段是为了 M1.x 启用 `@ConfigurationProperties` 覆盖时不需再加配置。Dev 不必为 M1.0 实现 yml→Map 绑定，降低复杂度。

#### 9.5.5 单元测试（**重写**，覆盖原 §3.5）

**文件**：`D:\AI\mywork\platform\eaiselp-adapter\src\test\java\com\eaiselp\adapter\defaultimpl\GlmLlmAdapterTest.java`

**方案 C 在方案 C 下的强化**：手写 HTTP 后，`getRestClient` 抽成 protected 方法，测试**用 MockWebServer 起本地 HTTP，让 RestClient 指向 localhost**，验证完整链路（请求体构造、鉴权头、响应解析、usage 映射、429 错误处理）——这是手写 HTTP 相对 SDK 的最大红利（SDK 不支持自定义 baseUrl 时无法做，见原 R2；RestClient builder 原生支持 `.baseUrl()`，MockWebServer 测试完全可行）。

测试覆盖点（Dev 必须写）：
1. `resolveModel`：`"opus"→glm-4-plus`、`"sonnet"→glm-4`、`"haiku"→glm-4-flash`、`null/""→glm-4`、未知值`"foo"→glm-4`、`"glm-4-air"→glm-4-air`（透传）。
2. `invoke` 在 apiKey 为空时抛 `IllegalStateException`（不连真实网络）。
3. **MockWebServer 成功路径**：起 MockWebServer，设 adapter.baseUrl=localhost，enqueue 伪造成功响应 JSON（含 choices/usage），验证 `invoke` 返回的 content/inputTokens/outputTokens/finishReason 正确，且**验证发出去的请求 body 含正确 model、messages、temperature、max_tokens，Authorization 头是 `Bearer <key>`**。
4. **MockWebServer usage 缺失路径**：enqueue 不含 usage 字段的响应，验证 inputTokens/outputTokens 为 null（不伪造）。
5. **MockWebServer 429 限流路径**：enqueue HTTP 429 + `{"error":{"code":"1113","message":"..."}}`，验证 `invoke` 抛 RuntimeException 且 message 含 "1113" 和 model 名。
6. **MockWebServer 超时路径**：enqueue 响应延迟 > readTimeout，验证抛 RuntimeException（含超时语义）。
7. `isAvailable()` 随 apiKey 翻转。

真实 API 集成测试（可选）：标 `@EnabledIfEnvironmentVariable(named="GLM_API_KEY")`，手动触发，CI 不跑（防烧钱）。

### 9.6 修订后改动顺序（覆盖原 §5）

| 步 | 改动 | 依赖 | 编译验证 |
|---|---|---|---|
| S1' | **回退** parent pom：删除 Dev 加的 community-zhipu-ai dependencyManagement 项（§9.5.1） | 无 | `mvn -N validate` |
| S2' | **回退** adapter pom：删除 Dev 加的 community-zhipu-ai 主依赖（§9.5.2）；保留 mockwebserver test 依赖 | S1' | `mvn -pl eaiselp-adapter dependency:resolve` |
| S3' | application.yml 加配置段（§9.5.4，与原 §3.4 一致） | 无 | 启动期校验 |
| S4' | **重写** GlmLlmAdapter.java：RestClient + Jackson 手写 HTTP（§9.5.3） | S2' | `mvn -pl eaiselp-adapter compile` |
| S5' | GlmLlmAdapterTest：MockWebServer 全覆盖（§9.5.5） | S4' | `mvn -pl eaiselp-adapter test` |
| S6' | runtime 整体打包验证 | S1'-S5' | `mvn -pl eaiselp-runtime -am package` |
| S7' | 端到端验证（QA/Ops，注入 GLM_API_KEY 启动） | S6' | 见第 7 节（不变） |

### 9.7 修订后风险点（覆盖原 §6，与上文冲突以此为准）

| ID | 风险 | 等级 | 应对 |
|---|---|---|---|
| ~~R1~~ | ~~0.31 SDK callTimeout NPE~~ | — | **方案 C 下不存在**（手写 HTTP 自控超时，无 SDK）。原 R1 作废。 |
| ~~R2~~ | ~~SDK 是否支持自定义 baseUrl~~ | — | **方案 C 下不存在**。RestClient builder 原生支持 `.baseUrl()`，MockWebServer 集成测试完全可行（§9.5.5）。原 R2 作废。 |
| R3 | API Key 泄露 | 高 | 不变，yml `${GLM_API_KEY:}` 占位 + 环境变量注入。 |
| R4 | GLM 限流 (QPM/TPM) | 中 | M1.0 单并发验证不触发；429 已识别（onStatus 读 error.code），冒泡由上层决定降级。M1.x 加指数退避重试（不在本 case）。 |
| R5 | 长上下文超 token 限制 | 中 | 不变。glm-4-plus 128K，单角色 prompt < 8K，无虞；超长配 yml 映射 glm-4-long。 |
| R6 | LLM 异常未捕获冒泡 | 中 | 不变。adapter 抛 RuntimeException，DerivationEngine 无 try-catch，冒泡到 controller。M1.0 接受（要真实失败信号）。 |
| R7 | 测试烧钱 | 低 | **方案 C 下降低**：MockWebServer 覆盖完整链路（含成功/失败/超时），真实 API 测试标 `@EnabledIfEnvironmentVariable` 手动触发，CI 零成本。 |
| R8 | ~~community 包路径~~ | — | **方案 C 下不存在**（不引 SDK，无包路径问题）。原 R8 作废。 |
| **R9** | **手写 HTTP 鉴权正确性** | 中 | 已核实智谱 v4 是标准 Bearer（非 JWT）；MockWebServer 测试断言请求头 `Authorization: Bearer <key>`（§9.5.5 测试点 3）。 |
| **R10** | **JSON 解析健壮性** | 中 | 用 `JsonNode.path()` 链式取值（choices/usage 缺失不 NPE）；usage 缺失置 null 不伪造；4xx/5xx body 完整读入异常 message。MockWebServer 覆盖（§9.5.5 测试点 3/4/5）。 |
| **R11** | **不传 response_format** | 低 | LangChain/GLM 兼容性踩坑（传 response_format 可能触发额外错误）。本方案请求体只传 model/messages/temperature/max_tokens，不传 response_format。 |
| **R12** | **RestClient 连接超时与 SDK 行为差异** | 低 | 用 SimpleClientHttpRequestFactory（JDK HttpURLConnection 底层），connect 10s / read 60s；M1.0 单并发无连接池压力，M1.x 高并发可换 OkHttp/Apache HttpClient requestFactory（不在本 case）。 |

### 9.8 SE 自检（反幻觉，覆盖原 §8，与上文冲突以此为准）

| 检查项 | 结论 |
|---|---|
| 新方案基于 Dev 三重验证证据？ | ✅ SE 已 Read adapter 现状（45 行占位）、parent pom（Dev 加的 L99-104）、adapter pom（Dev 加的 L13-17），确认 Dev 证据属实，A/B 不可行。 |
| 智谱 v4 API 调用细节核实？ | ✅ 端点 `POST .../paas/v4/chat/completions`、鉴权 `Bearer <api-key>`（非 JWT，纠正原方案 §2.1 错误）、请求/响应 JSON 格式、429 错误体 `{"error":{"code":"1113",...}}`——均经 [智谱 HTTP API 文档](https://docs.bigmodel.cn/cn/guide/develop/http/introduction) + [错误码文档](https://docs.bigmodel.cn/cn/faq/api-code) 核实。 |
| HTTP 客户端选型基于 adapter 现有依赖？ | ✅ SE 已 grep 源码（无现成 HTTP 客户端封装）+ Read adapter pom（已依赖 spring-boot-starter-web → 含 Spring 6.1 RestClient，Spring Boot 3.2.5 parent L32），零新增依赖。 |
| JSON 库选型基于 classpath 事实？ | ✅ spring-boot-starter-web 间接带入 Jackson（项目主序列化栈）；hutool-all 经 eaiselp-common 在 classpath 但不用 JSON 模块（避免两套 JSON 库并存）。 |
| 字段名映射核实？ | ✅ 智谱用下划线 `prompt_tokens`/`completion_tokens`，已纠正原 §2.5 的 SDK getter 名 `inputTokenCount`。 |
| mockwebserver 测试可行性？ | ✅ RestClient builder 原生支持 `.baseUrl(localhost)`，原 R2（SDK baseUrl 未确认）在方案 C 下消除。 |
| 改动是否侵入 runtime？ | ✅ 零侵入——DerivationEngine/AgentDefinition/LlmAdapter SPI 均不改，全部在 GlmLlmAdapter 内。 |

### 9.9 给 Dev 的明确续做指令（S1'-S7'）

1. **S1'（回退）**：parent pom 删除 L99-104 的 `langchain4j-community-zhipu-ai` dependencyManagement 项。
2. **S2'（回退）**：adapter pom 删除 L13-17 的 `langchain4j-community-zhipu-ai` 主依赖；保留 mockwebserver test 依赖（L21-26）；保留 `dev.langchain4j:langchain4j`（L12，既有依赖不动）。
3. **S3'**：application.yml 加 `eaiselp.adapter.llm.*` 配置段（同原 §3.4）。
4. **S4'（核心重写）**：GlmLlmAdapter.java 按 §9.5.3 重写——RestClient 单例 + Jackson 手写 HTTP，invoke 完整实现（请求体构造、Bearer 鉴权头、响应解析、usage 映射、429/超时/JSON 异常处理）。不引任何 langchain4j-zhipu-ai 类。
5. **S5'**：GlmLlmAdapterTest.java 按 §9.5.5 写——MockWebServer 覆盖成功/usage缺失/429/超时 + resolveModel 映射 + isAvailable + apiKey 空异常。
6. **S6'**：`mvn -pl eaiselp-runtime -am package` 全量打包。
7. **S7'**：交 QA/Ops 端到端验证（注入 GLM_API_KEY 启动，POST /api/runtime/derive，验证真实 GLM 内容 + 真实 token 落库，步骤同第 7 节）。

**SE 明确告诉 Dev**：原 §2.1/§3.1/§3.2/§3.3/§6 R1/R2/R8/§8 全部作废，以 §9 为准。S1/S2 需回退（删除已加的 community-zhipu-ai 坐标）。其余（映射表 §4、端到端验证 §7、API Key 安全 §2.4）不变。
