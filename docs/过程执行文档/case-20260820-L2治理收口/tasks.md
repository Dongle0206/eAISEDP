# 任务清单 — case-20260820-L2治理收口（标准库+数据治理+试用到期拦截）

| 字段 | 值 |
|---|---|
| 产出者 | team-ba（L1 需求分析员） |
| 日期 / 版本 | 2026-08-20 / v1.0 |
| 上游基线 | PRD v1.0（31 AC）；编排者裁决 Q1~Q9；SE 技术方案 v1.0（**§11 四批拆解序为本清单唯一拆解基线**）；DBA V6 + schema-h2.sql + 数据库设计（已落盘，SE 评审通过零修改） |
| 下游 | team-dev（认领执行）/ team-qa（AC 基线 + §QA 断言翻译标注）/ team-reviewer（公共验收约束为检查基线） |
| 配套契约 | 同目录 `api-contracts.md`（25 端点 + 登录响应扩展；前端任务直接引用契约编号 S1~S6/T1~T6/A1~A5/Q1~Q6/U1/U2） |

**拆解声明**：
1. 本清单严格按 SE 技术方案 §11 四批拆解序组织（批A 四域 CRUD+前端 → 批B 标准状态机+门禁打通 → 批C F3 到期拦截独立线 → 批D 恢复路径），未另起炉灶；与 PRD 表述冲突处均以 SE 方案+裁决为准并逐处标注（见 §QA 断言翻译标注）。
2. **DB 层已由 DBA 交付完成**（T0，标"已完成"）：V6 迁移、H2 schema、权限 seed 均已落盘，Dev 零动作。
3. SE 方案留白定稿 1 处：`TrialTipVo` 落 **eaiselp-common**（SE §4.1 给出"common 或 auth"两选项；因 `TenantSubscriptionService` 在 eaiselp-data 返回该类型，P3 禁止 data→auth 依赖，唯一可行解为 common）。此为定稿而非偏离。

**仓库根约定**（下文产物路径均为相对路径）：
- 后端仓库：`D:\AI\mywork\platform\`
- 前端仓库：`D:\AI\mywork\eaiselp-web-separate\`

---

## 公共验收约束（Reviewer 检查基线，适用于 T2~T5/T12/T13/T17~T20/T22 全部后端任务）

1. **Controller 薄层**：参数接收→Service 调用→`R<T>` 返回；请求 DTO 内嵌 static class；除"target 不能为空"级判空外不写业务校验（AdrController 先例）。
2. **Service 承载**：枚举/存在性/状态机/uk 兜底/审计/事务；`ServiceImpl<Mapper, Entity>` 继承 MP 基类；业务校验失败 `BizException(400, msg)`。
3. **tenant_id 只从 `TenantContext`/`LoginUser` 取**；禁止请求参数传 tenant_id、禁止 `@InterceptorIgnore`（G13 BLOCKER）。
4. **uk 冲突统一形态**：捕获 `DuplicateKeyException` → `BizException(400, "...已存在: ...")`（AdrServiceImpl:327-346 先例）——四域一致，不允许各写各的文案风格。
5. **审计统一**：全部走 `AuditService.log`，action/resource_type/detail 必含字段按 SE 方案 §8.1 清单逐条对照（AC-AUDIT.1）。
6. **枚举三件套**（AssetType/Sensitivity/CheckType）：`dbValue`+`fromDbValue`+合法值集；非法值 400 且 message 指名字段与合法值集合（AC-F2.2）。模板类型不校验（开放字典，P6 裁决）。
7. **风格一致性**：新端点一律 `/api/v1/` 前缀（G14）；代码无 team-* 字面量（G11）；四域 Controller/Service/测试命名与 hierarchy/ADR 先例同构。

---

## 任务总览

- **总数 24**：T0 已完成（DBA 交付）+ 开发任务 23（后端 13 / 前端 8 / 配置 1 / 测试回归 1；单测内嵌各任务，SE §9.1 编号 1~30 为锚点）。
- **并行组**（SE §11 四批序）：
  - **批A**（T1 半日先行，随后全并行）：T2/T3/T4/T5（四域后端）∥ T6/T7/T8/T9/T10/T11（前端线）
  - **批C**（独立线，与批A 即刻并行）：T17 → T18 → T19 ∥ T20 → T21
  - **批B**（依赖批A 的 T2/T7）：T12 ∥ T13 → T14 ∥ T15 → T16
  - **批D**（依赖批C 的 T18）：T22 → T23
- **关键路径**：`T1 → T2 → T12 → T13 → T16`（标准域最深链：包骨架 → CRUD → 状态机+自动取代 → 门禁打通查询 → 编排回归）。批C 线（T17→T18→T19→T22→T23）较短，不构成关键路径。
- **31 AC 全覆盖**，映射见各任务"验收"行；无孤儿 AC、无范围外遗留。

## 任务明细

### T0 [DB] V6 迁移 + H2 schema + 权限 seed —— **已完成（DBA 交付，Dev 零动作）**
- 模块：eaiselp-runtime 数据层
- 类型：数据库迁移/Schema（D-2 全盘采纳）
- 依赖：无
- 验收（已达成）：四表 DDL（t_standard/t_template/t_data_asset/t_data_quality_rule）+ 12 权限原子（id 1059~1070）+ 36 授权行（id 2134~2169），零 ALTER/零 UPDATE 天然幂等；AC-RBAC.4、AC-SWITCH.1 数据侧
- 产物（已落盘）：`eaiselp-runtime/src/main/resources/db/migration/V6__l2_governance_close.sql`；`eaiselp-runtime/src/test/resources/schema-h2.sql`；`docs/设计规划文档/case-20260820-L2治理收口-数据库设计.md`

### T1 [配置] governance 包骨架 + @MapperScan 增补
- 模块：eaiselp-runtime
- 类型：配置/骨架（D-1）
- 依赖：无（T0 已就绪）
- 验收：新建包 `com.eaiselp.runtime.governance`（含 dto 子包）；`EaiselpRuntimeApplication` 的 `@MapperScan.basePackages` 增补该包；应用启动无报错、既有测试全绿（确认 annotationClass=Mapper.class 下 Service 接口无误注册风险）
- 产物：`eaiselp-runtime/src/main/java/com/eaiselp/runtime/governance/`（包）；`eaiselp-runtime/src/main/java/com/eaiselp/runtime/EaiselpRuntimeApplication.java`（改）

### T2 [后端] 工程标准基础 CRUD（S1~S5）+ StandardStatus 枚举
- 模块：eaiselp-runtime/governance
- 类型：Entity+Mapper+Service+Controller+枚举
- 依赖：T1
- 验收（AC-F1.1/F1.5/F1.6）：
  - uk(tenant, code, version)：同编号同版本 400（DuplicateKeyException→400 统一形态）、同编号异版本共存；编号缺省生成 `STD-NNNN`（%04d 续推，复刻 AdrServiceImpl.maxVisibleSuffix，冲突重试 ≤3 次；自定义编号冲突直接 400）——单测锚点 10/11
  - 编辑限制：draft 全字段可编辑；published/deprecated 编辑任意字段 → 400"发布后不可编辑，请升版"；逻辑删后列表不可见+审计——单测锚点 14
  - 关联原则：relatedPrincipleCodes 逐 code 存在性校验（无效 400 指名）；空数组合法；列表 principleCode 筛选命中（JSON 内存过滤，ADR §4.2 口径）
  - relatedGateNames 入参存储 + 逐 name 存在且 enabled 校验（查既有 t_quality_gate_rule，不存在/已停用 400）——批B T13 补查询侧
  - 审计：standard_create/standard_update/standard_delete
- 产物：`governance/Standard.java`、`StandardMapper.java`、`StandardService.java`、`StandardServiceImpl.java`、`StandardStatus.java`（canTransitionTo/必填项内聚纯逻辑，复刻 AdrStatus）、`dto/StandardVo.java`、`controller/StandardController.java`（路径 `/api/v1/standards`，契约 AC-S1~S5）；测试 `eaiselp-runtime/src/test/java/com/eaiselp/runtime/governance/StandardServiceImplTest.java`

### T3 [后端] 模板库全部 6 端点（T1~T6）
- 模块：eaiselp-runtime/governance
- 类型：Entity+Mapper+Service+Controller
- 依赖：T1
- 验收（AC-F1.9/F1.10/F1.11/F1.12）：
  - uk(tenant, type, name) 三例：同型同名拒、异型同名合法；类型开放字典无枚举校验（自定义值"复盘报告"可存可筛）——单测锚点 17/18
  - 占位符：详情实时提取 `\{\{([A-Za-z0-9_]+)\}\}` 去重排序，不落库；无占位符空清单合法——单测锚点 19
  - 原地升版：编辑时 version 必须 ≠ 当前值（相同 400"版本必须变更"，不比较大小）；审计 template_update detail 含 oldVersion——单测锚点 20
  - 启停：PUT /{id}/enabled（对齐 gate-rules 先例）；停用默认隐藏、includeDisabled=1 可见；审计 template_status/template_create/template_delete——单测锚点 21
- 产物：`governance/Template.java`、`TemplateMapper.java`、`TemplateService.java`、`TemplateServiceImpl.java`、`dto/TemplateVo.java`、`controller/TemplateController.java`（`/api/v1/templates`，契约 AC-T1~T6）；测试 `governance/TemplateServiceImplTest.java`

### T4 [后端] 数据资产 5 端点（A1~A5）+ 资产删除联动
- 模块：eaiselp-runtime/governance
- 类型：Entity+Mapper+Service+Controller+枚举×2
- 依赖：T1；AC-F2.7 联动子项需 T5 的 `DataQualityRuleMapper`（并行开发时先提交该 mapper 接口，BaseMapper 无逻辑）
- 验收（AC-F2.1/F2.2/F2.3/F2.4/F2.7）：
  - uk(tenant, system, name)：同系统同名拒、跨系统同名合法——单测锚点 22
  - 枚举校验：AssetType/Sensitivity 非法值 400 指名字段与合法值集；5×4 全组合 200——单测锚点 23
  - 四维筛选：assetType/sensitivity/tag（JSON_CONTAINS 单选命中）/keyword（name+system LIKE）——单测锚点 24；标签候选聚合供前端筛选器
  - **删除联动**：逻辑删资产时关联质量规则同步逻辑删（idx_dqr_tenant_asset 定位），审计 asset_delete detail 含 ruleIds 数组可辨识——单测锚点 25
  - 审计：asset_create/asset_update/asset_delete
- 产物：`governance/DataAsset.java`、`DataAssetMapper.java`、`DataAssetService.java`、`DataAssetServiceImpl.java`、`AssetType.java`、`Sensitivity.java`、`dto/DataAssetVo.java`、`controller/DataAssetController.java`（`/api/v1/data-assets`，契约 AC-A1~A5）；测试 `governance/DataAssetServiceImplTest.java`

### T5 [后端] 质量规则 6 端点（Q1~Q6）
- 模块：eaiselp-runtime/governance
- 类型：Entity+Mapper+Service+Controller+枚举
- 依赖：T1
- 验收（AC-F2.5/F2.6/F2.7 部分）：
  - uk(tenant, ruleName) 同名拒；assetId 存在且**未逻辑删**（已删资产 id → 400）；checkType 枚举校验；threshold ∈ [0,100]（100.5/−1 → 400，边界 0/100 合法）——单测锚点 26
  - **登记覆盖式更新**：POST /{id}/check-results 覆盖 last_* 四列（result=pass|fail 登记人判定，平台不做阈值自动判定）；审计 dqrule_check_result detail 含旧值→新值+登记人（"旧值不覆盖审计"）——单测锚点 27
  - 资产详情聚合（A3）：规则数 + 各规则最近结果（idx_dqr_tenant_asset 命中）——支撑 AC-F2.6 Then
  - 审计：dqrule_create/dqrule_update/dqrule_delete/dqrule_check_result
- 产物：`governance/DataQualityRule.java`、`DataQualityRuleMapper.java`、`DataQualityRuleService.java`、`DataQualityRuleServiceImpl.java`、`CheckType.java`、`dto/DataQualityRuleVo.java`、`controller/DataQualityRuleController.java`（`/api/v1/data-quality-rules`，契约 AC-Q1~Q6）；测试 `governance/DataQualityRuleServiceImplTest.java`

### T6 [前端][配置] governance-dict.js 枚举文案与样式集中定义
- 模块：eaiselp-web-separate
- 类型：前端公共资产（P6/G11 前端侧）
- 依赖：无（可与后端并行）
- 验收：标准状态/资产类型/敏感等级（含四档色阶 class，机密红色警示）/检查类型/试用提示三档（normal 蓝/warning 黄/critical 红）的文案+样式映射集中**一处**；可被 T7~T10、T14、T15、T21 统一引用；四新页与改造页**禁止**散落定义枚举文案（PRD §0 前置约束，Reviewer 对照检查）
- 产物：`assets/js/governance-dict.js`

### T7 [前端] standard-list.html 工程标准页
- 模块：eaiselp-web-separate
- 类型：新页面（骨架对齐 adr-list.html：筛选条+列表+详情/编辑模态）
- 依赖：T6；按契约 AC-S1~S6 开发（S6 端点批B T12 交付后联调，页面交互可先完整实现）
- 验收（PRD §4.5 要素）：列表（编号/标题/版本/状态/关联原则/被引用门禁数）+ 状态筛选（缺省 draft+published，显式含 deprecated）+ 原则筛选 + 详情（五要素+正文 markdown 预览+双向关联区）+ 新建/编辑（draft）+ 发布/废弃确认（废弃必填原因，S6）+ 升版入口（预填同 code 新建 draft）；关联门禁选择器仅列 enabled 规则（§4.5 翻译口径）；写按钮按角色隐藏、后端 403 兜底
- 产物：`pages/standard-list.html`

### T8 [前端] template-list.html 模板库页
- 模块：eaiselp-web-separate
- 类型：新页面
- 依赖：T6；契约 AC-T1~T6
- 验收：列表（类型/名称/版本/启用/占位符数）+ 类型筛选（预置 5 值 + 已用自定义值聚合进候选）+ 启停开关 + 详情（正文预览+占位符清单）+ 新建/编辑（版本未变更的改版提示，前端校验+后端 400 双保险）
- 产物：`pages/template-list.html`

### T9 [前端] asset-list.html 数据资产页
- 模块：eaiselp-web-separate
- 类型：新页面
- 依赖：T6；契约 AC-A1~A5 + AC-Q1（资产详情聚合规则）
- 验收：列表（名称/系统/类型/责任人/敏感等级四档色阶/标签）+ 四维筛选（类型/等级/标签候选聚合/关键字）+ 详情（字段+关联质量规则数与各规则最近结果）+ 新建/编辑；敏感等级色阶引用 governance-dict（AC-F2.3/F2.4 筛选断言 + 等级视觉区分）
- 产物：`pages/asset-list.html`

### T10 [前端] quality-rule-list.html 质量规则页
- 模块：eaiselp-web-separate
- 类型：新页面
- 依赖：T6；契约 AC-Q1~Q6 + AC-A1（资产选择器分页搜索）
- 验收：列表（规则名/关联资产/检查类型/阈值附 %/最近结果+时间）+ 类型/结果筛选 + 登记"最近检查"弹窗（结果 pass|fail/实测值/时间缺省当前/备注）+ 新建/编辑（资产选择器、阈值 0~100 前端提示）
- 产物：`pages/quality-rule-list.html`

### T11 [配置][前端] menu.js 菜单挂载
- 模块：eaiselp-web-separate
- 类型：配置（D-10）
- 依赖：T7~T10 页面文件存在（同批联调）
- 验收：`COMMON_MENUS` 追加 4 项（工程标准 standard-list / 模板库 template-list / 数据资产 asset-list / 质量规则 quality-rule-list，全角色可见）；**layerHidden 零改动**（不限层，AC-SWITCH.1 菜单不隐藏）
- 产物：`assets/js/menu.js`（改）

### T12 [后端] 标准状态机流转 S6 + 发布自动取代事务
- 模块：eaiselp-runtime/governance
- 类型：Service 事务逻辑（D-7/D-8）
- 依赖：T2
- 验收（AC-F1.2/F1.4）：
  - 状态机全路径：draft→published、published→deprecated（必填原因，空 400）、draft→deprecated（必填原因）、deprecated→任何 400、published→draft 400——单测锚点 12
  - 发布自动取代：FOR UPDATE 事务（`SELECT ... WHERE tenant_id AND code AND status='published' FOR UPDATE` → 旧版置 deprecated（原因含"被 {code} {新版本} 取代"字样）→ 新版置 published）；同编号至多一个 published；**双审计**（standard_transit + standard_auto_deprecate）——单测锚点 13（H2 可跑 FOR UPDATE 路径）
  - 列表缺省 draft+published、deprecated 显式筛选可见
- 产物：`governance/StandardServiceImpl.java`（增 transit 方法）；测试 `StandardServiceImplTest.java`（增锚点 12/13 用例）

### T13 [后端] 标准-门禁打通查询侧（gateName 筛选 + 详情被引用 + D-9 已删除占位）
- 模块：eaiselp-runtime/governance
- 类型：Service 查询 + Mapper 手写 SQL（D-9）
- 依赖：T2
- 验收（AC-F1.3/F1.7 **按 §4.5 翻译后断言**，见 §QA 标注）：
  - S1 增 gateName 筛选参数（JSON 内存过滤，status=published 口径）——打回解析与规则页"已关联标准"复用
  - S3 详情"被引用门禁列表"（relatedGateNames 解析 name→规则当前信息，悬空 name 展示"未找到/已删除"占位）
  - **D-9 已删除占位查询**：StandardMapper 手写 `@Select` 旁路 @TableLogic（显式 is_deleted IN (0,1)），供 gateName 反查展示"已删除"行；方法级注释声明用途；仍被租户拦截器改写（不违 G13）——单测锚点 16
  - 门禁判定逻辑零改动（t_quality_gate_rule 与编排引擎代码不动）
- 产物：`governance/StandardMapper.java`（增手写 @Select）、`StandardServiceImpl.java`（筛选/详情扩展）；测试锚点 15/16

### T14 [前端] gate-rule-list.html 改造：只读"已关联标准"区
- 模块：eaiselp-web-separate
- 类型：既有页面增量改造
- 依赖：T13（gateName 查询可用）；契约 AC-S1（gateName+status=published 调用）
- 验收（AC-F1.7 前端侧，翻译后）：规则详情/编辑区新增只读"已关联标准"列表（`GET /api/v1/standards?gateName={name}&status=published`）；条目含"已删除"占位形态；不改规则编辑提交结构（关联在标准侧操作）
- 产物：`pages/gate-rule-list.html`（改）

### T15 [前端] case-detail.html 改造：打回原因旁"依据标准"
- 模块：eaiselp-web-separate
- 类型：既有页面增量改造
- 依赖：T13；契约 AC-S1 + 既有 `GET /api/v1/gate-rules/{id}`
- 验收（AC-F1.8）：按 steps_json 的 gateRuleId 两跳解析（规则 name → `standards?gateName=&status=published`）在打回原因旁渲染"依据标准：{code}《{title}》{version}"；无命中**不渲染空占位**；不改 steps_json 结构、不改门禁判定
- 产物：`pages/case-detail.html`（改）

### T16 [测试] 编排与门禁集成回归（批B 收口门禁）
- 模块：eaiselp-runtime
- 类型：集成回归（SE §9.1 回归项）
- 依赖：T12/T13/T14/T15
- 验收：OrchestrationServiceGateGuardTest / GateMarkTest / 既有 Controller 测试**全部不动通过**（AC-F1.7 Then"门禁判定行为不变"的既有编排用例回归）；批A/B 全部单测（锚点 10~16/17~21/22~27）绿
- 产物：回归执行记录（落 `docs/测试报告/qa-case-20260820-*.md` 由 QA 承接，Dev 侧跑通并在 dev-report 引用结果）

### T17 [后端][配置] ResultCode 40003 + TrialTipVo + LoginResponse.trialTip
- 模块：eaiselp-common / eaiselp-auth
- 类型：公共契约扩展（D-5）
- 依赖：无（批C 起点，可与 T1 同时启动）
- 验收：`ResultCode.TRIAL_EXPIRED = 40003`（message 含"试用已到期"基线文案）；`TrialTipVo`（daysLeft:int / level:normal|warning|critical / expireTime:yyyy-MM-dd HH:mm:ss）落 eaiselp-common（定稿理由见拆解声明 3）；`LoginResponse` 增可空字段 trialTip（null 时序列化不出现）；两模块编译通过、既有测试零回归
- 产物：`eaiselp-common/src/main/java/com/eaiselp/common/result/ResultCode.java`（改）、`eaiselp-common/src/main/java/com/eaiselp/common/dto/TrialTipVo.java`（新）、`eaiselp-auth/src/main/java/com/eaiselp/auth/dto/LoginResponse.java`（改）

### T18 [后端] TenantSubscriptionService 共享判定核心
- 模块：eaiselp-data
- 类型：Service（D-4，接口+Impl）
- 依赖：T17（TrialTipVo 类型）
- 验收（AC-F3.1/F3.2/F3.3/F3.4 口径层，**PRD §4.3.1 唯一口径，禁止自造**）：
  - `assertNotExpired(tenantId)`：trial 且 expire 非空且 now≥expire（含等于）→ BizException(40003, message 含"试用已到期"+升级指引)；trial+NULL → WARN 日志后放行（Q4）；非 trial → 直接放行（expire 完全忽略）
  - `buildTrialTip(tenant)`：非 trial/NULL/(expire−now)>7×24h → null；否则 N=ceil((expire−now)/24h)，level：N=1→critical（红色优先）、2≤N≤3→warning、4≤N≤7→normal
  - **不做任何缓存**（恢复路径下次登录即生效）；主键单查
  - 单测锚点 1~5（到期判定/豁免×3/NULL/四租户 T8/T7/T3/T1 数值断言/7×24h 边界；时间构造统一 `LocalDateTime.now().plusHours(...)` 相对法）
- 产物：`eaiselp-data/src/main/java/com/eaiselp/data/service/TenantSubscriptionService.java`、`impl/TenantSubscriptionServiceImpl.java`；测试 `eaiselp-data/src/test/java/com/eaiselp/data/service/TenantSubscriptionServiceTest.java`

### T19 [后端] AuthServiceImpl 登录链路改造
- 模块：eaiselp-auth
- 类型：既有链路改造（SE §4.2）
- 依赖：T17、T18
- 验收（AC-F3.1/F3.2/F3.3/F3.4 后端侧）：
  - ④.5 插入（账户禁用校验后、签发 JWT 前）：原⑥租户查询**提前合并**（全链路仍只查一次）→ assertNotExpired → 到期抛 40003：**不签发 JWT、不更新 last_login_at**；审计 login_trial_blocked（resource_id=tenantId + detail 含 tenantId/username/expireTime——AC-F3.1 断言点）
  - **防枚举顺序**：错密码+到期租户 → 40001 而非 40003——单测锚点 7
  - 成功路径：trialTip = buildTrialTip(tenant) 塞入响应——单测锚点 6/8（含 message 文案、lastLoginAt 未更新 verify、四档 tip 断言）
  - 回归：TC1~TC6 原用例不动通过——单测锚点 9
- 产物：`eaiselp-auth/src/main/java/com/eaiselp/auth/service/impl/AuthServiceImpl.java`（改）；测试 `eaiselp-auth/src/test/java/com/eaiselp/auth/service/impl/AuthServiceImplTest.java`（改，增锚点 6~9）

### T20 [后端] RuntimeController 派生前置校验（/derive + /orchestrate）
- 模块：eaiselp-runtime
- 类型：既有入口改造（SE §4.3，双点拦截）
- 依赖：T18
- 验收：两入口在参数校验后、资源预占前插 `assertNotExpired(TenantContext.get())`；到期 → 40003，**不 createPending、不 start、不烧 token**（Mock Service verify 零调用——单测锚点 30）；审计 derive_trial_blocked / orchestrate_trial_blocked（resource_type=tenant，detail 含 tenantId/username）；既有派生用例零回归
- 产物：`eaiselp-runtime/src/main/java/com/eaiselp/runtime/controller/RuntimeController.java`（改）；测试 `RuntimeControllerTest`（增锚点 30 用例）

### T21 [前端] login.html + 全局提示条 + api.js 40003 处理
- 模块：eaiselp-web-separate
- 类型：既有页面改造（SE §7.3）
- 依赖：T6（三档样式）、T19 联调（可按契约先行）
- 验收（AC-F3.1/F3.2/F3.5 前端侧）：
  - login.html：code=40003 特判渲染"试用已到期+升级指引"红色文案（不落通用错误弹窗）；登录成功响应含 trialTip → 存 `sessionStorage.trialTip` 后跳转
  - 各页公共头（dashboard 起步）：读 sessionStorage.trialTip 渲染顶部提示条（文案含剩余天数，normal 蓝/warning 黄/critical 红三档，样式引 governance-dict）；无 tip 不渲染；提示条对租户**全部角色**可见
  - api.js：通用 40003 处理（非登录页出现时提示并跳登录）
- 产物：`login.html`（改）、`pages/dashboard.html`（改，公共头）、`assets/js/api.js`（改）

### T22 [后端] TenantController 订阅端点 U1/U2（恢复路径，P1）
- 模块：eaiselp-runtime
- 类型：Controller 扩展（SE §4.4/§5.5）
- 依赖：T18（口径复用）
- 验收（AC-F3.5/F3.6）：
  - U1 `GET /api/v1/tenant/subscription`：tenant_admin/platform_admin 可访问（JWT claims roles 显式校验，不可伪造），其他角色 40301；出参 {edition, editionName, expireTime, daysLeft, expired, trial}
  - U2 `PUT /api/v1/tenant/{id}/subscription`：**仅 platform_admin**（tenant_admin 40301）；{edition?, expireTime?} null=不变支持单字段更新；edition ∈ trial/pro/enterprise/starter 非法 400；租户不存在 404；审计 tenant_edition_change（detail 含 oldEdition→newEdition、old→new expireTime、操作者）；修改后无缓存下次登录即生效
  - 单测锚点 28/29（角色矩阵/非法值/审计旧→新/恢复后 assertNotExpired 放行）
- 产物：`eaiselp-runtime/src/main/java/com/eaiselp/runtime/controller/TenantController.java`（改，+2 端点）；测试 `TenantControllerTest`（增锚点 28/29）

### T23 [前端] llm-key.html 租户设置页"订阅状态"区
- 模块：eaiselp-web-separate
- 类型：既有页面增量改造（挂现有租户自助管理入口）
- 依赖：T22（U1 契约）
- 验收（AC-F3.5 前端侧）："订阅状态"区展示 edition 展示名/expireTime 原值/剩余天数或已到期标识；仅 tenant_admin 可见该区（角色判断）；数据源 U1
- 产物：`pages/llm-key.html`（改）

---

## 给 QA 的断言翻译标注（31 AC 中需按 SE §4.5 翻译表改写的断言）

裁决 Q1 把标准↔门禁关联存储从"门禁侧"迁到**标准侧**（`t_standard.related_gate_names`，Flyway checksum 硬约束），PRD 原文按旧存储侧书写的 AC 需翻译后写用例（**AC 数值不变、操作主体换位**）：

| AC | PRD 原断言 | QA 用例应写为 |
|---|---|---|
| **AC-F1.3** | 打开门禁规则编辑页的关联标准选择器，仅列出 published 标准 | 打开**标准**编辑页（draft）的"关联门禁规则"选择器，仅列出本租户 **enabled** 的门禁规则；提交不存在/已停用规则 name → 400 |
| **AC-F1.3** | 提交含 draft/deprecated 标准 code 的关联请求 → 400 | draft 标准关联门禁后**发布前**，门禁侧打回展示不出现该标准（打回/详情解析一律 status=published 过滤）；published/deprecated 全字段只读含关联（AC-F1.5 原文不变，天然杜绝绕行） |
| **AC-F1.7** | 给规则 R 关联 STD-0002 → 成功；R 详情展示 | 在 STD-0002（draft）上提交 relatedGateNames=["R"] → 成功 → 发布 STD-0002；R 详情"已关联标准"区（只读，查 `GET /api/v1/standards?gateName=R&status=published`）展示"STD-0002《标题》v1.0"；逻辑删 STD-0002 后该区条目变"已删除"占位（D-9）；**R 门禁判定行为不变**（既有编排用例回归照跑） |
| AC-F1.8 | 断言本身不变 | 实现路径说明：case-detail 按 gateRuleId 两跳解析（规则 name → standards?gateName=）；断言"打回原因旁展示依据标准；未关联不显示空占位"照原文写 |

其余 27 条 AC（F1.1/F1.2/F1.4~F1.6/F1.9~F1.12/F2.1~F2.7/F3.1~F3.6/RBAC/SWITCH/AUDIT）按 PRD 原文直接写用例。AC-F3.4 附注说明：Q4 已裁决维持"trial+NULL=不过期"，原文即终稿无需修订。

---

## 变更影响评估

- **架构变更：是（模块化单体内轻度扩展）**——新增 `com.eaiselp.runtime.governance` 包（D-1）；eaiselp-data 新增跨 auth/runtime 共享的 `TenantSubscriptionService`（D-4，变更了两个 service 模块对 data 的消费关系）；登录/派生链路插入校验点（数据流变化）。**部署拓扑/服务清单/依赖方向（P3）均不变**。
- **需求调整：是**——新增 8 功能点（F1.1/F1.2/F1.3/F2.1/F2.2/F3.1/F3.2/F3.3），影响功能域：L2 治理资产（标准/模板/数据资产/质量规则四新域）+ 商用化前置（试用到期拦截）。属新 case 立项本身，非既有功能变更；对既有功能仅增量改造（gate-rule-list/case-detail/login/llm-key 四页 + 登录/派生两链路），无破坏性变更。
- **接口契约变更：是（纯新增，无破坏）**——新增 25 端点（S1~S6/T1~T6/A1~A5/Q1~Q6/U1/U2，见 api-contracts.md）；既有 `POST /api/v1/auth/login` 成功响应增可空字段 trialTip（加字段=非破坏，不升 v2，G14 版本兼容规则）；既有 `GET /api/v1/gate-rules/*` 契约零改动。
- **编码规范变更：否**——CLAUDE.md 与 ES-001/002/003 均未修改；本 case 严格遵循既有规范（G13 租户隔离/G14 v1 前缀/G11 无角色硬编码/V6 幂等惯例）。
- **文档更新建议**：
  - [不需更新] `D:\AI\mywork\platform\CLAUDE.md` — 原因：模块清单、分层规范、门禁规则均无变化；CLAUDE.md 由 team-standards 治理，本 case 无规范级变更
  - [不需更新] `D:\AI\mywork\platform\README.md` — 原因：内容为服务级概览（微服务清单/端口/快速开始），模块清单与职责无变化，不列功能点清单
  - [需更新（轻微）] `D:\AI\mywork\eaiselp-web-separate\README.md` — 原因：项目结构 js 清单逐项列出了 api.js/auth.js/menu.js 等，应补 `governance-dict.js` 一行（随 T6 交付顺手完成）
  - [需更新] `D:\AI\mywork\platform\docs\过程跟踪文档\changelog.md`（共享区） — 原因：ES-002 §8.2 工程级共享持续维护文档，case 收尾时记录本 case 交付（Ops/PM 动作，非 Dev 任务）
  - [不需更新] `docs/架构文档/*`（ADR/工程标准/质量门禁） — 原因：无规范变更、无 ADR 级架构决策（governance 包划分属 SE 方案已裁决范畴）
  - [不需更新] Swagger/OpenAPI 分组 — 原因：平台 M1 未引入 springdoc（CLAUDE.md §9.4，SP-2/SP-3 才落地），无 Swagger 资产存在；api-contracts.md 即本 case 接口文档权威
  - [不需更新] PRD/编排者裁决/技术方案/数据库设计 — 原因：均为本 case 过程产物已定稿；SE 方案 §4.5 翻译表已随方案下发 QA
- **README 更新清单（本次改动涉及的目录逐个判断）**：
  - [不需更新] `eaiselp-runtime/`（无 README） — 原因：不存在模块 README，按现状不新增（模块说明由 CLAUDE.md §3 承载）
  - [需更新（轻微）] `D:\AI\mywork\eaiselp-web-separate\README.md` — 原因：如上，js 清单补一行
  - [不需更新] `eaiselp-common/`、`eaiselp-auth/`、`eaiselp-data/`（无 README） — 原因：不存在，不新增
  - [不需更新] 根 `D:\AI\mywork\platform\README.md` — 原因：非项目级变化（服务清单/端口/铁律不变）

## 范围外（显式声明，防镀金；均承 PRD §7，不设"转后续"暗债）

模板编排注入（§7-1）、质量自动探测（§7-2）、支付/续费/自助升级（§7-3）、请求级到期拦截与 JWT 吊销（§7-4，Q3 已接受 24h 窗口+派生补位）、质量规则引擎自动判定（§7-5）、资产自动发现/血缘（§7-6）、标准条款结构化拆分与 diff（§7-8）、数据标准/主数据（§7-8）、敏感资产行级可见性/脱敏（§7-9，Q8 记录风险）、平台侧租户管理页面（§7-10，恢复路径本期 API only）、全局搜索接入（§7-11）。以上均已在 PRD 立项层面显式排除，非本 case 技术债。

---

## 本次经验沉淀

1. **数据层先行落盘时 BA 拆解要以"磁盘事实"而非"流程阶段"为基线**：本 case DBA 已先交付 V6/H2 schema（SE §0.2 核实），任务清单里 DB 层标"已完成"并保留追溯条目（T0），避免 Dev 重复实现或误判依赖阻塞；今后 BA 动笔前先核对上游角色产物是否已实际落盘。
2. **裁决改变 AC 隐含前提时，翻译表必须进任务清单而非只留在 SE 方案**：Q1 存储侧迁移导致 AC-F1.3/F1.7 断言换位，本清单在受影响任务（T13/T14）验收行显式引用翻译后口径，并单列"给 QA 的断言翻译标注"节——否则 Dev 按原文实现、QA 按原文写用例，双方向假缺陷。
3. **跨模块共享类型的位置由依赖方向（P3）唯一决定，不由"谁消费"决定**：TrialTipVo 被 auth（LoginResponse）与 data（TenantSubscriptionService 返回值）同时消费，只能落共同下游 eaiselp-common——SE 方案留了"common 或 auth"两选项时，BA 定稿要给出依赖图论证，防止 Dev 顺手放 auth 导致 data 反向依赖。
