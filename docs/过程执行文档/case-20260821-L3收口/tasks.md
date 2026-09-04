# 任务清单 — case-20260821-L3收口（GRC 风险合规 + 战略投资决策）

| 字段 | 值 |
|---|---|
| 产出者 | team-ba（L1 需求分析员） |
| 日期 / 版本 | 2026-09-04 / v1.0 |
| 上游基线 | PRD v1.0（5 功能点 34 AC）；编排者裁决 Q1~Q9；SE 技术方案 v1.0（**§11 四批拆解序为本清单唯一拆解基线；§0.3 消解表、§5 API 契约、§9.1 单测锚点 1~24 全量引用**）；DBA V7 + schema-h2.sql（已落盘并真库验证） |
| 下游 | team-dev（认领执行）/ team-qa（AC 基线 + §QA 断言标注）/ team-reviewer（公共验收约束为检查基线） |
| 配套契约 | 同目录 `api-contracts.md`（20 端点；前端任务直接引用编号 R1~R7 / C1~C5 / B1~B8） |

**拆解声明**：
1. 本清单严格按 SE 技术方案 §11 四批拆解序组织（批A 口径锚定+三域主线 → 批B 两状态机 → 批C 两聚合视图 → 批D 场景适配+收口），未另起炉灶；与 PRD 表述冲突处均以 SE 方案+裁决为准并逐处标注（见"给 QA 的断言标注"节）。
2. **DB 层已由 DBA 交付完成**（T0，标"已完成"，真库验证过）：V7 迁移（三表 + 10 权限原子 1071~1080 + 33 授权行 2170~2202）、H2 schema 同步均已落盘，Dev 零动作。
3. **V7 落盘 DDL 与 SE 方案 §3 的差异定稿**（BA 磁盘核对，冲突以 V7 为准，实体/VO/契约字段名按 V7 列名映射——SE 方案相应条目视为被 DBA 终稿覆盖）：

| 项 | SE 方案 §3 | V7 落盘（终稿） | 影响 |
|---|---|---|---|
| ROI 列名 | `roi` | **`roi_percent`** | 实体/VO/契约出参字段一律 `roiPercent`（数值 20.00 = 20.00%） |
| payback_years 宽度 | DECIMAL(20,1) | DECIMAL(16,1)（最坏值 14,2÷0.01≈1e14 预算） | 无代码影响 |
| rice_score | DECIMAL(5,2) NOT NULL | DECIMAL(10,2) **DEFAULT NULL**（迁移防御） | Service 恒写值（无除零路径），VO 断言恒非 null |
| risk_value / risk_level | TINYINT / VARCHAR(8) | INT / VARCHAR(16)（critical 8 字符零余量纠偏） | 无代码影响 |
| evidence_note | VARCHAR(500) | VARCHAR(1000)（证据常含多条线索） | 前端输入 maxlength 对齐 1000 |
| check_date | DATE NOT NULL | DATE NULL（应用层缺省取当天） | Service 缺省逻辑不变 |
| t_risk 索引 | idx_risk_tenant_value（排序） | **idx_risk_tenant_status**；排序索引否决（≤5000 行 filesort 毫秒级） | 排序仍 `ORDER BY risk_value DESC, id DESC`，无索引依赖 |
| t_business_case 索引 | idx_bc_tenant_rice | **无二级索引**（≤500 行量级论证） | 排序仍 `ORDER BY rice_score DESC, id DESC`，无索引依赖 |
| uk 命名 | uk_cc_/uk_bc_ | uk_check_tenant_name / uk_bizcase_tenant_name | 纯命名差异，无代码影响 |

4. **启动类零改动**（编排者约束）：governance 包 V6 已建、`@MapperScan` 已含该包（`EaiselpRuntimeApplication.java:24`），本 case **无包骨架任务**；`EaiselpTenantHandler` / `LayerGuardInterceptor` / `GlobalExceptionHandler` 同样零改动（SE §2.1 零改动清单）。

**仓库根约定**（下文产物路径均为相对路径）：
- 后端仓库：`D:\AI\mywork\platform\`
- 前端仓库：`D:\AI\mywork\eaiselp-web-separate\`

---

## 公共验收约束（Reviewer 检查基线，适用于 T1~T4/T10~T13 全部后端任务）

1. **Controller 薄层**：参数接收→Service 调用→`R<T>` 返回；请求 DTO 内嵌 static class；除"target 不能为空"级判空外不写业务校验（StandardController 先例）。
2. **入参 DTO 数值字段一律 BigDecimal**（probability/impact/reach/impact/effort/confidence/三金额——D-4）：Service 做"整数性（`stripTrailingZeros().scale()<=0`）+ 区间/步进"双校验，非法 400 指名字段与合法值集——**提交 1.5/0.05/0.85 不得落入 50000**（GlobalExceptionHandler 无 HttpMessageNotReadableException 专项处理的磁盘事实）。
3. **计算字段防伪造链**：六个计算列（risk_value/risk_level/net_benefit/payback_years/roi_percent/rice_score）**不出现在任何入参 DTO**（toEntity 不映射，客户端伪造值连绑定入口都没有——强于"忽略"）；Service 在 create 与 update 写库前调 Calculator 重算覆盖（AC-F1.5/F2.4）。
4. **tenant_id 只从 `TenantContext`/`LoginUser` 取**；禁止请求参数传 tenant_id、禁止 `@InterceptorIgnore`（G13 BLOCKER）；实体继承 BaseEntity（雪花 id + 审计五列 + @TableLogic）。
5. **uk 冲突统一形态**：捕获 `DuplicateKeyException` → `BizException(400, "...已存在: ...")`（AdrServiceImpl:327-346 先例）——三域一致。
6. **审计统一**：全部走 `AuditService.log`，action/resource_type/detail 必含字段按 SE §8.1 清单逐条对照（AC-AUDIT.1）；detail 含操作者与关键变更值（含被覆盖旧值）。
7. **状态机枚举复刻 `StandardStatus` 模式**：纯逻辑不抛异常、`canTransitionTo`/`requiredFieldsFor`/`fromDbValue`、终态无出边、自流转幂等。
8. **风格一致性**：新端点一律 `/api/v1/` 前缀（G14）；代码无 team-* 字面量（G11）；三域 Controller/Service/测试命名与 governance 四域先例同构；VO 的 `overdue` 布尔由服务端统一判定（D-10，防前端时钟偏差）。

---

## 任务总览

- **总数 19**：T0 已完成（DBA 交付）+ 开发任务 18（后端 8 / 前端与配置 9 / 测试收口 1；单测内嵌各任务，SE §9.1 编号 1~24 为锚点）。
- **并行组**（SE §11 四批序）：
  - **批A**（T1 半日先行锁口径，随后全并行）：T2/T4（依赖 T1 计算器）∥ T3（零计算器依赖）∥ T5 → T6/T7/T8 ∥ T9（前端线按 api-contracts.md 契约先行，后端联调收口）
  - **批B**（依赖批A）：T10（依赖 T2）∥ T11（依赖 T4）
  - **批C**（依赖批A，与批B 即刻并行）：T12（依赖 T2）∥ T13（依赖 T4）∥ T16（依赖 T4 的 B1 strategyId）；T14（依赖 T12+T6）、T15（依赖 T13+T8）
  - **批D**（收口）：T17（依赖 T6/T8）→ T18（依赖全部后端任务）
- **关键路径**（两条并列，均 5 节点）：`T1 → T2 → T12 → T14 → T18`（风险看板线：计算器 → 风险 CRUD → 聚合端点 → 热力图 → 回归收口）∥ `T1 → T4 → T13 → T15 → T18`（投资组合线：计算器 → 案例 CRUD → 聚合端点 → 组合视图 → 回归收口）。批B（T10/T11）链深较浅，不构成关键路径。
- **34 AC 全覆盖**，映射见各任务"验收"行；无孤儿 AC、无范围外遗留（范围外声明见文末）。

## 任务明细

### T0 [DB] V7 迁移 + H2 schema + 权限 seed —— **已完成（DBA 交付并真库验证，Dev 零动作）**
- 模块：eaiselp-runtime 数据层
- 类型：数据库迁移/Schema
- 依赖：无
- 验收（已达成）：三表 DDL（t_risk/t_compliance_check/t_business_case，幂等 IF NOT EXISTS）+ 10 权限原子（id 1071~1080，tenant_id=0）+ 33 授权行（id 2170~2202：role1×10/role2×10/role3×7/role4×3/role5×3），零 ALTER/零 UPDATE；AC-RBAC.5 数据侧、AC-SWITCH.1 数据侧
- 产物（已落盘）：`eaiselp-runtime/src/main/resources/db/migration/V7__l3_close.sql`；`eaiselp-runtime/src/test/resources/schema-h2.sql`（三表简化版 + seed MERGE INTO）
- 备注：V7 与 SE 方案 §3 的差异已在"拆解声明 3"定稿（roi_percent 列名/索引否决/宽度），**以 V7 为准**

### T1 [后端] 计算引擎 RiskCalculator + BizCaseCalculator + 边界单测（先行任务，锁全部数值口径）
- 模块：eaiselp-runtime/governance
- 类型：纯静态工具类 + 单测（D-3；final、私有构造、纯函数、零 Spring/ORM 依赖）
- 依赖：无（批A 第一位，先行交付锁定口径——SE R1 风险缓解）
- 验收（AC-F1.2/F1.3 数值侧 + AC-F2.2/F2.3/F2.4/F2.5 数值侧；单测锚点 §9.1-1/2）：
  - `RiskCalculator.riskValue/riskLevel`：等级边界 1/6→低、7/12→中、13/19→高、20/25→极高（六边界值闭区间断言）；P=4,I=3→12；P=1,I=5→5→低（**已知语义非缺陷**，代码注释锚定 PRD §4.1.2 防质疑工单）；P/I = 0/6/1.5/负数 → BizException(400) 指名字段（BigDecimal 整数性判校在计算前）
  - `BizCaseCalculator`：payback 100÷40=2.5、100÷30=3.3（HALF_UP 1 位）、net=0 与 net=−10 → null（N/A 不可投）、onetime=0&net=30 → 0.0（零成本，两种 N/A 语义分离断言）；roi (40×3−100)/100=20.00、(10×3−100)/100=−70.00（负值合法）、onetime=0 → null；rice 10×10×1.0÷1=100.00、5×3×0.8÷6=2.00、1×1×0.1÷10=0.01 最小值；confidence 离散校验 0.1~1.0 十档全过 + **0.05/0.15/0.85（合法小数但非步进）**/0/1.1 → 400；三金额负值 400、全 0 合法；极值 onetime=0.01 + net=1e12 不抛异常（D-11 溢出防御）
  - 全部 BigDecimal 运算 + HALF_UP；**两测试类脱离 Spring/H2 可独立运行**（QA 数值断言复用 PRD §4.1.2/§4.4.1 构造值表，不自行发明）
- 产物：`governance/RiskCalculator.java`、`governance/BizCaseCalculator.java`；测试 `eaiselp-runtime/src/test/java/com/eaiselp/runtime/governance/RiskCalculatorTest.java`、`BizCaseCalculatorTest.java`

### T2 [后端] 风险域基础 CRUD（R1~R5）+ 三枚举 + 关联对象 + 逾期 VO
- 模块：eaiselp-runtime/governance
- 类型：Entity+Mapper+Service+Controller+枚举×3
- 依赖：T1（RiskCalculator）
- 验收（AC-F1.1/F1.3/F1.5/F1.6/F1.7 + AC-F1.4 的 closed 只读子项；单测锚点 §9.1-3/4/6/7）：
  - R1 列表：默认排序 `risk_value DESC, id DESC`（写死，QA 断言用）；筛选 category/level/status/overdueOnly（SQL 口径 `review_date < CURRENT_DATE AND status <> 'closed'`）/keyword（risk_name LIKE）；VO 含服务端判定 `overdue` 布尔（D-10）
  - R2 创建：riskName/category/owner 缺失 → 400；P/I 1~5 整数校验（BigDecimal，0/6/1.5 → 400 指名）；risk_value/risk_level 由 RiskCalculator 算后落库（入参 DTO 无此二字段——防伪造链）；uk(tenant, risk_name) 冲突 400 统一形态；status 固定 open
  - R3 详情：related_objects 解析为 `[{type, id, name, deleted}]`（悬空/已逻辑删 → deleted=true 占位，不 400 不静默丢）
  - R4 编辑：open/mitigating 全字段可编辑且**编辑即重算覆盖**（提交伪造 risk_value=999 无绑定入口，库值恒=P×I）；**closed 编辑任意字段 → 400"终态只读"**
  - R5 逻辑删 + 审计 risk_delete
  - 关联对象（AC-F1.6）：relatedObjects 逐条 {type∈program/project/case, id} 存在性校验（注入 hierarchy 的 ProgramMapper/ProjectMapper + data 的 CaseService，P3 既有方向），非法 type/不存在 id → 400 指名；空数组/不填合法；被关联对象逻辑删后风险行为不变（deleted 占位仅展示层）
  - 逾期（AC-F1.7）：昨天 → overdue=true、今天/+30 天 → false；closed 后 false；overdueOnly 筛选命中且仅命中逾期行（日期构造统一 `LocalDate.now().plusDays(...)` 相对法）
  - 审计：risk_create / risk_update / risk_delete（detail 含名称/类别/P/I/重算后 value+level）
  - 枚举：RiskStatus / RiskCategory / RiskLevel 三件套（dbValue+fromDbValue+合法值集；非法 400 指名）
- 产物：`governance/Risk.java`、`RiskMapper.java`、`RiskService.java`、`RiskServiceImpl.java`、`RiskStatus.java`、`RiskCategory.java`、`RiskLevel.java`、`dto/RiskVo.java`、`dto/RelatedObjectVo.java`、`controller/RiskController.java`（`/api/v1/risks`，契约 R1~R5）；测试 `governance/RiskServiceImplTest.java`（锚点 3/4/6/7）

### T3 [后端] 合规检查域 5 端点（C1~C5）+ 两枚举
- 模块：eaiselp-runtime/governance
- 类型：Entity+Mapper+Service+Controller+枚举×2
- 依赖：无（零计算器依赖，可与 T1 并行——批A 唯一即启后端任务）
- 验收（AC-F1.8/F1.9/F1.10/F1.11；单测锚点 §9.1-8/9/10/11）：
  - C1 列表：默认 id DESC（PRD 未锁排序，QA 不做排序断言）；筛选 framework/result/overdueOnly（**na 不豁免逾期**）/keyword；VO 含 overdue
  - C2 创建：uk(tenant, check_name) 冲突 400；framework 枚举校验（djba2.0/iso27001/gdpr/custom）；**custom↔framework_name 双向联动**（custom 缺名 400 / custom 带名 200 / 非 custom 带名 400 防脏数据——四例逐条断言）；result 枚举（pass/fail/partial/na）；checkDate 缺省应用层取当天（V7 列可空）
  - C4 编辑：result 覆盖式单值当前态（不建历史表）；**审计 detail 含 oldResult→newResult + 证据**（历史唯一留痕，同 V6 质量规则先例）；非法 result → 400
  - C5 逻辑删 + 审计 compliance_delete；审计 compliance_create/update
  - 逾期（AC-F1.11）：recheck_date=昨天且 result=na → overdue=true（不豁免）；recheck_date 空 → false
- 产物：`governance/ComplianceCheck.java`、`ComplianceCheckMapper.java`、`ComplianceCheckService.java`、`ComplianceCheckServiceImpl.java`、`ComplianceFramework.java`、`ComplianceResult.java`、`dto/ComplianceCheckVo.java`、`controller/ComplianceCheckController.java`（`/api/v1/compliance-checks`，契约 C1~C5）；测试 `governance/ComplianceCheckServiceImplTest.java`（锚点 8~11）

### T4 [后端] 商业案例域基础 CRUD（B1~B5）+ BizCaseStatus 枚举 + 关联战略
- 模块：eaiselp-runtime/governance
- 类型：Entity+Mapper+Service+Controller+枚举（状态机）
- 依赖：T1（BizCaseCalculator）
- 验收（AC-F2.1/F2.4/F2.5/F2.8；单测锚点 §9.1-12/15/16）：
  - B1 列表：默认排序 `rice_score DESC, id DESC`；筛选 status/strategyId（**JSON 内存过滤，分页后过滤同 V6 principleCode 口径**，供 T16 战略反向区复用）/keyword
  - B2 创建：uk(tenant, case_name) 冲突 400；三金额 ≥0（负值 400、全 0 合法触发边界态）；reach/impact/effort 1~10 整数（BigDecimal 校验 0/11/1.5 → 400）；confidence 0.1 步进离散（0.05/0.15/0.85/两位小数 → 400）；四计算列（net_benefit/payback_years/**roi_percent**/rice_score）由 BizCaseCalculator 算后落库（入参 DTO 无此四字段）；构造值回归：{成本100, 运营20, 收益60, R5,I3,C0.8,E6} → {净40, 2.5, 20.00, 2.00}；status 固定 draft
  - B3 详情：related_strategy_ids 解析 `[{id, title, deleted}]`（战略逻辑删 → deleted 占位，计算与流转不受影响）
  - B4 编辑（draft 常规路径）：全字段可编辑且重算覆盖（提交 rice_score=999 无绑定入口）；**非 draft 编辑限制（approved/executing 只读、rejected/done 全只读）与 B5 非 draft 不可删随 T11 状态机闭环**（本任务 BizCaseStatus 枚举先行建好）
  - 关联战略（AC-F2.8）：relatedStrategyIds 逐 id 存在性校验（注入 hierarchy StrategyMapper；t_strategy 无 code 列——存 id，裁决 Q4），无效 id → 400 指名；空数组合法
  - 审计：bizcase_create / bizcase_update / bizcase_delete（detail 含名称+计算字段快照）
- 产物：`governance/BusinessCase.java`、`BusinessCaseMapper.java`、`BusinessCaseService.java`、`BusinessCaseServiceImpl.java`、`BizCaseStatus.java`、`dto/BusinessCaseVo.java`、`dto/RelatedStrategyVo.java`、`controller/BusinessCaseController.java`（`/api/v1/business-cases`，契约 B1~B5）；测试 `governance/BusinessCaseServiceImplTest.java`（锚点 12/15/16）

### T5 [前端][配置] governance-dict.js 追加 6 组字典与样式
- 模块：eaiselp-web-separate
- 类型：前端公共资产增量（P6/G11 前端侧）
- 依赖：无（可与后端并行）
- 验收：追加 riskCategory(5)/riskStatus(3)/riskLevel(4，含四档色阶 class)/complianceFramework(4)/complianceResult(4，含四色徽章)/bizcaseStatus(5) 文案+样式映射，**集中一处**；可被 T6/T7/T8/T14/T15/T16 统一引用；三新页与改造页**禁止散落定义枚举文案**（Reviewer 对照检查）；djba2.0 展示名=等保 2.0
- 产物：`assets/js/governance-dict.js`（改）

### T6 [前端] risk-board.html 风险清单 tab（页面骨架 + 主列表）
- 模块：eaiselp-web-separate
- 类型：新页面（骨架对齐 standard-list.html：筛选条+列表+详情/编辑模态）
- 依赖：T5（字典）；按契约 R1~R6 开发（R6 批B T10 交付后联调，交互可先完整实现）
- 验收（PRD §4.7 要素，AC-F1.1/F1.5/F1.6/F1.7 前端侧）：列表（风险名/类别/概率/影响/风险值/等级四档色阶徽章/owner/状态/复评日期[overdue 红标直消费 VO]）+ 筛选（类别/等级/状态/仅看逾期/关键字）+ 新建/编辑弹窗（P/I 用 1~5 下拉，前端先拦 0/6/小数、后端 400 兜底；计算字段不出现于表单）+ 关联对象三 tab 多选器（项目群/项目/Case）+ 详情模态（关联对象名列表、已删除占位）+ 状态流转操作（closed 弹窗必填处置说明）；写按钮按角色隐藏（gov-write 先例）、后端 403 兜底；引 sanitize.js 字段级转义（§7.3）
- 产物：`pages/risk-board.html`

### T7 [前端] compliance-check-list.html 合规检查页
- 模块：eaiselp-web-separate
- 类型：新页面
- 依赖：T5；契约 C1~C5
- 验收（PRD §4.7 要素，AC-F1.8~F1.11 前端侧）：列表（检查项/框架/条款引用/结果四色徽章/检查日期/复检日期[红标]/owner）+ 筛选（框架/结果/仅看逾期）+ 新建/编辑（**custom 联动**：framework=custom 显示 framework_name 输入、非 custom 隐藏并提交 null——前端后端双向一致）+ 详情；引 sanitize.js
- 产物：`pages/compliance-check-list.html`

### T8 [前端] business-case-list.html 案例清单 tab（页面骨架 + 主列表）
- 模块：eaiselp-web-separate
- 类型：新页面
- 依赖：T5；契约 B1~B7（B7 批B T11 交付后联调）
- 验收（PRD §4.7 要素，AC-F2.1/F2.7/F2.9 前端侧）：列表（名称/状态徽章/RICE/回收期[null→N/A]/**roiPercent 带展示 %**/关联战略数）+ 状态/关联战略筛选 + 新建/编辑弹窗（计算字段只读回显、保存后刷新；confidence 0.1 步进下拉恰 10 档；金额单位元）+ 流转操作（批准/拒绝[必填原因弹窗]/启动/完成——**approve 类按钮仅 admin 角色显示**，后端 403 兜底）+ 决策记录更新入口；引 sanitize.js
- 产物：`pages/business-case-list.html`

### T9 [前端][配置] menu.js 菜单挂载
- 模块：eaiselp-web-separate
- 类型：配置
- 依赖：T6/T7/T8 页面文件存在（同批联调）
- 验收：`COMMON_MENUS` 追加 3 项（风险合规 risk-board / 合规检查 compliance-check-list / 投资决策 business-case-list，全角色可见）；**layerHidden 零改动**（不限层，AC-SWITCH.1 菜单不隐藏）
- 产物：`assets/js/menu.js`（改）

### T10 [后端] 风险状态机流转 R6 + resolution_note 语义 + 审计
- 模块：eaiselp-runtime/governance
- 类型：Service 状态机扩展（D-5；RiskStatus 枚举 T2 已建，本任务接通 transit 端点）
- 依赖：T2
- 验收（AC-F1.4 全路径；单测锚点 §9.1-5）：
  - R6 `POST /{id}/transit`（risk:edit）：open→mitigating 200；mitigating→closed（必填 resolutionNote，空 400）；**mitigating→open 回退 200（§0.3-1 消解：裁决"单向"语义=终态不可回退，回退合法——RiskStatus 注释锚定结论）**；open→closed 跳级 400；closed 任何出边 400；自流转幂等
  - resolution_note 语义：非 closed 状态时该列须置 NULL（V7 注释契约）；closed 必填校验在流转入口
  - 审计 risk_transit（detail 含 from→to + 操作者 + resolutionNote）；每次合法流转一条
  - 错误码：非法流转 400（无新增错误码，裁决 Q9）
- 产物：`governance/RiskServiceImpl.java`（增 transit）、`controller/RiskController.java`（增 R6）；测试 `RiskServiceImplTest.java`（增锚点 5）

### T11 [后端] 案例状态机 B7 + 编辑/删除限制 + B6 决策记录端点
- 模块：eaiselp-runtime/governance
- 类型：Service 状态机 + 端点扩展（D-5/D-6）
- 依赖：T4
- 验收（AC-F2.6/F2.7/F2.9 代码侧；单测锚点 §9.1-13/14）：
  - B7 `POST /{id}/transit`（**bizcase:approve，§0.3-2：四类流转目标统一挂 approve 原子**）：draft→approved、draft→rejected（必填 rejectedReason，空 400）、approved→executing、executing→done 各 200；draft→executing 跳级 400；approved→rejected 400；rejected/done 终态出边 400；自流转幂等
  - 编辑限制闭环（AC-F2.7）：approved/executing 改输入字段 → 400"已批准，输入不可改，请复盘或新建案例"；rejected/done 任何编辑 → 400；**draft 编辑 → 200 且重算**（B4 限制分支接通）
  - 删除限制：仅 draft 可逻辑删；非 draft → 400（合规资产留痕）
  - B6 `PUT /{id}/decision-note`（bizcase:edit）：draft/approved/executing 可更新（执行期进展记录）→ 200；rejected/done → 400；审计 bizcase_decision_note（detail 含旧值→新值）
  - 审计 bizcase_transit（detail 含 from→to + 操作者 + rejectedReason/decisionNote 快照 + 计算字段）
  - AC-F2.9 权限分离由 @RequirePermission(bizcase:approve) 挂 B7 承载（PM 无该原子 → 403；集成断言在 T18）
- 产物：`governance/BusinessCaseServiceImpl.java`（增 transit/decision-note/限制分支）、`controller/BusinessCaseController.java`（增 B6/B7）；测试 `BusinessCaseServiceImplTest.java`（增锚点 13/14）

### T12 [后端] 风险看板聚合端点 R7
- 模块：eaiselp-runtime/governance
- 类型：Service 聚合读 + Controller（D-7；无新表）
- 依赖：T2
- 验收（AC-F1.12/F1.13/F1.14/F1.15；单测锚点 §9.1-17/18/19）：
  - R7 `GET /dashboard`（risk:view，无独立权限原子）：cells 恰 25 格 `{probability, impact, riskValue, riskLevel, count}`（**仅未 closed 计入**；AC-F1.12 数据集断言：3 格计数 1、22 格 0）+ levelDistribution 四档计数（由 (P,I)→level 推导，{低1,中0,高0,极高2} 构造集）+ highRisks 清单（level∈{high,critical} 未 closed，`risk_value DESC, id DESC`，overdue 透传；AC-F1.14 构造集：高15+极高20×2+中12 → 仅 3 条、并列 id 降序）
  - 实现约束：2 查询（GROUP BY probability,impact + 高风险清单查询），聚合经租户拦截器过滤（P11）；无任何写语义端点（写请求 405/404 天然，AC-F1.15）
- 产物：`governance/RiskService.java`/`RiskServiceImpl.java`（增 dashboard）、`dto/RiskDashboardVo.java`、`controller/RiskController.java`（增 R7）；测试锚点 17/18/19

### T13 [后端] 投资组合聚合端点 B8
- 模块：eaiselp-runtime/governance
- 类型：Service 聚合读 + Controller（D-7；无新表）
- 依赖：T4
- 验收（AC-F2.10/F2.11/F2.12；单测锚点 §9.1-20）：
  - B8 `GET /portfolio`（bizcase:view）：cases 全量（含 rejected/done，`rice_score DESC, id DESC`——AC-F2.10 构造集 100→50→2）+ summary 四项**投资口径**（status∈{approved,executing,done}：总一次性投入/总年运营/总年化净收益[可负]/总 3 年净收益=3×Σnet_benefit；空集 COALESCE 0；draft/rejected 不计——AC-F2.11 构造集断言总投入=100/净=40/3年=120）+ statusDistribution 五态计数（**全量口径，与汇总有意不同——同用例双断言**，AC-F2.12）
  - 实现约束：分页查询 + 2 聚合查询；聚合经租户拦截器过滤；Σ 直接用落库列 net_benefit/onetime_cost/annual_op_cost（D-2）
- 产物：`governance/BusinessCaseService.java`/`BusinessCaseServiceImpl.java`（增 portfolio）、`dto/PortfolioVo.java`、`controller/BusinessCaseController.java`（增 B8）；测试锚点 20

### T14 [前端] risk-board.html 看板 tab（5×5 热力图，纯 CSS Grid）
- 模块：eaiselp-web-separate
- 类型：既有新页第二 tab（D-9 并页；裁决 Q5 不引图表库，技术雷达纯 CSS 先例）
- 依赖：T12（R7 契约）+ T6（页面骨架）+ T5（色阶）
- 验收（PRD §4.7 要素，AC-F1.12~F1.14 前端侧）：**5×5 纯 CSS Grid（25 个 div）——X 轴=影响（I1~I5）、Y 轴=概率（P1~P5）写死**；格子底色按该格 riskLevel 四档色阶（governance-dict）、格内计数；等级分布四卡；高风险清单表（名称/P/I/等级/owner/复评日期，overdue 红标透传）；数据源单一 R7
- 产物：`pages/risk-board.html`（增看板 tab）

### T15 [前端] business-case-list.html 投资组合 tab
- 模块：eaiselp-web-separate
- 类型：既有新页第二 tab（D-9 并页）
- 依赖：T13（B8 契约）+ T8（页面骨架）+ T5
- 验收（PRD §4.7 要素，AC-F2.10~F2.12 前端侧）：四卡汇总（**显著标注"仅含已批准/执行中/已完成"投资口径**；负值正常展示；金额万/亿缩写展示——裁决 Q2 前端侧）+ 五态分布（BS 徽章计数）+ RICE 降序全量案例表（状态标注、回收期/ROI null→N/A）；数据源单一 B8
- 产物：`pages/business-case-list.html`（增组合 tab）

### T16 [前端] strategy-board.html 改造：只读"关联商业案例"区
- 模块：eaiselp-web-separate
- 类型：既有页面增量改造（D-8：战略反向展示，零新端点）
- 依赖：T4（B1 strategyId 参数）；契约 B1
- 验收（AC-F2.8 Then 项反向侧）：战略详情区新增只读"关联商业案例"列表（`GET /api/v1/business-cases?strategyId={id}`；显示名称/状态/RICE/回收期；空态文案"暂无关联投资"）；战略逻辑删场景由案例侧 deleted 占位承载（本页不涉及）；**风险四对象反向本期不做（PRD §7-8，不对称是有意的 Q3 备注）**；引 sanitize.js
- 产物：`pages/strategy-board.html`（改）

### T17 [前端] 场景 C 适配：关联选择器静默降级
- 模块：eaiselp-web-separate
- 类型：前端容错（SE §7.4）
- 依赖：T6/T8（两处选择器所在页）
- 验收（AC-SWITCH.1 Then 项）：risk-board 关联对象选择器加载 `/api/v1/strategies`（L3 关→43001）、`/api/v1/programs`、`/api/v1/projects`（L2 关→43002）时，页面层对 43001/43002 **静默降级为空列表**（不弹错误、对应 tab 显示"暂无可选对象"）；Case tab 恒正常；bizcase 关联战略选择器同口径（L3 关→空列表不报错）；三域自身页面/API 任何层开关组合下完整可用
- 产物：`pages/risk-board.html`（改）、`pages/business-case-list.html`（改）

### T18 [测试] RBAC/幂等/跨租户/审计集成收口 + 全量回归
- 模块：eaiselp-runtime
- 类型：集成测试与回归（SE §9.1-21~24 + §9.2 回归项）
- 依赖：T1~T4/T10~T13（全部后端）
- 验收（AC-RBAC.1/2/3/4/5 + AC-SWITCH.1 + AC-AUDIT.1 集成侧）：
  - 权限注解反射断言矩阵（锚点 21）：三 Controller 全部 20 端点 → @RequirePermission 值逐格对照 SE §6 表（R1/R3/R7=risk:view；R2=risk:create；R4/R5/R6=risk:edit；C1/C3=compliance:view；C2=compliance:create；C4/C5=compliance:edit；B1/B3/B8=bizcase:view；B2=bizcase:create；B4/B5/B6=bizcase:edit；**B7=bizcase:approve**）——防 Dev 漏挂
  - V7 幂等（锚点 22）：干净库 V1~V7 后 permission 10 行/role_permission 33 行 + **重放一次行数不变**（AC-RBAC.5）
  - 跨租户（锚点 23）：租户 B 三域列表/两聚合空 + 直查 404（AC-RBAC.4，拦截器语义）
  - 403 矩阵（锚点 24）：PM 风险 create/edit 200、compliance create 403、bizcase create/edit 200、**transit 全 403**；engineer/executive 写全 403、view/看板/组合 200（AC-RBAC.2/3）
  - 审计全覆盖断言：SE §8.1 十一类 action 逐类 ≥1 条、detail 含操作者与关键变更值（含被覆盖旧 result/旧 decision_note——AC-AUDIT.1）
  - 回归：V6 四域 + 既有 34 页全部既有用例**不动通过**（本 case 对既有代码零行为改动，仅 menu/dict/strategy-board 增量）
- 产物：`eaiselp-runtime/src/test/java/com/eaiselp/runtime/controller/`（反射矩阵测试）+ 集成测试类；回归执行记录由 QA 承接落 `docs/测试报告/`

---

## 给 QA 的断言标注（34 AC 中需特殊注意的口径——消解表与边界，防"实现正确但用例 FAIL"假缺陷）

| # | 主题 | QA 用例口径（权威） |
|---|---|---|
| 1 | **消解表 §0.3-1：风险回退**（裁决 Q3"单向"与 AC-F1.4 字面冲突） | **照 AC-F1.4 原文执行：mitigating→open → 200（回退合法）**；裁决"单向"语义收窄为"closed 终态不可 reopen、无终态回退"。closed→任何 = 400、open→closed 跳级 = 400 |
| 2 | **消解表 §0.3-2：案例 transit 权限**（PRD 仅锁定批准/拒绝） | B7 四类流转（approved/rejected/executing/done）**整体挂 bizcase:approve**：PM 对 draft→approved 与 draft→rejected 均 403（AC-F2.9 原文），对 approved→executing / executing→done 同样 403（SE 裁定延伸，可顺带断言） |
| 3 | **BigDecimal 边界断言**（D-4 磁盘事实） | probability/impact/reach/impact/effort = 1.5 等**非整数、confidence = 0.05/0.15/0.85（合法小数但非 0.1 步进）→ 断言 400 指名字段，不得 50000**（GlobalExceptionHandler 无反序列化专项处理，DTO BigDecimal 承载是唯一防线） |
| 4 | **字段名与 N/A 语义**（V7 差异定稿） | ROI 出参字段 = **`roiPercent`**（V7 列 roi_percent；数值 20.00 表示 20.00%，带 % 展示由前端拼）；paybackYears/roiPercent 为 **null = N/A**（两种 N/A 语义来源不同：payback=net≤0 不可投、roi=onetime=0 除零防御）；onetime=0 且 net>0 → paybackYears=**0.0 非 null** |
| 5 | **双口径并存断言**（AC-F2.11/F2.12） | 组合汇总（投资口径 status∈approved/executing/done）与状态分布（全量五态）**同用例内双断言**；风险看板三件套（cells/分布/高风险）均**排除 closed**（AC-F1.12~F1.14） |
| 6 | **溢出防御**（D-11/R5） | onetime=0.01、net≈1e12 极值 → 200 不 500（roi_percent DECIMAL(20,2) 列宽预算） |
| 7 | **排序断言锁死** | 风险列表 `risk_value DESC, id DESC`（并列新建在前）；案例列表/组合 `rice_score DESC, id DESC`；**合规列表默认 id DESC（PRD 未锁排序，不做排序断言）** |
| 8 | **已知语义非缺陷** | P=1,I=5 → v=5 → 低（纯数学映射，PRD §4.1.2 已写死，不接受"为什么不是高"类质疑工单）；na 复检逾期不豁免 |

其余 AC（F1.1/F1.5/F1.6/F1.7/F1.8/F1.9/F1.10/F1.11/F2.1/F2.5/F2.6/F2.7/F2.8/RBAC/SWITCH/AUDIT）按 PRD 原文直接写用例。

---

## 变更影响评估

- **架构变更：是（模块化单体内轻度扩展）**——既有 `com.eaiselp.runtime.governance` 包平铺追加三域（D-1，49 文件级追加）；跨包注入 hierarchy 的 Strategy/Program/Project Mapper 与 data 的 CaseService 属**P3 既有依赖方向内的消费**（V6 先例同构），无新方向无环；启动类/@MapperScan/租户拦截器/LayerGuard/全局异常处理器**五处零改动**（SE §2.1 零改动清单）。部署拓扑/服务清单/中间件均不变。
- **需求调整：是**——新增 5 功能点（F1.1/F1.2/F1.3/F2.1/F2.2），影响功能域：L3 治理收口（GRC 风险合规 + 战略投资决策，PRJ-004 尾巴闭口，三层全角色闭环）。属新 case 立项本身；对既有功能仅 strategy-board.html 一页增量只读区 + menu/dict 增量，无破坏性变更。
- **接口契约变更：是（纯新增，无破坏）**——新增 20 端点（R1~R7/C1~C5/B1~B8，见 api-contracts.md），全部 `/api/v1/` 前缀；既有端点契约零改动（G14 不升版本）；无新增错误码（裁决 Q9，沿用 40000/400/40101/40301/40400 家族）。
- **编码规范变更：否**——CLAUDE.md 与 ES-001/002/003 均未修改；本 case 严格遵循既有规范（G13 租户隔离/G14 v1 前缀/G11 无角色硬编码/V7 幂等惯例）。
- **文档更新建议**：
  - [不需更新] `D:\AI\mywork\platform\CLAUDE.md` — 原因：模块清单、分层规范、门禁规则均无变化；CLAUDE.md 由 team-standards 治理，本 case 无规范级变更
  - [不需更新] `D:\AI\mywork\platform\README.md` — 原因：内容为服务级概览（服务清单/端口/快速开始），模块清单与职责无变化，不列功能点清单
  - [需更新（轻微）] `D:\AI\mywork\eaiselp-web-separate\README.md` — 原因：`governance-dict.js` 描述行括号枚举了字典范围，应补 6 组新字典字样（随 T5 交付顺手完成；pages/ 下页面为示例性列举非全量清单，新页面无需逐页补）
  - [需更新] `D:\AI\mywork\platform\docs\过程跟踪文档\changelog.md`（共享区） — 原因：ES-002 §8.2 工程级共享持续维护文档，case 收尾时记录本 case 交付（Ops/PM 动作，非 Dev 任务）
  - [不需更新] `docs\架构文档\*`（ADR/工程标准/质量门禁） — 原因：无规范变更、无 ADR 级架构决策（governance 包内追加属 SE 方案已裁决范畴）
  - [不需更新] Swagger/OpenAPI — 原因：平台 M1 未引入 springdoc（CLAUDE.md §9.4，SP-2/SP-3 才落地），无 Swagger 资产存在；api-contracts.md 即本 case 接口文档权威
  - [不需更新] PRD/编排者裁决/技术方案/数据库设计 — 原因：均为本 case 过程产物已定稿；V7 与 SE 方案差异已在本清单"拆解声明 3"定稿并随清单下发 Dev/QA
- **README 更新清单（本次改动涉及的目录逐个判断）**：
  - [不需更新] `eaiselp-runtime/`（无 README） — 原因：不存在模块 README，按现状不新增（模块说明由 CLAUDE.md §3 承载）
  - [需更新（轻微）] `D:\AI\mywork\eaiselp-web-separate\README.md` — 原因：如上，governance-dict.js 描述行补 6 组新字典
  - [不需更新] 根 `D:\AI\mywork\platform\README.md` — 原因：非项目级变化（服务清单/端口/铁律不变）

## 范围外（显式声明，防镀金；均承 PRD §7，不设"转后续"暗债）

自动风险探测（§7-1）、自动合规扫描（§7-2，PRJ-005+）、财务系统集成（§7-3）、多币种与汇率（§7-4，PRJ-006+）、风险-项目自动联动（§7-5）、合规条款符合性自动判定（§7-6）、NPV/IRR/敏感性分析/蒙特卡洛（§7-7，回收期/ROI/RICE 三口径封顶）、**风险关联四对象反向聚合展示（§7-8，本期仅战略侧反向——不对称是有意的）**、评论协作流与到期推送提醒（§7-9）、风险多轮评估历史与残留风险再评分（§7-10，缓解后重评=直接编辑 P/I 留审计）、三域接入全局搜索（§7-11）、approved 自动立项联动（§7-12）。

**平台级技术债显式报备（非本 case 范围，需编排者+用户确认排期）**：SE 方案经验沉淀 2 提出"GlobalExceptionHandler 补 `HttpMessageNotReadableException` → 400 平台级兜底"（记为 V7+ 债务）——本 case 以 DTO BigDecimal 承载规避（D-4），**不扩面实施**；该兜底属全局异常链改造，影响全部既有 Controller 的错误语义，应独立立项。

---

## 本次经验沉淀

1. **SE 方案与 DBA 落盘 DDL 出现差异时，BA 必须产出"差异定稿表"进任务清单**：本 case V7 将 `roi` 定稿为 `roi_percent`、否决了两条排序索引、放宽了三处列宽——若 Dev 按 SE 方案字段名写实体、QA 按 PRD 字段名断言、契约再按方案旧名出参，三方错位且编译期不可见。BA 动笔前 grep 落盘迁移文件核对列名/索引/可空性，差异表随清单下发，是"磁盘事实优先"原则在 BA 侧的落地动作。
2. **计算类需求的拆解定式：计算器+边界单测独立成先行任务，并把全部数值 AC 集中映射到它的验收行**——本 case 12+ 条数值断言（12/20/25 边界、双 N/A 语义、confidence 离散、HALF_UP 舍入）全部收敛到 T1 单点锁定，后续批次口径争议归零；这与 PO 侧"公式四件套"经验构成上下游配对（PRD 给口径、计算器锁口径、QA 复用构造值），可直接沉淀为体系级拆解模板触发规则：凡 PRD 出现"自动算"字样，任务清单必含独立计算器先行任务。
3. **消解表必须三处落位（SE 方案 / BA 给 QA 标注 / 受影响任务验收行），不能只留一处**：本 case §0.3 两处消解（mitigating→open 回退合法、transit 整体挂 approve）若只在 SE 方案，Dev 读任务清单实现、QA 读 AC 写用例时仍会各执一词；先例 case-20260820 已验证"翻译表进任务清单"有效，本次进一步把消解结论写进 T10/T11 验收行与 RiskStatus 注释锚点要求，形成三重锚定。
