# PRD — eAISEDP M2 Phase 1：让平台"能登录、能看见"

| 字段 | 值 |
|---|---|
| Case | case-20260723-m2-phase1-web-auth |
| 文档版本 | v1.0 |
| 产出日期 | 2026-07-23 |
| 产出者 | team-po（L1 产品经理）|
| 触发 | L1 编排者派发——M2 Phase 1 dogfooding 落地（SP-1 前端 + SP-2 auth 前段 + RBAC 地基）|
| 上游输入 | 企业架构蓝图 v1.0 / M2 项目群计划 v1.0 / schema.sql / application.yml |
| 下游传导 | team-se（技术方案）/ team-ba（任务拆解）/ team-dev（实施）/ team-qa（验收）|
| 权威性 | 本 PRD 是 M2 Phase 1 范围内 PO/UX/BA/Dev/QA 的共同契约，验收标准即 QA 用例基线 |
| 状态 | 待评审 |

---

## 0. 阅读指引

| 读者 | 必读章节 | 怎么用 |
|---|---|---|
| **team-ux** | §4 页面规格（登录页 + 主框架线框图 + 状态）| 据线框图出视觉稿/交互细节 |
| **team-se** | §3 功能点清单 + §5 API 契约 + §6 权限矩阵 + §7 验收标准 | 据契约出技术方案、接口设计、拦截器设计 |
| **team-ba** | §3 功能点清单 + §7 验收标准 | 据功能点和 AC 拆 case 任务 |
| **team-dev** | §5 API 契约 + §6 RBAC 建表/seed | 按 API 契约编码，按 seed 数据初始化 |
| **team-qa** | §7 验收标准（6 条 AC）| 直接照 AC 写测试用例 |

**前置约束（不可违背）**：
- 企业架构蓝图 §7 架构原则 P1-P14 全部生效。本 PRD 重点受约束项：**P6**（平台零角色硬编码——RBAC 权限码/角色码是配置化数据，不是流程硬编码，合规）/ **P11**（多租户隔离贯穿——所有业务查询带 tenant_id）/ **P13**（API 版本化 /api/v1/）/ **P3**（依赖单向无环）。
- 已锁定架构决策（编排者注入）：5 类人类角色 / AI 角色被调度不登录 / 权限三层可扩展（M2 预置 5 模板，M3 开自定义）/ 前端 HTML+JS+jQuery+Bootstrap / 多租户 tenant_id 隔离 / JWT 认证。

---

## 1. 背景与目标

### 1.1 业务背景

eAISEDP 是**承载一套 AI 多 Agent 软件工程体系（L3 战略→L2 项目群→L1 实施）端到端运作的运行时平台**（企业架构蓝图 §1.1）。M1 已完成"最小可行平台"——模块化单体跑通手调派生、GLM 接通、CapabilityLoader 热加载。

M1 的局限：**无 Web 界面、无登录认证、无权限控制**。用户只能通过 Postman 手调 `/api/runtime/derive`，任何请求无身份校验、无租户隔离强制。这让平台停留在"开发验证"阶段，无法交付真实用户使用。

M2 Phase 1 的使命：**让平台从"命令行可调"升级到"浏览器能登录、能看见"**——这是整个 M2 闭环（Web 工作台驱动 Case 全生命周期）的第一块基石，对应 M2 项目群计划 SP-1（前端工程）+ SP-2（auth 前段：登录/JWT）+ RBAC 地基（5 模板角色建表 seed）。

### 1.2 业务目标与成功指标

| 目标 | 衡量指标 | 验收口径 |
|---|---|---|
| **用户能在浏览器登录平台** | 登录页可访问、输入凭据拿到 JWT、跳转主框架 | AC-F1、AC-F2 |
| **登录态可恢复** | 刷新页面不丢登录态（GET /current 恢复）| AC-F2 |
| **不同角色看见不同菜单** | 5 角色登录后左侧导航项不同 | AC-F5 |
| **权限校验生效** | 无权限访问受保护资源返回 403 | AC-F3 |
| **多租户隔离** | A 租户用户看不到 B 租户数据 | AC-F4 |
| **前端路由保护** | 未登录访问主框架自动跳登录页 | AC-F6 |

### 1.3 产品定位（dogfooding 自身）

本 Phase 1 的第一个真实用户是**平台自己的开发团队**（dogfooding）。开发的 admin 账户（schema.sql 已 seed）将获得 tenant_admin 角色，作为 Phase 1 验收的主测试账户。后续每多一个真实角色用户（项目经理/工程师/高管），就多一份对 RBAC 模型的真实验证。

### 1.4 与 M2 整体的关系（Phase 切分）

| Phase | 范围 | 对应 SP | 里程碑 |
|---|---|---|---|
| **Phase 1（本 PRD）** | 能登录、能看见（前端骨架 + 登录 + JWT + RBAC 地基 + 主框架）| SP-1 前段 + SP-2 auth 前段 | 浏览器可登录、按角色看菜单 |
| Phase 2 | Case 闭环（Case 列表/详情/派生/检查点 UI）| SP-1 后段 + SP-3 状态机 | 端到端跑通一个 L1 case |
| Phase 3 | 过程资产结构化 + 模型路由 + 配额 | SP-4/SP-5/SP-6/SP-7 | 架构债清除 + 商用闭环 |

本 PRD 只锁定 Phase 1。Phase 2/3 另出 PRD。

---

## 2. 用户故事（INVEST 原则）

### US-1（登录）
- 作为「任意人类角色用户」，我希望「在浏览器输入用户名密码登录平台」，以便「安全地访问我有权限的功能」。
- 作为「任意人类角色用户」，我希望「登录失败时看到清晰的错误原因」，以便「快速纠正（输错密码 / 账户被禁用）而不是反复盲试」。
- 作为「任意人类角色用户」，我希望「刷新浏览器页面后仍保持登录态」，以便「不用每次刷新都重新输密码」。

### US-2（主框架）
- 作为「不同角色用户」，我希望「登录后看到的左侧导航按我的角色动态显示」，以便「只看到与我职责相关的功能入口，不被无关菜单干扰」。
- 作为「任意登录用户」，我希望「顶部栏显示我的用户名、角色和退出按钮」，以便「随时确认当前身份并安全退出」。
- 作为「任意登录用户」，我希望「点击导航项能在多 Tab 中打开页面」，以便「同时查看多个功能而不互相覆盖」。

### US-3（RBAC 与多租户）
- 作为「平台管理员」，我希望「平台预置的 5 个模板角色自动初始化」，以便「不用手工配置就能开始分配权限」。
- 作为「租户 A 的用户」，我希望「只能看到我自己租户的数据」，以便「本租户数据不被其他租户窥探」。
- 作为「无权限的用户」，我希望「访问无权限的接口时被明确拒绝（403）」，以便「权限边界清晰、安全可控」。

### US-4（前端工程）
- 作为「前端开发者」，我希望「eaiselp-web 目录结构清晰、API base-url 可配置」，以便「本地开发连 localhost、部署连生产 IP 都只改一处配置」。
- 作为「前端开发者」，我希望「有统一的 API 封装层自动带 Authorization header 并处理 401」，以便「不用每个 ajax 请求重复写鉴权逻辑」。

---

## 3. 功能点清单

| 编号 | 功能点 | 优先级 | 一句话说明 | 涉及模块 |
|---|---|---|---|---|
| **F1** | 前端工程初始化（eaiselp-web）| **P0** | 新建纯前端目录（HTML5+JS+jQuery3.7+Bootstrap5.3），规划目录结构、配置 API base-url | eaiselp-web（新建）|
| **F2** | 登录页（login.html）| **P0** | 用户名+密码+登录按钮，成功存 JWT 跳主框架，失败显示错误 | eaiselp-web/pages |
| **F3** | JWT 认证后端 API | **P0** | POST /login + GET /current + POST /logout，BCrypt 校验 + HS256 签发 | eaiselp-auth（落地）/ eaiselp-common（JWT 工具）|
| **F4** | RBAC 5 模板角色（建表 + seed）| **P0** | 新增 5 张权限表，seed 30 权限 + 5 模板角色 + 权限矩阵 + 给 admin 分配角色 | eaiselp-data（schema 增量）|
| **F5** | 主框架（index.html）| **P0** | 左侧导航（按角色动态）+ 顶部栏（用户/角色/退出）+ 右侧多 Tab 内容区 | eaiselp-web/pages |
| **F6** | 权限校验中间件 | **P0** | 后端 JWT 拦截器（解析→注入 TenantContext→校验 @RequiresPermission）+ 前端 API 封装（带 header + 401 跳登录）| eaiselp-runtime（拦截器）/ eaiselp-common（注解+上下文）/ eaiselp-web/api |

**优先级说明**：6 个功能点全部 P0——它们构成"能登录能看见"的最小闭环，缺任何一个都无法验收。Phase 1 不设 P1/P2 功能点（P1/P2 在 Phase 2/3）。

---

## 4. 页面规格

### 4.1 登录页（login.html）

#### 4.1.1 布局线框图（文字版 ASCII）

```
┌──────────────────────────────────────────────────────────────┐
│                                                                │
│                                                                │
│                      ┌──────────────┐                          │
│                      │  eAISEDP     │   ← logo（占位图/文字）  │
│                      │    Logo      │                          │
│                      └──────────────┘                          │
│                                                                │
│            企业级 AI 软件工程平台                              │
│            AI-Powered Software Engineering Platform            │
│                                                                │
│         ┌────────────────────────────────────────┐            │
│         │  👤  用户名                              │            │
│         ├────────────────────────────────────────┤            │
│         │  🔒  密码                            👁 │   ← 密码可见切换
│         ├────────────────────────────────────────┤            │
│         │              [  登 录  ]                │            │
│         └────────────────────────────────────────┘            │
│                                                                │
│              ⚠ [错误提示区]（默认隐藏）                        │
│                                                                │
│                                                                │
│   © 2026 eAISELP · v0.2.0-M2          [开发期 HTTP 提示]       │
└──────────────────────────────────────────────────────────────┘
```

#### 4.1.2 核心交互元素

| 元素 | 类型 | 行为 | 校验规则 |
|---|---|---|---|
| 用户名输入框 | text input | 必填，聚焦高亮 | 非空；长度 1-64 |
| 密码输入框 | password input | 必填，右侧眼睛图标切换明文/密文 | 非空；长度 1-128 |
| 登录按钮 | button | 点击触发提交；提交中显示"登录中..."并禁用按钮防重复点击 | 表单非空才可点击 |
| 错误提示区 | div（默认隐藏）| 登录失败时显示红色错误文案；3 秒后自动消失或用户操作后消失 | — |
| HTTPS 提示 | 文字 | 开发期 HTTP 显示"开发期 HTTP 传输，生产请启用 HTTPS"；生产 HTTPS 不显示 | 由配置控制 |

#### 4.1.3 状态定义

| 状态 | 触发条件 | 表现 |
|---|---|---|
| **默认态** | 页面加载 | 空表单，登录按钮可点（前端校验通过后）|
| **加载态** | 点击登录后、响应返回前 | 登录按钮文案变"登录中..."、disabled、输入框只读、显示 spinner |
| **成功态** | 返回 code=0 | 存 JWT 到 localStorage，1 秒内跳转 index.html |
| **错误态-凭据错误** | 返回 code=40001（用户名或密码错误）| 错误提示区显示"用户名或密码错误"，密码框清空、聚焦 |
| **错误态-账户禁用** | 返回 code=40002（账户已禁用）| 错误提示区显示"账户已被禁用，请联系管理员" |
| **错误态-网络异常** | 请求超时/网络断开 | 错误提示区显示"网络异常，请检查连接"，按钮恢复可点 |
| **错误态-服务异常** | 返回 code=50000 | 错误提示区显示"服务暂时不可用，请稍后重试" |

#### 4.1.4 多端差异（M2）

- **PC 端（M2 本期支持）**：居中卡片布局，最小宽度 1024px，推荐 1366×768 及以上。
- **移动端（M3 范围外）**：本期不做响应式适配，移动端访问登录页布局可能错乱（已知限制，Phase 1 不修）。

---

### 4.2 主框架（index.html）

#### 4.2.1 布局线框图（文字版 ASCII）

```
┌──────────────────────────────────────────────────────────────────────┐
│ [eAISELP]  企业级 AI 软件工程平台         admin ▾  [角色徽章] ｜ 退出  │ ← 顶部栏 (60px)
├────────────┬─────────────────────────────────────────────────────────┤
│            │  [ 系统管理 × ] [ 租户管理 × ] [ + ]                      │ ← Tab 栏 (40px)
│            ├─────────────────────────────────────────────────────────┤
│ ▌系统管理  │                                                           │
│  租户管理  │                                                           │
│  模型路由  │                                                           │
│  适配器配置│              ┌─────────────────────────────┐             │
│  系统监控  │              │                               │             │
│            │              │      ⚙ 建设中                  │             │
│            │              │                               │             │
│            │              │   该功能将在后续版本提供        │             │
│            │              │                               │             │
│            │              └─────────────────────────────┘             │
│            │                                                           │
│  ────────  │                                                           │
│  退出登录  │                                                           │
└────────────┴─────────────────────────────────────────────────────────┘
   左侧导航        右侧多 Tab 内容区
   (220px)            (flex-1)
```

#### 4.2.2 核心交互元素

| 元素 | 类型 | 行为 |
|---|---|---|
| 顶部 logo + 标题 | 文字/图 | 点击回到首页 Tab |
| 用户名下拉 | dropdown | 点击展开用户菜单（显示完整角色列表、显示名、所属租户）|
| 角色徽章 | badge | 显示主角色（多角色显示第一个或"多角色"）；hover 显示全部角色 tooltip |
| 退出按钮 | button | 点击清 localStorage + 跳 login.html |
| 左侧导航项 | nav-link | 按当前用户角色动态渲染；点击在右侧打开新 Tab 或激活已开 Tab |
| Tab 栏 | tab-group | 已打开的页面以 Tab 形式排列；点 × 关闭 Tab；点 + 无（占位）|
| 内容区 | iframe/div | 当前激活 Tab 的内容；Phase 1 统一显示"建设中"占位页 |
| 左侧底部"退出登录" | nav-link | 与顶部退出等价，移动友好备用入口 |

#### 4.2.3 左侧导航按角色动态渲染（核心规则）

导航项数据**由后端返回**（GET /current 返回 user.menus，或前端按 user.roles 映射）。M2 Phase 1 采用**前端按 roles 映射**（简单可控），M3 改后端配置化。

| 角色 | 导航项（Phase 1 全部占位"建设中"）|
|---|---|
| platform_admin（平台管理员）| 系统管理 / 租户管理 / 模型路由 / 适配器配置 / 系统监控 |
| tenant_admin（企业管理员）| 用户管理 / 角色管理 / 项目群看板 / 工程标准 / 配额 |
| project_manager（项目经理）| Case 看板 / 派生进度 / 检查点审批 / 产物查看 |
| engineer（工程师）| 待办审查 / Case 详情 / 产物查看 / 我的任务 |
| executive（高管）| 战略看板 / 投资概览 / 风险矩阵 / 里程碑 / 效能度量 |

**多角色用户**：导航项取**所有角色的并集**。例如 admin 同时是 tenant_admin + ea + pgm + orchestrator（schema.sql seed），导航显示 tenant_admin 的菜单（其余 ea/pgm/orchestrator 是体系 AI 角色对应人类代理，不直接映射平台导航；具体映射规则见开放问题 Q-3）。

#### 4.2.4 状态定义

| 状态 | 触发条件 | 表现 |
|---|---|---|
| **加载态** | 进入 index.html 后调 GET /current 恢复登录态期间 | 全屏 spinner + "正在加载..." |
| **正常态** | /current 返回成功 | 渲染顶部栏 + 左侧导航（按角色）+ 默认激活第一个导航项 Tab |
| **未登录态** | /current 返回 401 | 清 localStorage，跳 login.html |
| **建设中占位** | 点击任意导航项 | 右侧内容区显示统一占位组件（图标 + "建设中" + "该功能将在后续版本提供"）|
| **退出确认** | 点击退出 | 弹确认框"确认退出登录？" → 确认则清 localStorage 跳 login.html |

#### 4.2.5 多端差异（M2）

- **PC 端（M2 本期支持）**：固定左侧导航 220px + 顶部 60px + Tab 栏 40px + 内容区 flex-1。最小宽度 1024px。
- **移动端（M3 范围外）**：左侧导航不折叠，移动端不适配（已知限制）。

---

## 5. API 契约（给 SE 和 Dev 直接用）

> 所有 API 走 `/api/v1/auth/**` 前缀（P13 版本化）。开发期前端直连 `http://localhost:8081`（runtime 端口，见 application.yml）。gateway 完整落地后（SP-2 后段），改走 gateway 端口；本期前端 base-url 可配置切换。

### 5.1 公共约定

#### 5.1.1 统一响应结构

```json
{
  "code": 0,           // 0=成功；非 0=业务错误码（见 5.1.3）
  "message": "success", // 人类可读消息（中文）
  "data": { ... }       // 业务数据，成功时必有；失败时可为 null
}
```

#### 5.1.2 鉴权约定

| API | 是否需 Authorization header |
|---|---|
| POST /api/v1/auth/login | 否（白名单）|
| GET /api/v1/auth/current | 是（Bearer {token}）|
| POST /api/v1/auth/logout | 是（Bearer {token}）|
| 其他所有业务 API（Phase 2+）| 是 |

Authorization header 格式：`Authorization: Bearer {jwt_token}`

#### 5.1.3 统一错误码表（Phase 1 范围）

| code | HTTP status | 含义 | 触发场景 |
|---|---|---|---|
| 0 | 200 | 成功 | 正常响应 |
| 40001 | 400 | 用户名或密码错误 | login 校验失败（用户不存在或密码不匹配）|
| 40002 | 400 | 账户已禁用 | login 时 t_user.status='disabled' |
| 40101 | 401 | 未登录或 token 缺失 | 请求未带 Authorization header |
| 40102 | 401 | token 无效或已过期 | JWT 解析失败 / 签名错误 / exp 过期 |
| 40301 | 403 | 无权限访问该资源 | @RequiresPermission 校验失败 |
| 42901 | 429 | 请求过于频繁 | （预留，gateway 限流，Phase 1 暂不触发）|
| 50000 | 500 | 服务内部错误 | 未捕获异常 |

**安全约定**：40001（用户名或密码错误）**故意不区分**"用户不存在"与"密码错误"，防止用户名枚举攻击。

---

### 5.2 POST /api/v1/auth/login（用户登录）

**用途**：用户名密码换 JWT token。

**请求**：
```
POST /api/v1/auth/login
Content-Type: application/json
```
```json
{
  "username": "admin",      // string, 必填, 1-64 字符
  "password": "xxx"         // string, 必填, 1-128 字符（明文，由 HTTPS 保障传输安全）
}
```

**校验规则**：
- username：非空，长度 1-64；空则返回 code=40001（不暴露字段缺失 vs 凭据错误）
- password：非空，长度 1-128；空则返回 code=40001

**处理逻辑**：
1. 按 (tenant_id, username) 查 t_user —— **M2 Phase 1 单租户 dogfooding**，tenant_id 取 dogfooding 默认租户（tenant_id=1）；多租户登录页选租户 M3 做。
2. 用户不存在 → 返回 code=40001。
3. BCrypt.matches(password, t_user.password) 校验密码 → 不匹配返回 code=40001。
4. t_user.status='disabled' → 返回 code=40002。
5. 全部通过 → 签发 JWT（payload 见 5.2.2）→ 更新 t_user.last_login_at → 返回 code=0 + token + user。

**响应（成功）**：
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9.eyJ1c2VySWQiOjEsInVzZXJuYW1lIjoiYWRtaW4iLCJ0ZW5hbnRJZCI6MSwidGVuYW50Tmlt...hN2s",
    "expiresIn": 86400,
    "user": {
      "id": 1,
      "username": "admin",
      "displayName": "平台管理员",
      "email": "admin@eaiselp.com",
      "tenantId": 1,
      "tenantName": "eAISEDP 平台开发(dogfooding)",
      "roles": ["tenant_admin"],
      "roleCodes": ["tenant_admin"],
      "permissions": ["user:view", "user:create", "role:view", "case:view", "quota:view", "..."],
      "avatar": null
    }
  }
}
```

**响应（失败）**：
```json
{
  "code": 40001,
  "message": "用户名或密码错误",
  "data": null
}
```

**状态码**：200（含业务错误码）/ 500（未捕获异常）

#### 5.2.1 JWT 规范（C-1 契约，SP-2 定义，本 Phase 1 落地）

| 字段 | 值 |
|---|---|
| 算法 | HS256 |
| 密钥 | yml 配置 `eaiselp.security.jwt.secret`（M2 开发期占位密钥，M3 接 Vault/KMS）|
| 有效期 | 24 小时（86400 秒），yml 可配 `eaiselp.security.jwt.expire-seconds` |
| 签发方 | `eaiselp-auth` |

**JWT Header**：
```json
{ "alg": "HS256", "typ": "JWT" }
```

**JWT Payload（claims）**：
```json
{
  "userId": 1,            // 用户 ID（long）
  "username": "admin",    // 用户名
  "displayName": "平台管理员",
  "tenantId": 1,          // 租户 ID（long）—— 多租户隔离核心字段
  "tenantCode": "eaiselp-self",
  "roles": ["tenant_admin"],         // 角色码数组
  "iat": 1753392000,      // 签发时间（秒）
  "exp": 1753478400       // 过期时间（秒）
}
```

> **注**：payload 不含 permissions（权限列表可能很长，避免 token 膨胀）；权限由 GET /current 实时查 t_role_permission 返回，或拦截器实时校验。

---

### 5.3 GET /api/v1/auth/current（获取当前登录用户）

**用途**：前端刷新 index.html 时调此接口恢复登录态、获取角色权限、渲染导航。

**请求**：
```
GET /api/v1/auth/current
Authorization: Bearer {token}
```

**处理逻辑**：
1. 拦截器解析 JWT → 取 userId / tenantId / roles → 注入 TenantContext。
2. 查 t_user（带 tenant_id 隔离）+ 关联 t_user_role → t_role → t_role_permission → t_permission 汇总 permissions。
3. 返回与 login 一致的 user 结构。

**响应（成功）**：与 login 的 data.user 结构一致（无 token 字段）。
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "id": 1,
    "username": "admin",
    "displayName": "平台管理员",
    "tenantId": 1,
    "tenantName": "eAISEDP 平台开发(dogfooding)",
    "roles": ["tenant_admin"],
    "roleCodes": ["tenant_admin"],
    "permissions": ["user:view", "user:create", "..."],
    "avatar": null
  }
}
```

**响应（失败）**：未带 token / token 无效 → code=40101 或 40102，HTTP 401。

---

### 5.4 POST /api/v1/auth/logout（退出登录）

**用途**：前端清 localStorage 即可。后端本接口为**可选语义接口**（预留 M3 token 黑名单）。

**请求**：
```
POST /api/v1/auth/logout
Authorization: Bearer {token}
```

**处理逻辑（M2 Phase 1）**：
- JWT 无状态，后端**不维护黑名单**（M3 做）。本接口仅记录退出日志（log.info）+ 返回成功。
- 真正的退出动作由**前端清 localStorage** 完成。

**响应**：
```json
{ "code": 0, "message": "success", "data": null }
```

---

### 5.5 API 契约汇总表

| # | Method | Path | 鉴权 | 用途 | 核心入参 | 核心出参 |
|---|---|---|---|---|---|---|
| 1 | POST | /api/v1/auth/login | 否 | 登录 | username, password | token, user{roles,permissions,tenantId} |
| 2 | GET | /api/v1/auth/current | 是 | 恢复登录态 | — | user{roles,permissions} |
| 3 | POST | /api/v1/auth/logout | 是 | 退出（前端清 storage 为主）| — | — |

**接口数**：3 个（1 公开 + 2 鉴权）。

---

## 6. RBAC 权限矩阵与建表设计

### 6.1 权限三层模型（架构蓝图锁定，可扩展）

```
┌─────────────────────────────────────────────────────┐
│  第一层：权限原子（t_permission）                     │
│  30 条 seed，按 module 分组                          │
│  形如：user:create / tenant:disable / case:derive    │
└───────────────────────┬─────────────────────────────┘
                        │ N:N
┌───────────────────────▼─────────────────────────────┐
│  第二层：角色（t_role + t_role_permission）           │
│  M2 预置 5 模板角色（system_template，不可删）        │
│  M3 开自定义（tenant_admin 可创建租户内角色）         │
└───────────────────────┬─────────────────────────────┘
                        │ N:N
┌───────────────────────▼─────────────────────────────┐
│  第三层：用户-角色关联（t_user_role）                 │
│  一个用户可有多角色（取权限并集）                     │
│  + 数据范围（data scope）：M2 简化为 tenant_id 隔离  │
│    M3 扩展（全部/本部门/本人）                        │
└─────────────────────────────────────────────────────┘
```

### 6.2 新增 5 张权限表

#### 6.2.1 t_permission（权限原子表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 主键 |
| permission_code | VARCHAR(64) UNIQUE | 权限码，如 `user:create` |
| permission_name | VARCHAR(128) | 中文名，如"创建用户" |
| module | VARCHAR(32) | 模块：user/tenant/system/model/adapter/program/case/artifact/strategy/quota/role |
| resource_type | VARCHAR(32) | 资源类型（如 user/tenant/case），数据范围控制用 |
| action | VARCHAR(32) | 动作：view/create/edit/delete/disable/derive/confirm/download |
| description | VARCHAR(500) | 描述 |
| create_time / update_time / is_deleted | — | 标准审计字段 |

#### 6.2.2 t_role（角色定义表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 主键 |
| tenant_id | BIGINT | 租户 ID；platform_admin=0（系统级），租户角色=租户 ID |
| role_code | VARCHAR(64) | 角色码，如 `platform_admin` |
| role_name | VARCHAR(128) | 中文名，如"平台管理员" |
| role_type | VARCHAR(16) | system_template（M2 预置）/ custom（M3 自定义）|
| data_scope | VARCHAR(16) | 数据范围：all（全部）/ tenant（本租户）/ self（本人）；M2 用 all/tenant |
| is_builtin | TINYINT | 1=系统预置不可删，0=可删 |
| description | VARCHAR(500) | 描述 |
| 标准审计字段 | — | — |

**唯一约束**：`uk_tenant_rolecode (tenant_id, role_code)`

#### 6.2.3 t_role_permission（角色-权限关联表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 主键 |
| role_id | BIGINT | 角色 ID |
| permission_id | BIGINT | 权限 ID |
| create_time | — | — |

**唯一约束**：`uk_role_perm (role_id, permission_id)`

#### 6.2.4 t_user_role（用户-角色关联表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 主键 |
| tenant_id | BIGINT | 租户 ID（隔离用）|
| user_id | BIGINT | 用户 ID |
| role_id | BIGINT | 角色 ID |
| create_time / create_by | — | — |

**唯一约束**：`uk_user_role (user_id, role_id)`

> **与 t_user.roles 字段的关系**：t_user 已有 `roles VARCHAR(512)` 字段（逗号分隔字符串，如 'tenant_admin,ea,pgm'）。新增 t_user_role 作为**权威关联源**。t_user.roles 保留作为**冗余快速读取字段**，由应用层在分配角色时同步更新；M3 评估废弃。开放问题 Q-1。

#### 6.2.5 t_service_account（AI 服务账号表，预留 M4）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 主键 |
| tenant_id | BIGINT | 租户 ID |
| account_code | VARCHAR(64) | 账号码，如 `team-po` / `derivation-engine` |
| account_name | VARCHAR(128) | 名称 |
| account_type | VARCHAR(32) | role_agent（体系角色）/ system_service（系统服务）|
| api_key | VARCHAR(256) | AI 角色调平台 API 的密钥（M4 用）|
| allowed_roles | JSON | 允许扮演的角色码数组 |
| status | VARCHAR(16) | active/disabled |
| expire_time | DATETIME | 过期时间 |
| 标准审计字段 | — | — |

**M2 Phase 1 只建表不 seed**（M4 AI 角色登录平台时启用）。

### 6.3 权限原子清单（30 条 seed）

按 module 分组：

| module | permission_code | permission_name | 适用角色（概览）|
|---|---|---|---|
| system | system:config:view | 系统配置查看 | platform_admin |
| system | system:config:edit | 系统配置编辑 | platform_admin |
| system | system:monitor:view | 系统监控查看 | platform_admin |
| system | system:log:view | 系统日志查看 | platform_admin |
| tenant | tenant:view | 租户查看 | platform_admin |
| tenant | tenant:create | 租户创建 | platform_admin |
| tenant | tenant:edit | 租户编辑 | platform_admin |
| tenant | tenant:disable | 租户禁用 | platform_admin |
| user | user:view | 用户查看 | platform_admin, tenant_admin |
| user | user:create | 用户创建 | platform_admin, tenant_admin |
| user | user:edit | 用户编辑 | platform_admin, tenant_admin |
| user | user:disable | 用户禁用 | platform_admin, tenant_admin |
| user | user:reset-password | 重置用户密码 | platform_admin, tenant_admin |
| role | role:view | 角色查看 | platform_admin, tenant_admin |
| role | role:create | 角色创建 | （M3 解锁，Phase 1 seed 但无角色授予）|
| role | role:edit | 角色编辑 | （M3 解锁）|
| model | model:routing:view | 模型路由查看 | platform_admin |
| model | model:routing:edit | 模型路由编辑 | platform_admin |
| adapter | adapter:config:view | 适配器配置查看 | platform_admin |
| adapter | adapter:config:edit | 适配器配置编辑 | platform_admin |
| program | program:view | 项目群查看 | tenant_admin, project_manager |
| program | program:create | 项目群创建 | tenant_admin |
| case | case:view | Case 查看 | tenant_admin, project_manager, engineer |
| case | case:create | Case 创建 | tenant_admin, project_manager |
| case | case:derive | Case 派生 | project_manager, engineer |
| case | case:checkpoint:confirm | 检查点确认 | project_manager |
| artifact | artifact:view | 产物查看 | project_manager, engineer, executive |
| artifact | artifact:download | 产物下载 | project_manager, engineer |
| strategy | strategy:view | 战略看板查看 | executive |
| quota | quota:view | 配额查看 | platform_admin, tenant_admin |
| quota | quota:edit | 配额编辑 | platform_admin |

**合计**：31 条（含 M3 预留的 role:create/role:edit，Phase 1 seed 但暂不授予任何角色）。与编排者要求的"~30 条"一致。

### 6.4 5 模板角色 × 权限矩阵

> ✓=有权限，空=无权限。tenant_admin 的权限**限本租户**（data_scope=tenant），platform_admin **全部**（data_scope=all）。

| permission_code | platform_admin | tenant_admin | project_manager | engineer | executive |
|---|---|---|---|---|---|
| system:config:view | ✓ | | | | |
| system:config:edit | ✓ | | | | |
| system:monitor:view | ✓ | | | | |
| system:log:view | ✓ | | | | |
| tenant:view | ✓ | | | | |
| tenant:create | ✓ | | | | |
| tenant:edit | ✓ | | | | |
| tenant:disable | ✓ | | | | |
| user:view | ✓ | ✓(本租户) | | | |
| user:create | ✓ | ✓(本租户) | | | |
| user:edit | ✓ | ✓(本租户) | | | |
| user:disable | ✓ | ✓(本租户) | | | |
| user:reset-password | ✓ | ✓(本租户) | | | |
| role:view | ✓ | ✓ | | | |
| role:create | | | | | *(M3)* |
| role:edit | | | | | *(M3)* |
| model:routing:view | ✓ | | | | |
| model:routing:edit | ✓ | | | | |
| adapter:config:view | ✓ | | | | |
| adapter:config:edit | ✓ | | | | |
| program:view | ✓ | ✓ | ✓ | | |
| program:create | ✓ | ✓ | | | |
| case:view | ✓ | ✓ | ✓ | ✓ | |
| case:create | ✓ | ✓ | ✓ | | |
| case:derive | ✓ | ✓ | ✓ | ✓ | |
| case:checkpoint:confirm | ✓ | ✓ | ✓ | | |
| artifact:view | ✓ | ✓ | ✓ | ✓ | ✓ |
| artifact:download | ✓ | ✓ | ✓ | ✓ | |
| strategy:view | ✓ | | | | ✓ |
| quota:view | ✓ | ✓ | | | |
| quota:edit | ✓ | | | | |

**矩阵摘要**：
- platform_admin：22 项（系统/租户/用户/角色/模型/适配器/项目群/Case/产物/战略/配额 全部，data_scope=all）
- tenant_admin：15 项（用户管理本租户 + 角色 + 项目群 + Case + 产物 + 配额，data_scope=tenant）
- project_manager：7 项（项目群 + Case 全套 + 检查点 + 产物）
- engineer：4 项（Case 查看 + 派生 + 产物查看下载）
- executive：2 项（战略 + 产物查看）

### 6.5 seed 数据计划

1. **30+1 权限原子** → INSERT t_permission（按 §6.3 清单）。
2. **5 模板角色** → INSERT t_role（platform_admin tenant_id=0 data_scope=all；其余 4 个 tenant_id=0 data_scope=all/tenant，role_type=system_template, is_builtin=1）。
   - 注：模板角色 tenant_id=0 表示系统级预置，所有租户共享；分配给具体用户时通过 t_user_role 绑定到租户内用户。
3. **角色-权限关联** → INSERT t_role_permission（按 §6.4 矩阵 ✓）。
4. **给 dogfooding admin 分配角色** → INSERT t_user_role（user_id=1 → role_id=tenant_admin 的 ID）。同步 UPDATE t_user.roles='tenant_admin'（与现有 seed 一致）。
5. **t_service_account** → 不 seed（M4 用）。

---

## 7. 验收标准（Given-When-Then，给 QA 直接用）

### AC-F1：用户登录

**AC-F1.1 正确凭据登录成功**
- **Given** 数据库存在 dogfooding admin 用户（username=admin, status=active, BCrypt 密码哈希已 seed）
- **When** 在登录页输入用户名 `admin` 和正确密码，点击登录
- **Then** 返回 HTTP 200 + `{code:0}`，data.token 为合法 JWT（jwt.io 可解析，payload 含 userId=1/tenantId=1/roles=["tenant_admin"]/exp 在 24h 后）；前端将 token 存入 localStorage，1 秒内跳转 index.html

**AC-F1.2 错误密码登录失败**
- **Given** 同上
- **When** 输入用户名 `admin` 和错误密码 `wrongpwd`
- **Then** 返回 HTTP 200 + `{code:40001, message:"用户名或密码错误"}`；前端登录页显示红色错误提示"用户名或密码错误"，密码框清空并聚焦；localStorage 不写入 token

**AC-F1.3 不存在的用户登录失败**
- **Given** 数据库不存在 username=`nouser`
- **When** 输入 `nouser` + 任意密码
- **Then** 返回 `{code:40001}`（**与错误密码同样的 code，不区分用户不存在 vs 密码错**，防枚举）；前端提示"用户名或密码错误"

**AC-F1.4 禁用账户登录失败**
- **Given** 数据库存在用户 disabled_user（status='disabled'）
- **When** 输入正确用户名密码
- **Then** 返回 `{code:40002, message:"账户已被禁用"}`；前端提示"账户已被禁用，请联系管理员"

**AC-F1.5 登录成功后更新最后登录时间**
- **Given** 同 AC-F1.1
- **When** 登录成功
- **Then** t_user.last_login_at 被更新为当前时间（精度秒）

### AC-F2：JWT 签发与解析

**AC-F2.1 JWT 结构正确**
- **Given** 成功登录拿到 token
- **When** 用 jwt.io 解析 token
- **Then** header.alg="HS256"；payload 含 userId/username/displayName/tenantId/tenantCode/roles/iat/exp 六类字段；exp - iat = 86400（24h）

**AC-F2.2 刷新页面恢复登录态**
- **Given** 已登录（localStorage 有 token），在 index.html 按 F5 刷新
- **When** 前端调 GET /api/v1/auth/current（带 Authorization header）
- **Then** 返回 code=0 + user 完整信息（含 roles + permissions）；前端不跳登录页，保持登录态；导航按角色渲染

**AC-F2.3 token 过期失效**
- **Given** 一个 exp 已过的 JWT（手工构造或等待过期）
- **When** 带 token 调 GET /api/v1/auth/current
- **Then** 返回 HTTP 401 + `{code:40102, message:"token 无效或已过期"}`；前端拦截 401 自动清 localStorage 跳 login.html

### AC-F3：RBAC 权限校验

**AC-F3.1 有权限能访问**
- **Given** 以 tenant_admin 身份登录（持有 user:view 权限）
- **When** 调用一个标注 @RequiresPermission("user:view") 的接口（Phase 1 可用一个测试桩接口验证）
- **Then** 返回 HTTP 200 + 正常业务数据

**AC-F3.2 无权限被拒绝**
- **Given** 以 engineer 身份登录（不持有 tenant:view 权限）
- **When** 调用标注 @RequiresPermission("tenant:view") 的接口
- **Then** 返回 HTTP 403 + `{code:40301, message:"无权限访问该资源"}`

**AC-F3.3 多角色权限取并集**
- **Given** 用户同时有 tenant_admin + project_manager 两角色
- **When** 查询其 permissions
- **Then** permissions 为两角色权限的并集（无重复）

### AC-F4：多租户隔离

**AC-F4.1 跨租户数据不可见**
- **Given** 租户 A（tenant_id=1）有用户 userA，租户 B（tenant_id=2）有数据 dataB（如某 t_case 记录 tenant_id=2）
- **When** userA 登录后调任意列表查询接口（如 GET /api/v1/cases，Phase 1 用测试桩）
- **Then** 返回结果中**不含** dataB（MyBatis-Plus 拦截器自动注入 WHERE tenant_id=1）

**AC-F4.2 跨租户直接访问被拒**
- **Given** userA 登录拿到 token（payload tenantId=1）
- **When** 用 userA 的 token 尝试访问 dataB 的详情（GET /api/v1/cases/{dataB_id}）
- **Then** 返回 404 或 403（不返回 dataB 内容，P11 多租户隔离验证）

> **注**：Phase 1 单租户 dogfooding 场景下，AC-F4 可通过**手工 INSERT 第二个租户 + 用户**构造测试数据验证；多租户管理 UI 在 Phase 2/3。

### AC-F5：主框架按角色动态渲染菜单

**AC-F5.1 platform_admin 看到对应菜单**
- **Given** 以 platform_admin 登录（构造测试：手工给某用户分配 platform_admin 角色）
- **When** 进入 index.html
- **Then** 左侧导航显示 5 项：系统管理 / 租户管理 / 模型路由 / 适配器配置 / 系统监控

**AC-F5.2 engineer 看到对应菜单**
- **Given** 以 engineer 登录
- **When** 进入 index.html
- **Then** 左侧导航显示 4 项：待办审查 / Case 详情 / 产物查看 / 我的任务

**AC-F5.3 点击导航项打开 Tab 并显示占位**
- **Given** 已登录进入 index.html
- **When** 点击任意导航项
- **Then** 右侧内容区打开一个新 Tab（Tab 标题为导航项名）；内容区显示"建设中"占位组件（图标 + 文案）；重复点击同一导航项激活已开 Tab 而非新开

**AC-F5.4 退出登录清状态**
- **Given** 已登录在 index.html
- **When** 点击顶部"退出"或左侧底部"退出登录"，确认退出
- **Then** localStorage 的 token 被清除；页面跳转 login.html；浏览器后退键不能回到 index.html（未带 token 被拦截跳回 login）

### AC-F6：前端路由保护

**AC-F6.1 未登录访问主框架跳登录**
- **Given** 浏览器无 token（localStorage 清空）
- **When** 直接访问 index.html
- **Then** 前端检测无 token，立即跳转 login.html（不发 /current 请求或 /current 返回 401 后跳转）

**AC-F6.2 token 无效跳登录**
- **Given** localStorage 存了一个伪造/损坏的 token
- **When** 访问 index.html，前端调 GET /current
- **Then** 返回 401（code=40102），前端清 localStorage 跳 login.html

**AC-F6.3 任意 API 401 自动跳登录**
- **Given** 已登录但 token 在会话中途过期
- **When** 用户操作触发任意需鉴权 API 调用，返回 401
- **Then** 前端 API 封装层统一拦截 401，清 localStorage，跳 login.html（用户无需手动处理）

---

## 8. 非功能需求

### 8.1 性能
- 登录接口（POST /login）P95 响应时间 ≤ 800ms（含 BCrypt 校验，BCrypt cost=10 约 100ms）。
- GET /current P95 ≤ 200ms（含权限聚合查询）。
- 前端首屏（login.html）加载 ≤ 2s（本地）。

### 8.2 安全
- 密码传输：M2 开发期 HTTP 可接受，**代码必须预留 HTTPS 切换开关**（配置项 `eaiselp.security.force-https`，生产开启后强制 HTTPS）。
- 密码存储：BCrypt（t_user.password 已是 BCrypt 哈希，cost=10）。
- JWT 密钥：yml 配置，严禁明文写死（用 `${JWT_SECRET:dev-placeholder}` 环境变量），M3 接 Vault/KMS。
- 防用户名枚举：login 失败统一返回 code=40001（不区分用户不存在/密码错）。
- 防 token 泄露：token 存 localStorage（已知 XSS 风险，M2 接受；M3 评估改 httpOnly cookie）。
- 防 CSRF：JWT in header 模式天然免疫 CSRF（非 cookie 自动携带）。

### 8.3 兼容性
- 浏览器：Chrome 100+ / Edge 100+ / Firefox 100+（M2 主测 Chrome 最新版）。
- 不支持 IE。
- 分辨率：最小 1024×768，推荐 1366×768 及以上。
- 移动端：M2 不适配（M3 范围外）。

### 8.4 国际化
- **M2 Phase 1 只支持中文**（所有文案、错误消息中文）。代码预留 i18n 结构（文案集中在前端 `assets/js/i18n.js` 的 key-value 映射，后端 message 走 MessageBundle），M3 加英文。**开放问题 Q-2**：项目 CLAUDE.md 未注入，若项目方有"必须双语"约定需补充。

### 8.5 可观测
- 登录成功/失败打 log.info（含 username / tenantId / 成功失败 / 耗时 / 来源 IP）。
- 权限拒绝（403）打 log.warn（含 userId / permission_code / resource）。
- 401（token 无效）打 log.info（含 token 前缀 / 失败原因）。

### 8.6 可配置性
- API base-url（前端）：`assets/js/config.js` 一处配置，本地 `http://localhost:8081` / 部署机 `http://{IP}:8081`。
- JWT 密钥/有效期：yml `eaiselp.security.jwt.{secret,expire-seconds}`。
- HTTPS 强制开关：yml `eaiselp.security.force-https`。

---

## 9. 范围外（明确不做，防止镀金）

| # | 范围外项 | 推迟到 | 理由 |
|---|---|---|---|
| 1 | Case 详情页 / Case 列表页 / 派生触发 UI / 检查点 UI | Phase 2 | 本 Phase 只搭框架，Case 闭环是 Phase 2 核心 |
| 2 | 自定义角色管理 UI（创建/编辑/删除角色）| M3 | M2 只预置 5 模板；role:create/role:edit 权限 seed 但 UI 不做 |
| 3 | 用户管理 UI（增删改查用户、分配角色界面）| Phase 2 | M2 Phase 1 只做登录，用户管理 UI 是 Phase 2 tenant_admin 功能 |
| 4 | 移动端 / 响应式适配 | M3 | 本期固定 PC 端 1024px+ |
| 5 | 密码找回 / 修改密码 / 首次登录改密 | M3 | M2 只登录 |
| 6 | SSO / LDAP / OAuth2 第三方登录 | M4 | M2 用本地用户名密码 |
| 7 | 多租户登录页（选租户下拉）| M3 | M2 单租户 dogfooding，tenant_id 取默认租户 |
| 8 | Token 黑名单（服务端强制失效）| M3 | M2 JWT 无状态，退出靠前端清 storage |
| 9 | gateway 完整落地（路由/限流/熔断）| SP-2 后段（Phase 1 之后）| 本 Phase 1 前端可直连 runtime:8081；gateway 是 SP-2 后段工作 |
| 10 | 滑动验证码 / 防暴力破解锁定 | M3 | M2 单租户内部使用，风险可控 |
| 11 | 导航项后端配置化（菜单表 t_menu）| M3 | M2 前端按 roles 硬映射，简单可控 |
| 12 | 审计日志持久化（t_audit_log）| M3 | M2 只 log.info，不入库 |

---

## 10. 开放问题（待确认）

| # | 问题 | 影响范围 | 默认处理（未确认前按此执行）| 确认方 |
|---|---|---|---|---|
| **Q-1** | t_user.roles 字段（逗号分隔字符串）与新增 t_user_role 关联表如何共存？是否废弃 t_user.roles？| F4 数据模型 | 保留 t_user.roles 作为冗余快速读取字段，t_user_role 为权威源，应用层分配角色时双向同步；M3 评估废弃 t_user.roles | team-ea + team-steward |
| **Q-2** | 项目 CLAUDE.md 未注入，是否要求 Phase 1 必须中英双语？| 全局 i18n | 默认只中文，代码预留 i18n 结构；若项目方有双语约定需补 | 编排者/项目方 |
| **Q-3** | dogfooding admin 现有 roles='tenant_admin,ea,pgm,orchestrator'（ea/pgm/orchestrator 是体系 AI 角色对应的人类代理）。前端导航映射时，ea/pgm/orchestrator 是否映射到平台导航项？| F5 导航映射 | M2 只把 tenant_admin 映射到"用户管理/角色管理/..."导航；ea/pgm/orchestrator 暂不映射（它们是体系协作角色，不是平台功能角色）。admin 登录后只看到 tenant_admin 的菜单 | team-po + team-ea |
| **Q-4** | M2 Phase 1 前端是否需要走 gateway，还是直连 runtime:8081？| F1 前端配置 + F6 拦截 | 默认直连 runtime:8081（gateway 是 SP-2 后段）；前端 config.js 预留 gateway 切换开关 | team-ea + 编排者 |
| **Q-5** | JWT payload 是否需要 permissions 字段（避免每次 /current 查库）？| F3 JWT 设计 | 默认不放 permissions（防 token 膨胀），权限由 /current 实时查或拦截器实时校验 | team-se |
| **Q-6** | BCrypt cost 因子（t_user 现有哈希 cost=10）登录耗时约 100ms，是否接受？| 性能 | 接受（P95 ≤ 800ms 含此开销）| team-po |

---

## 11. 自检（ES-002 §1.3 强制）

### 11.1 产出落盘自检
- [x] 本 PRD 已 Write 落盘到 `D:\AI\mywork\platform\docs\需求文档\M2-Phase1-PRD.md`
- [x] Test-Path 自检：见向编排者汇报的绝对路径 + 字节数
- [x] 向编排者汇报绝对路径

### 11.2 决策基于已有资产的自检

| 决策 | 基于的已有资产 | 是否从头设计 |
|---|---|---|
| 6 功能点切分 | 编排者注入的 Phase 1 范围（功能点 1-6 明确给出）+ M2 项目群计划 SP-1/SP-2 章程 | 否，正式化编排者范围 |
| 5 角色 RBAC | 企业架构蓝图 §1.4（5 类人类角色）+ 编排者锁定决策 | 否，采纳已锁定决策 |
| 权限三层模型 | 编排者锁定决策（权限原子化+角色可自定义+数据范围）| 否，采纳 |
| t_user 登录字段 | schema.sql t_user 表（tenant_id/password/status/display_name/email 已有）| 否，复用现有字段 |
| BCrypt 密码校验 | schema.sql dogfooding admin 用户已 seed BCrypt 哈希（cost=10）| 否，沿用 |
| JWT 设计 | M2 项目群计划 C-1 契约（payload 含 tenant_id/user_id/roles，HS256）| 否，采纳 C-1 契约 |
| API 路径 /api/v1/ | 编排者要求 + 架构蓝图 P13（版本化）| 否，遵循 |
| runtime 端口 8081 | application.yml（server.port=8081）| 否，复用 |
| 多租户隔离 | 架构蓝图 P11 + schema.sql 所有表带 tenant_id + MyBatis-Plus 拦截器已存在 | 否，遵循 |
| 导航项按角色映射 | 编排者明确给出 5 角色各自导航项 | 否，采纳 |
| 范围外清单 | 编排者明确给出 6 项范围外 + 补充 Phase 切分 | 否，采纳+扩展 |

**结论**：所有决策基于编排者注入的锁定决策 + 企业架构蓝图 + M2 项目群计划 + schema.sql + application.yml。无凭空设计。权限矩阵 30 条权限原子和 5 角色映射是基于编排者导航项反推的最小完备集。

### 11.3 与上游一致性自检
- [x] 5 角色与企业架构蓝图 §1.4 一致
- [x] JWT 契约与 M2 项目群计划 C-1 一致（payload 字段对齐）
- [x] API 版本化遵循 P13
- [x] 多租户隔离遵循 P11
- [x] 零角色硬编码——RBAC 权限码/角色码是配置化数据（t_permission/t_role seed），不是流程硬编码，遵循 P6
- [x] 范围外项与编排者要求一致并扩展（Phase 切分细化）

---

## 本次经验沉淀

1. **PRD 的"基于已有资产"必须落到具体表字段和配置项，不能停留在概念复用**。本次读 schema.sql 发现 t_user 已有 password（BCrypt cost=10）/status/roles/last_login_at 字段，所以登录 API 直接复用、BCrypt 校验逻辑沿用、AC-F1.5 验收 last_login_at 更新才能写死；读 application.yml 发现 runtime 端口 8081，前端 base-url 才能写 `http://localhost:8081` 而非拍脑袋的 8080。教训：PRD 引用上游资产时，必须 Read 实际文件确认字段/端口/配置真实存在，不能凭文档记忆（架构蓝图也是读 schema.sql 才知道有这些字段）。否则 BA 拆任务、Dev 编码时会发现"PRD 说的字段不存在"返工。

2. **API 契约的错误码要"故意不区分"以防范安全风险，这条要写进 PRD 显式说明**。本次 login 接口 40001（用户名或密码错误）故意不区分"用户不存在"vs"密码错误"，防用户名枚举攻击。这种"看起来该细分却故意合并"的设计决策，如果不写进 PRD，Dev 可能会"好心"拆成两个 code（如 40001 用户不存在 / 40002 密码错），引入安全漏洞。教训：涉及安全的反直觉设计（合并错误码、不暴露内部状态、统一响应结构），PRD 必须显式标注理由，防止 Dev 按常规优化引入风险。

3. **多租户隔离的验收标准在"单租户 dogfooding"场景下容易写成空话，必须给出构造第二租户的测试方法**。本次 AC-F4（跨租户数据不可见）在 M2 单租户场景下天然无法触发（只有一个租户）。如果只写"跨租户不可见"，QA 无法执行。所以补充了"手工 INSERT 第二个租户 + 用户构造测试数据"的具体方法，让 AC 可测。教训：验收标准要考虑"当前环境是否天然具备触发条件"，不具备时必须给出构造方法（mock/造数据/手工 SQL），否则 AC 是死的标准。
