# ADR-001: 模块边界与 repackage 策略

| 字段 | 值 |
|---|---|
| 编号 | ADR-001 |
| 标题 | capability/adapter/data 的模块边界（library vs service）与 spring-boot-maven-plugin repackage 策略 |
| 日期 | 2026-07-21 |
| 状态 | 已接受（Accepted）|
| 决策者 | team-ea（L3 企业架构师）|
| 触发 | M1.1 Dogfooding 第一个 case：修复 M1.0 编译失败 |
| 影响范围 | eaiselp-capability / eaiselp-adapter / eaiselp-data / eaiselp-runtime / 父 POM |
| 下游传导 | L2-Standards（写工程标准）→ L1-SE（出技术方案）→ L1-Dev（执行改造）|

---

## 1. 背景（为什么需要这个决策）

### 1.1 事件

M1.0 编译/runtime 打包出现失败：
```
eaiselp-runtime 编译报错：
程序包 com.eaiselp.capability.loader 不存在
程序包 com.eaiselp.capability.model 不存在
程序包 com.eaiselp.adapter.spi 不存在
```

### 1.2 直接根因（机制层）

- 父 POM（`pom.xml` 第 131-136 行）在 `pluginManagement` 里给 `spring-boot-maven-plugin` 加了 `<executions><execution><id>repackage</id><goals><goal>repackage</goal></goals>...`
- 所有引用该 plugin 的子模块都会触发 repackage
- capability/adapter/data 这些被 runtime 当 **Maven 库依赖**的模块也被 repackage 成 fat jar
- fat jar 把 class 重定位到 `BOOT-INF/classes/...`，runtime 编译期 classpath 找不到 `com.eaiselp.capability.loader.CapabilityLoader` 等符号 → 编译失败

### 1.3 深层根因（架构层 — 本 ADR 真正要解决的问题）

表面看是 repackage 配置问题，**本质是模块边界没定义清楚**。当前 4 个核心模块的职责形态自相矛盾：

| 模块 | 证据 | 表现出的"服务"特征 | 表现出的"库"特征 |
|---|---|---|---|
| eaiselp-capability | `EaiselpCapabilityApplication.java`（独立 main + `@EnableDiscoveryClient`）；`CapabilityController` 暴露 `/api/capability/**` 6 个端点 | 有 | runtime `pom.xml` 第 18 行把它作为 Maven 库依赖；`DerivationEngine.java` 第 5 行直接 `import com.eaiselp.capability.model.AgentDefinition` 同进程调用 |
| eaiselp-adapter | `EaiselpAdapterApplication.java`；`AdapterController` 暴露 `/api/adapter/status` | 有 | runtime `pom.xml` 第 19 行 Maven 库依赖；`DerivationEngine.java` 第 3-4, 24, 42 行直接注入 `AdapterFactory`、调 `getLlmAdapter()` |
| eaiselp-data | `EaiselpDataApplication.java`（带 `@MapperScan`）；8 个 entity + 8 个 mapper | 有 | runtime 当前未依赖（M1.0 简化），但 entity 类是天然库形态 |
| eaiselp-runtime | `EaiselpRuntimeApplication.java` 第 8 行 `scanBasePackages` 包含 `com.eaiselp.capability, com.eaiselp.adapter` | — | **既不当真用 Feign 远程调它们（直接 bean 注入），也不当真把它们当独立服务（同进程组件扫描）** |

**关键矛盾**：`DerivationEngine` 是**同进程本地调用** capability/adapter 的 bean（构造注入 + 直接调方法），却走的是 Maven 库依赖。同进程 + 库依赖本身可行（这就是模块化单体），但**同时保留独立 Application 类 + Nacos 注册 + REST Controller**就形成了"既是库又是服务"的双重身份——这正是 repackage 一刀切导致编译爆炸的根源：fat jar 重定位规则对"库"是破坏性的，对"服务"是必要的。

---

## 2. 业界对标

### 对标 1：Spring Cloud 官方推荐的"共享库 + 可执行服务"二分法

Spring Boot/Spring Cloud 生态（含官方 `spring-boot-maven-plugin` 文档与 Spring Initializr 生成的多模块样例）长期遵循一条不成文但被广泛验证的约定：

- **`spring-boot-starter-*` 这类 starter 是 library**：`packaging=jar`，**不配** `spring-boot-maven-plugin` 的 repackage，**没有** `@SpringBootApplication`，可以被任何服务依赖。
- **业务微服务是 executable jar**：有 `@SpringBootApplication`，配 repackage，打成 fat jar 独立启动。

这条二分法的本质是：**repackage 不可逆地改变了 jar 的内部布局（BOOT-INF/classes）**，所以一旦 repackage，这个 jar 就不能再被别的模块当 classpath 库依赖。官方 plugin 文档明确："`repackage` goal repackages your existing JAR/WAR archive so that it can be executed from the command line"——它的语义就是"做成可执行制品"，不是"做成可依赖制品"。

### 对标 2：模块化单体（Modular Monolith）模式

Simon Brown / Spring 团队近年在企业实践中推动的 Modular Monolith：在 M1/M2 阶段不强拆微服务，而是**单进程内的强边界模块**——每个模块是一个独立 Maven/Gradle 模块，通过 Maven 依赖复用，**但只有 1 个 `@SpringBootApplication`（最外层 host 模块）**，内层业务模块不独立启动、不独立注册到 Nacos。等真正需要独立扩容/独立部署时（通常是流量/团队规模触发），再把模块外移成独立服务，调用方式从 bean 注入改成 Feign。

**业界共识**：库与服务的边界是**部署单元**，不是**源码模块**。同一个源码模块，今天是库（被打进 host 进程），明天可以是服务（独立进程 + Feign 调用），但**不能在同一次构建里既是库又是服务**。

---

## 3. 架构原则（library vs service 边界）

以下原则由本 EA 定义，**约束 L1 编排者做模块/打包决策**，由 L2-Standards 写入工程标准：

### 原则 P1：库与服务的二选一（核心原则）

> 一个 Maven 模块在**同一次构建产物里**，要么是 library，要么是 service，不可兼得。

**判定标准**（任一命中即为 service）：
1. 有 `@SpringBootApplication` 的 main 类
2. 注册到服务发现（`@EnableDiscoveryClient` / Nacos）
3. 配了 `spring-boot-maven-plugin` 的 `repackage` execution
4. 独立进程启动、独立扩缩容

**全部不命中才是 library**：被打进别的进程，自身不启动。

### 原则 P2：库不能 repackage

> library 模块**禁止**配 `spring-boot-maven-plugin` 的 repackage execution。repackage 是 service 专属。

理由：repackage 把 class 重定位到 `BOOT-INF/classes/`，破坏了 jar 作为 classpath 库的可用性（本次编译失败就是违反此原则的直接后果）。

### 原则 P3：依赖方向单向、无环

> runtime → capability/adapter/data → common，单向依赖。被依赖的模块不能反向依赖下游（capability 不能 import runtime）。

当前未违反，写入标准是为了防止未来回归。

### 原则 P4：repackage 配置的位置（修正机制根因）

> `spring-boot-maven-plugin` 的 `repackage` execution **只允许出现在真正 service 模块的 POM 里**（直接 `<plugins>` 段），**不允许放在父 POM 的 `pluginManagement` 里全局开启**。

理由：父 POM `pluginManagement` 是"默认配方"，一旦配方里含 repackage execution，所有引用该 plugin 的子模块（无论是不是 service）都会被 repackage，无法选择性关闭。把 repackage 下沉到 service 模块自身，是"显式优于隐式"。

### 原则 P5：模块化单体的演进预留

> 当前选择 library 形态的模块，**保留**未来外移为独立服务的可能：包结构、Bean 命名、对外 API（Controller 或内部 Service 接口）保持清晰边界，以便 M2/M3 真正需要拆服务时，只需"加 Application + Feign 客户端 + 改依赖"，不必大改业务代码。

这是"演进优于一次到位"的具体落地——今天模块化单体，明天可以无痛拆服务，但今天不做拆服务的全部成本（独立部署/Nacos 注册/独立 Application）。

---

## 4. 方案对比

### 方案 A：全部拆成"纯库 + 独立服务"（彻底微服务化）

capability/adapter/data 既保留 library jar（供 runtime 同进程调用），又各自起独立服务进程对外暴露 REST。

| 维度 | 评价 |
|---|---|
| 可演进性 | 高（一步到位微服务）|
| 复杂度 | **极高**：要做 library/service 双 artifact、双 POM profile、双 Application；同进程调用 vs Feign 远程调用两套路径并存；M1.0 还没跑通就引入 |
| M1.0 修复成本 | **极大**：需要重构 4 个模块的构建结构、引入 Feign 客户端、解决双调用路径一致性 |
| 契合 DESIGN.md | 部分契合（DESIGN 第 101 行提了 Feign），但 DESIGN 第 171 行 M1.0 范围只要求"最小运行时手调派生跑通"，不要求微服务化 |

**结论：过度工程化。M1.0 阶段拆微服务是典型的 premature distribution**——Martin Fowler 的经典警告："我见过太多项目因为过早分布式化而失败"。否决。

### 方案 B：保持现状，但去掉 library 模块的 Application 类和 repackage

承认 M1.0 是模块化单体：capability/adapter/data 降级为纯 library（删 Application 类、删 Controller 或保留但不被独立启动、从 Nacos 移除），repackage 只在 runtime 配。runtime 通过 bean 注入同进程调用。

| 维度 | 评价 |
|---|---|
| 可演进性 | 中-高（保留包结构边界，未来可外移）|
| 复杂度 | **低**：删 Application 类 + 调 POM 配置，业务代码（DerivationEngine）不动 |
| M1.0 修复成本 | **小**：与根因机制修正天然一致（repackage 下沉到 service）|
| 契合 DESIGN.md | 与 DESIGN 第 171 行"M1.0 = 最小运行时跑通派生"完全契合；与第 86-101 行的"8+1 微服务"目标是演进关系，不是矛盾（M2/M3 再拆）|

**风险点**：capability/adapter 现有的 `/api/capability/**`、`/api/adapter/**` REST 端点会失去独立入口（因为 host 进程是 runtime，端点会被 runtime 的 scanBasePackages 接管）——但这恰恰是模块化单体的预期行为，端点合并到 runtime 进程暴露，无功能损失。

**结论：推荐。** 与根因修正、与 M1.0 目标、与业界 modular monolith 实践全部一致，是当前代价最小、未来可演进的方案。

### 方案 C：runtime 改用 Feign 远程调 capability/adapter（真微服务但只对 runtime）

承认 4 个模块都是真服务，runtime 不再 Maven 依赖 capability/adapter，改用 Feign 客户端调它们的 REST API。

| 维度 | 评价 |
|---|---|
| 可演进性 | 高 |
| 复杂度 | **高**：要为每个跨模块调用定义 Feign 接口、解决 DTO 共享（需引 eaiselp-common 或独立 api 模块）、调试链路变长、本地开发要起 4 个进程 |
| M1.0 修复成本 | **大**：DerivationEngine 第 3-5, 24-30 行的同进程注入要全改成 Feign 调用 + 异步/超时/重试/降级处理 |
| 契合 DESIGN.md | 契合第 101 行（OpenFeign），但与第 171 行 M1.0 范围矛盾——M1.0 是"手调派生跑通"，引入 4 进程分布式是 M2 的事 |

**结论：方向正确但时机错误。** 同方案 A 一样属于 premature distribution。可在 M2/M3 流量/团队规模触发时再做（届时本 ADR 标记"已替代"，新写 ADR-002）。

---

## 5. 裁决

### 5.1 采用方案 B

**M1.0/M1.1 阶段，capability / adapter / data 定性为 library（库），不独立部署。**

| 模块 | 定性 | packaging | repackage | Application 类 | Nacos 注册 | 被依赖方式 |
|---|---|---|---|---|---|---|
| eaiselp-common | library | jar | 否 | 无 | 无 | Maven 依赖 |
| **eaiselp-capability** | **library** | jar | **否** | **删除** | **移除** | Maven 依赖 + bean 注入 |
| **eaiselp-adapter** | **library** | jar | **否** | **删除** | **移除** | Maven 依赖 + bean 注入 |
| **eaiselp-data** | **library** | jar | **否** | **删除** | **移除** | Maven 依赖（runtime 引入后用 mapper）|
| eaiselp-runtime | **service（host）** | jar | **是** | 保留 | 保留 | — |
| eaiselp-gateway | service | jar | 是 | 保留 | 保留 | — |
| eaiselp-auth | service | jar | 是 | 保留 | 保留 | — |
| eaiselp-observability | service | jar | 是 | 保留 | 保留 | — |
| eaiselp-admin | service | jar | 是 | 保留 | 保留 | — |

### 5.2 配套机制修正（交给 L1-SE 落地，本 ADR 只定方向）

1. **父 POM**：`pluginManagement/spring-boot-maven-plugin` 里**移除** `<executions>` 段（即第 131-136 行的 repackage execution），只保留 `<configuration>`。repackage execution 下沉到各 service 模块自己的 POM。
2. **capability/adapter/data 的 POM**：移除 `<plugin>spring-boot-maven-plugin</plugin>`（库不需要它）。
3. **capability/adapter/data 的源码**：删除 `EaiselpCapabilityApplication.java` / `EaiselpAdapterApplication.java` / `EaiselpDataApplication.java`；从所有 Application 类的 `@EnableDiscoveryClient` 注册列表中移除这三个模块。
4. **Controller 去留**：`CapabilityController` / `AdapterController` 的 `@RestController` 保留（其端点会被 runtime 的 `scanBasePackages=com.eaiselp.capability` 接管，合并到 runtime 进程暴露）——这正是 runtime Application 第 8 行 scanBasePackages 已经在做的事，无需改动业务代码。
5. **DerivationEngine**：业务代码零改动（第 3-5, 24-30 行的同进程 bean 注入继续有效）。

> 说明：本节描述的是"目标态"，具体改文件清单、改的顺序、回归验证步骤由 L1-SE 出技术方案，本 ADR 不越界。

### 5.3 演进预留（M2/M3 触发条件）

当以下任一条件成立，启动"library → service 外移"评估（届时新写 ADR-002 替代本 ADR 的相关条款）：
- capability 的 markdown 热更新需要独立扩容
- adapter 需要支持多供应商并行/灰度
- data 的数据量/并发触发独立分库
- 团队按模块分拆（Conway's Law 触发）

外移动作（届时）：恢复 Application 类 + Nacos 注册 + repackage + runtime 改 Feign 客户端。因本 ADR 已保留包结构与 Controller 边界，外移成本可控。

---

## 6. 后果（Consequences）

### 正向
- **修复 M1.0 编译失败**：repackage 不再波及 library 模块，runtime 的 Maven 依赖恢复可用。
- **构建语义清晰**：每个模块构建产物唯一（库就是库，服务就是服务），不再有"既是库又是服务"的歧义。
- **本地开发简单**：只需启动 runtime 一个进程即可手调派生（契合 DESIGN.md M1.0 目标）。
- **演进路径保留**：包结构、Controller、Bean 命名边界完整，未来拆服务代价可控。

### 负向 / 代价
- **失去 capability/adapter 的独立部署能力**（M1.0 阶段不需要，可接受）。
- **runtime 进程变胖**：把 capability+adapter+data 的依赖都打进 fat jar（M1.0 规模下无性能问题）。
- **需要更新 DESIGN.md 第 86-101 行的"8+1 微服务"表述**：补充"M1 阶段为模块化单体，M2+ 演进为微服务"的过渡说明，避免文档与实现矛盾（建议 L2-Standards 或 L1-SE 一并处理）。

### 约束（施加给下游）
- **L2-Standards** 必须把原则 P1-P5 写入 `CLAUDE.md` / 工程标准，并加入质量门禁（编译期可校验：library 模块的 POM 不应出现 repackage execution）。
- **L1-SE** 出技术方案时必须遵循 P4（repackage 不进父 POM pluginManagement）。
- **未来任何新模块** 必须先按 P1 判定 library/service，再决定 POM 配置——这是不可绕过的前置检查。

---

## 7. 备选方案未选理由（汇总）

| 方案 | 未选核心理由 |
|---|---|
| A 全部拆库+独立服务 | premature distribution，M1.0 阶段过度工程化，与"最小运行时跑通"目标矛盾 |
| C runtime 改 Feign | 方向对但时机错，4 进程分布式是 M2 的事，M1.0 不应承担此复杂度 |

---

## 8. 引用证据

| 证据 | 文件:行 | 说明 |
|---|---|---|
| 父 POM repackage 全局开启（机制根因）| `pom.xml:131-136` | pluginManagement 里挂了 repackage execution |
| runtime 库依赖 capability/adapter | `eaiselp-runtime/pom.xml:18-19` | 当库用 |
| runtime 同进程组件扫描 | `EaiselpRuntimeApplication.java:8` | scanBasePackages 含 capability/adapter |
| runtime 同进程 bean 注入调用 | `DerivationEngine.java:3-5, 24, 42` | import + 构造注入 AdapterFactory，非 Feign |
| capability 独立服务特征 | `EaiselpCapabilityApplication.java:8-9` | 独立 main + @EnableDiscoveryClient |
| capability 独立 REST 端点 | `CapabilityController.java:12-13` | `/api/capability/**` |
| adapter 独立服务特征 | `EaiselpAdapterApplication.java:7-8` | 独立 main + @EnableDiscoveryClient |
| DESIGN 微服务设计意图 | `DESIGN.md:86-101` | 8+1 微服务 + OpenFeign |
| DESIGN M1.0 实际范围 | `DESIGN.md:171` | "最小运行时…手调派生跑通" |

---

## 9. 决策记录元数据

- 本 ADR 为不可变历史记录。若决策变更，新写 ADR-002 并将本 ADR 状态改为"已替代"，不修改本文件正文。
- 本 ADR 由 team-ea 产出，传导链：EA（本文件）→ L2-Standards（写工程标准）→ L1-SE（出技术方案）→ L1-Dev（执行改造）→ L1-Reviewer（按 P1-P5 验收）。
