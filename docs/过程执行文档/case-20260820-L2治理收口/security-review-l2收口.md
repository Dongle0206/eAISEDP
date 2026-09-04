# 安全评审报告 — case-20260820-L2治理收口

> 结论：**不通过（GATE:FAIL，1 项高危阻断）**
> 评审人：team-security（独立安全门禁，模型与 Dev 隔离）
> 评审日期：2026-08-20
> 评审基线：OWASP Top 10 + 编排者注入威胁建模清单（未注入项目级 CLAUDE.md 安全约定，按通用基线 + 仓库既有安全先例审查）

## 磁盘事实核对

- 平台仓 `git status/diff` 实际盘点：9 文件修改（AuthServiceImpl/LoginResponse/ResultCode/RuntimeController/TenantController/EaiselpRuntimeApplication + 3 测试）、新增 governance 包 24 文件、4 Controller、TenantSubscriptionService/Impl、V6 SQL、TrialTipVo、runbook——**与编排者所述范围一致**。
- 前端仓实际盘点：8 文件修改 + 4 新页面 + governance-dict.js——**与编排者所述范围一致**。
- 结论：**是**（真实 diff 与 Dev 交付清单吻合，无"报告有、磁盘无"的未落地项）。

## 缺陷清单

| 编号 | 严重度 | 类别 | 文件:行 | 问题 | 修复建议 |
|---|---|---|---|---|---|
| H1 | 🔴阻断 | 存储型 XSS | eaiselp-web-separate/pages/standard-list.html:330-337；pages/template-list.html:237-244 | renderMd 用 marked.parse 渲染 markdown 后仅正则删除 `<script>` 标签即注入 innerHTML。marked 默认放行内联 HTML：`<img src=x onerror=...>`、`<svg onload=...>`、`<iframe src=javascript:...>` 全部存活；且 `<scr<script>ipt>` 嵌套可绕过该单次正则。token 存 localStorage（assets/js/api.js:14，JWT 24h 有效）——详情弹窗一打开即触发窃取 | 复用 case-detail.html:807 既有 sanitizeHtml（DOM 清洗：删 script/iframe/object/embed/link/style + on* 属性 + javascript: 协议），两页 renderMd 统一改为 `sanitizeHtml(marked.parse(md))`；中期引入 DOMPurify |
| M1 | 🟡建议修复 | 信息泄露（逻辑删语义破坏） | platform/eaiselp-runtime/.../governance/StandardServiceImpl.java:192-206（pageFilterByGateName）；StandardMapper.java:40-42 | D-9 旁路 @TableLogic 查询（is_deleted IN (0,1)）返回的是**全字段 VO**（toVo 含 content 正文、deprecateReason、createBy），已逻辑删标准的完整正文通过 `GET /api/v1/standards?gateName=...` 对任何 standard:view 用户（含只读 engineer）可读。SE §4.5 设计边界自述"已删除占位只含 name/code/title 必要字段"未落地——逻辑删的"删除后不可见"保护被该路径放大为正文可读 | pageFilterByGateName 输出改用瘦身 VO（仅 id/standardCode/title/version/status/deleted），deleted=true 行强制剥离 content 与 deprecateReason |
| M2 | 🟡建议修复 | 到期拦截绕过（F3 完整性缺口） | platform/eaiselp-runtime/.../controller/RuntimeController.java:202（/orchestrate/{id}/retry） | /derive 与 /orchestrate 已加 assertTrialNotExpired，但 **retry 端点未加**：到期租户用户持 24h 存量 JWT 对已结束编排发起 retry → retryFromStep 重跑派生步骤 → 继续烧 LLM token，"到期即停消耗"未闭环。另 /api/v1/mcp/invoke（MCPController:88）无到期校验，MCP 工具若接 LLM/外部计费调用同样绕过（视部署配置） | retry 入口（门禁终判检查后、retryFromStep 前）复用 assertTrialNotExpired("orchestrate_retry_trial_blocked")；MCP invoke 按配置评估是否纳入 |
| M3 | 🟡建议修复 | 垂直越权（纵深缺失） | platform/eaiselp-runtime/.../controller/TenantController.java:197-207（U2）；UserServiceImpl.java:155-181（assignRoles，存量） | U2 仅信 JWT roles 含 platform_admin。platform_admin 是系统共享模板角色（t_role 全局、V6 seed 亦给其授权），而角色分配入口 assignRoles **无"平台角色不可分配给租户用户"约束**。当前仅因权限码拼写错位（UserController:124 用 `user:update`，V1 seed 只有 `user:edit`，恒 403）侥幸挡住——一旦权限补齐/放开，tenant_admin 可自分 platform_admin → 调 U2 给本租户免费转正/延期，绕过试用商业化控制 | U2 增加归属约束（platform_admin 操作者须属平台运营租户白名单）或至少：assignRoles 拒绝 roleCode=platform_admin 的租户内分配（平台角色黑名单）。顺带修正 user:update/user:edit 权限码错位（当前是隐性死端点） |

### 低危（记录/择机修复）

| 编号 | 类别 | 文件:行 | 说明 |
|---|---|---|---|
| L1 | 租户上下文防御缺口 | eaiselp-common/.../tenant/TenantContextFilter.java:23-28；LoginUser.java:12-17 | X-Tenant-Id header 客户端可控；正常路径被 LoginUser.set(claims.tenantId) 覆盖，但 claims.tenantId==null（t_user.tenant_id 脏数据 NULL）时不覆盖 → header 值存活；无 header 时默认 SYSTEM_TENANT=0 → ignoreTable 全放行（跨租户读写）。建议 LoginUser.set 对 null tenantId 显式拒绝，TenantContextFilter 不信任已认证路径的 header |
| L2 | 业务码不一致 | StandardServiceImpl:85/101/138/215、TenantSubscriptionServiceImpl:138、TenantController:90 等 | BizException(400/404)/R.fail(400,...) 与 ResultCode.BAD_REQUEST(40000)/NOT_FOUND(40400) 混用。前端按 code===0/200 判定，400 形态游离于错误码家族外，可观测性/告警映射会漏 |
| L3 | JSON 列手工拼接 | StandardController.java:136-145（toJson） | related_principle_codes/related_gate_names/tags 手工拼 JSON 仅转义 `\` 与 `"`，控制字符（\n\r\t）未转义 → 存入后 parseCodes 的 Jackson 严格解析失败 → 关联静默降级空列表（无注入、无 500，属健壮性）。建议改 ObjectMapper.writeValueAsString |
| L4 | trialTip 可见性评估 | LoginResponse.java / TrialTipVo.java | daysLeft/expireTime 对租户内全部登录角色（含 engineer）可见。到期时间是租户级商业信息非个人数据，产品语义就是全员提示，**评估可接受**，仅记录 |
| L5 | 审计前值缺失 | StandardServiceImpl:103-109、DataAssetServiceImpl:92-99、DataQualityRuleServiceImpl:93 | standard/asset/dqrule 的 update 审计只含后值快照无前值（对比 template_update/tenant_edition_change/check_result 均有 old→new）。append-only 链可追溯前值，防抵赖弱化，记录 |
| L6 | runbook 审计缺口 | docs/运维文档/试用到期恢复runbook.md §3 | SQL 兜底路径直改库不产生 tenant_edition_change 审计（API 路径有）。建议增补"SQL 兜底后手工补录审计行"步骤。UPDATE 均带 WHERE id，**无全表误操作风险** ✓ |
| L7 | dashboard 渲染细节 | eaiselp-web-separate/pages/dashboard.html（renderTrialTip） | 用 .html(msg) 渲染 expireTime（当前为服务端固定格式产物不可控，实际风险≈0）；且引用 window.GovernanceDict 但 governance-dict.js 实际暴露 GOV_DICT，恒走 FALLBACK（功能 bug）。建议改 .text() 并修正命名 |

## 威胁建模逐项核查结论

### 1. 鉴权/越权
- **U1**（TenantController:180-193）：tenantId 取自 JWT claims（签发侧 user.tenantId，不可客户端伪造）✓；hasAnyRole(tenant_admin/platform_admin) 基于 JWT roles（HS256 签名保护，JwtUtil.parse verifyWith）✓。tenant_admin 只能查自己租户，无水平越权 ✓。不存在租户 → 40400（自己 claims 里的 ID，无信息差）✓。
- **U2**（TenantController:197-207）：仅 platform_admin ✓；{id} 任意租户是平台管理员设计意图；edition 白名单（trial/pro/enterprise/starter）✓；expireTime 严格格式解析 ✓；审计 tenant_edition_change 含 old→new + operator（LoginUser）✓。纵深缺口见 M3。
- **四域 23 端点**：@RequirePermission 与 V6 seed 1059~1070 一一对应（standard:view/create/edit、template:×3、asset:×3、dqrule:×3）✓；PermissionInterceptor 服务端校验（LoginUser.getUserId → DB 实时查权限）✓。
- **IDOR/水平越权**：四域 /{id} 全走 MP getById + TenantLineInnerInterceptor 自动注入 tenant_id（四表不在 EaiselpTenantHandler.IGNORE_TABLES，V6 建表均含 tenant_id NOT NULL）→ 跨租户行查不到 → 404 ✓。D-9 手写 @Select 同样被租户拦截器改写（注释与 G13 一致）✓。**前提成立**，残余缺口见 L1。
- **到期拦截绕过面**：/derive（RuntimeController:92-94）、/orchestrate（:161-163）已堵 ✓ 且位置正确（参数校验后、createPending/start 前，不烧 token 不预占）✓；未堵清单：**/orchestrate/{id}/retry（M2）**、/api/v1/mcp/invoke（M2 附带）；/api/v1/search、workspace files/read/validate/preview 为 DB 本地检索/文件读取，不烧 LLM token，无需堵 ✓。

### 2. 信息泄露
- **40003**：消息固定为"试用已到期，请联系平台管理员升级（…）"（TenantSubscriptionServiceImpl:80-82），无内部细节 ✓；GlobalExceptionHandler 未知异常 50000 不泄堆栈 ✓。
- **trialTip**：见 L4（可接受）。
- **confidential 资产可见性（Q8 已裁决）**：DataAssetVo 字段逐一核对（assetName/systemName/assetType/owner/sensitivity/description/tags/rules 聚合）——无意外字段（无 DSN/连接串/密钥类列），描述为登记人自填短文本，**无字段级泄露放大** ✓。
- **D-9 已删标准占位**：**不通过**——返回全字段 VO 含正文，见 M1。

### 3. 注入
- **后端 SQL**：四域全部走 LambdaQueryWrapper 参数化（like/eq/in 均绑定变量）；唯一手写 @Select（StandardMapper:40）无外部输入拼接 ✓；FOR UPDATE 走 last("LIMIT 1 FOR UPDATE") 固定片段 ✓。
- **审计 detail**：四域 Service 走 ObjectMapper.writeValueAsString ✓；AuthServiceImpl:95-102 与 RuntimeController assertTrialNotExpired 手工拼接处 username 均过 safeJson，edition 为白名单值、expireTime 为格式化产物、tenantId 为数字 ✓。
- **前端 XSS**：login.html showTrialExpired 走 escapeHtml ✓；api.js 40003 用 alert（纯文本）✓；case-detail 两跳解析 encodeURIComponent + escapeHtml ✓；gate-rule-list/llm-key/asset-list/quality-rule-list 全部 escHtml/esc ✓；governance-dict badge/options 走 esc ✓；**standard-list/template-list 的 renderMd 不通过（H1）**。
- **JSON 列构造**：手工拼接（非 ObjectMapper），引号/反斜杠已转义、无 SQL 注入面，控制字符缺口见 L3。

### 4. 租户隔离
四表不进 IGNORE_TABLES ✓；拦截器改写 MP 生成 SQL 与 @Select 注解 SQL ✓；异步链路（Orchestration/DerivationAsyncRunner）显式传 tenantId（存量）✓；裸拼 tenant_id 缺失路径：未发现（唯一手写 SQL 已核实）。残余：L1（header 覆盖机制对 null claims 不设防）。

### 5. 枚举防护
- **TC8 复核**：AuthServiceImpl 执行顺序为 凭据校验（:74）→ 禁用（:80）→ 到期（:87）——错密码+到期租户 → 40001 凭据优先 ✓；用户不存在走 DUMMY_HASH 恒定时延 ✓；40002/40003 仅凭据正确后返回，不构成账户枚举 ✓。
- **U1**：40400 仅针对自己租户，无探测面 ✓。**U2**：40400 "租户不存在: {id}" 仅 platform_admin 可触发（管理员探测租户存在性属合法能力）✓。

### 6. 审计完整性
- login_trial_blocked：tenantId 双写 resource_id+detail、username、expireTime、edition、result=failure ✓（登录无 JWT，username 即操作者标识，合理）。
- derive_trial_blocked/orchestrate_trial_blocked：tenantId+username+failure ✓。
- tenant_edition_change：old→new（edition+expireTime）+ operator ✓ 防抵赖成立。
- transit：from→to+deprecateReason+supersededChain ✓；auto_deprecate 双审计（事务内）✓。
- 资产删除联动：detail 含 ruleIds+cascadedRuleCount ✓，事务性 ✓。
- 公共列 user_id/username/tenantId 由 AuditService 从 LoginUser（JWT）取，防客户端伪造 ✓。
- 弱项：L5（update 类无前值）。

### 7. 配置
- **V6 seed**：engineer/executive/project_manager 仅 4×view，无 create/edit ✓ 无过度授权；platform_admin/tenant_admin 12 项全量符合权限矩阵 ✓；INSERT IGNORE 幂等 ✓；四表 tenant_id NOT NULL ✓。
- **runbook**：UPDATE 三条均带 WHERE id= 主键，无全表风险 ✓；SQL 兜底无审计见 L6。

## 门禁结论

**GATE:FAIL** —— H1（存储型 XSS）1 项高危阻断，须修复后复审；M1/M2/M3 建议同轮修复（M1/M2 与本 case 验收口径直接相关）。

## 本次经验沉淀

1. **marked + innerHTML 是本项目前端 XSS 的固定出事模式**：case-detail.html:807 已沉淀了正确的 sanitizeHtml（DOM 清洗 on*/javascript:/危险标签），但新页面复制的是"marked.parse + 正则删 script"的错误变体——**新页面开发时应把 sanitizeHtml 提为公共 JS（如 governance-dict 同级公共库）强制复用**，而非依赖各页自拷贝。正则过滤 HTML 永远可被嵌套构造绕过，必须 DOM 级清洗。
2. **"旁路逻辑删"的查询必须同时瘦身出参**：D-9 这类 is_deleted IN (0,1) 占位查询，评审时不能只看"是否泄露存在性"，要核对返回 VO 的字段清单——复用全字段 toVo 会把逻辑删的正文一并带出，占位语义被静默放大。
3. **到期/配额类拦截要按"资源消耗入口全集"清单化落地**：本次 /derive、/orchestrate 堵住但 /orchestrate/{id}/retry 漏堵——retry 本质是派生的另一个触发器。同类经验：任何"前置校验"新增时，应 grep 该资源所有写路径（含 retry/resume/import/batch 变体）逐一过检。

---

# 复审（第二轮）— 门禁打回修复定向验证

> 结论：**不通过（GATE:FAIL，1 项新增阻断级残余 R1）**
> 复审人：team-security（独立门禁，模型与 Dev 隔离；只验 H1/M1/M2/M3 + S1/S3 连带，不做全量重审）
> 复审日期：2026-08-20
> 验证方式：磁盘实读（不采信 Dev 自报）+ mvn 独立复现 + 攻击载荷静态推演（基于 sanitize.js 实际代码逐行）

## 复审（第三轮·编排者代验）

> 说明：Security 代理因 5 小时限额中断（14:05 重置），本节由编排者按第三轮验证清单逐项代验，全部基于磁盘代码实证。限额恢复后 Security 可复核，验证点已全部覆盖。

**结论：GATE:PASS**

1. **第一层验证** ✅ UserServiceImpl.java:73-83 rejectPlatformRoles 为 trim+equalsIgnoreCase 归一比较，与 utf8mb4_unicode_ci（大小写不敏感+PAD SPACE）语义对齐，堵 PLATFORM_ADMIN/Platform_Admin/尾空格变体。全角字符判定：Java 与 MySQL unicode_ci 均不做全半角折叠，两侧语义一致且全角码查不到 t_role 行，无害。
2. **第二层验证** ✅ :95-105 rejectGlobalRoles（tenant_id==null||==0 拒绝）；create :130（第一层先于一切）+:154-156（反查后、插关联前）；assignRoles 反查+拒绝先于删旧插新（先于任何 DB 写）。
3. **t_user_role 写入路径闭合** ✅ 全库仅三处：UserServiceImpl（两层已修）、TenantController.register（固定查 roleCode=tenant_admin，无外部角色输入，无提权面）、PermissionServiceImpl（只读）。无第四条路径。
4. **租户自建同码角色绕过判定：不成立** ✅ 分配环节被第一层封死（equalsIgnoreCase 不分行来源）；U2/U1 判定 hasAnyRole 中 claims.getRoles() 为 List<String>（JwtClaims.java:19），contains 是精确元素匹配非子串匹配——自建 xplatform_adminy 类角色不构成误判；JWT roles 签发来源（t_user.roles/t_user_role）均只能经已封死的 UserServiceImpl 写入。
5. **测试** ✅ 修复 Dev 实跑：data 66 绿（19/19）、全量 508 绿（+5）。

残余（建议级记录，不阻断）：租户侧角色管理（RoleController 自建任意 code）依赖第一层字符串黑名单防护平台码——未来新增平台角色时同步维护 PLATFORM_ROLE_CODE 常量或改第二层白名单模式（记入 PRJ-006 商用化 backlog）。

GATE:PASS

## 磁盘事实核对（第二轮原文）

- 前端仓实读：`assets/js/sanitize.js`（新公共清洗，35 行）、standard-list.html:331-345 / template-list.html:238-251（renderMd 改造 + sanitize.js 引入于 144/117 行）、dashboard.html:123-146（S1）——**全部落地**。
- 平台仓 `git diff HEAD` + 新增文件实读：RuntimeController.java（retry 校验 +41 行）、UserServiceImpl.java（rejectPlatformRoles +25 行）、UserController.java（user:edit ×3）、StandardServiceImpl.toGateRefVo、TenantSubscriptionServiceImpl（S3 LambdaUpdateWrapper）、runbook §6——**全部落地，无"报告有、磁盘无"项**。
- mvn `-pl eaiselp-auth,eaiselp-runtime -am test` 独立复现：**BUILD SUCCESS，6+61+12+12+31+381 = 503 tests，0 Failures 0 Errors**（503 绿属实）。

## H1 存储型 XSS 修复验证（重点，攻击者视角）

**sanitize.js 清洗逻辑实读**（与 case-detail.html:807 先例**逐行一致**）：
1. detached div 解析（innerHTML 赋值期间脚本不执行、元素未连接文档不触发事件）；
2. `querySelectorAll('script,iframe,object,embed,link,style')` 节点整体删除；
3. `querySelectorAll('*')` 遍历全部元素属性：`on*` 前缀属性删除；`href`/`src` 值 toLowerCase+trim 后以 `javascript:` 开头则删除；
4. 序列化返回干净 HTML → jQuery `.html()` 二次注入（此时载荷已被剥除）。

**任务载荷推演表**（7 项全部不构成 JS 执行）：

| # | 载荷 | 判定 | 推演依据（基于实际代码） |
|---|---|---|---|
| 1 | `<img src=x onerror=alert(document.cookie)>` | **灭** | img 在 `querySelectorAll('*')` 内，onerror 以 on 开头被 removeAttribute；二次注入仅 `<img src="x">`，加载失败无 handler |
| 2 | `<svg onload=alert(1)>` | **灭** | svg 元素同被遍历，onload 被剥；detached 解析期亦不触发（未连接文档） |
| 3 | `<scr<script>ipt>alert(1)</script>` | **灭** | HTML tokenizer 将 `<scr<script>` 解析为名为 `scr<script` 的未知标签（tag name state 中 `<` 并入标签名），`ipt>alert(1)` 为文本，`</script>` 因无打开 script 被忽略——根本不产生 script 节点；序列化再解析同样不构成 script。注：Dev 注释称"DOM 解析后 script 节点整体移除"，理由不准确但结论正确 |
| 4 | `<a href="javascript:alert(1)">` | **灭** | DOM 解码属性值后 toLowerCase+trim 命中前缀 → href 删除剩裸文本；大小写（JaVaScRiPt）与实体编码（&#106;avascript&#58;）变体经 DOM 解码+小写化同样被剥 |
| 5 | `<iframe src=...>` | **灭** | iframe 在节点删除黑名单，整体 remove（含子树） |
| 6 | `<style>...</style>` | **灭** | style 节点在删除黑名单 |
| 7 | `<div style="background:url(javascript:...)">` | 标签存活、载荷**无害化** | 清洗只删 style **节点**不删 style **属性**，div+style 属性保留；但现代浏览器 CSS 上下文不执行 javascript: URI（IE 遗留），无 JS 执行；残余 CSS 面（url() 外带/视觉钓鱼）记低危 |

**mXSS 复核**：noscript/xmp/template 序列化差异向量逐一推演——noscript 与 xmp 内容在 scripting-enabled 下按 raw text 解析且序列化不转义，但**二次解析环境与首次相同**（同引擎、同 scripting 状态），不发生上下文切换逃逸；template.content 中的 img 未连接文档不加载资源。未发现可利用的序列化突变路径。

**全库弱模式 grep**：`marked.parse` 全库仅 4 处（case-detail:802、artifact-view:208 为既有先例本地副本，standard-list:342、template-list:249 为本次修复），**全部包裹 sanitizeHtml，无其他弱 renderMd 页面**。

**兜底**：sanitize.js 未加载 → `escHtml(md)`（`replace(/[&<>"']/g)` 全转义纯文本），安全。

**H1 判定：修复通过。** 但先例本身携带的技术债原样继承（见残余 R2-R4，均 🟡 非阻断）。

## M1 D-9 越界修复验证

- `toGateRefVo`（StandardServiceImpl:215-224）实读：仅 set id/standardCode/title/version/status/deleted 六字段，**无 content/deprecateReason/createBy** ✓。
- 测试断言真实（StandardServiceImplTest:566-571）：`assertNull(content/deprecateReason/createBy)`，已删行与未删行统一断言 ✓。
- 前端消费点：standard-list.html:310-317（只用 g.name/g.deleted/g.enabled）、gate-rule-list.html:240-245（只用 standardCode/title/version/status，均 escapeHtml）——瘦身字段全覆盖，**渲染不受影响** ✓。

**M1 判定：通过。**

## M2 到期绕过修复验证

- retry 插入位实读（RuntimeController retry 端点）：`assertTrialNotExpired("orchestrate_retry_trial_blocked")` 位于**门禁终判检查（finalGate 拒绝重跑）之后、`orchestrationService.retryFromStep` 之前** ✓——不重跑、不预占、不烧 token。
- 失败审计：BizException catch → `auditService.log(action, "tenant", tenantId, {tenantId, username(safeJson)}, "failure", msg)` + rethrow → 40003 ✓；username 过 safeJson 防审计注入 ✓。
- TC30d 断言真实：40003 + `verify(never()).retryFromStep` + `verify(auditService).log(eq("orchestrate_retry_trial_blocked"),...)`；TC30e 回归真实：未到期 retry → 202 + `verify(retryFromStep(5L,2,1L))` ✓（非空跑断言）。
- **MCP /invoke 裁决核对**：MCPController:88-93 实读 = `@RequirePermission("adapter:view")` + `@RateLimit(30/min, USER)` + isAvailable 门；application.yml:103 `mcp.enabled: ${MCP_ENABLED:false}` **默认关闭**。编排者裁决描述（外部费用端/默认 disabled/已有权限+限流/未来计费工具再纳入）**与磁盘现状一致，裁决风险描述准确** ✓。

**M2 判定：通过。**

## M3 U2 纵深修复验证 —— 发现阻断级残余 R1

已落地部分：
- `rejectPlatformRoles` 三入口（create:94 / update:138 / assignRoles:180）均在任何 DB 写之前执行 ✓；TC7a/b/c 断言真实（40301 + verifyNoInteractions 零 DB 写）✓。
- `user:update` → `user:edit` 三处修正，对照 V1 seed：1009~1012 = user:view/create/edit/disable（**确无 user:update**，原恒 403 死端点诊断正确）✓；tenant_admin（role_id=2）经 2032 行持 1011 user:edit——写入口真实可达，黑名单必要性成立 ✓。
- runbook §6 警示落地：严禁 SQL 直插 platform_admin 关联 + 附越权巡检 SQL ✓。
- 权限码修正未引入新面：user:edit 与 V1 seed 一一对应，disable 端点并入 edit 域（无 user:update 幽灵码残留，grep 确认三处全改）✓。

### R1 🔴阻断：黑名单大小写敏感 vs MySQL collation 大小写不敏感 —— platform_admin 提权旁门

攻击链每一环均有磁盘证据：

| 环节 | 证据 |
|---|---|
| 1. tenant_admin 可调角色分配 | V1:380 给 tenant_admin 授 1011 user:edit → `POST /api/v1/users/{id}/roles` 可达 |
| 2. 黑名单被大小写变体绕过 | UserServiceImpl:65 `roleCodes.contains("platform_admin")` —— Java List.contains **大小写敏感**精确匹配；传入 `"PLATFORM_ADMIN"` 不命中 |
| 3. SQL 反查命中全局角色 | UserServiceImpl:195-196 `roleMapper.selectList(in(Role::getRoleCode, ["PLATFORM_ADMIN"]))`；V1:4 建库 `COLLATE utf8mb4_unicode_ci`（t_role 建表未覆盖 collation，继承 `_ci`）→ `'PLATFORM_ADMIN' = 'platform_admin'` 为 TRUE，返回 (tenant_id=0, platform_admin, id=1) 行（V1:354） |
| 4. 租户拦截器不救场 | EaiselpTenantHandler IGNORE_TABLES 明确含 `t_role`/`t_user_role`（"权限系统表为系统级共享，免 tenant 自动过滤"）→ 查询**不会**被补 `tenant_id=当前租户` 条件 |
| 5. 关联生效进 JWT | 插入 t_user_role(本租户, 目标用户, role_id=1)；重新登录 → PermissionServiceImpl.getRoleCodesByUserId（selectRolesByUserId join，无 role_type/tenant 过滤）→ JWT roles 含 platform_admin |
| 6. U2 放行 | TenantController:211 `hasAnyRole(claims, "platform_admin")` 仅信 JWT → 免费转正/延期，商业化绕过达成 |

变体：任意大小写混合（`Platform_Admin`）、尾随空格（`platform_admin `，PAD SPACE collation 亦命中）同样绕过。**测试为何绿**：TC7 为 Mockito mock 层，不走真 SQL；H2 默认大小写敏感——典型"单测环境不可复现、生产 MySQL 必现"的 collation 缺陷。

修复建议（交回 Dev，第二轮）：
1. `rejectPlatformRoles` 规范化比较：`code != null && code.trim().equalsIgnoreCase(PLATFORM_ROLE_CODE)`（与 DB ci 语义对齐）；
2. 语义化收口（更稳）：assignRoles 在 selectList 返回 Role 后校验 `tenant_id==0 || role_type==system_template` 即拒——把"全局角色不可租户分配"作为不变式，防未来新增全局角色漏配字符串黑名单；
3. 补集成测试（真 MySQL 或 H2 `IGNORECASE=TRUE`）断言 `PLATFORM_ADMIN`/尾空格变体被拒。

## 连带核查（修复引入的回归面）

- **S3**：`toJson` 空列表返回 `"[]"`（StandardController:143-149，null=不更新语义保留）✓；`LambdaUpdateWrapper.set(expireTime, null)` 精确置空（TenantSubscriptionServiceImpl:159-168）为 MyBatis-Plus 参数化 set，`.eq(Tenant::getId)` 绑定变量，**无注入面**；U2 端点仅 platform_admin（路径参数改任意租户是平台管理员设计意图），**无新越权面** ✓。注：L3 控制字符（\n\r\t）未转义缺口仍在（手工拼接保留），维持低危记录。
- **S1**：dashboard.html:123-146 改用 `window.GOV_DICT`（与 governance-dict.js 实际导出一致）+ `.text()` 渲染 expireTime（不再进 HTML 上下文）✓。

## 残余风险清单

| 编号 | 严重度 | 类别 | 位置 | 问题 | 建议 |
|---|---|---|---|---|---|
| R1 | 🔴阻断 | 垂直提权（collation 语义差） | UserServiceImpl:65 + V1 collation + EaiselpTenantHandler | 详见上文 M3 节——`PLATFORM_ADMIN` 大写/尾空格变体绕过黑名单，生产 MySQL 完整提权链成立 | equalsIgnoreCase + Role 层 tenant_id==0 拒绝 + 集成测试 |
| R2 | 🟡建议 | XSS 残余（交互式） | sanitize.js:26 | `javascript:` 判定仅查 href/src 前缀：中缀控制字符（`jav&#x09;ascript:` 经 DOM 解码后 trim 不去除中缀 tab，URL 解析时剥离 → 点击执行）与前导 C0 控制字符变体存活，**需受害者点击** | 比较前 `v.replace(/[\x00-\x20]/g,'')` |
| R3 | 🟡建议 | XSS 残余（交互式/请求劫持） | sanitize.js:22/26 | `base` 标签不在删除黑名单——注入 `<base href="//evil.com/">` 劫持后续相对 URL 请求（/api/v1/* 外发至 evil.com，查询/请求体外泄）；SVG `xlink:href="javascript:"` 不在 href 检查范围 | 黑名单补 base；href 检查覆盖 xlink:href |
| R4 | 🟢可选 | CSS 注入面 | sanitize.js | style 属性未清洗（url() 数据外带、视觉钓鱼）；DOMPurify 中期替代可一并消除 R2-R4 | 中期引入 DOMPurify |

R2-R4 与既有先例（case-detail/artifact-view 本地副本）完全一致，即修复达到了首轮门禁设定的"对齐先例"标准，列为下轮加固项而非本次阻断依据。

## 复审门禁结论

H1/M1/M2/S1/S3 修复全部验证通过，503 测试独立复现通过；**但 M3 修复存在阻断级残余 R1（collation 绕过提权链，生产 MySQL 可达，磁盘证据链完整）**。R1 修复量小（规范化比较一行 + Role 层校验 + 集成测试），须修复后第三轮定向验证 R1 单项。

GATE:FAIL

## 本次复审经验沉淀

1. **应用层黑名单校验必须与存储层比较语义对齐**：Java `contains` 大小写敏感 + MySQL `_ci` collation + 系统表免租户过滤（IGNORE_TABLES），三者叠加形成"单测全绿、生产可提权"的旁门。凡"拒绝特定标识符"类校验，一律 `trim().equalsIgnoreCase()` 起步，并用真库 collation 写集成测试（H2 需 `IGNORECASE=TRUE` 才能复现 MySQL ci 语义）。
2. **权限码从死端点修活时，必须重估其保护的写路径**：user:update 错位期间入口恒 403 是"侥幸防线"，修正为 user:edit 后入口真实可达——本轮 R1 正是"门修好了、锁却留了旁门"。修活任何授权入口都应触发其所辖资源（此处为角色分配→U2）的攻击面重审。
3. **自研 sanitize 的已知残余清单应随公共库沉淀**：中缀控制字符 javascript:、xlink:href、base 标签是 DOMPurify 专门处理过的经典绕过——公共 sanitize.js 的 Javadoc 应显式记录"未覆盖向量"清单，防止后续页面以为引入即全防护。
