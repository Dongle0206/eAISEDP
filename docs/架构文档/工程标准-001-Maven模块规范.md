# 工程标准-001：Maven 模块规范（library vs service）

| 字段 | 值 |
|---|---|
| 编号 | ES-001 |
| 标题 | Maven 多模块项目的模块边界与打包规范 |
| 日期 | 2026-07-21 |
| 状态 | 强制执行（Mandatory） |
| 来源 | 翻译自 `ADR-001-模块边界与repackage策略.md`（P1–P5） |
| 适用范围 | eaiselp-* 全部 Maven 子模块（M1.0/M1.1 及以后所有版本） |
| 适用角色 | L1-SE（技术方案）、L1-Dev（落地）、L1-Reviewer（验收） |
| 上游 | ADR-001（不可变，本标准与之冲突时以 ADR-001 为准；若 ADR-001 被替代，本标准同步更新） |

---

## 0. 阅读对象与定位

- **L1-SE**：新增/重构任何模块前，必须按本标准第 1 节先做 library/service 判定，再出方案。
- **L1-Dev**：照第 2、3、4 节的 POM 模板和 Application 规则写代码，不得自创配置形态。
- **L1-Reviewer**：按第 6 节 checklist 逐条验收，任一阻断项不通过即打回。
- **本标准只规定"模块如何构建"，不规定业务逻辑**。业务逻辑规范见 CLAUDE.md「编码规范」段。

---

## 1. 模块分类定义（落地 P1）

### 1.1 二分法总则

一个 Maven 模块在**同一次构建产物里**只能是下列两类之一：

| 类别 | 定义一句话 | 构建产物语义 |
|---|---|---|
| **library（库）** | 被打进别的进程，自身不启动，作为 classpath 依赖被引用 | 普通 jar，class 在根包，可被任意模块 `import` |
| **service（服务）** | 独立进程启动、独立注册、独立部署 | 可执行 fat jar（repackage），class 重定位到 `BOOT-INF/classes/`，**不可被其他模块当 classpath 库依赖** |

> 同一个源码模块"今天是库、明天是服务"是允许的（演进），但**同一次构建产物里不能既是库又是服务**。

### 1.2 service 的判定（P1 的 4 个充分条件）

任一命中即为 service。**全部不命中才是 library**。

| # | 充分条件 | 检查方法 |
|---|---|---|
| S1 | 源码中存在带 `@SpringBootApplication` 的 main 类 | grep 源码 `@SpringBootApplication` |
| S2 | 注册到服务发现（`@EnableDiscoveryClient` / `@EnableEurekaClient` / Nacos 配置） | grep 源码 + 看 `application.yml` 的 `spring.cloud.nacos.discovery` |
| S3 | POM 配了 `spring-boot-maven-plugin` 的 `repackage` execution | 看 POM（直接 plugins 或经 pluginManagement 继承）|
| S4 | 设计上独立进程启动、独立扩缩容（看 DESIGN / ADR 裁决） | 查 ADR 决策表 |

> **判定顺序**：先查 S4（设计意图），再用 S1/S2/S3 交叉验证。若设计意图是 library 但代码命中 S1/S2/S3 任一条，**判为"代码与设计矛盾"，必须修正代码（删 Application 类 / 移除 repackage），不是改设计**。

### 1.3 当前项目的模块定性（来自 ADR-001 §5.1，强制）

| 模块 | 定性 | packaging | repackage | Application 类 | Nacos 注册 | 被依赖方式 |
|---|---|---|---|---|---|---|
| eaiselp-common | **library** | jar | 否 | 无 | 无 | Maven 依赖 |
| eaiselp-capability | **library** | jar | **否** | **无（须删除现有）** | **无（须移除）** | Maven 依赖 + bean 注入 |
| eaiselp-adapter | **library** | jar | **否** | **无（须删除现有）** | **无（须移除）** | Maven 依赖 + bean 注入 |
| eaiselp-data | **library** | jar | **否** | **无（须删除现有）** | **无（须移除）** | Maven 依赖（mapper 注入）|
| eaiselp-runtime | **service（host）** | jar | **是** | 保留 | 保留 | — |
| eaiselp-gateway | service | jar | 是 | 保留 | 保留 | — |
| eaiselp-auth | service | jar | 是 | 保留 | 保留 | — |
| eaiselp-observability | service | jar | 是 | 保留 | 保留 | — |
| eaiselp-admin | service | jar | 是 | 保留 | 保留 | — |

> **新增模块**：L1-SE 必须先填一张同样的定性表（附在技术方案里），按 §1.2 判定后才能动 POM。定性表纳入 ADR 决策库归档。

---

## 2. POM 模板

### 2.1 父 POM（`<packaging>pom</packaging>`）— 落地 P4

**关键约束（P4）**：父 POM 的 `pluginManagement` 段**只能放无副作用的 `<configuration>`**，**禁止**放带 `<executions>` 的副作用配置。repackage 这种"一引用就触发"的 execution 必须下沉到具体 service 模块自己的 `<plugins>` 段。

父 POM `pluginManagement` **正确形态**（推荐）：

```xml
<build>
    <pluginManagement>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <version>${spring-boot.version}</version>
                <!-- 只放 configuration，禁止 executions 段 -->
                <configuration>
                    <mainClass>${start-class}</mainClass>
                </configuration>
            </plugin>
        </plugins>
    </pluginManagement>
    <plugins>
        <!-- 全局真正生效的插件：maven-compiler-plugin 等，不含 spring-boot-maven-plugin -->
    </plugins>
</build>
```

**禁止形态**（M1.0 编译失败的根因，POM 第 131-136 行曾是这样）：

```xml
<!-- ❌ 禁止：pluginManagement 内放 executions -->
<pluginManagement>
    <plugins>
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
            <executions>
                <execution>
                    <id>repackage</id>
                    <goals><goal>repackage</goal></goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</pluginManagement>
```

**为什么**：`pluginManagement` 是"默认配方"，配方里一旦含 repackage execution，所有引用该 plugin 的子模块（无论是不是 service）都会被 repackage——这正是 M1.0 把 capability/adapter/data 错误打成 fat jar 的机制根因。下沉到 service 自身 POM 是"显式优于隐式"。

### 2.2 library 模块 POM — 落地 P1、P2

library 模块的 `<build>` 段**不得出现** `spring-boot-maven-plugin`。库不需要可执行化。

**正确形态**（以 eaiselp-capability 为例）：

```xml
<project>
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.eaiselp</groupId>
        <artifactId>eaiselp-parent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>eaiselp-capability</artifactId>

    <dependencies>
        <!-- 业务依赖：starter-web 可以保留（Controller 端点由 host 进程接管）；common 必须有 -->
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
        <dependency><groupId>com.eaiselp</groupId><artifactId>eaiselp-common</artifactId></dependency>
        <!-- 注意：library 模块不应依赖 spring-cloud-starter-alibaba-nacos-discovery（自身不注册，host 进程已注册） -->
    </dependencies>

    <!-- ❌ 禁止 <build><plugins> 里出现 spring-boot-maven-plugin -->
    <!-- ✅ 允许：finalName、resources、其他与可执行化无关的插件 -->
</project>
```

library 模块 POM **检查清单**：
- [ ] 无 `<plugin>spring-boot-maven-plugin</plugin>`（无论在直接 plugins 还是经 pluginManagement 继承）
- [ ] 无 `spring-cloud-starter-alibaba-nacos-discovery` 依赖（library 自身不注册到 Nacos）
- [ ] `packaging` 默认 `jar`，无需显式声明
- [ ] `<finalName>` 可选保留（仅影响本地构建产物名，无副作用）

### 2.3 service 模块 POM — 落地 P1、P4

service 模块**必须**在自己 POM 的 `<plugins>` 段显式声明 `spring-boot-maven-plugin` **并带 repackage execution**。execution 不能依赖父 POM pluginManagement 自动触发（P4 要求显式下沉）。

**正确形态**（以 eaiselp-runtime 为例）：

```xml
<project>
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.eaiselp</groupId>
        <artifactId>eaiselp-parent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>eaiselp-runtime</artifactId>

    <dependencies>
        <!-- service 模块才允许 nacos-discovery（自身注册） -->
        <dependency><groupId>com.alibaba.cloud</groupId><artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId></dependency>
        <dependency><groupId>com.eaiselp</groupId><artifactId>eaiselp-common</artifactId></dependency>
        <dependency><groupId>com.eaiselp</groupId><artifactId>eaiselp-capability</artifactId><version>${project.version}</version></dependency>
        <dependency><groupId>com.eaiselp</groupId><artifactId>eaiselp-adapter</artifactId><version>${project.version}</version></dependency>
    </dependencies>

    <build>
        <finalName>${project.artifactId}</finalName>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <!-- version/configuration 从父 POM pluginManagement 继承，无需重复 -->
                <executions>
                    <execution>
                        <id>repackage</id>
                        <goals><goal>repackage</goal></goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

service 模块 POM **检查清单**：
- [ ] `<plugins>` 段显式声明 `spring-boot-maven-plugin`
- [ ] 显式带 `<executions><execution><id>repackage</id><goals><goal>repackage</goal></goals></execution></executions>`
- [ ] 有 main 类（`@SpringBootApplication`）
- [ ] 有 nacos-discovery 依赖（自身注册）

> **现状偏差（M1.0）**：当前 5 个 service 模块的 POM（runtime/gateway/auth/observability/admin）只写了 `<plugin>spring-boot-maven-plugin</plugin>` 没写 executions，是靠父 POM pluginManagement 的 execution 自动触发的——P4 落地后父 POM 移除 execution，这些 service 模块的 POM 必须补上 executions，否则不再 repackage，无法独立启动。**L1-SE 技术方案必须覆盖这一改动**。

---

## 3. Application 类规则 — 落地 P1、P5

### 3.1 规则表

| 模块类别 | 允许 `@SpringBootApplication`？ | 允许 `@EnableDiscoveryClient`？ | 允许 main 方法？ |
|---|---|---|---|
| library | **否** | **否** | **否** |
| service | 是（且必须有，作为进程入口） | 是（若设计要注册到 Nacos） | 是 |

### 3.2 library 模块如何暴露 REST 端点（P5 演进预留）

library 模块**可以**保留 `@RestController` / `@RequestMapping`（即 Controller 类），但这些端点**不会**因为 library 自身启动而暴露——它们由**引用它的 host service**（如 runtime）通过 `scanBasePackages` 扫描接管，合并到 host 进程暴露。

**runtime 当前实现**（`EaiselpRuntimeApplication.java:8`）已经在这样做：

```java
@SpringBootApplication(scanBasePackages = {
    "com.eaiselp.runtime",
    "com.eaiselp.common",
    "com.eaiselp.capability",   // ← 把 capability 的 Controller/Bean 接管进 runtime 进程
    "com.eaiselp.adapter"        // ← 同上
})
```

因此 capability/adapter 的 Controller 类（`CapabilityController` / `AdapterController`）**保留不删**，端点在 runtime 进程下照常可达（`/api/capability/**`、`/api/adapter/**`）。

### 3.3 删除 Application 类时的隐藏项（给 L1-Dev 的提示）

`EaiselpCapabilityApplication.java` 当前带 `@EnableScheduling`（第 10 行）。删除该类时，**必须**把 `@EnableScheduling` 迁移到 runtime 的 Application 类，否则 capability 模块内基于 `@Scheduled` 的定时任务会失效。L1-SE 技术方案须显式列此项。

---

## 4. 依赖方向约束 — 落地 P3

### 4.1 单向无环图

```
                      ┌──────────────────────┐
                      │  eaiselp-common      │  library（最底层，被所有人依赖）
                      └──────────▲───────────┘
                                 │
              ┌──────────────────┼──────────────────┐
              │                  │                  │
     ┌────────┴───────┐  ┌───────┴────────┐  ┌──────┴─────────┐
     │ eaiselp-       │  │ eaiselp-       │  │ eaiselp-       │
     │ capability     │  │ adapter        │  │ data           │   library
     │ (library)      │  │ (library)      │  │ (library)      │
     └────────▲───────┘  └───────▲────────┘  └──────▲─────────┘
              │                  │                  │
              └──────────────────┼──────────────────┘
                                 │
                      ┌──────────▼───────────┐
                      │  eaiselp-runtime     │  service（host，M1 唯一启动进程）
                      └──────────▲───────────┘
                                 │ (并行，不互相依赖)
        ┌──────────────┬─────────┴────────┬──────────────┐
        │              │                  │              │
   ┌────┴────┐   ┌─────┴─────┐   ┌─────────┴────┐  ┌──────┴────────┐
   │ gateway │   │   auth    │   │observability │  │    admin      │   service
   │ service │   │  service  │   │   service    │  │   service     │
   └─────────┘   └───────────┘   └──────────────┘  └───────────────┘
```

### 4.2 规则

| 规则 | 描述 |
|---|---|
| R1 | common 只能依赖第三方库，**禁止**依赖任何 eaiselp-* 模块 |
| R2 | capability / adapter / data 只能依赖 common（+ 第三方），**禁止**相互依赖、**禁止**依赖 runtime / gateway / auth / observability / admin |
| R3 | runtime 可以依赖 common + capability + adapter + data，**禁止**被任何 library 模块反向依赖 |
| R4 | gateway / auth / observability / admin 可以依赖 common，**禁止**依赖 runtime（横向 service 不互相依赖，跨 service 调用走 Feign / 网关） |
| R5 | 全图无环。任何形式的循环依赖（A→B→A）是阻断级缺陷 |

> **检验方法**：用 `mvn dependency:tree` 或 IDEA 的"Analize Dependencies"查看依赖图；P3 违反属 Reviewer 阻断项。

---

## 5. P5 演进预留的工程化要求

当前是 library 的模块（capability/adapter/data），**必须**保留以下"未来外移"的边界，以便 M2/M3 真正需要拆服务时只需"加 Application + Feign"，不大改业务代码：

| 保留项 | 具体要求 |
|---|---|
| 包结构 | 每个模块根包独立（`com.eaiselp.capability` / `com.eaiselp.adapter` / `com.eaiselp.data`），不交叉 |
| Bean 命名 | 模块内的 `@Service` / `@Component` 用模块前缀命名（如 `CapabilityLoader`、`AdapterFactory`），避免与 runtime 同名 Bean 冲突 |
| Controller 边界 | 跨模块对外端点用 `/api/{module}/**` 前缀（如 `/api/capability/**`），便于未来按模块拆路由 |
| 跨模块调用契约 | runtime 调 capability 用接口注入（如 `AdapterFactory`），不要直接 new 实现类；未来切 Feign 时只换注入方式 |

**演进触发条件**（来自 ADR-001 §5.3，届时新写 ADR-002 替代本标准相关条款）：
- capability 的 markdown 热更新需要独立扩容
- adapter 需要支持多供应商并行/灰度
- data 的数据量/并发触发独立分库
- 团队按模块分拆（Conway's Law 触发）

---

## 6. L1-Reviewer 验收 checklist（强制，≥10 条）

验收任何涉及模块/POM/Application 改动的 PR 时，逐条核对。"阻断"项不通过即打回，"警告"项需 L1-SE 书面说明。

| # | 检查项 | 类别 | 通过判据 |
|---|---|---|---|
| C1 | 父 POM `pluginManagement/spring-boot-maven-plugin` 内**无** `<executions>` 段 | 阻断 | 父 POM 只剩 `<configuration>` |
| C2 | library 模块（common/capability/adapter/data）的 POM **无** `spring-boot-maven-plugin`（直接 plugins 段）| 阻断 | 4 个模块 POM 的 `<build>` 不含该 plugin |
| C3 | library 模块源码**无** `@SpringBootApplication` 注解的类 | 阻断 | grep 结果为空 |
| C4 | library 模块源码**无** `@EnableDiscoveryClient` / `@EnableEurekaClient` | 阻断 | grep 结果为空 |
| C5 | library 模块 POM **无** `spring-cloud-starter-alibaba-nacos-discovery` 依赖 | 阻断 | 4 个模块 POM 不含该依赖 |
| C6 | service 模块（runtime/gateway/auth/observability/admin）的 POM **显式**声明 `spring-boot-maven-plugin` + repackage `<executions>` | 阻断 | 5 个模块 POM 各自带 executions（不再依赖父 POM 触发） |
| C7 | service 模块源码**存在** `@SpringBootApplication` 入口类 | 阻断 | grep 结果非空 |
| C8 | 依赖方向无环：runtime → capability/adapter/data → common，**无**反向 | 阻断 | `mvn dependency:tree` 检验，R1–R5 全过 |
| C9 | library 模块的 Controller 类**保留** `@RestController` + `/api/{module}/**` 端点前缀（演进预留）| 警告 | 删除 Controller 须 L1-SE 书面说明 |
| C10 | runtime `scanBasePackages` **包含**所有被它接管的 library 模块根包 | 阻断 | runtime Application 的 scanBasePackages 含 capability/adapter/data |
| C11 | library 模块的包结构独立、Bean 命名带模块前缀、无与 host 同名 Bean 冲突 | 警告 | 翻库检查 |
| C12 | 新增模块时，L1-SE 技术方案**附模块定性表**（按 §1.3 格式），且判定证据齐全 | 阻断 | 无定性表的新模块 PR 直接打回 |
| C13 | 删除 library 模块 Application 类时，其上的横切注解（如 `@EnableScheduling`、`@EnableAsync`）已迁移到 host Application | 阻断 | 对照被删类的注解逐项核对迁移去向 |
| C14 | 文档一致性：改完 POM/Application 后，ADR-001 §5.1 模块定性表与本标准 §1.3 仍吻合 | 警告 | 有偏差须更新本标准并记 changelog |

> 自动化检验：C1–C5、C6、C7、C10、C13 可由 `质量门禁-模块边界.md` 的 PowerShell 脚本自动检查（QA 手动跑，M1 阶段未接 CI/CD）。C8/C9/C11/C12/C14 人工复核。

---

## 7. 变更日志

| 日期 | 版本 | 变更 | 作者 |
|---|---|---|---|
| 2026-07-21 | 1.0 | 初版，翻译自 ADR-001 P1–P5 | team-standards |

---

## 8. 反馈机制

- 本标准执行中发现不可落地或与现实矛盾 → L1 角色反馈 team-standards。
- 标准与 ADR 冲突且 ADR 需调整 → team-standards 上报 team-ea。
