# DBA 角色经验库

### [2026-08-18] 草案期迁移直改、发布后只追加
`tags: flyway, 迁移, severity: high`
- 判断靠证据：确认"无构建日志+实体仅骨架+团队有先例"三事实才直改草案；缺一走 V5。Flyway checksum 冻结是硬约束
- 关联：V4__three_tier_model.sql

### [2026-08-18] 多租户关联索引一律 tenant_id 打头
`tags: 索引, 多租户, severity: high`
- 租户拦截器改写后的 SQL 才是索引设计依据；"中间列不在查询条件"的复合索引等于半根索引

### [2026-08-18] 行为开关列的 DEFAULT 是对存量行的一次性表态
`tags: 开关, 默认值, severity: mid`
- DEFAULT 1=升级不改现有行为（opt-out）；语义"新能力默认关"则 DEFAULT 0。先定语义再定默认值
