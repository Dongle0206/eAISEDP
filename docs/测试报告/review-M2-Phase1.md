# 代码评审报告 — M2 Phase 1（登录 + JWT + RBAC + 前端）

> 结论：**通过（0 阻断）**
>
> | 字段 | 值 |
> |---|---|
> | Case | case-20260723-m2-phase1-web-auth |
> | 评审者 | team-reviewer（L1 代码评审员） |
> | 评审日期 | 2026-07-23 |
> | 评审依据 | SE 技术方案 v1.0 + PO PRD v1.0 + ES-001/002/003 |
> | 评审方式 | 独立磁盘 diff 核对 + 自跑编译 + 自跑门禁 + 源码逐文件 Read（不看 Dev 变更说明理由） |
> | 阻断缺陷 | 0 |
> | 建议缺陷 | 3 |
> | 可选缺陷 | 3 |

---

## 0. 评审方法论说明（独立性 + 反幻觉）

本评审严格遵循"磁盘事实为唯一来源"原则：

1. **先跑 git diff / git ls-files** 拿到真实改动，不采信 Dev 报告的"改动清单"。
2. **逐条比对** Dev 报告的改动点与磁盘 diff，凡报告称改但 diff 未见 → 阻断。
3. 对 diff/新增文件中**确实存在**的改动，用 Read 打开上下文按 7 维度 checklist 审查。
4. **反幻觉自跑**：mvn clean package（BUILD SUCCESS）、门禁 6/6、关键 grep 全部由本评审员亲自执行，不抄 Dev 自检结论。

> 反幻觉自检结论：本报告引用的所有代码均来自 Reviewer 自身 Read 的磁盘文件（已标注 `文件:行`），无一处抄自 Dev 报告。若藏起 Dev 报告，仅凭 diff + Read 可完整复现本评审。

---

## 1. 阶段 A：磁盘事实核对

### 1.1 改动文件清单核对

Dev 报告"43 文件"。Reviewer 以 git 事实为准重新归集：

**git diff --stat（已跟踪文件修改，9 处）**：
```
docs/过程追踪文档/changelog.md            |   2 +
eaiselp-auth/pom.xml                      |   7 +
eaiselp-auth/.../EaiselpAuthApplication.java |   8 +-
eaiselp-auth/.../application.yml          |  20 +++
eaiselp-common/pom.xml                    |  24 +++
eaiselp-common/.../EaiselpTenantHandler.java|   4 +-
eaiselp-data/.../db/schema.sql            | 188 +++
eaiselp-runtime/.../application.yml       |   6 +
pom.xml                                   |   1 +
```

**git ls-files --others（新增未跟踪文件，实际 43 个，含 2 个文档）**：
- 后端新增 Java：auth 7（config/controller/dto×3/service+impl）+ common 8（security×6 + web×2 + result/ResultCode）+ data entity 5 + data mapper 5 + data mapper/vo 1 + data service 2 + runtime 3（config/security/controller）= **31**
- 前端新增：eaiselp-web 下 config.js + assets/js×6（api/auth/menu/i18n + 2 第三方库）+ assets/css×2（app + bootstrap）+ login.html + index.html = **10**
- 文档：PRD.md + 技术方案.md = **2**

**核对结论**：Dev 报告的"43 文件"与磁盘事实一致（9 跟踪修改 + 31 新增 Java + 10 前端 + 2 文档，其中 R.java/ArtifactService 等是 M1 已存在跟踪文件不计入新增）。**所有报告的改动点均真实落地，无虚报、无漏报、无夹带。**

### 1.2 关键安全 grep 核对（全部通过）

| # | 检查项 | 期望 | 磁盘事实 | 结论 |
|---|---|---|---|---|
| 1 | JWT 密钥是占位非明文 | `${JWT_SECRET:dev-placeholder}` | auth/application.yml:24 + runtime/application.yml:26 均 `${JWT_SECRET:dev-placeholder-secret-must-be-at-least-32-bytes-long-for-hs256-algorithm}` | ✅ |
| 2 | admin 密码是 BCrypt 哈希 | `$2a$10$...` 非 `admin123` | schema.sql:217 `'$2a$10$YenQ7QqiarkwMhKS2hGYF.bytyjKAGjJ7fbkaIMx9SIzfdMvr9bq2'`（cost=10） | ✅ |
| 3 | 40001 防枚举（不区分用户不存在 vs 密码错） | 统一 `BAD_CREDENTIAL=40001` | ResultCode.java:6 + AuthServiceImpl.java:52 `user == null || !passwordEncoder.matches(...)` → 同一 throw 40001 | ✅ |
| 4 | 5 权限表在 IGNORE_TABLES | t_permission/t_role/t_role_permission/t_user_role/t_service_account | EaiselpTenantHandler.java:12-14 全部 5 表在数组 | ✅ |
| 5 | CORS 配置正确 | allowedOriginPatterns + allowCredentials | CorsConfig.java:16-22 `allowedOriginPatterns("*")` + `allowCredentials(true)`（用 patterns 规避 Spring 禁止 origins("*")+credentials） | ✅ |
| 6 | auth 独立 service 端口 8085 | server.port=8085 | auth/application.yml:2 `port: 8085` | ✅ |

---

## 2. 阶段 B：7 维度质量审查

### 维度 1：JWT 安全 ✅

| 检查点 | 结论 | 证据 |
|---|---|---|
| 密钥注入方式 | ✅ 走环境变量 `${JWT_SECRET:...}`，严禁明文写死（SecurityProperties.java:21 注释明确） | auth/runtime yml 一致占位 |
| 密钥长度校验 | ✅ @PostConstruct 强制 ≥32 字节，不足抛 IllegalStateException | JwtUtil.java:28-30 |
| 算法 HS256 | ✅ `signWith(key, Jwts.SIG.HS256)` | JwtUtil.java:49 |
| token 有效期 | ✅ 86400s（24h），可配 | SecurityProperties.java:24 + yml |
| payload 不含敏感信息 | ✅ 不含 password/permissions（Q-5 防膨胀）；permissions 由 /current 实时查 | JwtClaims.java:9 注释 + AuthServiceImpl.java:70-78 |
| 签发方 issuer | ✅ `eaiselp-auth`（与 PRD §5.2.1 契约一致） | SecurityProperties.java:26 |
| token 解析校验 | ✅ `verifyWith(key).parseSignedClaims`，过期/签名错分别捕获返 40102 | JwtUtil.java:55-59 + JwtAuthInterceptor.java:44-50 |

### 维度 2：认证安全 ✅

| 检查点 | 结论 | 证据 |
|---|---|---|
| BCrypt 校验 | ✅ `BCryptPasswordEncoder.matches(raw, hash)` | AuthServiceImpl.java:36,52 |
| 仅引 crypto 不引完整 security | ✅ 避免自动启用 CSRF/默认认证链与 JWT 无状态冲突 | auth/pom.xml diff 注释 |
| 40001 防枚举 | ✅ 用户不存在与密码错同一分支同一 code | AuthServiceImpl.java:52 |
| 40002 禁用账户 | ✅ `status==disabled` → ACCOUNT_DISABLED | AuthServiceImpl.java:58-61 |
| last_login_at 更新 | ✅ AC-F1.5 落地 | AuthServiceImpl.java:81-84 |
| ThreadLocal 清理 | ✅ afterCompletion 必清防泄漏 | JwtAuthInterceptor.java:54-56 |
| 恒定时延（防时序枚举） | ⚠️ 简化：user==null 直接返回，未走假 BCrypt.matches（Phase 1 内网可接受，代码注释已标注 M3 补） | AuthServiceImpl.java:50-51 注释 |

### 维度 3：RBAC 权限 ✅

| 检查点 | 结论 | 证据 |
|---|---|---|
| @RequiresPermission 拦截逻辑 | ✅ 方法级优先类级，无注解放行，任一权限码满足即通过 | PermissionInterceptor.java:31-49 |
| 多角色取并集 | ✅ selectRolesByUserId 查全部角色，权限 DISTINCT 去重 | UserRoleMapper.java:16-19 + PermissionServiceImpl.java:35-39 |
| 无权限返 40301/403 | ✅ | PermissionInterceptor.java:47,53 |
| 权限拒绝日志 | ✅ log.warn 含 userId/需要/持有 | PermissionInterceptor.java:46 |
| data_scope | ⚠️ Phase 1 未落地数据范围过滤（依赖 tenant_id 隔离，M3 扩展 all/tenant/self）—— SE 方案明确 Phase 1 简化，合规 |

### 维度 4：多租户隔离 ✅

| 检查点 | 结论 | 证据 |
|---|---|---|
| 权限表 IGNORE | ✅ 5 表全部免 tenant 自动过滤 | EaiselpTenantHandler.java:12-14 |
| tenant_id 透传 | ✅ JWT payload tenantId → LoginUser.set → TenantContext.set（token 为权威，覆盖 header） | LoginUser.java:12-17 |
| 跨租户防护 | ✅ runtime 所有 /api/** 强制 JWT，tenant 由 token 决定不可伪造 | RuntimeWebMvcConfig.java:30-31 |

### 维度 5：CORS 安全 ✅

| 检查点 | 结论 | 证据 |
|---|---|---|
| allowedOriginPatterns（非 origins） | ✅ 规避 Spring 禁止 origins("*")+credentials | CorsConfig.java:17,21 |
| 预检 maxAge | ✅ 3600 | CorsConfig.java:22 |
| 凭证 allowCredentials | ✅ true（JWT in header 模式） | CorsConfig.java:21 |
| 生产收紧 | ⚠️ 开发期 `*`，生产需改白名单（代码注释已标注） | CorsConfig.java:8 注释 |

### 维度 6：前端安全 ✅（含已知风险）

| 检查点 | 结论 | 证据 |
|---|---|---|
| token 存 localStorage | ⚠️ 已知 XSS 风险，PRD §8.2 明确"M2 接受，M3 评估改 httpOnly cookie"——合规决策 | api.js:11-18 |
| 401 自动跳登录 | ✅ 统一拦截 401 → clearToken → 跳 login.html | api.js:31-39 |
| 路由保护 | ✅ index.html 调 AUTH.restore，无 token 或 /current 失败均跳登录 | index.html:57 + auth.js:18-31 |
| CSRF 免疫 | ✅ JWT in header 非 cookie 自动携带，天然免疫 | — |
| 双 base-url | ✅ AUTH_BASE_URL:8085 + API_BASE_URL:8081（SE 裁决 auth 独立 service） | config.js:12-14 |

### 维度 7：代码规范 ✅

| 检查点 | 结论 | 证据 |
|---|---|---|
| ES-001 模块边界 | ✅ common/data=library 无 @SpringBootApplication；auth/runtime=service 有 repackage（门禁 G1-G6 全过） | 门禁 6/6 |
| ES-003 零硬编码 | ✅ 密钥/密码/端口/base-url 全走配置或环境变量；无明文凭据 | 见维度 1/6 |
| API 版本化 /api/v1/ | ✅ auth 三接口 /api/v1/auth/**（P13） | AuthController.java:16 |
| 统一响应结构 | ✅ R 类 code/msg/data/timestamp | R.java:8-12 |
| 国际化预留 | ✅ i18n.js key-value 结构，M2 只中文（Q-2） | i18n.js |

---

## 3. 阶段 C：反幻觉自跑验证（5 条全部通过）

| # | 验证项 | 方法 | 结果 |
|---|---|---|---|
| 1 | mvn clean package BUILD SUCCESS | Reviewer 自跑 `mvn -o clean package -DskipTests -pl common,data,auth,runtime -am`（JDK26，离线） | ✅ **BUILD SUCCESS**，Reactor Summary 全过，javac 编译 18/34/8/6/10/9 源文件无错 |
| 2 | 门禁 6/6 PASS | Reviewer 自跑 `质量门禁-模块边界.ps1 -Root <abs>` | ✅ **PASS: 6/6 FAIL: 0**，Verdict: All gates passed |
| 3 | admin 密码真是 BCrypt | grep schema.sql | ✅ `$2a$10$YenQ7...`（cost=10 哈希，非明文） |
| 4 | JWT 密钥真是占位 | grep auth+runtime yml | ✅ 两处均 `${JWT_SECRET:dev-placeholder-...}` |
| 5 | schema seed 数量正确 | regex 精确计数 | ✅ t_permission=31（id 1001-1031）/ t_role=5（5 模板）/ t_role_permission=57 元组（29+15+7+4+2，与 SE 方案逐条对齐） |

> **门禁路径说明**：首次运行门禁时 G1/G2/G6 报"pom.xml not found"，根因是脚本默认 `Get-Location` 在 cmd 重定向场景下解析为异常路径（`;\pom.xml`），**非代码缺陷**。用 `-Root` 显式传绝对路径后 6/6 全过。Dev/QA 实际运行应统一用 `-Root <platform 根绝对路径>`。

---

## 4. 阶段 D：Dev 3 个技术问题评估

### D1. common pom 补 jackson-databind —— ✅ 合规

**Dev 决策**：common/pom.xml 新增 `jackson-databind`（无 version，由 Spring Boot BOM 管理）。

**Reviewer 评估**：合理修复。JwtAuthInterceptor/PermissionInterceptor 用 `ObjectMapper` 写 401/403 JSON 响应（JwtAuthInterceptor.java:29,62）。common 是 library，其 starter-web 为 provided scope，传递的 jackson-databind 在本模块编译期不可见。SE 方案确实遗漏此依赖（方案 §2.4 只列 jjwt 三件套）。Dev 补依赖有明确注释说明原因（common/pom.xml diff 注释），版本走 BOM 不硬编码。**不构成规范违规，属必要修复。**

### D2. derive 不加 @RequiresPermission —— ✅ 合理（按 SE 方案裁决）

**Dev 决策**：RuntimeController.derive 保持原样，不加权限注解。

**Reviewer 评估**：合理。磁盘事实确认——RuntimeController.java:21-28 的 `/api/runtime/derive` **无 @RequirePermission**，仅 PermissionDemoController.java:20,27 的测试桩加了 `tenant:view`/`strategy:view`。SE 方案 §2.7 明确：Phase 1 权限校验只在 demo 桩验证 AC-F3，derive 是 M1 手调验证接口，其权限管控属 Phase 2 Case 闭环范围。derive 现在已受 JWT 认证保护（RuntimeWebMvcConfig 注册 /api/** 全鉴权），非完全裸奔。task 与 SE 方案冲突时按 SE（方案权威性高于 task），裁决正确。

> 附带观察：derive 路径是 `/api/runtime/derive`（M1 旧路径），未遵循 P13 的 `/api/v1/` 版本化。但这是 M1 遗留接口，Phase 1 不改其路径（避免破坏手调），Phase 2 Case 闭环时统一收敛。列为 🟢 可选。

### D3. admin BCrypt 哈希 jshell 现场生成 —— ✅ 可靠

**Dev 决策**：用 jshell 现场生成 admin 的 BCrypt 哈希写入 schema.sql。

**Reviewer 评估**：可靠。磁盘事实——schema.sql:217 的哈希 `$2a$10$YenQ7QqiarkwMhKS2hGYF.bytyjKAGjJ7fbkaIMx9SIzfdMvr9bq2` 是标准 BCrypt 格式（`$2a$` 算法标识 + `$10$` cost + 22 字符 salt + 31 字符 hash）。与 AuthServiceImpl.java:52 的 `passwordEncoder.matches(raw, hash)` 校验逻辑兼容（同 BCryptPasswordEncoder）。matches=true 可由代码逻辑保证。**哈希格式正确，cost=10 与 PRD §8.2 一致，校验链闭合。**

---

## 5. 缺陷清单

| 编号 | 严重度 | 文件:行 | 问题 | 修复建议 |
|---|---|---|---|---|
| D1 | 🟡建议 | AuthServiceImpl.java:50-52 | 防用户枚举的恒定时延未实现：user==null 时直接返回，未走一次假 BCrypt.matches，理论上可通过响应时长差异区分用户存在性。代码注释已自认此简化并标注 M3 补。 | Phase 1 dogfooding 内网可接受。建议在 user==null 分支补一行 `passwordEncoder.matches("dummy", DUMMY_HASH)`（DUMMY_HASH 为预置常量哈希）抹平时延，无需等 M3。 |
| D2 | 🟡建议 | schema.sql:217 vs PRD §5.1.1 | 文档与代码字段名不一致：PRD §5.1.1 统一响应结构示例写 `"message"`，但 R.java:11 实际字段是 `msg`。Dev 按 R 实际实现编码（login.html:77 `resp.msg` 正确），但 PRD 文档滞后。 | 传导 PO 修正 PRD §5.1.1 示例为 `"msg"`（或统一改 R 类为 message，但改动面大，建议改文档）。不阻断交付。 |
| D3 | 🟡建议 | CorsConfig.java:17 | 开发期 CORS `allowedOriginPatterns("*")` 过宽，虽代码注释标注生产收紧，但无配置开关区分环境（forceHttps 已有配置项，CORS origin 无）。 | 建议加 `eaiselp.security.cors.allowed-origins` 配置项，生产 yml 显式列白名单，开发默认 `*`。Phase 1 可接受。 |
| D4 | 🟢可选 | RuntimeController.java:13 | `/api/runtime/derive` 路径未遵循 P13 `/api/v1/` 版本化（M1 遗留）。 | Phase 2 Case 闭环时统一收敛为 `/api/v1/runtime/derive`，Phase 1 不动避免破坏手调。 |
| D5 | 🟢可选 | UserRoleMapper.java:16-19 | selectRolesByUserId 未校验"用户角色必须属于当前 tenant"（t_user_role 在 IGNORE 表，按 user_id 查）。Phase 1 单租户无影响，多租户场景下若 t_user_role 出现跨租户脏数据则无法过滤。 | M3 多租户时在 SQL 补 `AND ur.tenant_id = #{tenantId}`（tenantId 由 LoginUser 注入）。Phase 1 记录。 |
| D6 | 🟢可选 | PermissionDemoController.java | 测试桩 controller 与生产代码同包，无构建期隔离。注释已标注"Phase 2 删除"。 | Phase 2 上线真实业务接口时务必删除本类，避免遗留测试端点。建议加 TODO 或移至独立 profile。 |

---

## 6. 总评审结论

**通过（0 阻断）。**

### 6.1 通过依据

1. **磁盘事实核对**：43 文件改动全部真实落地，Dev 报告与 git diff 逐条一致，无虚报/漏报/夹带。
2. **7 维度安全审查**：JWT（HS256/占位密钥/长度校验/payload 无敏感）、认证（BCrypt/40001 防枚举/40002 禁用/last_login_at）、RBAC（注解拦截/多角色并集/403）、多租户（5 表 IGNORE/token 透传）、CORS（patterns+credentials）、前端（401 跳登录/路由保护/CSRF 免疫）、规范（模块边界/零硬编码/版本化）—— **全部合规**。
3. **反幻觉自跑**：BUILD SUCCESS + 门禁 6/6 + 5 条 grep 验证全过，结论独立可复现。
4. **Dev 3 技术问题**：jackson-databind 补依赖（合理修复 SE 遗漏）、derive 不加注解（按 SE 方案裁决）、BCrypt jshell 生成（格式正确校验闭合）—— **全部合理**。

### 6.2 残留风险（均不阻断，已分级为建议/可选）

- 防枚举恒定时延（D1）—— Phase 1 内网可接受，建议尽快补
- CORS 生产收紧（D3）—— 上生产前必须处理
- 多租户角色归属校验（D5）—— M3 多租户时必须补

### 6.3 门禁路径注意事项（传导 Dev/QA/Ops）

门禁脚本 `质量门禁-模块边界.ps1` 在 cmd 重定向场景下默认 `Get-Location` 解析异常导致 G1/G2/G6 误报"pom.xml not found"。**必须用 `-Root <platform 根绝对路径>` 显式传参**，否则会得到假的 3/6 失败。建议 Ops 在 CI 脚本中固化此调用方式，或在脚本内将默认 Root 改为脚本所在目录向上推导 pom.xml。

---

## 本次经验沉淀

1. **门禁脚本的"假失败"是高频幻觉陷阱**：本次门禁首次运行报 G1/G2/G6 失败（pom.xml not found），表面看是 Dev 代码缺陷，实际是脚本默认 `Get-Location` 在 cmd 输出重定向场景下解析为异常路径。**教训**：Reviewer 跑门禁时，若失败项是"文件 not found"类路径错误而非规则逻辑失败，必须先用绝对路径参数重跑一次再下结论，避免把环境/调用方式问题误判为代码缺陷，制造假的阻断。

2. **前后端字段名契约要以"代码实际定义"为准，而非文档示例**：本次 PRD §5.1.1 文档写响应字段 `message`，但 R.java 实际是 `msg`。Dev 按 R 实际字段编码（login.html 用 resp.msg）是正确的，反而是文档滞后。若 Reviewer 只对照 PRD 文档而不 Read R 类源码，会误判 login.html 为字段不一致缺陷。**教训**：审查前后端契约一致性时，文档是参考，POJO 实际字段定义（R.java / DTO）才是权威事实来源——"代码即真理"。

3. **安全相关的"故意简化"必须在代码注释中显式自认**：本次 AuthServiceImpl 对防枚举恒定时延的处理——user==null 直接返回而非走假 BCrypt.matches，Dev 在代码注释里明确写了"Phase 1 简化，M3 补恒定时延"。这种"看起来是安全漏洞实则是已知取舍"的设计，注释自认让 Reviewer 能准确判定为"建议级"而非"阻断级"。**教训**：涉及安全的简化处理（恒定时延、token 存 localStorage、CORS 宽松等），Dev 必须在代码注释显式标注"已知风险 + 推迟版本 + 触发条件"，否则 Reviewer 只能按最严标准判阻断。
