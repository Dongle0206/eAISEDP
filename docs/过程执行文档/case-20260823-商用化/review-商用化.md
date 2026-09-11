# 代码评审报告 — case-20260823-商用化（订阅计划 + 计量出账 + SLA 轻量承诺）

> 评审人：team-reviewer（独立复现，不采信自报）| 日期：2026-09-10
> 结论：**不通过（GATE:FAIL）**——阻断缺陷 2 项，建议 6 项，可选 3 项
> 依据：PRD v1.0 + 编排者裁决 Q1~Q6 + SE 技术方案 v1.0（§0.3 消解表最高优先）+ tasks.md（差异定稿表 V8 字段名）+ api-contracts.md + CLAUDE.md（ES-001/002/003）

## 0. 磁盘事实核对（先行，最高优先）

- 两仓 `git status`/`git diff` 全量盘点，与编排者交付清单逐条比对：platform（tracked 8 文件改 + 新增 44 个主/测试文件）与 web（3 改 + 5 新页）**均真实落地**；data subscription 包 5 service + impl + 7 dto、entity/mapper 3+3、runtime subscription 3、TenantSubscriptionService M、IGNORE_TABLES/ResultCode/application-test.yml/schema-h2 diff 均在磁盘。
- 任务描述称"4 新 Controller"，磁盘实为 **5 个**（多 `CostCenterController.java`）——经查为 tasks.md T10 明确产物（非夹带），编排者浓缩描述漏列，非缺陷。
- 测试独立复现：`mvn -pl eaiselp-auth,eaiselp-runtime -am test` exit 0，**743 全绿 0 失败 0 跳过**（auth 12 + common 6 + data 122 + capability 12 + adapter 58 + runtime 533），与"data 122 / runtime 533"口径精确吻合。
- 反声明：本报告全部结论基于本人 `git diff` + Read + 独立执行测试；未采信任何 Dev 自报文字。

## 1. 编排者收尾两处复核（重点）

### 1.1 SubscriptionApplyService 外层 wrap（SubscriptionApplyService.java:83-94）

**判定：正确。**

- `BizException extends RuntimeException` 且存在 `(int, String)` 构造（BizException.java 磁盘核实）。
- `@Transactional(rollbackFor = Exception.class)`：catch `RuntimeException` 后转抛 `BizException(500)`，异常仍从代理方法抛出，TransactionInterceptor 按 rollbackFor 匹配回滚——**BizException 无论按 RuntimeException 还是 Exception 均命中，事务必回滚**，AC-F1.5"无半应用状态"语义成立。
- `catch (BizException e) { throw e; }` 重抛不受影响；40004/400/40400 业务异常原样透传。
- 佐证：`SubscriptionApplyRollbackTest` 全绿（H2 真回滚验证）。

### 1.2 RollbackTest 断言 Boolean.FALSE（SubscriptionApplyRollbackTest.java:81）

**判定：断言有效。**

- 测试方法**无 @Transactional**（非测试自身回滚假象）：`applyPlan` 事务由 TransactionProxy 管理，H2 真提交/真回滚。
- 构造链完整：真 t_tenant 行（trial/expire 非空/层开关 0）→ `@MockBean QuotaMapper` 独立上下文替换 → `selectOne→new Quota()` 走覆写分支 → `update` doThrow RuntimeException → wrap 转 BizException → `assertThrows(BizException)` → 五断言（edition/planCode/expire/t_quota COUNT=0/`assertEquals(Boolean.FALSE, strategyEnabled)`）。
- `assertEquals(Boolean.FALSE, Boolean)` 走 Object 等值：actual 为 null 时给出明确 FAIL 而非 NPE，优于 `assertFalse`。若事务未回滚，①②③④断言必失败——断言具备真实区分力。
- ⑤ 层开关断言因 ④ 先抛而未到达 ⑤，属"平凡保持旧值"——与 tasks.md T4 / SE §9.1-13 设计（doThrow 于 QuotaMapper）一致，非缺陷。
- `@BeforeAll` 显式 `TableInfoHelper.initTableInfo(Quota.class)`（@MockBean 替换后 lambda 缓存缺失的补位）处理正确。

## 2. 必查项逐条结论

| # | 必查项 | 结论 |
|---|---|---|
| 1 | git 盘点 + 743 绿复现 | ✅ 全部落地；743 全绿独立复现 |
| 2 | BillingCalculator 数值四例 + 恒等式 | ✅ 9,015.00 / 101.00 / 386.71（含当天）/ ceil 1,000 恰不进位 / 恰等 0——CalculatorTest 纯静态断言 + InvoiceServiceImplTest H2 落库断言双真实；恒等式 `total=(proration>0?proration:planPrice)+overage` 5 组构造 + 落库断言成立（base 恒 2 位，十进制结合律，数学正确） |
| 3 | 出账正确性 | ✅ B2 trial 无行（SELECT COUNT 断言）；B3 转正赠送 + fallback 双分支（审计判源 oldEdition/newEdition）；uk(tenant,period) 幂等三态（draft 覆盖/issued/paid 保护 + DuplicateKey 兜底转已存在分支）；定时 cron `0 0 2 1 * *` Asia/Shanghai + @ConditionalOnProperty 熔断 + CAS + billingExecutor(core/max=1/queue=0/AbortPolicy) 独立池 + 拒绝时回滚 CAS 占位；application-test.yml `scheduler-enabled: false` |
| 4 | U2 兼容 | ✅ 分叉点唯一（planMode 判空一处，planId 优先/planCode 判空）；存量路径零穿过（planCode/planId 均 null 时行为逐字节同旧）；互斥 400 / 全空走既有语义；U2 裸通道不动 plan_code（applyPlan 为唯一写点）；custom 多份 40004（SubscriptionApplyServiceTest 覆盖）；存量 TenantControllerTest 零改动（不在修改文件清单，743 绿含其全量） |
| 5 | 五类一事务 + 层开关 | ✅ H2 真回滚（见 §1.2）；层开关走 TenantLayerService.setLayerEnabled（缓存失效在事务内，D-1 关键依据）；t_quota 水位不动（Q4）；uk 并发 upsert 兜底 |
| 6 | 权限注解 vs seed / IGNORE_TABLES / 金额镜像 | ✅ 15 端点 @RequirePermission 与附 B 矩阵逐格一致；U2/平台租户走 claims 显式 PA；V8 seed 1081~1089 + 2203~2220 与 PRD §4.9 矩阵一致；IGNORE_TABLES 追加 t_plan（注释锚定 V8 头契约）；上限镜像取数学域 999,999,999,999.99 / 999,999.999999（D-6 定稿，V8 注释笔误已豁免）。**但 seed 3 条查询冒烟无自动化承载（T19 缺失，见 D2）** |
| 7 | 前端五页 | ✅ 全部引 sanitize.js + escHtml（14~26 处/页）、零裸 innerHTML；金额后端为准（入参 BigDecimal + 域校验；plan-list 前端 type=number/min=0/isNaN 先拦）；plan/invoice 仅 PA（菜单 ROLE_MENUS.platform_admin 专属挂载）；incident 写按钮页内 PA/TA/PM 角色隐藏；menu.js layerHidden 零改动（AC-G3） |
| 8 | 平台租户端点 | ✅ claims 显式 platform_admin 校验（U1 先例形态）+ 40101/40301；keyword 企业名/编码模糊 + 分页；只读零审计 |
| 9 | V8/schema-h2 注释规范 | ✅ 幂等惯例（IF NOT EXISTS/INSERT IGNORE/information_schema 动态加列/守卫回填）+ ID 顺延注释齐全；**但 t_plan 缺列（见 D1）** |

## 3. 缺陷清单

| 编号 | 严重度 | 文件:位置 | 问题 | 修复建议 |
|---|---|---|---|---|
| D1 | 🔴阻断 | eaiselp-runtime/src/main/resources/db/migration/V8__commercialization.sql（t_plan DDL，52-70 行）；schema-h2.sql 同缺口 | **V8 与 schema-h2 的 t_plan 均缺 `edition_override` 列**，而 Plan 实体（Plan.java:58）、契约 P2（custom 必填）、D-9 双向校验（SubscriptionPlanServiceImpl:281-295）、P4 编辑 UPDATE（:141 显式 set edition_override）、applyPlan custom 取 editionOverride（SubscriptionApplyService:115）全依赖该列。MyBatis-Plus SELECT 全列投影 → **生产部署 V8 后任何 t_plan 查询（P1/P3/U2 应用/40004 判定/出账④）即 Unknown column 500**。测试靠 H2BillingTables.ensure / InvoiceServiceImplTest.ensureTables 运行时 `ALTER TABLE t_plan ADD COLUMN IF NOT EXISTS edition_override` 补列掩盖缺口（注释自认"DBA 落盘缺口，已上报编排者，生产需 V8 修订或 V9 补列"）。tasks.md 差异定稿表 D-1~D-6 亦未收录此缺口（"逐列核对"声明与磁盘不符）。编排者"真库已验"与此矛盾：真库若按 V8 建表代码一跑即炸，若手工补列则新环境部署仍炸 | DBA 出 V8 修订或 `V9__plan_edition_override.sql`（information_schema 动态判断 ADD COLUMN，幂等）；schema-h2.sql 同步补列；移除两处测试侧补列 hack；差异定稿表补录该条 |
| D2 | 🔴阻断 | eaiselp-runtime/src/test/java/com/eaiselp/runtime/controller/（目录空缺） | **T19 测试收口任务未交付**：RBAC 反射断言矩阵（SE §9.1-18，16 端点逐格）、V8 幂等重放（锚点 19，权限 9/授权 18/套餐 seed 3 行数 + 重放不变）、跨租户与 403 矩阵（锚点 20）、AC-G3 不限层（锚点 21）——全测试树 grep `plan:view/bill:manage/incident:create/cost:view` 零命中。AC-G1/G2/G3/F1.9/F2.10/F3.4/F3.5 无自动化承载；tasks.md"27 AC 全覆盖"与磁盘不符。"seed 3 条查询冒烟"（QA 标注 4，编排者必查 6）亦无落盘 | 补交 T19 测试类（或由编排者明确改派 QA 并更新 tasks.md 留痕）；743 绿不含锚点 18~21，不能作为这些 AC 的门禁证据 |
| D3 | 🟡建议 | SubscriptionApplyService.java:240 | custom 岐义（"多份在架请用 planId"）复用 `ResultCode.MODEL_TIER_FORBIDDEN(40004)`——一个业务码承载两个语义（模型档位超范围 / 套餐岐义），客户端按码分支处理有歧义风险。编排者追加裁决背书了行为本身，但语义混用未在任何契约登记 | api-contracts.md 错误码表为 40004 登记双语义，或后续版本顺延独立码 |
| D4 | 🟡建议 | RuntimeController.java:175-178（orchestrate 挂点） | 编排模式仅校验显式 modelTier，未指定即完全不校验——编排内部按 orchestrator 路由的实际档位不经过 40004 enforcement，存在绕过面（与 D-10"派生入口"字面一致但弱于 /derive 全覆盖） | 登记为已知限制（编排内部路由处校验属后续增量），或在 OrchestrationService 角色路由处补挂点 |
| D5 | 🟡建议 | PlatformTenantController.java:36 | 分页参数名 `pageSize`，其余四 Controller 及契约 §0 通用约定均为 `size`——同一前端资产两种参数名 | 统一为 `size`（编排者追加端点尚未被前端大量引用，改动窗口仍在） |
| D6 | 🟡建议 | api-contracts.md §2 / GenerateSummaryVo | U2 入参表未收录 `planId`（编排者追加的双参分叉未回写契约）；GenerateSummaryVo 出参多 `skippedLocked` 字段（契约未记） | 契约文档补记两处（向前兼容超集，不影响已实现方） |
| D7 | 🟡建议 | SubscriptionApplyService.java:89-93 | wrap 转抛仅拼 message，原始异常堆栈丢失（log 无 error 记录）——生产排障只剩一句摘要 | wrap 内 `log.error("[Apply] 套餐应用失败（回滚）", e)` 后再转抛，或 BizException 携 cause |
| D8 | 🟢可选 | SubscriptionPlanServiceImpl.java:360-374 | 同 code 在架唯一为 selectCount+insert 非原子（并发双创建可同时过）——SE §3.1 已知接受（条件唯一无法用 uk），运营低频操作风险低 | 可加分布式锁或最终一致巡检；维持现状亦可 |
| D9 | 🟢可选 | InvoiceServiceImpl.java:81-86 | `tenants = one != null ? List.of(one) : List.of()` 赋值后紧接 throw，冗余分支 | 直接 `if (one == null) throw ...; tenants = List.of(one);` |
| D10 | 🟢可选 | 前端五页 | 内联样式硬编码 hex 颜色（7~14 处/页）——与既有页面/governance-dict 徽章先例一致，非违规 | 维持现状（项目前端无主题变量约束） |

## 4. 其他核对说明（无缺陷）

- **编排者收尾两处均正确**（详见 §1）。
- TenantController `@Autowired(required=false)` 字段注入打破构造器注入惯例——有明确权衡注释（AC-F1.8 存量测试 8 参构造零改动为合并门禁），planMode 分支在纯 Mockito 环境 null 防御由既有路径承载，可接受；建议后续随存量测试重构收口（未单列缺陷，并入技术债观察）。
- RuntimeController `resolveDeriveTier` 缺省 "sonnet" 与 DerivationEngine:46 同源一致（磁盘核实），无误拦。
- 信息隔离：InvoiceMapper 聚合 SQL 投影无 cost 列、buildDetailJson 零 cost 引用、InvoiceServiceImplTest 含"序列化无 cost 字段"断言。
- 金额精度：(12,6) 单价 0.335 全链原样存取（H2 集成断言 1.01/101.00 过）。
- U1 出参扩展向前兼容（null 防御 fillPlanSnapshot）；transit 条件更新防并发双流转。

## 5. 本次经验沉淀

1. **"测试侧补列"是结构缺口的完美伪装**：H2BillingTables/ensureTables 用 `ADD COLUMN IF NOT EXISTS` 在测试上下文补齐 V8 缺列，743 全绿 + 契约一致 + 实体正确——四绿一红的结构性炸点只剩迁移文件本身。评审计费/CRUD 类交付时，**实体字段集 ↔ 迁移 DDL 列集的逐列 diff 必须独立做一遍**，不能信"DBA 定稿真库已验"的流程叙述（真库验证若不含代码联跑，缺列不可见）。
2. **"总数绿"不能替代"清单绿"**：743 绿复现了，但按 tasks.md 任务号盘点发现 T19（收口测试）整体缺失——锚点 18~21 的 AC 门禁被"全量回归绿"的乐观总数掩盖。门禁应以任务/锚点为单元逐格核对，总数只做烟雾参考。
3. **编排者收尾代码同样过门禁且应重点过**：本次两处收尾（事务 wrap、Boolean.FALSE 断言）经独立复核均正确，但若评审对"编排者亲手写"的部分默认信任，就违背了"只看代码不看作者"的独立性根基——收尾人恰是对 Dev 报告持怀疑态度的人，其自身改动更需等强度审查。

---

# 复审（第二轮）— D1/D2 定向复审

> 评审人：team-reviewer（独立复现，不采信自报）| 日期：2026-09-10 | 范围：仅首轮 D1/D2 两阻断项 + 修复副作用面
> 结论：**通过（GATE:PASS）**——D1/D2 修复均真实落地并独立核实，无新增阻断；建议 1 项新增（O1）、措辞歧义观察 1 项（O2）

## 1. D1 复核（t_plan 缺 edition_override 列 + 测试侧 ALTER hack 掩盖）

| 修复点 | 独立核实结果 |
|---|---|
| V8:61 补列 | ✅ Read 核实：`edition_override` VARCHAR(16) DEFAULT NULL`，带 D-9 custom 必填注释 |
| schema-h2:335 同步 | ✅ Read 核实：`edition_override VARCHAR(16)`，注释"生产同宽" |
| ALTER hack 零命中 | ✅ 通配符递归 grep 独立复现：`edition_override` 在全部 Java 源码（6 模块 main+test）仅命中新契约测试的断言文本与 SubscriptionApplyServiceTest:206 的 `getEdition()` 断言（实体 getter，非 SQL）；`t_plan` 在 H2BillingTables.java / InvoiceServiceImplTest.ensureTables 中零命中——两文件现仅剩 t_governance_log/t_quota 补建 + t_tenant 业务列（首轮已接受范围，非 D1 范畴） |
| 防回归锚 | ✅ TC_C2a:254-258 真 contains 断言（详见 §2） |

**O2（措辞歧义观察，不阻断）**：修复自报"删 H2BillingTables.java"字面为删除文件，磁盘实际文件保留（被 SubscriptionApplyRollbackTest:44 / SubscriptionApplyServiceTest:63 引用，删文件将编译失败），实际删除的是其中的 edition_override ALTER 段。首轮修复建议原文为"移除两处测试侧补列 hack"（指补列段），修复实质与建议精确一致——判定为自报简写歧义而非虚报。

## 2. D2 复核（T19 收口测试补交）

新增 `CommercializationRbacSwitchContractTest.java`（495 行，7 用例全绿）逐项读源码核验：

- **TC_C1（反射矩阵）**：4 注解域 Controller × 声明方法反射取 `@RequirePermission`，端点全集 vs 契约矩阵双向 assertEquals（漏挂/错挂/新端点未登记均红）+ 恰 15 端点计数 + PlatformTenantController claims 形态（无注解原子/仅 GET）。
- **TC_C2a（V8 seed 契约）**：**非空壳**。readClasspath 真读 V8 文本，正则解析 9 权限原子（id 恰 1081~1089 + code 集合）、18 授权（id 恰 2203~2220）、角色分布 9/4/3/1/1、五角色权限码逐格、套餐 seed 恰 3 行（101~103）、INSERT IGNORE 幂等。**D1 防回归锚真实**：:256 对 `CREATE TABLE IF NOT EXISTS `t_plan`` 语句段 contains("`edition_override` VARCHAR(16) DEFAULT NULL")——V8 再删该列即红；空转风险已由 :190 前置断言（建表块必须存在）封死。
- **TC_C2b（H2-V8 防漂移）**：H2 MERGE seed 的权限 id→code、授权三元组 (id,role,perm)、套餐 id 与 V8 逐行 assertEquals；H2 t_plan 建表含 edition_override（:304）。
- **TC_C3（AC-G3 不限层）**：反射读 LayerGuardInterceptor 两前缀清单，11 条商用化 URI × 全前缀 startsWith 零命中断言。
- **TC_C4（403 矩阵）**：**非空壳**。真实 `PermissionInterceptor.preHandle` × 15 端点 × 5 角色 = 75 格逐格 `assertEquals(expected.contains(roleId), pass)`，失败消息带端点+角色+权限码；拒绝路径另验 HTTP 403 状态码；角色→权限码 mock 取 PRD §4.9 权威矩阵（与 TC_C2a 的 V8 seed 断言互为独立锚点，双链都锚定同一矩阵）。TC_C4b 未登录 401。
- **TC_C5（claims 矩阵）**：直调 Controller——PA 200、TA/PM/engineer/executive 40301、未登录 40101，verify 查询次数证"先拒绝后查库"。

## 3. 测试独立复现

`mvn -pl eaiselp-auth,eaiselp-runtime -am test` exit 0，BUILD SUCCESS（3:12 min）：common 6 + data 122 + auth 12 + capability 12 + adapter 58 + runtime 540 = **750 全绿 0 失败 0 跳过**，与自报"743+7"口径精确吻合（runtime 533→540，新类恰 7 用例）。

## 4. 修复副作用面（定向）

- **S 系列无升级**：D3 SubscriptionApplyService:240 行号精确未漂移（40004 双语义原样）；D4 RuntimeController:178-180 原样；D5 PlatformTenantController:36 `pageSize` 原样；D7 该文件 log.error 零命中维持。D6/D8~D10 不涉本轮改动面。
- **LoginUser ThreadLocal 雷**：LoginUser.java git status 零改动；main 树（4 模块通配 grep）无 LoginUser.clear 调用；修复仅测试侧——新类 @AfterEach clearThreadLocal() 用 clear()（注释正确解释 set(null) 不清 TenantContext 桥的缘由），与 DataQualityRuleServiceImplTest:74 先例一致，另 TC_C4b/TC_C5 用例内各自 clear。
- 改动面盘点：git status 与首轮一致（M 由 8→9，新增恰为 schema-h2.sql；?? 由 44→45，新增恰为本测试类），无范围外夹带。

## 5. 复审新增缺陷/观察

| 编号 | 严重度 | 位置 | 问题 | 建议 |
|---|---|---|---|---|
| O1 | 🟢可选 | H2BillingTables.java:18-39 vs InvoiceServiceImplTest.java:62-85 | t_governance_log/t_quota 建表 + t_tenant 补列逻辑整段复制粘贴（首轮既有重复，本轮未恶化）——一处漂移另一处不同步 | ensureTables 改为委托 H2BillingTables.ensure(jt) 单点收敛 |
| O2 | 🟢措辞 | 修复自报 | "删 H2BillingTables.java"实为删其中 ALTER 段（文件因被两测试类引用不可删）——自报简写歧义 | 自报按"文件级/段落级"区分措辞，避免复审误判 |

## 6. 本次经验沉淀（复审）

1. **Windows findstr 递归陷阱（本次自纠错）**：`findstr /s "pat" dir`（路径以目录结尾）静默零命中且不报错，/"零命中"结论建立在无效命令上；必须 `findstr /s "pat" dir\*.java` 带通配符才真递归。复审中第一次"hack 零命中"结论即踩此坑，靠交叉验证（findstr 无输出的反常）发现并全量重验——**grep 类"零命中"结论须先证命令本身有效**（如故意搜一个必命中词做阳性对照）。
2. **cmd 整行 `%errorlevel%` 一次性展开**：`cmd1 & echo %errorlevel%` 显示的是行解析时的旧值，复合命令里的退出码全部不可信——分步执行或读真实输出为准。
3. **"删 hack"类自报要区分文件级/段落级**：被引用的基建文件只能删段落；评审核对时以"缺陷修复建议原文的动作对象"（首轮建议=移除补列段）而非自报简写为判据，避免把措辞歧义误判为虚报。

GATE:PASS
