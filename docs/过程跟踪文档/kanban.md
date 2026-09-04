# 任务看板 — eAISEDP 平台建设（PRG-001）

> 更新：2026-08-20 | 编排者：team-orchestrator | 数据契约：markdown（dogfooding 成熟后迁 t_project）

## Todo
- [ ] Wave2 L3 收口：GRC 风险合规 + 投资决策分析（PRJ-004 尾巴，PO 已排队）
- [ ] Wave4 企业资产适配：MCP 真实适配器（Jira/Confluence/GitLab，Mock+契约先行）
- [ ] Wave5 商用化：订阅/计费/SLA（PRJ-006，含 U2 白名单模式/平台角色体系重构 IMP-018）
- [ ] 测试机环境重搭：原生 MySQL（deploy\setup_mysql_native.bat 已备）+ #22 联测

## Doing
- [ ] [L1-QA] case-20260820-L2治理收口 验收测试（31 AC 用例矩阵 + 人工联测清单）

## Done（最近）
- [x] [Wave3 工程债] R2~R4 XSS 加固清零（sanitize.js 协议白名单+向量面+CSS 面；两先例页收敛公共版）+ IMP-012 评估关闭（索引全命中，写放大>收益，见 changelog v0.2.1）| #23 部署脚本
- [x] [case-20260820-L2治理收口] 全链路交付：PO（31 AC）→ 裁决 Q1~Q11 → SE（25 端点/governance 包/双点拦截）→ DBA（V6 已真实 MySQL 验证：V1→V6 链+幂等重放）→ BA（24 任务四批）→ Dev×4 批（492→503→508 全绿）→ Reviewer PASS（0 阻断/3 建议/3 可选全修）→ Security 三轮（FAIL→FAIL→PASS：拦下存储型 XSS 高危 + ci collation 提权绕过，7 载荷 0 存活）
- [x] [F3 商用硬伤] 试用到期拦截：登录 40003 + 临期三档 + 派生/编排/retry 三入口 + U2 恢复路径 + runbook
- [x] [F1/F2] 标准库（多版本+门禁打通）/ 模板库 / 数据资产 / 质量规则 四域 + 前端 4 页 + RBAC（1059~1070）
- [x] [安全] sanitize.js 公共 XSS 清洗 / D-9 占位瘦身 / 平台角色两层防提权 / user:edit 权限码修正

## 已归档
- [x] case-20260721-M1.0-编译修复（commit 2f84e10 + c4ea0c0）
- [x] case-20260722-ES002-执行瑕疵补全（commit 1bba983）
- [x] case-20260722-DerivationEngine持久化（REQ-003 + IMP-003）
- [x] case-20260818-三层贯通企业级平台（f252f0c + 收尾 93aaa39，237 测）
- [x] case-20260818-L2治理核心（03d2cc5，389 测）
- [x] Flyway V4/V5 幂等化修复（e563e75，#20 部署失败 1060 根治）

## 阻塞
- 无（测试机 Docker Desktop 停用已破阻塞：#22 部署脚本 Docker/原生 MySQL 自适应）

## 遗留技术债（M2 看板顺延）
- [ ] IMP-008：质量门禁 .ps1 中文注释字面量（低危）
- [ ] IMP-009：G7 不区分 case 并发漏报（低危）
- [ ] IMP-011：G10 判定逻辑与文档语义（接 CI/CD 时强化）
- [ ] IMP-012：t_derivation.deleted 无索引（Wave3 V7 处理）
- [ ] RuntimeController jackson-datatype-jsr310 WARN（P12，非阻塞）
