# 代码评审报告 — case-20260821-L3收口
> 结论：**不通过**（阻断 1 / 建议 4 / 可选 2）
> 评审人：team-reviewer（独立复现，不采信自报）　评审日期：2026-09-04

## 0. 磁盘事实核对（先行）

- platform 仓 `git status`：M schema-h2.sql（+109 行）+ untracked 30 主代码 Java（governance 根 20 + dto 7 + controller 3）+ V7__l3_close.sql + 6 测试类 + 4 docs 条目——与编排者清单一致，无漏报无夹带。
- web 仓 `git status`：M README/governance-dict.js/menu.js + A 三新页——一致。
- `mvn -pl eaiselp-auth,eaiselp-runtime -am test` 独立复现 **BUILD SUCCESS**：common 6 + data 66 + auth 12 + capability 12 + adapter 31 + runtime 496 = **623 全绿**（新增 6 测试类共 111：RiskCalculator 12 / RiskServiceImpl 27 / BizCaseCalculator 23 / BusinessCaseServiceImpl 26 / ComplianceCheckServiceImpl 16 / L3RbacSwitchContract 7）。
- 测试断言真实性抽查：计算器/Service 测试的构造值全部心算复核为真（100÷40=2.5、100÷30=3.3 HALF_UP、(40×3−100)/100=20.00、净0 ROI −100.00、5×3×0.8÷6=2.00、1×1×0.1÷10=0.01、等级边界 6/7/12/13/19/20/25），防伪造用例真实预置伪造值后断言被重算覆盖（RiskServiceImplTest:189-201），无恒真断言。
- **重点核对 t_risk.description 补列**：V7:50（`description TEXT`，注释"DBA r2 补列，v1 漏建"）与 schema-h2.sql（`description CLOB`）**两文件一致**；但应用层未贯通——见 D1（阻断）。

## 1. 缺陷清单

| 编号 | 严重度 | 文件:行 | 问题 | 修复建议 |
|---|---|---|---|---|
| D1 | 🔴阻断 | `Risk.java`（无字段）/ `RiskController.java:124-143,170-182` / `RiskVo.java`（无字段）/ `RiskServiceImpl.java:97-110` | **t_risk.description 补列仅落 DDL，应用层全链路缺失**。证据链：V7:50 与 H2 均有该列（DBA r2 补列）；PRD §4.1.1（需求设计说明.md:143）字段清单含"描述"；api-contracts.md:78 R2 入参表含 `description \| String`；前端 risk-board.html:467 提交、:411 详情渲染、:432 编辑回填——但 Java 侧实体/RiskSaveRequest/toEntity/RiskVo/edit 的 LambdaUpdateWrapper 五处全缺。后果：用户填写的风险描述被 Jackson 静默丢弃，详情恒"（未填写）"，AC-F1.1（描述三字段）功能不可用，DDL 补列成死列 | Risk 实体加 `private String description;`；RiskSaveRequest 加字段并入 toEntity 映射；RiskVo 加字段并在 toVo 填充；edit wrapper 加 `.set(Risk::getDescription, patch.getDescription())`；补创建/编辑/清空三态测试断言 |
| D2 | 🟡建议 | `RiskVo.java:16-17` | Javadoc 声称"V7 t_risk 无 description 列（DBA 终稿），本 VO 与实体均无该字段"——与磁盘 V7:50 直接矛盾的过时虚假陈述，将误导后续开发者按"无此列"演进 | 修复 D1 时同步删除/改写该段（改为"列已补，实体/VO 承载"） |
| D3 | 🟡建议 | `RiskServiceImpl.java:212-215` / `BusinessCaseServiceImpl.java:242-246` | transit 落库 update 仅 `.eq(id)`，不带 from 状态前置条件——并发双 transit 存在 TOCTOU 窗口（如 closed 落库瞬间另一 mitigating→open 请求覆盖终态）。与 StandardServiceImpl:267 先例同模式故不阻断，但新表无历史包袱可一次做对 | update wrapper 追加 `.eq(status, from.dbValue())`，影响行数=0 时返回 409/重读 |
| D4 | 🟡建议 | `RiskServiceImpl.java:97` / `BusinessCaseServiceImpl.java:99,202,242` / `ComplianceCheckServiceImpl.java:75` | 编辑/流转走 `update(wrapper)` 单参重载不触发 MP MetaObjectHandler，update_by 恒不更新（生产 update_time 有 ON UPDATE 兜底、H2 无）；审计 detail 含 operator 故追溯不受损，但列契约"常规审计列"未闭环 | wrapper 显式 `.set(updateBy, operatorName())` 或改用实体承载更新 |
| D5 | 🟡建议 | risk-board.html:274-276 | 关联对象选择器固定 `size=500` 拉取 programs/projects/cases——已关联对象超出 500 截断时在编辑弹窗不可见（未勾选），PUT 全量保存将静默丢失该关联 | 渲染勾选态时按 prefill.relatedObjects 的 id 追加拉取缺失对象，或对截断场景提示 |
| D6 | 🟢可选 | `ComplianceCheckServiceImpl.java:81` | 非 custom 框架时 frameworkName 纯空白串（如 `" "`）可通过 `isBlank` 校验并落库为空白值 | set 前 normalize（trim 后空→null） |
| D7 | 🟢可选 | risk-board.html:591-596 | 前端 `levelOf` 复刻服务端四段映射（仅 heat title 兜底用）——双源口径，服务端 cells 恒 25 格自带 riskLevel，该函数实际近乎死码 | 直接消费 cells 数据，删除本地映射 |

## 2. 必查项逐条结论（通过面）

1. **计算器数值**：payback 双 N 语义分离（net≤0→null 先判、onetime=0 且 net>0→0.0）、ROI 先乘 100 后除单次 HALF_UP（`BizCaseCalculator.java:66-69`）、RICE 2 位、confidence ×10 整数判 0.1 步进、除零全防、金额 ≥0——与 PRD §4.4.1/§4.1.2 唯一口径逐条一致，构造值断言真实。
2. **防伪造**：六计算字段入参模型零暴露（RiskSaveRequest 无 riskValue/riskLevel、CaseSaveRequest 无四计算列）+ create/edit 重算覆盖 + 测试预置伪造值断言被覆盖。BigDecimal 整数性校验（`stripTrailingZeros().scale()>0`→400 指名），1.5 不落 50000（D-4 落地）。
3. **状态机消解表**：`RiskStatus`/`BizCaseStatus` 流转表与 §0.3 一致——mitigating→open 回退合法、closed/rejected/done 终态无出边、open→closed 跳级 400、executing 唯一出边 done、自流转幂等短路；closed→必填 resolutionNote、rejected→必填 rejectedReason。
4. **B7 approve 原子**：transit 整体挂 `@RequirePermission("bizcase:approve")`（BusinessCaseController.java:107），PM 无 1080 → 403；前端 approve 按钮仅 PA/TA 可见（business-case-list.html:252,336）；契约测试 TC_L1b 锁定。
5. **权限注解 vs seed**：20 端点注解与 1071~1080 逐原子对应；V7 与 H2 seed 同 id 集合（1071~1080 / 2170~2202 共 33 行）、角色分布 role1×10/role2×10/role3×7/role4×3/role5×3、PM 无 1075/1076/1080（利益冲突隔离）；INSERT IGNORE / MERGE 幂等、零 ALTER。
6. **租户隔离/审计/不限层**：三表不进 IGNORE_TABLES（契约测试反射断言）、手写聚合 SQL 显式 `is_deleted=0` 且受拦截器改写、LayerGuard 前缀不命中；审计全覆盖 create/edit/remove/transit/decision_note（含 oldResult→newResult、from→to、operator）；20 端点全挂权限注解。
7. **updateById null 陷阱**：Risk/BizCase/Compliance edit 均走 LambdaUpdateWrapper 显式 set——payback/roi N/A 变更、custom→标准框架清 frameworkName、非 closed 清 resolutionNote 均可落 NULL（Dev 自报属实，测试有断言）。
8. **前端**：三页 escHtml/escAttr 字段级转义 + sanitizeHtml 纵深；save body 不发任何计算字段；approve/流转按钮按角色渲染（后端 403 兜底）；热力图纯 CSS Grid（零图表库）；custom 联动前端先拦（非 custom 提交 null）；`node --check` 两 js + 三页内嵌 script 提取语法检查全过。
9. **遗留处置**：relatedObjects id 字符串语义（program/project=数字 id、case=caseId 业务键）在 Controller/ServiceImpl/RelatedObjectVo 三处 Javadoc 标注清晰；锚点 23/24 API 级用例归 QA 承接在 L3RbacSwitchContractTest.java:25-27 明确交代理由。
10. **roiPercent 差异定稿**：实体/V7/VO/前端全链统一 roiPercent（roi_percent），与 tasks.md 定稿一致。

## 3. 本次经验沉淀

1. **"补列"类修复必须验五层贯通**：DDL（迁移+测试 schema）→ 实体 → 入参 DTO/映射 → VO 回显 → update wrapper 显式 set。本次 DBA r2 补列只推进了 DDL 层，Java 侧五处全缺且 VO Javadoc 留下与磁盘矛盾的"无此列"声明——评审此类跨角色修复时，先 grep 列名在两仓的出现面即可秒判贯通性。
2. **先例一致性是并发缺陷严重度的校准器**：transit 无状态前置条件的 TOCTOU 窗口，单看是新缺陷，对照 StandardServiceImpl 先例同模式后降为建议级——"与先例同模式"不等于正确，但阻断判定应锚定项目既定基线，避免评审口径漂移。
3. **Windows 环境评审工具链**：git -C 绝对路径 + findstr 替代 grep、mvn 需 `-f` 指定 pom 且管道 tail 不可用；HTML 内嵌 script 语法校验可用 node 提取 `<script>` 块后 `new Function()` 批量断言，替代 node --check 的文件级限制。

GATE:FAIL


---

## D1 修复记录（编排者代行，2026-08-21）

- Risk.java 实体 +description 字段（含 Javadoc）
- RiskController.RiskSaveRequest +description / toEntity +setDescription 映射（create/edit 双路径共用）
- RiskVo +description 字段 + Javadoc 虚假陈述改写（原"V7 无 description 列"已失真）
- RiskServiceImpl.edit wrapper +.set(Risk::getDescription)（LambdaUpdateWrapper 显式 set，清空语义贯通）
- RiskServiceImpl.toVo +setDescription（出参贯通）
- 验证：mvn -pl eaiselp-auth,eaiselp-runtime -am test 全量 623 绿 BUILD SUCCESS
- 前端 risk-board.html 提交/渲染/回填三处本已按契约实现（评审报告确认），无需改动


## 复审（第二轮，D1 定向）

> 结论：**通过**。只验 D1 修复（其余首轮已过）。独立磁盘核对，不采信修复记录自述。
> 改动面核对：git status 与首轮一致，修复落点均在 untracked 文件内，无新增夹带。

### 六点核实（全部属实）
1. `Risk.java:51-52` — `private String description;` 含 Javadoc
2. `RiskController.java:134` RiskSaveRequest.description + `:177` toEntity `setDescription`；create(`:66`)/update(`:81`) 双路径共用 toEntity
3. `RiskVo.java:43` description 字段 + `:16-17` Javadoc 改写——原"无此列"虚假陈述消除
4. `RiskServiceImpl.java:105` edit wrapper 显式 set，PUT 清空语义落 NULL
5. `RiskServiceImpl.java:298` toVo `setDescription` 出参
6. DDL 双侧在位复核：`V7__l3_close.sql:50`（TEXT）/ `schema-h2.sql:224`（CLOB）

### 贯通链闭合验证
risk-board.html:467 提交 → Jackson 绑定 → toEntity:177 → create insert / edit wrapper:105 → toVo:298 出参 → :411 详情渲染 / :432 编辑回填。全链闭合，AC-F1.1 描述三字段可用。

### 测试独立复现
mvn -pl eaiselp-auth,eaiselp-runtime -am test：BUILD SUCCESS，6+66+12+12+31+496 = **623 全绿**（RiskServiceImplTest 27 通过）。ERROR 日志均为负路径用例预期注入，Failures: 0。

### 首轮建议级状态
- D2 已随 D1 修复确认消除；D3（transit TOCTOU）/D4（update_by）/D5（size=500 截断）代码未变维持建议级无升级。

### 新发现（可选级，不阻断）
- R1 🟢：RiskController:133 / RiskVo:42 的"缓解措施"Javadoc 错落在 description 字段（编排者已顺手修正）
- R2 🟢：RiskServiceImpl:106 缩进格式

GATE:PASS
