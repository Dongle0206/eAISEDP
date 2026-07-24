# CLAUDE.md — eAISELP Platform

> 本文件是 L1 各角色（PO/BA/SE/Dev/Reviewer/QA/Ops/PM）运行时的项目上下文注入。
> 由 **team-standards（L2 方法论标准负责人）** 统一治理，其他角色**遵循**，不擅自修改。
> 变更经 team-standards 审核后入库，重大变更记录到本文件末尾「变更日志」。

---

## 1. 项目定位（一句话）

企业级多 Agent 协同运作体系（AISOps）的后端承载平台。多 Maven 模块项目，M1 阶段为**模块化单体**（单进程 host + 若干 library），M2+ 按流量/团队规模演进为微服务。

> 架构权威文档：`docs/架构文档/ADR-001-模块边界与repackage策略.md`（不可变历史记录）。
> 本文件与 ADR 冲突时，以 ADR 为准；ADR 被替代时本文件同步更新。

---

## 2. 技术栈

| 维度 | 选型 | 版本 |
|---|---|---|
| 语言 | Java | 17 |
| 构建 | Maven 多模块 | parent 1.0.0-SNAPSHOT |
| 框架 | Spring Boot | 3.2.5 |
| 云 | Spring Cloud + Spring Cloud Alibaba | 2023.0.1 / 2023.0.1.0 |
| 注册/配置 | Nacos | （随 SCA） |
| ORM | MyBatis-Plus | 3.5.5 |
| DB | MySQL | 8.0.33 |
| 工具 | Hutool / Lombok / Commonmark | 5.8.27 / 1.18.46 / 0.22.0 |
| LLM 编排 | LangChain4j | 0.31.0 |
| 状态机 | Spring StateMachine | 4.0.0 |

---

## 3. 模块清单与定性（来自 ADR-001 §5.1，强制）

| 模块 | 定性 | 启动？ | repackage？ | Application 类？ |
|---|---|---|---|---|
| eaiselp-common | library | 否 | 否 | 无 |
| eaiselp-capability | library | 否 | 否 | 无（须删除现有） |
| eaiselp-adapter | library | 否 | 否 | 无（须删除现有） |
| eaiselp-data | library | 否 | 否 | 无（须删除现有） |
| eaiselp-runtime | service（host）| 是 | 是 | 保留 |
| eaiselp-gateway | service | 是 | 是 | 保留 |
| eaiselp-auth | service | 是 | 是 | 保留 |
| eaiselp-observability | service | 是 | 是 | 保留 |
| eaiselp-admin | service | 是 | 是 | 保留 |

**M1 唯一需要启动的进程是 eaiselp-runtime**。capability/adapter/data 的端点（`/api/capability/**`、`/api/adapter/**`）由 runtime 通过 `scanBasePackages` 接管，合并到 runtime 进程暴露。

---

## 4. 工程标准（核心约束，L1 必须遵循）

> 详细规范见 `docs/架构文档/工程标准-001-Maven模块规范.md`。以下为高频关键约束摘要。

### 4.1 模块边界（ADR-001 P1–P5，阻断级）

- **P1**：一个模块在同一次构建产物里要么是 library 要么是 service，不可兼得。判定 service 的 4 个充分条件（任一命中）：有 `@SpringBootApplication` / 注册服务发现 / 配了 repackage / 独立进程启动。
- **P2**：library 模块**禁止**配 `spring-boot-maven-plugin` 的 repackage（repackage 把 class 重定位到 BOOT-INF/classes/，破坏 jar 作为库的可用性——M1.0 编译失败的根因）。
- **P3**：依赖单向无环。`runtime → capability/adapter/data → common`。library 模块禁止反向依赖 service 模块。
- **P4**：repackage execution **禁止**进父 POM `pluginManagement`（pluginManagement 只放无副作用的 configuration，带 executions 副作用的必须下沉到具体 service 模块 POM）。
- **P5**：library 模块保留包结构/Bean 命名/Controller 边界，未来外移为服务只需"加 Application + Feign"。

### 4.2 POM 编写规范

- **父 POM `pluginManagement/spring-boot-maven-plugin`**：只放 `<configuration>`，**禁止** `<executions>` 段。
- **library 模块 POM**：`<build>` 段**不得**出现 `spring-boot-maven-plugin`；不依赖 `spring-cloud-starter-alibaba-nacos-discovery`。
- **service 模块 POM**：`<build><plugins>` 段**必须**显式声明 `spring-boot-maven-plugin` 并带 repackage `<executions>`（P4 落地后不能依赖父 POM 自动触发）。

### 4.3 Application 类规范

- **library 模块禁止**有 `@SpringBootApplication` / `@EnableDiscoveryClient` / `@EnableEurekaClient` / main 方法。
- **service 模块必须有** `@SpringBootApplication` 入口类。
- 删除 library 模块的 Application 类时，其上的横切注解（如 `@EnableScheduling`、`@EnableAsync`）**必须迁移到 host（runtime）Application 类**。

### 4.4 跨模块调用规范（M1 模块化单体）

- runtime 调 capability/adapter/data 用 **bean 注入**（同进程），不用 Feign。
- runtime 的 `@SpringBootApplication(scanBasePackages=...)` **必须**包含所有被它接管的 library 模块根包（当前含 capability/adapter）。
- library 模块的 Controller 类**保留** `@RestController` + `/api/{module}/**` 前缀（演进预留）。

---

## 5. 质量门禁（PR 合并前必跑）

> 详细规则与脚本见 `docs/架构文档/质量门禁-模块边界.md` 和 `.ps1`。

M1 阶段未接 CI/CD，QA 手动跑 PowerShell 脚本：

```powershell
# 在项目根执行
powershell -ExecutionPolicy Bypass -File .\docs\架构文档\质量门禁-模块边界.ps1
```

6 条阻断级规则：

| 规则 | 检查 | 类别 |
|---|---|---|
| G1 | 父 POM `pluginManagement/spring-boot-maven-plugin` 无 `<executions>` | 阻断 |
| G2 | library 模块 POM 无 `spring-boot-maven-plugin` | 阻断 |
| G3 | library 模块无 `@SpringBootApplication` 类 | 阻断 |
| G4 | library 模块无 `@EnableDiscoveryClient` / `@EnableEurekaClient` | 阻断 |
| G5 | library 模块 POM 无 `nacos-discovery` 依赖 | 阻断 |
| G6 | service 模块 POM 显式含 repackage `<executions>` | 阻断 |

任一 FAIL → PR 打回，修复后重跑全部 G1–G6（不可只跑失败项）。

---

## 6. 文档体系索引

| 文档 | 路径 | 作用 |
|---|---|---|
| 架构决策（不可变）| `docs/架构文档/ADR-001-*.md` | EA 裁决，本 CLAUDE.md 与工程标准的权威来源 |
| 工程标准（模块）| `docs/架构文档/工程标准-001-Maven模块规范.md` | 模块分类/POM 模板/Application 规则/依赖图/Reviewer checklist |
| 工程标准（执行）| `docs/架构文档/工程标准-002-执行规范与文档体系.md` | 产出物落盘 / 文档目录归属 / Windows 工程 / Dev 报告反幻觉 |
| 工程标准（平台承载）| `docs/架构文档/工程标准-003-平台承载规范.md` | 零角色硬编码 / 模型路由配置化 / 多租户 / 接口版本 / 过程资产 / 分场景激活（翻译 EA 蓝图 P6-P14）|
| 质量门禁（模块）| `docs/架构文档/质量门禁-模块边界.md` + `.ps1` | G1–G6 模块边界门禁 |
| 质量门禁（产出物）| `docs/架构文档/质量门禁-产出物落盘.md` + `.ps1` | G7–G10 产出物落盘与执行规范门禁 |
| 质量门禁（平台承载）| `docs/架构文档/质量门禁-平台承载规范.md` + `.ps1` | G11–G15 平台承载规范门禁 |
| 设计 | `DESIGN.md` | 总体设计（注：第 86-101 行"8+1 微服务"是 M2+ 目标，M1 为模块化单体，见 ADR-001 §6 负向） |

---

## 7. 变更日志

| 日期 | 版本 | 变更 | 作者 |
|---|---|---|---|
| 2026-07-21 | 1.0 | 初版。基于 ADR-001 落地模块边界工程标准与门禁。 | team-standards |
| 2026-07-22 | 1.1 | 追加 §8（ES-002 摘要）+ §6 文档索引补 ES-002 / 产出物门禁。基于 M1.1 Dogfooding 验证报告 4 个执行瑕疵（IMP-004/005/006/007）。 | team-standards |
| 2026-07-23 | 1.2 | 追加 §9（ES-003 摘要）+ §6 文档索引补 ES-003 / 平台承载门禁。翻译 EA 蓝图 P6-P14 为平台承载工程标准 + G11-G15 门禁（M2 SP-2/SP-3/SP-4/SP-6 强制约束来源）。 | team-standards |

---

## 8. 工程标准 ES-002 摘要（执行规范与文档体系，2026-07-22 追加）

> 完整规范见 `docs/架构文档/工程标准-002-执行规范与文档体系.md`。以下为 L1 各角色必须记住的硬约束摘要。ES-002 与 ES-001（§4）互补：ES-001 管"业务/构建层规范"，ES-002 管"执行层规范"。两者都是阻断级。

### 8.1 产出物落盘（修复 IMP-004，对应门禁 G7）

**所有 L1 关键角色的产出物必须 Write 落盘为物理文件，不能只输出在 Agent 回复里。** 落盘后必须 `Test-Path` 自检 + 向编排者报告绝对路径。

| 角色 | 必落盘路径（相对项目根） |
|---|---|
| Reviewer | `docs/测试报告/review-<里程碑>-<主题>.md` |
| QA | `docs/测试报告/qa-<里程碑>-<主题>.md` |
| SE | `docs/设计规划文档/<里程碑>-<主题>-技术方案.md` |
| Dev | `docs/过程跟踪文档/case-<id>/dev-report-<里程碑>-<主题>.md` |
| Security / Performance | `docs/测试报告/security-*.md` / `perf-*.md` |
| Ops | `docs/过程跟踪文档/case-<id>/ops-checklist-*.md` |
| PO / BA | `docs/需求文档/<case-id>-*.md` 或 case 目录内 |

编排者派生 Reviewer/QA/SE 时，prompt 里**必须**带这一句："你的产出物必须 Write 落盘到 `<具体路径>`，不能只输出在回复里。完成后 `Test-Path` 自检并向我报告绝对路径。"

### 8.2 文档目录归属（修复 IMP-005）

| 类型 | 归属 | 位置 |
|---|---|---|
| 工程级共享（跨 case 持续维护） | 共享区顶层 | `docs/过程跟踪文档/changelog.md` / `kanban.md` / `risks.md` / `<里程碑>-验证报告.md`；`docs/架构文档/ADR-*` / `工程标准-*` / `质量门禁-*` |
| case 级归档（单 case 产物） | case 目录或标准目录 | `docs/设计规划文档/*-技术方案.md`；`docs/测试报告/review-*.md` / `qa-*.md`；`docs/过程跟踪文档/case-<YYYYMMDD>-<标题>/dev-report-*` / `ops-checklist-*` |

**禁止预创建空占位目录**（git 不跟踪空目录，预创建只产生噪音）。case 目录命名：`case-<YYYYMMDD>-<简短标题>`。

### 8.3 Windows 工程规范（修复 IMP-006，对应门禁 G8/G9）

1. **commit message**：含中文或多行时必须用 `git commit -F <utf8-no-bom-file>`，不要在 cmd 直接传 `-m "中文"`。
2. **PowerShell 重定向**：丢弃输出用 `| Out-Null` 或 `2>$null`（紧贴无空格）；**禁止** `> $null`（有空格）或 cmd 上下文下用 `$null`——会生成字面 `$null` 文件。
3. **CRLF**：项目根 `.gitattributes` 推荐 `* text=auto eol=lf`（`.ps1/.cmd/.bat` 保 `eol=crlf`，二进制 `*.png/*.jar/*.pdf` 标 `binary`）。落地由 L1-Dev 执行，team-standards 不越界。
4. **中文路径**：优先 ASCII（新增目录）；PowerShell 用 `Get-ChildItem`，不要用 cmd `findstr`；核对未跟踪文件用 `git ls-files --others --exclude-standard`。
5. **脚本编码**：所有 `.ps1`/`.sh`/`.cmd` 一律**纯 ASCII**（含注释）；若必须含中文，文件须 UTF-8 with BOM（PS 5.1 检测到 BOM 才用 UTF-8 解码）。

### 8.4 Dev 报告规范（修复 IMP-007，对应门禁 G10）

- Dev 报告每条改动描述**必须**以"对照 HEAD"为基准，**禁止**以"目标态"为基准。
- 正确：`pom.xml:122-134 — 对照 HEAD，新增 <configuration>...</configuration>`
- 错误：`删 executions`（如 HEAD 本无 executions，diff 实为新增 configuration）
- **反幻觉硬约束**：Dev 报告产出前必须跑 `git diff --stat` + `git diff <file>` 核对自己写的改动方向；未在 diff 里出现的改动禁止写入报告。
- 模板见 ES-002 §4.4。

### 8.5 配套门禁 G7–G10

跑法（在项目根）：
```powershell
powershell -ExecutionPolicy Bypass -File .\docs\架构文档\质量门禁-产出物落盘.ps1
```

| 规则 | 检查 | 类别 |
|---|---|---|
| G7 | Reviewer / QA / SE 产出物物理文件存在（> 100 字节） | 阻断 |
| G8 | 工作区无 `$null` 字面文件 | 阻断 |
| G9 | 最近 commit subject 无乱码 | 警告 |
| G10 | Dev 报告含改动描述时须引用 HEAD | 警告 |

任一阻断（G7/G8）FAIL → PR 打回，修复后重跑全部 G7–G10。

---

## 9. 工程标准 ES-003 摘要（平台承载规范，2026-07-23 追加）

> 完整规范见 `docs/架构文档/工程标准-003-平台承载规范.md`。以下为 L1 各角色必须记住的硬约束摘要。ES-003 翻译 EA 蓝图 §7 P6-P14，与 ES-001（构建层）/ ES-002（执行层）互补，三者都是强制级。配套门禁 G11-G15（`质量门禁-平台承载规范.ps1`）。

### 9.1 平台零角色硬编码（P6，对应 SP-4，门禁 G11）

**eaiselp-*/src/main/java 不得出现 team-* 角色名 / skill 名 / command 名 / 流程阶段名的字面量硬编码。** 所有这类映射必须配置化（yml/表）或从体系 markdown 加载。

- 当前违反点：`DerivationEngine.java:88-97` `guessType()` switch 硬编码 11 个角色名 → SP-4 重构为 `eaiselp.artifact.type-mapping` yml 配置（@ConfigurationProperties 注入 Map）。
- 例外：agents-config/ 下体系 markdown 可含角色名；*.yml 配置可含角色名；代码注释（// 和 Javadoc 续行 `*`）不算违反。
- G11 当前 WARN（SP-4 完成前），SP-4 完成后升 BLOCKER。

### 9.2 模型路由配置化（P8，对应 SP-6，门禁 G12）

**模型档位（opus/sonnet/haiku）→ 具体模型名映射不得内联在 Java 代码常量。** 必须迁移到 `t_model_routing` 配置表（SP-6 新增，全局配置表无 tenant_id）或 application.yml。

- 当前违反点：`GlmLlmAdapter.java:44-48` `MODEL_MAPPING` 内联 → SP-6 迁移到 t_model_routing 表，ModelRoutingService 放 adapter 模块内部（避免 adapter→data 反向依赖，符合 P3）。
- 模型换代正确流程：UPDATE t_model_routing 一行 → 刷新缓存 → 平台代码零改动（不再改 GlmLlmAdapter 重新编译）。
- G12 当前 WARN（SP-6 完成前），SP-6 完成后升 BLOCKER。

### 9.3 多租户隔离贯穿（P11，对应 SP-2 / SP-7，门禁 G13）

1. 所有业务表带 tenant_id（M1 8 表均有；M2 新增表带 tenant_id；t_model_routing 全局配置表豁免）。
2. 所有业务 SQL 自动注入 tenant_id 过滤（MyBatis-Plus 拦截器 EaiselpTenantHandler 已落地）。
3. **禁止 `@InterceptorIgnore(tenantLine="true")` 绕过拦截器**——确需绕过必须 EA 审批 ADR，并在代码注释标注 ADR 编号。
4. 新增 Controller 从 `TenantContext` 取 tenant_id，不从请求参数取（防客户端伪造）。
5. SP-2 落地后：gateway 从 JWT 解析 tenant_id 注入 `X-Tenant-Id` header；runtime TenantContextFilter 读 header 填 ThreadLocal（不重复鉴权）。
- G13 当前 BLOCKER（M1 实测 0 命中 @InterceptorIgnore，PASS）。

### 9.4 接口版本化（P13，对应 SP-2 / SP-3，门禁 G14）

1. **新增 REST API 路径以 `/api/v1/` 开头**——破坏性变更通过升版本（/api/v2/），不改 v1 路径。
2. M1 已有 API（/api/runtime /api/capability /api/adapter 无 v1）属遗留，SP-2/SP-3 渐进迁移，迁移期双路径并存。
3. 对外 API 必须文档化（OpenAPI/Swagger，SP-2/SP-3 落地 springdoc-openapi）。
- G14 当前 WARN（M2 渐进迁移期，M1 遗留 Controller 报 WARN 不阻断）。
- 版本兼容规则：新增字段/端点 = 不升版本；删除字段/改类型/改语义 = 升 v2。

### 9.5 过程资产入平台（P10，对应 SP-4，门禁 G15）

**每个 case 的关键产出（PRD/方案/评审/测试报告/代码）必须结构化入 t_artifact，不只入 git。**

t_artifact 字段填充规范（SP-4 落地后强制）：
| 字段 | 填充要求 |
|---|---|
| `type` | 必填，从 `eaiselp.artifact.type-mapping` 配置映射（不再 guess）|
| `frontmatter` | 必填（SP-4 后），结构化 JSON `{version, review_status, approver, tags}` |
| `doc_key` | M2 填平台内 doc_id；M3 填企业系统文档 ID |
| `contract_key` | 可选，data-contract 标识（Steward 协同定义后强制）|
| `derivation_id` | 必填，关联派生记录（追溯来源）|

- 当前状态：t_artifact 三字段 frontmatter/doc_key/contract_key 全 NULL（M1 临时方案），SP-4 强化。
- G15 当前 WARN（SP-4 完成前），SP-4 完成后升 BLOCKER。

### 9.6 分场景层级激活（P14 / ADR-003，对应 SP-5，无独立门禁）

CapabilityLoader 按 `t_tenant.edition` 过滤加载 agent/skill：
- enterprise → L3+L2+L1（全部 22 agent）
- pro → L2+L1（约 18）
- starter（SP-5 新增档位）→ L1（14 角色）

AgentDefinition 增加 `layer` 字段（L1/L2/L3），从 markdown frontmatter `layer:` 解析。22 个 agent markdown SP-5 批量补 layer（standards 协同定义规范）。验收标准见 M2 项目群计划 §SP-5。

### 9.7 唯一调度入口 + 落库兜底（P7 / P12，已落地记录性）

- **P7**：所有派生必经 DerivationEngine，不得绕过直接调 LLM/Git/DocStore。新代码调 LLM 必须经 `AdapterFactory.getLlmAdapter()`，不得直接 new 具体适配器。
- **P12**：落库失败只 log 不重抛（`DerivationEngine.java:61-66` try-catch Throwable）。该 try-catch 不得删除（除非有 EA 审批 ADR 改为重抛）。

### 9.8 配套门禁 G11-G15

跑法（在项目根）：
```powershell
# 默认 M1 阶段（G11/G12/G15 为 WARN）
powershell -ExecutionPolicy Bypass -File .\docs\架构文档\质量门禁-平台承载规范.ps1

# SP-4 完成后（G11/G15 升 BLOCKER）
powershell -ExecutionPolicy Bypass -File .\docs\架构文档\质量门禁-平台承载规范.ps1 -Phase m2-sp4-done

# SP-6 完成后（G12 升 BLOCKER）
powershell -ExecutionPolicy Bypass -File .\docs\架构文档\质量门禁-平台承载规范.ps1 -Phase m2-sp6-done
```

| 规则 | 检查 | M1 阶段 | SP-4/SP-6 后 |
|---|---|---|---|
| G11 | eaiselp-*/src/main/java 无 team-* 角色名硬编码（排除注释/配置/test）| WARN | BLOCKER |
| G12 | eaiselp-*/src/main/java 无 glm-4-*/MODEL_MAPPING 内联（排除配置）| WARN | BLOCKER |
| G13 | eaiselp-*/src/main/java 无 @InterceptorIgnore（除非有 ADR 审批）| BLOCKER | BLOCKER |
| G14 | Controller @RequestMapping 以 /api/v1/ 开头（M1 遗留报 WARN）| WARN | WARN |
| G15 | t_artifact frontmatter/doc_key/contract_key 不全 NULL | WARN | BLOCKER |

阶段切换（WARN→BLOCKER）由 team-standards 改 `-Phase` 默认值执行，切换记录到 ES-003 §11 变更日志。WARN 不改变 exit code（仍 0），BLOCKER FAIL → exit 1 → PR 打回。

### 9.9 M2 子项目与 ES-003 章节对应（L1 编排者约束索引）

| 子项目 | 解的原则 | 遵循章节 | 门禁 |
|---|---|---|---|
| SP-1 Web 工作台 | P7/P11/P13 | §9.3/§9.4/§9.7 | G13/G14 |
| SP-2 auth+gateway | P11/P13 | §9.3/§9.4 | G13/G14 |
| SP-3 状态机+检查点 | P7/P12 | §9.7 | — |
| **SP-4 过程资产结构化** | **P6/P10** | **§9.1/§9.5** | **G11/G15** |
| SP-5 体系按档位激活 | P14/ADR-003 | §9.6 | — |
| **SP-6 模型路由配置化** | **P8** | **§9.2** | **G12** |
| SP-7 配额强校验 | P11 | §9.3 | G13 |
