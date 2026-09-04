# 变更日志

## v0.1.0（进行中）

### 缺陷修复
- [BUG-001] 2026-07-21 | M1.0 编译失败：spring-boot-maven-plugin repackage 配置错误导致 library 模块被错误打包，破坏模块间依赖 | 严重度：高 | 影响范围：全部 9 模块（核心卡点） | 状态：已修复（commit 2f84e10，Reviewer 通过 0 阻断 / QA 11 PASS + 1 N/A）
- [BUG-002] 2026-07-21 | Docker Desktop 已安装但 WSL2 后端未启用（VirtualMachinePlatform / Microsoft-Windows-Subsystem-Linux 两特性均 Disabled），Linux 容器无法启动 | 严重度：高 | 影响范围：M1.0 基础设施启动 | 状态：待处理（依赖人工装 WSL2+重启）

### 新需求
- [REQ-001] 2026-07-21 | M1.1 Dogfooding：用平台造平台，第一个 case 修复 M1.0 编译问题，端到端验证 L3→L1 协同链路 | 来源：内部 | 状态：已交付（commit 2f84e10，L3 EA + L2 Standards + L1 SE/Dev/Reviewer/QA/Ops 全链路协同验证通过）
- [REQ-005] 2026-07-23 | L3+L2 顶层设计：企业架构蓝图+商业策略+治理框架+M2项目群计划+ES-003（dogfooding 实战，模拟传统软件服务企业战略转型）| 来源：内部 | 状态：已交付（commit d8fef95，3 份 L3 + 2 份 L2 + 门禁，待 push）
- [REQ-006] 2026-07-23 | M2 Phase 1：前端工程初始化+登录页+JWT 认证+RBAC 5 角色+主框架（让平台"能登录、能看见"）| 来源：内部 | 状态：开发中
- [REQ-002] 2026-07-22 | 工程标准 ES-002 补全：M1.1 暴露的 4 个执行瑕疵（Reviewer Write 落盘 / case 目录归属 / Windows 工程标准 / Dev 报告措辞规范）| 来源：内部（M1.1 验证报告 §四）| 状态：已交付（ES-002 落地，Reviewer 通过 0 阻断 / QA 9 PASS + DEF-01 非阻断，反向验证 3 条全命中预期 FAIL）
- [REQ-003] 2026-07-22 | DerivationEngine 派生结果持久化：消除 "M1.1 接 data 持久化" TODO（DerivationEngine.java:49），data 模块补 Service 层（M2 必需），让派生结果真实入库 | 来源：内部（M1.0 核心目标 + IMP-003 一并清理）| 状态：已交付（2026-07-22，0 阻断 / 0 建议 / 2 可选 D1+D2 / QA 13 PASS + 1 N/A，反向验证 TC-10/TC-11 破坏源码看测试反应证明非空壳，hash 见 `git log --grep=DerivationEngine持久化`）
- [REQ-004] 2026-07-22 | 编排者边界修订：用户反馈 team-orchestrator SKILL.md 第 4 条"不越权执行"过度泛化到调研类活，方法论架构师完成元评估，需 L2 Standards 落地修订（拆 4A/4B/4C + 同步三处权威源）| 来源：用户反馈 | 状态：已交付（SKILL.md 4A/4B/4C 落地，三处权威源 SHA256 一致 678304BF…E36D，commit 见 `git log --grep=编排者边界修订`）

### 改进/技术债
- [IMP-001] 2026-07-21 | 平台缺 Maven 模块规范：library 模块与 service 模块的边界未明确 | 状态：已完成（ES-001 + ADR-001）
- [IMP-002] 2026-07-21 | 平台根 POM 缺统一的 spring-boot-maven-plugin repackage 策略 | 状态：已完成（P1-P5 落地）
- [IMP-003] 2026-07-21 | Reviewer D1：library 模块（capability/adapter/data）的 application.yml 仍残留 server.port / spring.cloud.nacos.discovery 等独立服务配置，POM 已删 nacos 依赖但配置层未清理（实际无害）| 状态：已完成（data/application.yml 删除，配置迁移到 runtime/application.yml，随 REQ-003 同 commit 落地）
- [IMP-004] 2026-07-22 | Reviewer 报告未 Write 落盘（M1.1 瑕疵 1）| 状态：已修复（ES-002 §1 + review-M1.0 补写 + G7 阻断门禁）
- [IMP-005] 2026-07-22 | case 级产物 vs 工程级共享资产目录归属混乱（M1.1 瑕疵 2）| 状态：已修复（ES-002 §2 工程级 vs case 级映射表 + 禁空占位目录）
- [IMP-006] 2026-07-22 | Windows 环境陷阱（$null/cmd转码/CRLF）（M1.1 瑕疵 3）| 状态：已修复（ES-002 §3 commit -F / 重定向 / .gitattributes / 中文路径 / 脚本编码 5 条）
- [IMP-007] 2026-07-22 | Dev 报告措辞偏差（"删 X"实为"加 Y"）（M1.1 瑕疵 4）| 状态：已修复（ES-002 §4 对照 HEAD 描述 + 反幻觉硬约束 + G10 警告门禁）
- [IMP-008] 2026-07-22 | Reviewer D1：质量门禁-产出物落盘.ps1 行 40/42/44/46/49-52 注释含中文字面量，违反 ES-002 §3.5 自身规范（PS 5.1 ANSI 下乱码，不影响逻辑）| 状态：M2 待办
- [IMP-009] 2026-07-22 | Reviewer D2：G7 不区分 case，多 case 并发漏报风险（M1 阶段已声明简化）| 状态：M2 待办
- [IMP-010] 2026-07-22 | Reviewer D3：项目根无 .gitattributes，CRLF 噪音仍存在（ES-002 §3.3 已定义内容，待 Dev 落地）| 状态：已完成（QA 兼 Dev 落地，585 字节，git check-attr 验证 11 类扩展名规则生效）
- [IMP-011] 2026-07-22 | QA DEF-01：G10 判定逻辑（`-match 'HEAD'` 子串）与 md §2 文档语义（"以'对照 HEAD'开头"）不一致，dev-report 含"as of HEAD"/"HEADER"会被误判 PASS | 状态：M2 待办（M2 接 CI/CD 时强化为 `-match '对照\s*HEAD'`）
- [IMP-012] 2026-07-22 | Reviewer D1（可选）：t_derivation.deleted 字段无索引（按 deleted 过滤的查询全表扫描），数据量上来后影响性能 | 状态：M2 待办（logic-delete 字段加索引，迁移脚本幂等）
- [IMP-013] 2026-07-22 | Reviewer D2（可选）：DerivationPersistenceService 的 artifact JSON 拼接用手工 StringBuilder（M1.2 决策，规避引入 ObjectMapper 依赖），可读性差且易转义错 | 状态：M2 待办（引 ObjectMapper 后改用 writeValueAsString，需评估对 library 模块依赖影响）
- [IMP-014] 2026-07-22 | 用户反馈：team-orchestrator SKILL.md 第 4 条"不越权执行"过度泛化到调研类活，导致编排者要么过度调研（违反字面），要么机械派子智能体（浪费 token/时间）。SKILL.md 第 4 条与第 82 条"编排者独立抽查"自身矛盾，第 82 条才对。元评估：docs/agent-memory/编排者最佳实践评估.md | 状态：已完成（SKILL.md 4A/4B/4C 落地 + 三处权威源 SHA256 一致：用户级 ~/.zcode + 团队级 agents-config + 评估文档 platform/docs/agent-memory）

### 变更登记（team-pm）
- 2026-08-18 | 需求 | 三层贯通企业级平台骨架（L3战略→L2项目群/项目→L1 Case 四级联动+下行约束+上行事件） | 平台Owner（dogfooding核心需求） | 新增t_strategy/t_program/t_project/t_architecture_principle/t_quality_gate_rule五表、Case表加project_id外键、三级CRUD API、三级管理页面、编排上下文注入机制、项目进度自动汇总
- 2026-08-18 | 交付 | 三层贯通骨架交付（237测全绿，联测清单45条待部署机执行）
- 2026-08-18 | 需求 | 交付收尾+API文档（D5注入可见/D10门禁旁路/D4D7入口/M2定界/Swagger） | 三层贯通评审遗留 | 前端编排详情/OrchestrationService重试路径/2页面入口/注入定界/springdoc
- 2026-08-18 | 交付 | 收尾5项+Swagger交付（242测全绿）
- 2026-08-18 | 需求 | L2治理核心+知识资产（DORA/里程碑/依赖/ADR/技术雷达） | PRG-001 | 新表3张+API+看板
- 2026-08-18 | 交付 | L2治理核心交付（389测全绿，联测10条待部署机）


## v0.2.0（2026-08-20 L2 治理收口）

### 新需求
- [REQ-007] 2026-08-20 | L2 治理收口：标准库（多版本状态机+门禁打通）+ 模板库（原地升版）+ 数据治理（资产目录+质量规则）+ 试用到期拦截（登录/派生/编排/retry 四入口 40003 + 临期三档 + platform_admin 恢复路径）| 来源：PRG-001 PRJ-003 尾巴 + PRJ-006 前置（商用硬伤：试用永不过期）| 状态：已交付（case-20260820-L2治理收口，PO/SE/DBA/BA/Dev×4/Reviewer/Security×3/QA 全链路；V6 迁移真实 MySQL 验证）

### 缺陷修复（安全门禁拦截）
- [BUG-003] 2026-08-20 | 存储型 XSS：standard/template 两页 renderMd 仅删 script 标签，img onerror 载荷存活可窃 localStorage token | 严重度：高危 | 状态：已修复（sanitize.js 公共 DOM 级清洗，7 类载荷 0 存活）
- [BUG-004] 2026-08-20 | 平台角色提权：M3 黑名单 contains 大小写敏感，生产 ci collation 下 PLATFORM_ADMIN 变体可绕过→免费转正 | 严重度：高 | 状态：已修复（两层纵深：trim+equalsIgnoreCase + tenant_id=0 行级拒绝，H2 测不出的 collation 差异由 Security 真库视角发现）
- [BUG-005] 2026-08-20 | 到期绕过：/orchestrate/{id}/retry 缺到期校验，存量 JWT 可继续烧 token | 状态：已修复（三入口闭合，MCP /invoke 裁决 Q10 不纳入）
- [BUG-006] 2026-08-20 | D-9 越界：旁路逻辑删查询返回已删标准正文 | 状态：已修复（toGateRefVo 占位瘦身）
- [BUG-007] 2026-08-20 | dashboard.html 既有 JS 语法错误（HEAD 就有，const reqs 缺 ]）| 状态：顺手修复（Reviewer 确认最小）
- [BUG-008] 2026-08-20 | 权限码错位：user:update→user:edit（V1 seed 1009~1012），写端点恒 403 隐性死端点 | 状态：已修复

### 改进/技术债
- [IMP-015] Flyway V6：4 新表 + 12 权限原子 + 36 授权行，纯 IF NOT EXISTS 零 ALTER（V4 r4/V5 r2 幂等规范延续）
- [IMP-016] sanitize.js 公共 XSS 清洗 + governance-dict.js 前端字典集中（P6）
- [IMP-017] U2 租户订阅恢复端点 + 试用到期恢复runbook（一行 SQL 5 分钟恢复）
- [IMP-018] R2-R4 XSS 残余加固（中缀控制字符/base 标签/CSS 面）→ PRJ-006 backlog
