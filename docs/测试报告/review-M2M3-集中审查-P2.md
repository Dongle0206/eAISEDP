# 代码评审报告 — M2+M3 集中补审查 P2（看板/配额/Artifact入库/全文检索/CI）

> 结论：**通过（附建议项）**
>
> 审查范围：4 个 commit
> - 1e7ab98 看板+配额（DashboardController 3 API + DerivationTaskService 配额校验）
> - 2f72688 Artifact 入库（DerivationPersistenceService 填充 content/frontmatter）
> - 1ca4f72 全文检索（ArtifactService.search 的 OR 优先级）
> - e172a4b CI/CD（.github/workflows/ci.yml 3 阶段）
>
> 门禁：`mvn clean package` BUILD SUCCESS（10 模块全过）+ `mvn test` BUILD SUCCESS（22 测试全过，0 失败 0 错误）。

## 磁盘事实核对（第一关，已通过）

| Commit | 报告/声称改动 | git diff 真实落地 | 一致性 |
|---|---|---|---|
| 1e7ab98 | DashboardController 3 API + QuotaExceededException + GlobalExceptionHandler 429 + DerivationService 月度量查询 + DerivationTaskService.checkQuotaBeforeSubmit | 5 文件 372 增 1 删，逐文件 diff 与声称完全对应 | 一致 |
| 2f72688 | Artifact.content 字段 + schema MEDIUMTEXT + DerivationPersistenceService 填充 content/frontmatter/docKey/contractKey | 4 文件 26 增 1 删，逐文件 diff 与声称完全对应 | 一致 |
| 1ca4f72 | ArtifactService.search（LIKE OR）+ SearchController（摘要投影） | 3 文件 277 增（含 deploy/dplexecute_08.bat），diff 与声称对应 | 一致 |
| e172a4b | ci.yml 3 阶段（build/security-scan/deploy-staging） | 1 文件 142 行新增，diff 与声称对应 | 一致 |

**无虚报、无夹带未提及改动。** 所有规范审查基于真实 diff + Read 上下文，独立于 Dev 报告。

## 缺陷清单

| 编号 | 严重度 | 文件:行/位置 | 问题 | 修复建议 |
|---|---|---|---|---|
| D1 | 🟡建议 | DerivationPersistenceService.java:136-146 `buildFrontmatter` | 手工字符串拼接 JSON（拼 `pa.getRole()` / `result.getModel()` 进双引号字段值），未做转义。schema.sql `t_artifact.frontmatter` 是 MySQL **JSON 严格类型列**（line 152），而 H2 测试用 CLOB 不校验合法性——测试通过但产线 INSERT 非法 JSON 会直接抛错丢失产物落库。注释自称"M1.2 不引 Jackson，M2 改 ObjectMapper"，但 M2 已过仍未改（项目已显式引入 Jackson，ObjectMapper 可用）。触发条件：管理员在 t_model_routing 配含 `"`/`\` 的模型名，或 agent 定义 role 含特殊字符。 | 改用 ObjectMapper 序列化：`new ObjectMapper().writeValueAsString(map)`；或至少对拼入字段做 JSON 字符串转义。偿还自注的技术债。 |
| D2 | 🟡建议 | DerivationPersistenceService.java:109 `a.setStage(result.getStatus())` | 语义错配：`result.getStatus()` 是**派生结果状态**（固定值 `"success"`，见 DerivationEngine.java:60），而 `t_artifact.stage` 列语义是**产物所属阶段**（requirements/design/test 等流程阶段，与 t_case.current_stage 同口径）。把 "success" 塞进 stage 列会污染看板/检索的阶段维度统计。 | stage 应取产物真实阶段（如从 role 推导：team-po→requirements，team-arch→design），或暂留 NULL 等真实阶段字段就位再填，勿用派生状态占位。 |
| D3 | 🟡建议 | ci.yml:73-78（安全扫描 secret scan） | secret 扫描只 grep 固定串 `3f3582bb`（历史泄漏的 GLM key），属于"防特定串再出现"而非通用密钥检测。git 历史检索显示该 key 已在 commit 5020820（仓库首个 commit，重新 init 恢复）中存在于源码——虽当前工作树 yml 已改用 `${GLM_API_KEY:}` 占位（已移除明文），但**git 历史中仍可被检出**，仅靠 grep 防新增无法消除已泄漏事实。dependency-check 只 grep `mvn dependency:tree` 的 WARNING 行，未接 OWASP Dependency-Check/Trivy/Snyk，CVE 检测实际未做。 | 1) 已泄漏 key 立即在 GLM 控制台轮换作废（运维动作，非代码）；2) secret 扫描换 TruffleSec/Gitlegs 做通用模式检测；3) dependency 阶段接 `org.owasp:dependency-check-maven` 或 Trivy 做 CVE 扫描。 |
| D4 | 🟡建议 | DerivationService.java:88-99 `countAndTokensByRoleSince` | 死代码：全代码库无任何调用方（DashboardController 只调无参版 `countAndTokensByRole`）。 | 删除该方法，或在看板"按月切换"功能上线时再补（YAGNI）。 |
| D5 | 🟢可选 | DerivationPersistenceService.java:105 `setDocKey(caseId+"-"+type+"-"+derivationId)` | docKey 设计为唯一标识，但 schema.sql 的 t_artifact 既无 doc_key UNIQUE 约束也无索引（line 151），后续按 docKey 查询会全表扫描且无法防重复入库。contractKey 同。 | 若 docKey 承诺唯一性，补 `UNIQUE KEY uk_tenant_dockey(tenant_id, doc_key)`；若仅查询辅助，补普通索引。 |
| D6 | 🟢可选 | ci.yml:65-69（quality gates step） | `powershell -File docs/架构文档/质量门禁-模块边界.ps1` 在 ubuntu runner 上跑，注释自承"PS on Linux runner 需要 pwsh，暂跳过"并 `continue-on-error: true`——质量门禁在 CI 实际未生效（永远绿）。文件本身存在（已验证），但执行不了。 | 装 pwsh（`apt-get install -y powershell`）或改写门禁脚本为跨平台（bash/python）。同样 build 阶段 Test 也 `continue-on-error: true`（line 64），意味着 CI 不强制测试通过——M2 阶段可接受，但应有明确的"转强制"时间点。 |
| D7 | 🟢可选 | DashboardController.java:121-128 / DerivationService.java:60-67 `sumTokensSince` | SQL 片段 `IFNULL(SUM(input_tokens),0)+IFNULL(SUM(output_tokens),0) AS total` 用原生列名拼接在 QueryWrapper.select() 里，绕过 Lambda 的列名安全。列名 `input_tokens`/`output_tokens`/`create_time`/`role` 是硬编码字符串，若后续实体改列名（@TableField）不会在此同步报错。 | 当前实体无 @TableField 别名，列名与字段驼峰一致，风险低；若未来加列名映射，需注意此处 SQL 字符串需手动同步。可接受，记为提示。 |

## 各项审查重点回答

### 重点1：看板 SQL 聚合 N+1？——**无 N+1，合格**
- `countCaseByStatus()`（DashboardController.java:141-160）：单条 `SELECT status, COUNT(*) AS cnt FROM t_case GROUP BY status`，预置 6 枚举补零，避免逐状态 N 条 count。正确。
- `countAndTokensByRole()`（DerivationService.java:71-79）：单条 `SELECT role, COUNT(*), SUM(input)+SUM(output) GROUP BY role`。正确。
- `overview()` 聚合：statusMap 复用 caseStats 的 groupBy 结果（不重复查库），派生总数/token 总数在内存对 roleStats 求和（不重复查库），仅 artifactTotal 和 checkpointPending 各 1 条 count——总 SQL 数固定 ~4 条，无 N+1。

### 重点2：配额 COUNT/SUM 多租户拦截器影响？——**拦截器覆盖正确，但无并发防护**
- t_quota / t_derivation 均不在 `EaiselpTenantHandler.IGNORE_TABLES`（已 Read 核对，IGNORE_TABLES 仅含 t_tenant/t_user/t_system_config/权限表/t_model_routing/t_governance_log 等），租户拦截器会自动注入 tenant_id 过滤。配额校验 `checkQuotaBeforeSubmit` 的 selectOne(COUNT/SUM) 都被拦截器限定在当前租户范围内。正确。
- 设计选择合理：用实时 COUNT/SUM(t_derivation) 做"强一致口径"而非读 t_quota.*_used（异步回写有延迟窗口），避免配额被绕过。
- **潜在风险（未列为缺陷因属设计权衡）**：check 与 createPending 的 INSERT 之间无锁/无原子性，高并发下多个请求可能同时通过 check 后各自 INSERT 导致超额（TOCTOU）。当前限流（派生 10/分/租户）+ 当月额度量级下实际冲突概率低，可接受；若额度收紧需补分布式锁或 INSERT...SELECT 原子判定。

### 重点3：搜索 LIKE SQL 注入？——**无注入，参数化绑定安全**
- `ArtifactService.search`（ArtifactService.java:37-49）用 `LambdaQueryWrapper.like(Artifact::getContent, kw).or().like(Artifact::getTitle, kw)`，MyBatis-Plus 的 like() 走 `?` 占位符参数化绑定，keyword 作为 PreparedStatement 参数传入，**不存在字符串拼接 SQL，无注入风险**。
- **OR 优先级安全**：用 `.and(w -> w.like(...).or().like(...))` 把 title/content 包成独立 OR 子组（生成 `WHERE tenant_id=? AND (content LIKE ? OR title LIKE ?)`），不会与租户拦截器注入的 tenant_id 条件错位（若不嵌套会变成 `WHERE tenant_id=? AND content LIKE ? OR title LIKE ?` → title 命中可跨租户）。**写法正确，是防 OR 优先级漏洞的正解。**
- 性能：自承技术债（LIKE '%kw%' 全表扫描），限流 60/分/用户兜底，M4 计划换全文索引——可接受。

### 重点4：CI/CD 安全扫描误报漏报？——**漏报偏多，见 D3/D6**
- 误报：低。grep 固定串 `3f3582bb` 几乎不会误报。
- 漏报：偏多。（a）secret 扫描只查一个特定历史串，不做通用密钥模式检测（D3）；（b）dependency-check 只 grep WARNING 不做 CVE（D3）；（c）质量门禁 powershell 在 ubuntu 跑不了 + continue-on-error（D6）；（d）Test 阶段 continue-on-error，CI 不强制测试通过（D6）。
- 已泄漏 key 仍在 git 历史（commit 5020820），grep 防新增无法清除——需轮换 key（D3 运维动作）。

## 总评审结论

**通过（附 4 建议 + 3 可选项）。**

四项改动均真实落地，无虚报无夹带；编译 + 测试门禁全过；看板无 N+1、配额多租户拦截正确、搜索无 SQL 注入且 OR 优先级写法正确——四个审查重点的核心安全性全部合格。无阻断级缺陷。

D1（JSON 手工拼接 + MySQL JSON 严格列）和 D2（stage 语义错配）建议尽快修：前者在管理员配异常模型名时会导致产线产物落库失败（虽有 try-catch 兜底不阻断主流程，但会静默丢数据）；后者污染阶段维度统计。D3（CI 安全扫描能力）和 D4（死代码）属改进项。D5/D6/D7 可选。

## 本次经验沉淀

1. **JSON 严格类型列 + 手工字符串拼接 = 隐形产线炸弹**：MySQL `JSON` 列会校验合法性，而 H2 测试常用 `CLOB` 不校验——导致"测试绿、产线炸"的典型场景。审查 `frontmatter`/`metadata` 类字段时，必须同时核对 schema.sql（产线类型）与 schema-h2.sql（测试类型）是否一致，且任何手工拼 JSON 的代码（含 `+`/StringBuilder append 字段值）都应视为缺陷，哪怕当前数据看着安全。项目已有 ObjectMapper 时，"不引 Jackson 用拼接"的自注技术债必须追问是否已偿还。

2. **MyBatis-Plus 租户拦截器 + 自定义 OR 条件的优先级陷阱**：`LambdaQueryWrapper.like(A).or().like(B)` 若不包在 `.and(w -> w... )` 里，生成的 `WHERE tenant_id=? AND A OR B` 会因 SQL AND 优先级高于 OR，导致 B 条件绕过租户隔离（跨租户泄漏）。正确写法是 `.and(w -> w.like(A).or().like(B))` 生成 `WHERE tenant_id=? AND (A OR B)`。审查多租户 + OR/IN 组合查询时，这是必查点。

3. **CI 安全扫描的"形似神不似"反模式**：`grep 固定串` / `grep WARNING` / `powershell on ubuntu + continue-on-error` 三件套会让 CI 永远绿但实际不防护。审查 CI 时不能只看"有没有这步"，要看：(a) 工具是否真的在该平台可执行；(b) continue-on-error 是否让失败被吞；(c) 扫描规则是通用模式还是特定串；(d) 已泄漏 secret 在 git 历史中的残留是否配套轮换。git log -S 追踪历史泄漏是核对 CI 必要性的有效手段。
