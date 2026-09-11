# 安全评审报告 — case-20260823-商用化（team-security 独立验证）

> 结论：**通过（GATE:PASS）**。0 阻断 / 2 建议 / 5 可选
> 评审人：team-security（独立验证，不采信自报）| 本报告由 Security 全文回复、编排者代落盘
> 基线：OWASP Top 10 + 通用基线 + 仓内既有安全先例（V7 防伪造、G13 tenant_id 服务端取值）

## 磁盘事实核对
- platform（未提交）：8 文件修改 + 新增全部与 Dev 报告一致——subscription 包 7 服务接口 + 5 impl + 6 DTO、runtime/subscription 三类、5 新 Controller、V8、测试目录；EaiselpTenantHandler IGNORE_TABLES 增 t_plan（t_invoice/t_incident 不进清单）。**一致**。
- web（未提交）：menu.js/governance-dict.js/README 修改 + 5 新页。**一致**（tenant-list 全新文件，"订阅变更"为新增能力）。
- mvn 复现：全量 **743 tests / 0 failures / 0 errors / 0 skipped**（66 套件聚合）。

## 威胁面核查结论（浓缩）

**1. 资金面 — 通过**
- 用量聚合 InvoiceMapper.sumSuccessTokens：`#{}` 全参数化、显式 tenant_id、两段 UNION 均 status='success'（失败/运行中不计，B 口径对齐）；t_derivation.cost 未进任何投影/API（内部成本与客户计价隔离成立）。
- 出账无金额入参：transit 仅收 target 白名单；generate 仅 period（YearMonth.parse 校验 400）+tenantId。金额服务端 BigDecimal 生成，DECIMAL(14,2)/(12,6) 域上限镜像（BillingCalculator:139 指名 400）。
- 负账单路径关闭：plan CRUD signum()<0→400；overage=max(0,…)、proration 参数域校验。
- 幂等/并发：uk_invoice_tenant_period + DuplicateKey 兜底；draft 才可覆盖、issued/paid 锁定；transit 条件更新 eq(status,current) 防并发双流转。
- 流转/补生成仅 PA：bill:manage（seed 1085 仅 role_id=1），PermissionInterceptor 服务端强制。
- 定时入口仅 @Scheduled（非 REST 可触发）；REST 补生成同 Service 入口同 PA 权限；防重入三层（CAS+单线程池 queue=0+uk）。

**2. 提权/越权 — 通过**
- 拦截链：RateLimit(0)→JWT(1,/api/** 全覆盖)→Permission(2)→LayerGuard(3)。LoginUser.set 用 JWT claims 覆盖 TenantContext——X-Tenant-Id 头伪造被覆写；仅 /api/v1/tenant/register 白名单，其 INSERT 显式携带新租户 id，头伪造无效。
- 权限种子核对（V8:217-252）：plan:view/create/edit + bill:view/manage 仅 PA；cost:view=PA+TA（engineer/executive 无 → Q5 成立）；incident:view 五角色、create/edit=PA/TA/PM。前端隐藏仅装饰，403 服务端兜底。
- U2 双参仍在 hasAnyRole("platform_admin") 门内；互斥校验+custom 岐义 40004。PlatformTenantController 显式 PA 校验，返回仅业务列（无联系人/LLM Key/内部配置）。
- IDOR：CostCenterController tenant_id 只取 JWT claims；detailForTenant 第二道归属校验 + draft 不可见；t_invoice/t_incident 拦截器自动隔离。
- t_plan 进 IGNORE_TABLES 边界：TA 无 plan:view 不能直读套餐目录；租户侧仅经 U1 订阅快照与费用中心套餐卡透出（无敏感列），边界成立。

**3. SYSTEM 通道 — 通过**：定时线程无登录态→TenantContext 缺省 0→拦截器全放行；InvoiceServiceImpl 全程显式 tenantId（实体 set/wrapper eq 逐行核过，无 ThreadLocal 依赖）；单租户失败不中断全量、进 failed 清单。

**4. 注入/XSS — 通过**：后端 SQL 全参数化；detail 快照走 ObjectMapper；RuntimeController 审计拼接处过 safeJson；五页动态渲染统一 escHtml/escAttr；period 前端 regex+type=month、后端 YearMonth.parse 双校验；transit target 取自固定 data-target。

**5. 信息泄露 — 通过**：engineer/executive 无 cost:view/bill:view；出参无内部成本列；错误信息指名字段不泄堆栈/SQL。

**6. 审计 — 基本完整**：plan_create/update（old→new）、套餐应用（五类快照+operator）、出账（scheduled/manual 区分）、bill_transit（from/to/operator/time）、incident create/update 均落 t_governance_log。

## 缺陷清单
| 编号 | 严重度 | 文件:行 | 问题 | 修复建议 |
|---|---|---|---|---|
| S1 | 🟡建议 | InvoiceServiceImpl:271-274 | bill_transit 审计在 DB 更新后写且异常吞掉——审计写失败时资金状态已变更无审计行 | 资金域审计改同步写（或失败告警） |
| S2 | 🟡建议 | TenantContextFilter:23 | TenantContext 可被 X-Tenant-Id 头设置，安全依赖 JWT 拦截器后置覆写（当前无洞）；新增白名单路径即成越权面 | 仅从 JWT 派生（删头解析）或校验头=claims |
| S3 | 🟢可选 | TenantController:128 | register 审计 JSON 拼接无转义（仅可读性，解析有兜底） | 换 ObjectMapper/safeJson |
| S4 | 🟢可选 | SubscriptionPlanServiceImpl:67 等 | keyword LIKE 未转义 % _ 通配符（量小影响可忽略） | like 前转义 |
| S5 | 🟢可选 | IncidentServiceImpl:158-163 | incident_update old 快照缺三字段旧值 | 补齐 |
| S6 | 🟢可选 | BillingCalculator:147 | requirePrice 仅判空不判负（入口已关闭） | 加 signum 拦截 |
| S7 | 🟢可选 | InvoiceController:66 等 | 裸码与 ResultCode 混用 | 统一常量 |

## 经验沉淀
1. MyBatis-Plus SYSTEM 通道惯例：定时任务跨租户安全完全取决于 Service 显式携带 tenantId——评审必须逐 wrapper/insert 核对，禁 ThreadLocal 断言。
2. X-Tenant-Id 头 + JWT 覆写模式：安全性系于"JWT 拦截器路径覆盖率=全部业务路径"隐式不变量；每新增白名单路径都在扩大攻击面，应登记为回归项。
3. 平台级表对租户的降维通道：权限裁剪除查种子外，还需追"平台数据经何只读接口流入租户侧"。

GATE:PASS
