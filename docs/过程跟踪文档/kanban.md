# 任务看板 — case-20260722-ES002-执行瑕疵补全

> **背景**：M1.1 Dogfooding 验证报告暴露 4 个执行层面瑕疵（Reviewer 报告未落盘 / case vs 工程目录混乱 / Windows 环境陷阱 / Dev 报告措辞偏差）。
>
> **修复策略**：L2 Standards 主导新增 ES-002 工程标准（4 章规范）+ G7-G10 质量门禁（4 条规则 + 可执行 .ps1），系统性修复。

## Todo
（无）

## Doing
（无）

## Done
- [x] [L2-Standards] ES-002 工程标准规范（4 章：角色产出物落盘 / 文档体系与目录归属 / Windows 工程标准 / Dev 报告规范）
- [x] [L2-Standards] G7-G10 质量门禁（4 条规则 + 可执行 .ps1，含 3 条反向验证）
- [x] [L1-Ops] .gitattributes 落地（585 字节，11 类扩展名规则生效）
- [x] [L1-Ops] CLAUDE.md 追加 §8 + 更新 §6/§7
- [x] [L1-Reviewer] 评审通过（第1轮，0 阻断 / 2 建议 / 1 可选，5 条 Standards 声明独立复现）
- [x] [L1-QA] 测试通过（9 PASS + DEF-01 非阻断，反向验证 3 条全命中预期 FAIL）
- [x] [L1-Reviewer] 补写 review-M1.0-编译修复.md（修复 IMP-004）
- [x] [L1-Ops] git 归档（commit 1bba983）
- [x] [L1-Dev] DerivationEngine 持久化实现：data 模块 Service 层 + runtime 引依赖 + DerivationPersistenceService 独立 Bean
- [x] [L1-Reviewer] 评审通过（第1轮，0 阻断 / 0 建议 / 2 可选 D1+D2）
- [x] [L1-QA] 测试通过（13 PASS + 1 N/A，反向验证 TC-10/TC-11 破坏源码看测试反应证明非空壳）

## 已归档
- [x] case-20260721-M1.0-编译修复（commit 2f84e10 + c4ea0c0 收尾）
- [x] case-20260722-ES002-执行瑕疵补全（commit 1bba983）
- [x] case-20260722-DerivationEngine持久化（2026-07-22，REQ-003 交付 + IMP-003 完成，hash 见 git log --grep）

## 阻塞
- 无

## 待办（M2 转入）
- [ ] IMP-003：library 模块 application.yml 残留独立服务配置（引 data 到 runtime 时清理）
- [ ] IMP-008：质量门禁 .ps1 行 40/42/44/46/49-52 注释含中文字面量
- [ ] IMP-009：G7 不区分 case，多 case 并发漏报风险
- [ ] IMP-011：G10 判定逻辑与 md §2 文档语义不一致（M2 接 CI/CD 时强化）
- [ ] BUG-002：Docker Desktop WSL2 后端未启用（依赖人工装 WSL2+重启）
