# 代码评审报告 — DerivationEngine 派生结果持久化（case-20260722）

> 结论：**通过**

> 评审员独立验证日期：2026-07-22
> 评审方法：磁盘 diff 核对 + 独立 grep + 独立构建/单测/门禁复跑，Dev 报告仅作线索，所有结论以磁盘事实为准。

---

## 〇、磁盘事实核对（前置门禁，最高优先级）

### Dev 报告改动点逐条比对

| Dev 报告条目 | 磁盘事实 | 结论 |
|---|---|---|
| `eaiselp-data/application.yml` 删除（IMP-003） | `git status` 显示 `D`，`Test-Path=False`，`git diff` 删除 25 行 | ✅ 真实落地 |
| `eaiselp-runtime/pom.xml` 引 data + H2 + surefire argLine | `git diff` 显示 +16 行，含 data 依赖、H2 test scope、surefire argLine 显式声明 + 3 行 JDK 26 注释 | ✅ 真实落地 |
| `EaiselpRuntimeApplication.java` 加 @MapperScan + scanBasePackages 加 data | `git diff` 显示 @MapperScan("com.eaiselp.data.mapper")、scanBasePackages 5 项含 "com.eaiselp.data" | ✅ 真实落地 |
| `DerivationEngine.java` 加落库逻辑 | `git diff` 显示构造器加第 3 个参数 persistenceService、derive() 第 6.1 步 try-catch Throwable 调 persist | ✅ 真实落地 |
| `runtime/application.yml` 加 datasource + mybatis-plus | `git diff` 显示 +13 行，含 datasource 三键、mybatis-plus configuration + global-config | ✅ 真实落地 |
| data 4 个 Service 新文件 | `git ls-files --others` 列出 DerivationService/ArtifactService + 2 个 Impl，`Test-Path=True` ×4 | ✅ 真实落地 |
| `DerivationPersistenceService.java` 独立 Bean | `Test-Path=True`，@Service + @Transactional(rollbackFor=Exception.class) 齐备 | ✅ 真实落地 |
| 4 个测试文件 | `Test-Path=True` ×4（DerivationEngineTest / DerivationEnginePersistenceFailureTest / application-test.yml / schema-h2.sql） | ✅ 真实落地 |

**核对结论：12 文件全部真实落地，无虚报、无夹带。** 报告与磁盘 diff 100% 一致。

### 未跟踪额外文件（不在本 case 范围，仅记录）
`deploy.zip` / `deploy/` / `docker-compose.prod.yml` / 技术方案文档 / changelog.md 改动 —— 属其他 case 产物或文档，不在 DerivationEngine 持久化功能审查范围。

---

## 一、反幻觉 5 条独立验证（不采信 Dev 报告，全部自跑）

| # | Dev 声明 | 独立验证手段 | 结果 |
|---|---|---|---|
| 1 | mvn clean package BUILD SUCCESS | 本评审员自跑 `mvn clean package -pl eaiselp-data,eaiselp-runtime -am -DskipTests` | ✅ `[INFO] BUILD SUCCESS`（Reactor Summary 输出亲见） |
| 2 | 4 单测全过 | 本评审员自跑 `mvn test -pl eaiselp-runtime -am` | ✅ `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS` |
| 3 | 门禁 6/6 PASS | 本评审员自跑 `质量门禁-模块边界.ps1` | ✅ `PASS: 6/6    FAIL: 0`（G1-G6 全 PASS） |
| 4 | TC-3 真验证落库失败兜底（日志含 `[Derive] 落库失败但返回派生结果`） | 看 mvn test 实时日志 | ✅ 日志亲见 `ERROR ... [Derive] 落库失败但返回派生结果: role=team-po, case=case-test-3` + 堆栈定位 `DerivationEngine.java:62` |
| 5 | TC-3 用 @MockBean 与 TC-1 真实 Bean 物理隔离 | Read 两个测试类源码 | ✅ TC-3 第 41 行 `@MockBean DerivationPersistenceService persistenceService`；TC-1 第 34-40 行 `@Autowired DerivationEngine` + `@MockBean` 仅 mock AdapterFactory/ContextAssembler/LlmAdapter，DerivationPersistenceService 留真实 Bean。两类的 @MockBean 集合不同 → Spring 上下文缓存自动分桶 |

**反幻觉结论：5/5 全部独立复现，无幻觉。**

---

## 二、7 维度代码质量审查

### 维度 1：字段映射正确性 ✅
- `DerivationPersistenceService.persist()` 字段映射严格对照 entity 与 SE §6.2/§6.3：
  - Derivation：caseId / role / model / inputTokens / outputTokens / status / experience / durationMs / finishedAt / producedArtifacts 全覆盖 entity 可填字段
  - Artifact：caseId / role / type / derivationId（关联）覆盖
  - 明确跳过 `content` / `output`（§3.5 走 M2 外部存储，注释在 DerivationPersistenceService javadoc 第 24 行）
  - `stage / modelTier / cost / retryCount / startedAt / errorMsg` 留 NULL（§3.6，DerivationResult 当前无对应字段），retry_count 列有 DEFAULT 0 兜底
- 关键验证：`a.setDerivationId(d.getId())` 在 `derivationService.save(d)` 之后取回填的雪花 id，关联正确（TC-1 第 99 行 `assertEquals(d.getId(), a.getDerivationId())` 断言通过）

### 维度 2：@Transactional 自调用陷阱 ✅
- `DerivationEngine` 自身**不**带 @Transactional，落库走外部注入的 `DerivationPersistenceService`（独立 Bean，由 Spring AOP 代理）
- `DerivationPersistenceService.persist()` 标 `@Transactional(rollbackFor = Exception.class)`，由代理调用，事务真正生效
- 自调用陷阱规避正确（javadoc 第 14-20 行有设计说明，与代码实现一致）

### 维度 3：落库失败兜底 ✅
- `DerivationEngine.derive()` 第 61-66 行 `try { persist } catch (Throwable t) { log.error }`，**捕获 Throwable（含 Error）不重抛**
- 符合 SE §3.3/§3.4 决策："派生结果已构建属高价值产物，不能让 DB 抖动拖垮主流程"
- TC-3 真实验证：mock persist 抛 RuntimeException → derive() 仍返回 status=success（日志 + 测试断言双重印证）

### 维度 4：多租户隔离 ✅
- `t_derivation` / `t_artifact` 表均有 `tenant_id` 列（schema-h2.sql 第 6/32 行）
- `BaseEntity.tenantId` 标 `@TableField(fill = FieldFill.INSERT)`，`EaiselpMetaObjectHandler.insertFill()` 自动填 `TenantContext.get()`
- `TenantLineInnerInterceptor` 注册在 `MybatisPlusConfig`，对业务表 SQL 自动加 tenant_id 条件
- 测试在 `@BeforeEach` 显式 `TenantContext.set(1L)`，模拟真实租户上下文（更贴近生产），非依赖拦截器缺失

### 维度 5：测试质量 ✅
- **TC-1**（DerivationEngineTest:75）：真实 H2 + 真实 Service 全链路，断言 count=1 + 10+ 字段值 + produced_artifacts 摘要含 `"type":"prd"` + derivation_id 关联
- **TC-2**（DerivationEngineTest:104）：mock LlmAdapter.invoke 抛 RuntimeException，断言 `assertThrows` + count=0（LLM 失败在落库前抛出，不入库正确）
- **TC-3**（DerivationEnginePersistenceFailureTest:68）：@MockBean 替换 persistenceService 让 persist 抛异常，断言 result.status=success + caseId/role 正确 + artifacts 仍在内存
- **TC-4**（DerivationEngineTest:122）：多 artifact 场景（当前 extractArtifacts 产 1 个，javadoc 明确注释 M2 扩展后扩 N），断言 derivation_id 关联

**唯一测试注释偏差（非缺陷）**：TC-4 命名"多 artifact 场景"但实际只验证单 artifact，javadoc 第 115-119 行已诚实说明 M1.2 范围内不增强 extractArtifacts，仅验证"关联链路完整"作为多 artifact 最小子集。属于合理范围声明，不算缺陷。

### 维度 6：配置正确性 ✅
- `runtime/application.yml` datasource 三键（url/username/password）齐全，driver-class-name 正确
- mybatis-plus configuration + global-config 完整迁移自 data/application.yml（逐行比对 git show HEAD 内容一致）
- data/application.yml 真删（Test-Path=False），消除了 IMP-003（library 模块禁带 service 配置）

### 维度 7：门禁有效性 ✅
- `质量门禁-模块边界.ps1` 跑出 6/6 PASS：
  - G1 parent pluginManagement 无 executions ✓
  - G2 library 无 spring-boot-maven-plugin ✓
  - G3 library 无 @SpringBootApplication ✓（data 仍只引 Service 层，未引 Application 类）
  - G4 library 无服务发现注解 ✓
  - G5 library 不依赖 nacos-discovery ✓
  - G6 service 显式 repackage ✓

---

## 三、架构原则符合性（ADR-001）

| 原则 | 要求 | 验证 | 结论 |
|---|---|---|---|
| **P1** 库与服务二选一 | data 是 library（无 Application 类、无 repackage、无服务发现）；runtime 是 service | `mvn dependency:tree -pl eaiselp-data` 输出仅 common；G2/G3/G4/G5 全 PASS；runtime 仍带 repackage | ✅ |
| **P3** 依赖单向 | runtime → data → common，无环 | runtime 依赖树：common/capability/adapter/data；data 依赖树：仅 common；无环 | ✅ |
| **P5** 演进预留 | data 加 Service 层不影响未来外移为独立服务 | Service 接口/实现标准分层（IService/ServiceImpl），未来外移仅需配 Application 类即可 | ✅ |

---

## 四、Dev 2 处偏离的合理性评估

### 偏离 1：TC-3 独立成测试类（DerivationEnginePersistenceFailureTest） — **合理**
- 依据：Spring Boot Test 的 @MockBean 是**上下文级 Bean 替换**。TC-1/TC-4 依赖真实 `DerivationPersistenceService` 落 H2，TC-3 需要 @MockBean 替换它抛异常。两者 @MockBean 集合不同，Spring ContextCache 会自动分桶成两个独立 ApplicationContext，物理上无法共存于同一测试类。
- 代码佐证：TC-3 javadoc 第 30-32 行有明确说明；TC-3 第 41 行 `@MockBean DerivationPersistenceService`，TC-1 第 34-40 行无此注解。
- 结论：偏离有充分技术依据，且文档化清晰，**接受**。

### 偏离 2：runtime pom 加 surefire argLine — **合理**
- 依据：JDK 26 + Mockito/ByteBuddy。ByteBuddy 官方支持到 Java 22，JDK 26 需 `-Dnet.bytebuddy.experimental=true` 才能生成 mock 字节码，否则 @MockBean 全失败。
- 代码佐证：pom.xml 第 42-46 行显式 `<argLine>-Dnet.bytebuddy.experimental=true</argLine>` + 3 行注释说明原因（"JDK 26 + Mockito/ByteBuddy 兼容"）。
- 实测验证：本次评审员用 JDK 26.0.1 跑 mvn test，4 单测全过 → argLine 真生效。
- 结论：偏离有充分技术依据 + 注释解释 + 实测验证，**接受**。

---

## 五、缺陷清单

| 编号 | 严重度 | 文件:行 | 问题 | 修复建议 |
|---|---|---|---|---|
| D1 | 🟢可选 | `runtime/src/main/resources/application.yml:20` 与 `data/src/main/resources/db/schema.sql` | `logic-delete-field: deleted` 与 schema 实际列名 `is_deleted` 看似不一致。**实际不构成缺陷**：`BaseEntity.deleted` 字段用 `@TableField(value = "is_deleted")` 显式映射列名，字段级注解优先于全局配置，且本配置是从被删的 data/application.yml 原样迁移（git show HEAD 第 17 行同款），属历史既有。 | 可选：未来某次清理时把全局配置改为 `logic-delete-field: is_deleted` 与 schema 对齐，或在全局配置上加注释说明"以字段注解为准"。本 case 不强制修。 |
| D2 | 🟢可选 | `DerivationPersistenceService.java:90-100` | `summarizeArtifacts` 手工字符串拼接 JSON，未引 Jackson。type/role 虽是系统内部枚举值（非用户输入）无注入风险，但若未来字段含特殊字符（引号/反斜杠）会破坏 JSON。javadoc 已注释"M2 改 ObjectMapper"。 | 可选：保持现状即可（M2 已规划改造），或在 type/role 含特殊字符时做 escape。本 case 不强制修。 |

**阻断缺陷：0；建议缺陷：0；可选缺陷：2（均不影响本 case 通过，属历史遗留或 M2 规划内改进）。**

---

## 六、总评审结论

## **通过**

### 依据汇总
1. **磁盘事实核对**：12 文件全部真实落地，Dev 报告与 git diff 100% 一致，无虚报无夹带
2. **反幻觉 5 条**：BUILD SUCCESS / 4 单测全过 / 门禁 6/6 / TC-3 兜底日志 / TC-3 @MockBean 隔离 —— 全部独立复现
3. **7 维度质量**：字段映射 / @Transactional 规避自调用 / 落库兜底 / 多租户 / 测试质量 / 配置 / 门禁 —— 全 PASS
4. **架构原则**：P1 库服务二选一 / P3 依赖单向无环 / P5 演进预留 —— 全符合
5. **Dev 2 处偏离**：TC-3 独立成类 + surefire argLine —— 均有充分技术依据 + 代码注释 + 实测验证，合理接受
6. **阻断缺陷：0**

### 建议（非阻断，供后续迭代参考）
- D1 全局 logic-delete-field 命名与 schema 对齐（历史遗留，非本 case 引入）
- D2 M2 改用 ObjectMapper 生成产物摘要 JSON（Dev 已在 javadoc 规划）

---

## 七、本次经验沉淀

1. **MP 逻辑删除字段命名的"伪矛盾"识别法**：当看到 `logic-delete-field: deleted` 全局配置而 schema 列名是 `is_deleted` 时，不要直接判缺陷。必须先 Read entity 基类，看 `@TableField(value = "is_deleted")` 字段级注解是否显式映射列名（字段级注解优先级 > 全局配置）。本次 BaseEntity 第 31 行 `@TableField(value = "is_deleted", select = false)` 已显式映射，全局配置实际不生效，不构成缺陷。**经验：MP 配置审查必须 entity 注解 + 全局配置 + schema 三方对照，单看任一方都会误判。**

2. **@MockBean 不能与真实 Bean 共用 Spring Context 的识别**：Spring Boot Test 的 ContextCache 按 @MockBean 集合 + @SpringBootTest 配置指纹分桶。同一测试类内若需替换某 Bean，整个类的所有测试方法都吃同一份替换。因此"既要真实落库验证又要 mock 落库失败"必须拆两个测试类。**经验：看到测试拆分类时，先确认是否因 @MockBean 集合差异导致 ContextCache 分桶，而非 Dev 随意拆分。**

3. **落库兜底"捕获 Throwable 而非 Exception"的合理性**：DB 抖动可能抛 `DataAccessException`（Exception 子类），但连接池耗尽、JVM OOM 等可能抛 `Error` 子类。对"派生结果必须返回"的核心流程，catch Throwable 是激进但合理的兜底（运维通过 log 追踪，调用方无感）。**经验：try-catch 范围要看业务语义——若"无论如何必须返回结果"，catch Throwable 合理；若只想吞业务异常，catch Exception 即可。不要一看到 catch Throwable 就判过宽。**
