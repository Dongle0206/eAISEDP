# Runbook — 试用到期拦截误伤/恢复处置（case-20260820 F3）

| 字段 | 值 |
|---|---|
| 适用 | eAISEDP M2（试用到期拦截上线后） |
| 触发场景 | ① 客户续费/签约需即时恢复登录；② 怀疑到期拦截**误伤**正常租户（最大商用风险，SE 风险 R1） |
| 目标 | 5 分钟内恢复租户可登录，无需发版 |
| 权限 | platform_admin（API 路径）/ DBA（SQL 兜底路径） |

## 1. 判定口径（排查前先看，PRD §4.3.1 唯一口径）

| 口径项 | 规则 |
|---|---|
| 拦截条件 | `edition='trial'` **且** `expire_time` 非空 **且** `now ≥ expire_time`（含等于） |
| 豁免 | `edition ≠ 'trial'`（pro/enterprise/starter）即使 expire_time 已过也**不拦截、不提示** |
| NULL 防御 | trial 且 `expire_time IS NULL` → 视为未设置，不拦截（脏数据防御，Q4） |
| 拦截点 | 登录（凭据校验后、签 JWT 前，错误码 40003）+ 派生入口（/api/runtime/derive、/orchestrate 与 /orchestrate/{id}/retry，参数校验后、资源预占前；M2 安全评审后 retry 已同口径补堵） |
| 生效时机 | 判定服务**零缓存**（每次主键直查 t_tenant）——改库/调 API 后，**该租户下一次登录/派生立即生效** |

## 2. 恢复路径 A：platform_admin API（首选，自带审计）

```bash
# U2：修改租户订阅（仅 platform_admin 的 JWT 可调；tenant_admin 调用 → 40301）
# 方式 1：转正（edition 改正式版，expire_time 从此忽略）
curl -X PUT "http://<host>:<port>/api/v1/tenant/{tenantId}/subscription" \
  -H "Authorization: Bearer <platform_admin_token>" \
  -H "Content-Type: application/json" \
  -d '{"edition":"pro"}'

# 方式 2：延期（仍是 trial，延长 30 天；expireTime 格式 yyyy-MM-dd HH:mm:ss）
curl -X PUT "http://<host>:<port>/api/v1/tenant/{tenantId}/subscription" \
  -H "Authorization: Bearer <platform_admin_token>" \
  -H "Content-Type: application/json" \
  -d '{"expireTime":"2026-09-19 12:00:00"}'

# 置空 expire_time（=「未设置」语义，Q4 防御口径，不再拦截）
curl -X PUT "http://<host>:<port>/api/v1/tenant/{tenantId}/subscription" \
  -H "Authorization: Bearer <platform_admin_token>" \
  -H "Content-Type: application/json" \
  -d '{"expireTime":""}'
```

审计：每次成功修改在 `t_governance_log` 留 `tenant_edition_change`（detail 含 oldEdition→newEdition、old→new expireTime、操作者）。

## 3. 恢复路径 B：SQL 兜底（API 不可用时，一行即恢复）

```sql
-- 先确认现状（edition / expire_time / 是否被拦截口径命中）
SELECT id, tenant_name, edition, expire_time, status FROM t_tenant WHERE id = <tenantId>;

-- 转正（expire_time 从此完全忽略，AC-F3.3）
UPDATE t_tenant SET edition = 'pro' WHERE id = <tenantId>;

-- 或仅延期 30 天（保持 trial）
UPDATE t_tenant SET expire_time = DATE_ADD(NOW(), INTERVAL 30 DAY) WHERE id = <tenantId>;

-- 或置空（Q4「未设置」语义）
UPDATE t_tenant SET expire_time = NULL WHERE id = <tenantId>;
```

改完后**无需重启/刷新缓存**：判定服务零缓存直查，该租户用户下一次登录即恢复（存量被拦用户重登即可；已被签发的 JWT 在到期前仍受派生入口校验约束，恢复后同样放行）。

## 4. 误伤排查步骤（怀疑拦错了正常租户）

1. 看拦截审计：`SELECT * FROM t_governance_log WHERE action IN ('login_trial_blocked','derive_trial_blocked','orchestrate_trial_blocked','orchestrate_retry_trial_blocked') AND resource_id = '<tenantId>' ORDER BY id DESC;`
   （detail 含 tenantId/username/expireTime/edition）
2. 按第 1 节口径核对：若 `edition` 实为 pro/enterprise/starter 或 `expire_time` 为 NULL 却被拦 → 属缺陷，按第 3 节 SQL 先恢复客户，再上报缺陷（附上述审计行）。
3. 若口径命中（确为 trial 已到期）但属商业特批 → 走第 2/3 节恢复，并留存 `tenant_edition_change` 审计。
4. 事后在 t_governance_log 补查 `login_trial_blocked` 与 `tenant_edition_change` 两条记录时间线，确认"拦截→恢复"闭环（运营周报素材）。

## 5. 注意事项

- **禁止**通过改 `status` 列（租户启停）来"绕过"到期问题——语义不同，会引入租户禁用副作用。
- 误伤最常见根因：注册链路外的手工插入租户未写 expire_time（NULL，不会拦截，Q4 兜底）；或运维误改 edition。先查数据再动刀。
- 本 runbook 与 SE 技术方案 §10 R1（兜底一行 SQL）对应；API 路径详见 `docs/过程执行文档/case-20260820-L2治理收口/api-contracts.md` AC-U1/U2。

## 6. U2 提权路径警示（M3 安全评审，运维红线）

U2（`PUT /api/v1/tenant/{id}/subscription`）是**商业化控制的核心开关**：持有 platform_admin 角色 JWT 即可对任意租户转正/延期。运维操作时注意：

- **平台管理员账号是唯一合法入口**。平台角色 `platform_admin` 为 system_template 全局角色（`t_role.tenant_id=0`），**严禁**通过 SQL 向 `t_user_role` 插入 platform_admin 关联来"给租户管理员提权方便操作"——应用层已有黑名单拦截（UserServiceImpl：create/update/assignRoles 分配 platform_admin 一律 40301），SQL 直插会绕过该防线。
- 若确需为租户配置平台侧运营人员：在平台运营租户（tenant_id=0 体系）内建号并分配角色，不要在客户租户内提升已有账号。
- 事后自查命令（发现异常提权立即回收并改密）：
  `SELECT ur.*, u.username FROM t_user_role ur JOIN t_user u ON u.id = ur.user_id JOIN t_role r ON r.id = ur.role_id WHERE r.role_code = 'platform_admin' AND ur.tenant_id <> 0;`
  ——正常应为**空集**；非空即说明存在绕过应用层的提权，需按审计链（`user_assign_roles` / `tenant_edition_change`）追查。
- 每次真实使用 U2 恢复/转正后，核对 `t_governance_log` 的 `tenant_edition_change` 审计行：operator 应为平台运营账号，old→new 值与本 runbook 操作记录一致。
