# 测试报告 — M2 Phase 1（登录 + JWT + RBAC + 前端）

> 结论：**静态验证通过（条件通过）** —— 代码逻辑审查 + 门禁 6/6 全过；mvn 编译因本机环境缺 JDK17 未能本地复现，依赖 Reviewer 自跑结论（BUILD SUCCESS）。
>
> | 字段 | 值 |
> |---|---|
> | Case | case-20260723-m2-phase1-web-auth |
> | 测试者 | team-qa（L1 测试工程师） |
> | 测试日期 | 2026-07-23 |
> | 测试方式 | 静态验证（磁盘事实核对 + 编译 + 门禁 + 逐文件源码走查） |
> | 评审依据 | PO PRD v1.0（6 条 AC-F1~F6）+ SE 技术方案 v1.0 + Reviewer 报告 |
> | 结论 | **静态通过**；端到端动态验证见 §4 curl 清单（需运行环境执行） |

---

## 0. 磁盘事实核对（强制第一步）

### 0.1 核对方法

按"Dev/Reviewer 报告不可信，磁盘为唯一事实"原则，本次核对独立执行，**未采信 Reviewer 报告的"BUILD SUCCESS/门禁 6/6"**，而是：
1. 自跑 `git status` / `git diff --stat` 拿真实改动；
2. 逐文件 Read 任务指定的 6 个文件 + 全部依赖类（DTO/ResultCode/SecurityProperties/JwtClaims/LoginUser/拦截器注册/PermissionInterceptor/schema seed）；
3. 自跑门禁脚本（用 `-Root` 绝对路径规避 Reviewer 报告的 cmd 路径坑）；
4. 自跑 mvn 编译（见 §1.3 环境约束）。

### 0.2 git 事实核对

- `git status`：工作树干净，无未提交改动（`git diff --stat` 为空）。所有改动已落在 2 个本地提交（`10fd924 feat(M2-Phase1)` + 后续 deploy 修复），与"已交付"一致。**无夹带未提交改动。**
- `git log`：最近提交 `10fd924 feat(M2-Phase1): 前端工程+登录页+JWT认证+RBAC 5角色+主框架`，与本次验收范围一致。

### 0.3 报告路径偏差（重要，已澄清）

任务清单指定的前端文件路径为：
- `D:\AI\mywork\platform\eaiselp-web\api.js`
- `D:\AI\mywork\platform\eaiselp-web\auth.js`

**磁盘事实**：上述两个根目录路径不存在。前端 JS 实际位于 `eaiselp-web\assets\js\` 下：
- `D:\AI\mywork\platform\eaiselp-web\assets\js\api.js`
- `D:\AI\mywork\platform\eaiselp-web\assets\js\auth.js`

由 `login.html:43` (`<script src="assets/js/api.js">`) 与 `index.html:48-49` 引用证实。**这是任务输入的路径少写了一层 `assets/js/`，非 Dev 交付缺陷**；文件内容已正确读取并审查（见 §3）。

### 0.4 真实交付物清单核对

| 类别 | 任务/Reviewer 声称 | 磁盘事实 | 结论 |
|---|---|---|---|
| auth 后端 Java | 7 文件 | controller/AuthController + service(AuthService+impl) + dto×3 + config/AuthWebMvcConfig + Application | ✅ 齐全 |
| common security | JwtUtil + JwtAuthInterceptor + 4 依赖类 | JwtUtil/JwtAuthInterceptor/JwtClaims/LoginUser/SecurityProperties/RequirePermission 全部存在 | ✅ 齐全 |
| 前端 | api.js + auth.js + config.js + login.html + index.html | 均在 `eaiselp-web\`（JS 在 `assets/js/` 子目录） | ✅ 齐全 |
| RBAC 拦截器实现 | PermissionInterceptor（runtime） | `eaiselp-runtime\...\security\PermissionInterceptor.java` 存在并真实注册 | ✅ 齐全 |
| schema seed | 31 权限 + 5 角色 + 57 关联 + admin 角色绑定 | 全部落地（见 §3.4） | ✅ 齐全 |

---

## 1. 编译与门禁验证

### 1.1 门禁脚本（自跑，通过）

脚本：`D:\AI\mywork\platform\docs\架构文档\质量门禁-模块边界.ps1`
调用：`powershell -ExecutionPolicy Bypass -File "...\质量门禁-模块边界.ps1" -Root "D:\AI\mywork\platform"`（采纳 Reviewer 报告经验，显式传 `-Root` 绝对路径，规避 cmd 重定向导致的 `Get-Location` 异常）。

| 门禁 | 规则 | 结果 |
|---|---|---|
| G1 | 父 POM pluginManagement 的 spring-boot-maven-plugin 不得有 executions | ✅ PASS |
| G2 | library 模块 POM 不得含 spring-boot-maven-plugin | ✅ PASS（common/capability/adapter/data 干净）|
| G3 | library 模块不得有 @SpringBootApplication | ✅ PASS（4 个 library 全无）|
| G4 | library 模块不得有 @EnableDiscoveryClient | ✅ PASS |
| G5 | library 模块不得依赖 nacos-discovery | ✅ PASS |
| G6 | service 模块必须显式声明 spring-boot-maven-plugin + repackage | ✅ PASS（5 个 service 全有）|

**汇总：PASS 6/6，FAIL 0。Verdict: All gates passed。**

### 1.2 mvn 编译（环境受限，未本地复现）

- 根 `pom.xml` 要求 JDK 17（`<java.version>17</java.version>` + `<release>17</release>`，pom.xml:26-29,151）。
- 本机环境：`java -version` = **JDK 16.0.1**（`C:\Program Files\Java\jdk-16.0.1`），**仅此一个 JDK**。
- 系统内 `D:\AI\jdk17.zip` 存在但**损坏**（PowerShell `Expand-Archive` 报"找不到中央目录结尾记录"，无法解压）。
- 实跑 `mvn clean package -DskipTests` 结果：`eaiselp-common` 编译期即报 **`错误: 不支持发行版本 17`**（`Fatal error compiling`），在第一个模块就中断。

**判定**：编译失败是**环境约束（JDK 版本不足）而非代码缺陷**。pom 要求 17 合理（Spring Boot 3.2.5 + jjwt 0.12.6 均需 JDK17+）。Reviewer 报告称用 JDK26 离线 `mvn -o clean package` 得 BUILD SUCCESS，本环境无法复现，故编译项标注为"依赖 Reviewer 结论，未本地复现"。

> 建议：Ops 在 CI/部署机预装 JDK17+，或在本地补装可用 JDK17 后由 QA 复跑一次 `mvn clean package -DskipTests` 闭环此项。

### 1.3 测试代码覆盖（未覆盖项）

- `eaiselp-runtime\src\test\resources\schema-h2.sql` 仅含 M1 派生表（t_derivation/t_artifact），**未含 RBAC 5 张表**（t_permission/t_role/t_role_permission/t_user_role/t_service_account）。
- **auth 模块无 `src/test` 目录**——登录/JWT/RBAC 无单元测试。
- 故 AC-F1（登录凭据校验）、AC-F2（JWT 解析/过期）、AC-F3（权限拦截）**均无自动化测试覆盖**，只能靠 §4 的 curl 端到端手测。这是测试覆盖缺口（非阻断，Phase 1 可接受手测，但建议 Phase 2 补 auth 单测）。

---

## 2. 测试用例（按 PRD 6 条 AC 反推）

> 说明：因无 auth 单测环境，下表为"预期行为"用例基线；可执行性见 §4 curl 清单。"关联代码行"均为 QA 自行 Read 得出（非抄 Reviewer 报告）。

| 用例ID | 分类 | 前置条件 | 步骤 | 预期 | 关联AC | 关联代码（QA 自查） |
|---|---|---|---|---|---|---|
| TC-01 | 正常 | admin 已 seed（status=active, BCrypt cost=10）| POST /login {admin, 正确密码} | HTTP200 + code=0 + token 为合法 HS256 JWT；payload 含 userId=1/tenantId=1/roles=["tenant_admin"]；exp-iat=86400 | AC-F1.1, F2.1 | AuthServiceImpl.java:43-93；JwtUtil.java:35-51 |
| TC-02 | 异常 | 同上 | POST /login {admin, wrongpwd} | HTTP200 + code=40001 + msg="用户名或密码错误"；localStorage 不写 token | AC-F1.2 | AuthServiceImpl.java:52-56（统一 40001）|
| TC-03 | 异常 | username=nouser 不存在 | POST /login {nouser, any} | code=40001（**与 TC-02 同 code，防枚举**）| AC-F1.3 | AuthServiceImpl.java:52 `user==null` 与密码错同一 throw |
| TC-04 | 异常 | 用户 status='disabled' | POST /login {正确凭据} | code=40002 + msg="账户已被禁用" | AC-F1.4 | AuthServiceImpl.java:58-61 |
| TC-05 | 正常 | TC-01 成功 | 查 t_user.last_login_at | 已更新为当前时间（精度秒）| AC-F1.5 | AuthServiceImpl.java:81-84 |
| TC-06 | 正常 | 有 token | GET /current（带 Bearer） | code=0 + user 完整信息（含 roles+permissions）；导航可渲染 | AC-F2.2 | AuthController.java:29-37；AuthServiceImpl.java:95-106 |
| TC-07 | 异常 | exp 已过的 JWT | GET /current | HTTP401 + code=40102 + msg="token 无效或已过期"；前端清 token 跳 login | AC-F2.3 | JwtAuthInterceptor.java:44-46 |
| TC-08 | 异常 | 无 Authorization header | GET /current | HTTP401 + code=40101 + msg="未登录或 token 缺失" | AC-F6 | JwtAuthInterceptor.java:36-38 |
| TC-09 | 正常 | tenant_admin 登录 | GET /api/v1/demo/tenant-view（带 token，@RequirePermission("tenant:view")）| HTTP200 + 业务数据（tenant_admin 经 role_id=2→无 tenant:view 权限，**预期应 403，见缺陷 D1**）| AC-F3.1 | PermissionDemoController.java:20；schema.sql:378-384 |
| TC-10 | 异常 | engineer 登录 | GET /api/v1/demo/tenant-view | HTTP403 + code=40301 + msg="无权限访问该资源" | AC-F3.2 | PermissionInterceptor.java:44-48 |
| TC-11 | 边界 | 多角色用户 | 查 permissions | 两角色权限并集去重 | AC-F3.3 | PermissionServiceImpl.java:35-39（distinct）|
| TC-12 | 正常 | 浏览器无 token | 直接访问 index.html | 前端 AUTH.restore 检测无 token 跳 login.html | AC-F6.1 | auth.js:18-19；index.html:57 |
| TC-13 | 异常 | localStorage 伪造 token | 访问 index.html | /current 返 401→清 token 跳 login | AC-F6.2 | auth.js:20-26 |
| TC-14 | 正常 | 已登录会话中途 token 过期 | 触发任意鉴权 API | api.js 拦截 401→clearToken→跳 login | AC-F6.3 | api.js:31-39 |

> **TC-09 预期修正说明**：PRD AC-F3.1 期望"tenant_admin 持有 user:view 能访问"，但 demo 桩 `/tenant-view` 标的是 `tenant:view` 权限。查 schema.sql seed：tenant_admin（role_id=2）的 15 项权限（378-384 行）**不含 tenant:view**（tenant:view 仅授予 platform_admin）。故 tenant_admin 访问 `/tenant-view` 实际会被 403。要验证 AC-F3.1"有权限能访问"，应改用 platform_admin 账户，或 demo 桩改用 tenant_admin 持有的权限码（如 user:view）。这是 demo 桩与 AC 的设计错配，见缺陷 D1。

---

## 3. 代码逻辑审查（逐文件，QA 自查）

### 3.1 AuthController.java ✅

- 三接口路径 `/api/v1/auth/{login,current,logout}`，符合 P13 版本化（行 16,23,29,40）。
- login 标 `@Valid @RequestBody`，触发 LoginRequest 的 @NotBlank/@Size 校验（行 24）。
- current/logout 从 `LoginUser.get()` 取 claims（行 32,42），**不信任前端传 userId**——防伪造，正确。
- current 在 claims 为空时返 40101（行 33-35），双保险（拦截器已挡，controller 再判）。

### 3.2 AuthServiceImpl.java ✅（含已知简化）

- login 按 (tenant_id, username) 查（行 46-48），单租户 dogfooding 用 `default-tenant-id`（行 39-40）。
- **40001 防枚举**：`user==null || !matches` 同一分支同一 throw（行 52-56）——符合 PRD §5.1.3 安全约定。代码注释自认"恒定时延未实现，user==null 直接返回"（行 50-51），Phase 1 内网可接受，Reviewer 已记为建议级 D1。
- 40002 禁用判断（行 58-61）。
- JWT payload **不含 permissions**（行 71-78，Q-5 防膨胀），permissions 由 /current 实时查（行 104）——与 PRD §5.2.1 注一致。
- last_login_at 更新（行 81-84）—— AC-F1.5 落地。

### 3.3 JwtUtil.java ✅

- @PostConstruct 强制 secret ≥32 字节，不足抛 IllegalStateException（行 28-30）—— HS256 安全要求。
- 签发 HS256（行 49），payload 含 userId/username/displayName/tenantId/tenantCode/roles（行 41-46），issuer=eaiselp-auth（行 39）。
- parse 用 `verifyWith(key).parseSignedClaims`（行 55-59），过期/签名错由调用方分别捕获。

### 3.4 JwtAuthInterceptor.java ✅

- 无 token/非 Bearer → 40101（行 36-38）；过期 → 40102（行 44-46）；其他 JwtException → 40102（行 47-50）。
- afterCompletion **必清 ThreadLocal**（行 54-56）——防线程池泄漏，正确。
- LoginUser.set 同步注入 TenantContext（LoginUser.java:14-16）——多租户隔离核心，token 为权威覆盖 header。

### 3.5 拦截器注册链路 ✅（关键，曾疑虑已排除）

- **auth 模块**（AuthWebMvcConfig.java:17-21）：仅注册 JwtAuthInterceptor，白名单 `/api/v1/auth/login`。auth 自身接口不需权限码，合理。
- **runtime 模块**（RuntimeWebMvcConfig.java:28-37）：注册**双拦截器**——JwtAuthInterceptor(order=1) + PermissionInterceptor(order=2)，全 `/api/**`。**注解 @RequirePermission 在 runtime 真实生效**（非"有注解无实现"）。
- 之前 QA一度因 findstr 路径模式误判"PermissionInterceptor 未注册"，经直接 Read RuntimeWebMvcConfig.java 确认注册完整，排除该疑虑。

### 3.6 前端 api.js / auth.js / config.js ✅

- api.js：自动带 `Authorization: Bearer {token}`（行 26）；**统一拦截 401→clearToken→跳 login**（行 31-39）—— AC-F6.3 落地。
- auth.js：restore 在无 token 或 /current 失败时均跳 login（行 18-30）—— AC-F6.1/F6.2 落地。
- config.js：双 base-url，AUTH=8085 / API=8081（行 12-14），符合 SE 裁决"auth 独立 service"。
- login.html：登录成功存 token 跳 index（行 72-74）；失败按 resp.msg 提示（行 77，与 R.java 实际字段 `msg` 一致，非 PRD 文档的 `message`）。

### 3.7 schema.sql seed ✅（数据完整）

| 表 | 期望 | 磁盘事实 | 行号 |
|---|---|---|---|
| t_permission | 31 条 | 31 条（id 1001-1031）| 318-349 |
| t_role | 5 模板角色 | platform_admin/tenant_admin/project_manager/engineer/executive 全有 | 352-357 |
| t_role_permission | 按矩阵 | 57 元组（29+15+7+4+2）| 364-401 |
| t_user_role | admin→tenant_admin | (3001, tenant_id=1, user_id=1, role_id=2) | 406-407 |
| admin 密码 | BCrypt cost=10 | `$2a$10$YenQ7Qqi...` | 217 |
| 幂等性 | 重跑不报错 | 全用 `INSERT IGNORE` + UNIQUE 约束 | 318,352,364... |

---

## 4. 端到端验证 curl 命令清单（交付用户执行）

> 前置：① 已安装 JDK17+ 并 `mvn clean package -DskipTests` 通过；② MySQL 已执行 `schema.sql`（含 RBAC seed）；③ auth 服务（8085）与 runtime 服务（8081）已启动；④ admin 初始密码需向 Dev/Ops 确认（schema seed 的 BCrypt 哈希对应明文）。

### 4.1 登录（AC-F1）

```bash
# TC-01 正确凭据登录（把 <PWD> 换成 admin 实际密码）
curl -i -X POST http://localhost:8085/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"admin\",\"password\":\"<PWD>\"}"
# 预期：HTTP 200，body.code=0，data.token 非空，data.user.roles=["tenant_admin"]

# TC-02 错误密码
curl -i -X POST http://localhost:8085/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"admin\",\"password\":\"wrongpwd\"}"
# 预期：HTTP 200，body.code=40001，body.msg="用户名或密码错误"

# TC-03 不存在的用户（应与 TC-02 同 code=40001，防枚举）
curl -i -X POST http://localhost:8085/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"nouser\",\"password\":\"anything\"}"
# 预期：HTTP 200，body.code=40001
```

### 4.2 恢复登录态 / token 失效（AC-F2）

```bash
# 先拿 token（变量保存）
TOKEN=$(curl -s -X POST http://localhost:8085/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"admin\",\"password\":\"<PWD>\"}" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
echo "TOKEN=$TOKEN"

# TC-06 带 token 恢复登录态
curl -i -X GET http://localhost:8085/api/v1/auth/current \
  -H "Authorization: Bearer $TOKEN"
# 预期：HTTP 200，code=0，data.user 含 roles+permissions

# TC-08 无 token 访问受保护接口
curl -i -X GET http://localhost:8085/api/v1/auth/current
# 预期：HTTP 401，code=40101，msg="未登录或 token 缺失"

# TC-07 伪造/损坏 token
curl -i -X GET http://localhost:8085/api/v1/auth/current \
  -H "Authorization: Bearer fake.invalid.token"
# 预期：HTTP 401，code=40102，msg="token 无效或已过期"

# TC-07b 篡改 token（base64 解码 payload 改 tenantId 再编码，签名会失配）
curl -i -X GET http://localhost:8085/api/v1/auth/current \
  -H "Authorization: Bearer <篡改后的token>"
# 预期：HTTP 401，code=40102（签名校验失败）
```

### 4.3 RBAC 权限校验（AC-F3，打 runtime 8081 的 demo 桩）

```bash
# 注意：demo 桩在 runtime（8081），需用 runtime 签发的 token
# （若 auth/runtime 共用同一 JWT_SECRET，admin 的 token 两端通用）

# TC-09 platform_admin 访问 tenant-view（platform_admin 有 tenant:view，应放行）
#   前置：需构造一个 platform_admin 角色用户（手工 INSERT t_user_role 给某 user 绑 role_id=1）
curl -i -X GET http://localhost:8081/api/v1/demo/tenant-view \
  -H "Authorization: Bearer $TOKEN_PLATFORM_ADMIN"
# 预期：HTTP 200，code=0，body.data 含"你有 tenant:view 权限"

# TC-10 engineer 访问 tenant-view（engineer 无 tenant:view，应 403）
#   前置：构造 engineer 用户（绑 role_id=4）
curl -i -X GET http://localhost:8081/api/v1/demo/tenant-view \
  -H "Authorization: Bearer $TOKEN_ENGINEER"
# 预期：HTTP 403，code=40301，msg="无权限访问该资源"

# TC-10b 无 token 访问 demo（应先被 JWT 拦截器挡在 401，而非进到权限校验）
curl -i -X GET http://localhost:8081/api/v1/demo/tenant-view
# 预期：HTTP 401，code=40101
```

### 4.4 退出（AC-F5.4 后端语义）

```bash
curl -i -X POST http://localhost:8085/api/v1/auth/logout \
  -H "Authorization: Bearer $TOKEN"
# 预期：HTTP 200，code=0（M2 无黑名单，仅日志；前端清 localStorage 才真退出）
```

### 4.5 多租户隔离（AC-F4，需造第二租户数据）

```bash
# 前置：手工 INSERT 第二租户(tenant_id=2)+用户，并用该用户登录拿 TOKEN_B
# TC-F4 用 tenant A 的 token 访问 tenant B 的数据 —— Phase 1 无业务列表接口，
#   可通过 JWT payload tenantId 不可伪造性 + MyBatis-Plus 租户拦截器验证：
#   用 TOKEN_A（tenantId=1）的 token，篡改 payload tenantId=2 → 签名失配 → 401
#   （证明 tenantId 由签名保护，不可伪造）
```

### 4.6 JWT 结构核验（AC-F2.1，用 jwt.io 或命令行）

```bash
# 解码 token 的 payload（第二段）查看 claims
echo "$TOKEN" | cut -d'.' -f2 | base64 -d 2>/dev/null
# 预期 JSON 含：userId/username/displayName/tenantId/tenantCode/roles/iat/exp
# 校验：exp - iat == 86400
```

---

## 5. 缺陷清单

| 编号 | 严重度 | 位置 | 问题 | 建议 |
|---|---|---|---|---|
| Q-D1 | 🟡建议 | PermissionDemoController.java:20 vs AC-F3.1 | demo 桩 `/tenant-view` 标 `tenant:view`，但 PRD AC-F3.1 的 Given 是"tenant_admin 持有 user:view"。tenant_admin 的 seed 权限（schema.sql:378-384）不含 tenant:view，导致用 tenant_admin 验证 AC-F3.1 会得到 403 而非 200——**demo 桩权限码与 AC 验证账户不匹配**。 | 验证 AC-F3.1 时改用 platform_admin 账户（其有 tenant:view），或把 demo 桩权限码改成 tenant_admin 持有的 `user:view`。不影响生产，仅影响验收手测路径。 |
| Q-D2 | 🟡建议 | schema.sql:356 | engineer 角色 `data_scope='self'`，但 PRD §6.4 矩阵/§6.5 seed 计划说模板角色用 all/tenant。文档与 seed 小偏差。 | Phase 1 data_scope 未实际用于过滤逻辑（依赖 tenant_id 隔离），不阻断。建议 PO 统一文档与 seed。 |
| Q-D3 | 🟡建议 | auth 模块无 src/test | 登录/JWT/RBAC 无单元测试；H2 测试 schema 不含 RBAC 5 表。AC-F1/F2/F3 全靠手测。 | Phase 2 补 auth 单测 + 扩展 schema-h2.sql 含 RBAC 表，降低回归风险。 |
| Q-D4 | 🟢可选 | 编译环境 | 本机 JDK16 无法编译（pom 要求 17），jdk17.zip 损坏。 | Ops 预装 JDK17+；非代码缺陷。 |

> 注：Reviewer 报告的 D1（防枚举恒定时延）、D2（msg vs message 文档）、D3（CORS 收紧）经 QA 复核均属实，此处不重复，仅列 QA 新增发现。其中"msg vs message"已由 login.html:77 实际用 `resp.msg` 证实前端正确（Dev 按 R.java 实际字段编码），仅 PRD 文档滞后。

---

## 6. 覆盖情况

| AC | 自动化测试 | 静态审查 | 手测 curl | 覆盖度 |
|---|---|---|---|---|
| AC-F1（登录）| ❌ 无 auth 单测 | ✅ 代码逻辑闭合 | ✅ §4.1 | 静态+手测覆盖 |
| AC-F2（JWT）| ❌ | ✅ | ✅ §4.2/4.6 | 静态+手测覆盖 |
| AC-F3（RBAC）| ❌ | ✅ 拦截器链路确认 | ✅ §4.3（含 D1 注意）| 静态+手测覆盖 |
| AC-F4（多租户）| ❌ | ✅ token 透传+IGNORE 表 | ⚠️ 需造第二租户 | 部分（单租户环境需造数）|
| AC-F5（菜单渲染）| ❌ | ✅ menu.js + index.html | 需浏览器 | 静态覆盖（浏览器手测）|
| AC-F6（路由保护）| ❌ | ✅ api.js/auth.js | ✅ §4.2 | 静态+手测覆盖 |

**未覆盖**：① 动态运行时行为（未启动服务跑 curl）；② mvn 编译（环境缺 JDK17）。这两项需在具备 JDK17+MySQL+运行环境后由用户/Op执行。

---

## 7. 结论

**静态验证通过（条件通过）。**

### 7.1 通过依据
1. **磁盘事实核对**：43 文件交付真实落地，无虚报/夹带；前端文件路径经澄清在 `assets/js/` 子目录（任务输入路径少写一层，非缺陷）。
2. **门禁 6/6 全过**（QA 自跑，用 `-Root` 绝对路径）。
3. **代码逻辑审查**：登录（BCrypt+40001 防枚举+40002+last_login_at）、JWT（HS256+≥32字节校验+过期/签名分支）、RBAC（双拦截器链路闭合+多角色并集+403）、多租户（token 透传+5 表 IGNORE）、前端（401 跳登录+路由保护）—— **全部与 PRD AC 对齐**。
4. **seed 数据完整**：31 权限+5 角色+57 关联+admin 绑定，INSERT IGNORE 幂等。

### 7.2 未闭环项（不阻断，需后续补）
- mvn 编译：本机 JDK16 不足，依赖 Reviewer 的 BUILD SUCCESS 结论，建议补 JDK17 后复跑。
- 动态端到端：§4 curl 清单需在运行环境执行（QA 未启服务）。
- auth 单测：缺失，建议 Phase 2 补。

### 7.3 失败用例数
**0**（静态审查层面无失败；TC-09 预期需按 Q-D1 调整验证账户，非代码 bug）。

---

## 本次经验沉淀

1. **任务输入的文件路径不可全信，必须用 dir/Read 核对真实位置**。本次任务清单写 `eaiselp-web\api.js`，实际在 `eaiselp-web\assets\js\api.js`。若 QA 直接按给定路径 Read 失败就报"文件缺失/不通过"，会制造假阻断。教训：路径不存在时，先用 `dir /s` 递归找文件真实位置，再判断是"交付缺失"还是"路径写错"——前者是 Dev 缺陷，后者是输入偏差，性质完全不同。

2. **"注解有定义+拦截器类有实现+是否注册到 WebMvc 链"是 RBAC 三段式验证，少一段就失效**。本次验证 @RequirePermission 时，注解（common）、拦截器实现（runtime）分散在不同模块，QA 一度用错误 findstr 模式误判"未注册"，最后靠直接 Read RuntimeWebMvcConfig.java 才确认双拦截器注册完整。教训：RBAC 这类"声明+实现+装配"分离的设计，验证时必须三段都查到（注解定义在哪、拦截器实现在哪、哪个 WebMvcConfig 注册了它），任一缺失就是真缺陷（典型失效模式：注解写了但忘注册→权限形同虚设）。

3. **mvn 编译失败要先区分"环境约束"还是"代码缺陷"再下结论**。本次 `不支持发行版本 17` 是本机 JDK16 不足（pom 合理要求 17），非代码问题。若直接判"编译失败→不通过"会把环境问题误归咎于 Dev。教训：编译/门禁失败时，先看错误类型——"版本不支持/命令找不到/路径异常"多为环境或调用方式问题，"符号找不到/类型不匹配/语法错误"才是代码缺陷。前者换环境/换参数复跑，后者才退回 Dev。
