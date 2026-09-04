# 代码评审报告 — case-20260820-L2治理收口

> 结论：**通过**（0 阻断 / 3 建议 / 3 可选）
> 评审人：team-reviewer（模型与 Dev 隔离，独立复现，未采信 Dev 自报）
> 注：本报告由 team-reviewer 产出、编排者代落盘（Reviewer 会话无写盘权限）

## 一、磁盘事实核对（强制第一步）

- 后端 `git status`：8 个 M + untracked（governance 包 21 文件、4 Controller、V6、TenantSubscriptionService×3、TrialTipVo、测试 5 组）——与编排者清单**逐文件一致**，无漏报、无虚报。
- 前端 `git status`：8 M + 5 untracked——一致。
- 批C/批B/批A 各改动点均在 diff 中真实落地；`git diff` 逐文件比对无"报告了但未落地"项。
- 清单外文件核查：`deploy/migrate_docker_to_native.bat`、`deploy/setup_mysql_native.bat`、`docs/运维文档/试用到期恢复runbook.md` 为 DBA/Ops 同 case 过程产物（对应技术方案 R1 兜底 runbook），**非 Dev 代码夹带**。
- 测试独立复现：`mvn -pl eaiselp-auth,eaiselp-runtime -am test` exit 0，聚合 surefire XML：**492 tests / 0 failures / 0 errors / 0 skipped**（adapter 31 / auth 12 / capability 12 / common 6 / data 58 / runtime 373），与"492 绿"自报一致。

## 二、必做核查项结果（全部独立复现）

1. **标准发布事务**：`StandardServiceImpl.transit` @Transactional；`autoDeprecateCurrentPublished` 先 `LIMIT 1 FOR UPDATE` 锁现行 published → 置 deprecated（原因=「被 {code} {新版本} 取代」）→ 再发布新版，锁序与 SE §3.2.1 钉死一致；发布+自动废弃双审计；同态幂等短路；状态机内聚 `StandardStatus.canTransitionTo/requiredFieldsFor`（复刻 AdrStatus 先例）。✓
2. **D-9 旁路 @TableLogic**：`StandardMapper.selectPublishedWithDeletedForGateRef` 显式 `is_deleted IN (0,1)`，`t.is_deleted AS deleted` 别名映射 BaseEntity.deleted（select=false 不进 MP 列清单）；方法级注释声明用途与租户隔离依据；t_standard 不在 `EaiselpTenantHandler.IGNORE_TABLES`，手写 SQL 仍被拦截器改写注入 tenant_id——G13 前提成立。✓
3. **到期拦截**：④.5 插入位=账户禁用校验后、签发 JWT 前；原⑥租户查询提前合并（全链路仍一次）；防枚举顺序正确（TC8 真实断言 40001 + verify never login_trial_blocked）；不签 token、verify never updateById；审计 resource_id=tenantId（claims=null 兜底可检索）。`TenantSubscriptionServiceImpl` 口径逐条对齐 PRD §4.3.1（now≥expire 含等于 / 仅 trial / NULL 放行 WARN / ≤7×24h 窗口 / ceil 取整 / N=1 红色优先）。✓
4. **U1/U2 角色校验**：U1 tenant_admin|platform_admin、U2 仅 platform_admin，均显式校验 JWT claims roles（40301）。✓
5. **模板版本必变更**：`patch.getVersion().equals(exist.getVersion())` → 400（不比较大小）；占位符 `\{\{[A-Za-z0-9_]+\}\}` TreeSet 去重排序，不落库。✓
6. **资产删除联动**：事务内 `dqRuleMapper.delete(assetId wrapper)` MP 逻辑删 + 审计 detail 含 ruleIds/cascadedRuleCount。✓
7. **权限注解全量核对**：四 Controller 共 23 端点 `@RequirePermission` 与 V6 seed 1059~1070 的 code 一一对应（standard/template/asset/dqrule × view/create/edit）；V6 seed 36 行 2134~2169 分布 role1×12/role2×12/role3×4/role4×4/role5×4 逐 id 与 PRD AC-RBAC.4 契约一致。✓
8. **租户隔离**：四域查询全走 MP wrapper（拦截器自动注入）；唯一手写 SQL 见上；`@InterceptorIgnore` 全库扫描零命中（G13 PASS）；新代码 team-* 字面量零命中（G11 PASS，仅命中既有 QualityGateRuleController 注释——注释豁免）。
9. **前端**：menu.js 四入口挂 COMMON_MENUS 无条件渲染（全角色可见）、不挂 layerHidden（AC-SWITCH.1）；写按钮按角色隐藏 + 后端 40301 兜底；login.html 40003 特判红色文案 + escapeHtml；资产描述/标准标题等回显均 escHtml。
10. **测试真实性抽查**（≥3 用例读断言）：StandardServiceImplTest「发布新版自动取代」断言 wrapper SQL 含 `for update`、取代顺序（patched[0]=旧版 id 先 deprecated）、至多一个 published、双审计含 `v1.0→v2.0` 链；AuthServiceImplTest TC7/TC8/TC9a-d 断言 40003/40001/tip 数值；RuntimeControllerTest TC30a/b verify never createPending/start/runAsync。**均真实非空壳**。
11. **审计全覆盖（AC-AUDIT.1）**：四域 CRUD/流转/自动取代/启停/登记/恢复/三类拦截均有 `auditService.log`，action 与 SE §8.1 清单一一对应。

## 三、Dev 遗留项核查

| 项 | 结论 |
|---|------|
| 批C#1 dashboard.html 顺手修复 | **确认正确且最小**：HEAD 原码 `const reqs = [` 以 `)` 闭合（`git show HEAD:pages/dashboard.html:150-154`）确为语法错误，修复为单字符 `];` |
| 批A#3 JSON 内存过滤 | **与 ADR §4.2 先例逐行同构**（分页后 records 过滤 + setTotal(filtered.size)，对齐 AdrServiceImpl），编排者裁决维持成立 |
| 批B#1 纯 Mockito FOR UPDATE 断言 | **可接受**：断言真实覆盖锁 SQL 形态/取代顺序/双审计/至多一个 published；真实 MySQL 行为已由编排者真库验证；与 SE §9.1.13"在 H2 可跑"表述有形态差异（见 O3） |
| 批B#2 RuntimeController 既有 WARN | diff 仅三处插入（import/字段/校验调用+私有方法），**零删改既有行**，非本次引入 |

## 四、缺陷清单

| 编号 | 严重度 | 文件:位置 | 问题 | 修复建议 |
|---|---|---|---|---|
| S1 | 🟡建议 | eaiselp-web-separate\pages\dashboard.html:124-127 | trialTip 样式分支引用 **不存在的全局名** `window.GovernanceDict`（governance-dict.js 实际导出 `window.GOV_DICT`，字典在 `GOV_DICT.data.trialTip`），且本页未引入 governance-dict.js——该分支恒为死代码，永远走 Bootstrap FALLBACK。功能上三档视觉仍达成（FALLBACK 亦三色），但违背 P6 前端"文案/样式集中一处"约束，注释声称"以 GovernanceDict.trialTip 映射为准"永不生效 | 引入 governance-dict.js 并改用 `GOV_DICT.data.trialTip[tip.level]`（cls+文案），或删除死分支仅保留 FALLBACK 并修正注释 |
| S2 | 🟡建议 | pages\standard-list.html:330-335、pages\template-list.html:237 | `renderMd` 仅剥 `<script>` 标签，未复刻平台既有 sanitizeHtml 先例（pages\artifact-view.html:207-221 / case-detail.html:807：剥 iframe/object/embed/link/style + on* 事件属性 + javascript: 协议）——markdown 正文为用户输入，`<img onerror=...>` 类存储型 XSS 面对全员只读用户开放。写入门槛为 edit 权限（管理员）故不判阻断，但防护低于同库基线 | 复制 artifact-view 的 sanitizeHtml 于两页（或抽为公共 JS），renderMd 输出过 sanitize |
| S3 | 🟡建议 | StandardController.java:135-145 + StandardServiceImpl.edit:96-99；DataAssetController.java:93-103 + DataAssetServiceImpl.edit:78-91 | PUT 全量编辑下"清空可选字段"静默失效：`toJson([])` 返回 null + MP `updateById` 忽略 null 字段 → 提交空数组想清空关联原则/关联门禁/标签（及置空 owner/description）时不落库，回显仍为旧值。AC 未覆盖该边界（AC-F1.6 仅测 create 空数组），属真实编码缺陷 | `toJson` 空列表返回 `"[]"`（parseCodes 兼容）使 JSON 列可被更新为空；文本可空字段改用 UpdateWrapper 显式 set null，并在测试补"编辑清空关联/标签"用例 |
| O1 | 🟢可选 | V6__l2_governance_close.sql:49 | t_standard.deprecate_reason 列 COMMENT 错别字"被 {编号} {新版本} **取得**语义文案"应为"取代" | 修 COMMENT（V6 未发布前可直接改；已发布则留待 V7） |
| O2 | 🟢可选 | TemplateServiceImpl.java:220-228 | audit 每次调用 new ObjectMapper（Standard/DataAsset/DataQualityRule 均静态复用） | 提为 `private static final ObjectMapper OM`，与同类一致 |
| O3 | 🟢可选 | StandardServiceImplTest.java:457-495 | 发布取代用例为纯 Mockito 断言 wrapper SQL 字符串含 `for update`，SE 方案 §9.1.13 表述为"验证 FOR UPDATE 事务路径在 H2 可跑"——形态差异（本 case 可接受：真库已验证） | 测试方法注释补一句"锁行为以真库验证为准，此处断言 SQL 形态与事务顺序"，防后续误读 |

## 五、规范符合度总览

模块边界（P1-P5）：零 POM 改动、零新依赖、TrialTipVo 落 common 的依赖方向论证成立（D-4/P3）✓。命名/注释密度/异常处理与 hierarchy 先例一致 ✓。V6 幂等规范（IF NOT EXISTS + INSERT IGNORE + 零 ALTER）与 V4 r4/V5 r2 一致 ✓。schema-h2 追加按既有 CLOB/TIMESTAMP 简化风格并注明映射 ✓。API 全部 /api/v1/ 前缀（G14）✓。

## 本次经验沉淀

1. **`updateById` null 忽略语义是 JSON 数组列编辑的隐形陷阱**：`toJson(空列表)→null` 让"清空关联/标签"PUT 静默失效——凡"列表→JSON 字符串"转换 + MP 部分更新的组合，都应显式决定空集合落 `[]` 还是 null，并补清空用例（本次 S3）。
2. **前端"优先走集中字典、兜底 FALLBACK"写法要验证全局名真的存在**：dashboard 引用了不存在的 `GovernanceDict`（实际 `GOV_DICT`），三元保护让错误静默降级、测试照绿——集中字典类公共 JS 应在引用页 grep 校验导出名（本次 S1）。
3. **markdown 渲染的 sanitize 基线一旦在部分页面建立（artifact-view/case-detail），新页面弱化实现即是相对缺陷**：先例即规范，评审时应以库内最强 sanitize 为基线而非"有没有做"（本次 S2）。

---

**结论：通过。计数：阻断 0 / 建议 3 / 可选 3。无阻断项。**

关键复现证据（绝对路径）：
- `D:\AI\mywork\platform\eaiselp-runtime\src\main\java\com\eaiselp\runtime\governance\StandardServiceImpl.java`（发布事务 :239-294）
- `D:\AI\mywork\platform\eaiselp-runtime\src\main\java\com\eaiselp\runtime\governance\StandardMapper.java`（D-9 :40-42）
- `D:\AI\mywork\platform\eaiselp-data\src\main\java\com\eaiselp\data\service\impl\TenantSubscriptionServiceImpl.java`（口径 :62-120）
- `D:\AI\mywork\platform\eaiselp-runtime\src\main\resources\db\migration\V6__l2_governance_close.sql`
- `D:\AI\mywork\eaiselp-web-separate\pages\dashboard.html`（S1/S3 涉及）

GATE:PASS
