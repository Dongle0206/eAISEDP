# Dev 角色经验库

### [2026-08-18] 多源指令任务号漂移开工前显式对账
`tags: 任务对账, 依赖闭包, severity: high`
- 协调者批次编号与 tasks.md 可能错位；落地前按每任务的可编译依赖闭包校验，编号映射和排除项写进完成报告

### [2026-08-18] 数据模型是 API 签名的最终裁决者
`tags: schema, 签名, severity: mid`
- 指示与已落盘 schema 冲突时以 schema 为准（如 stage 是 VARCHAR 非 int），偏差清单显式记录

### [2026-08-18] seed 的每租户×10 id 块设计增行零成本
`tags: seed, 幂等, severity: low`
- 8000000+rank×10+ord 预留空位，增行只加 UNION 分支无 id 冲突
