package com.eaiselp.runtime.controller;

import com.eaiselp.common.ratelimit.RateLimit;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.JwtClaims;
import com.eaiselp.common.security.LoginUser;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.data.entity.Checkpoint;
import com.eaiselp.data.service.CheckpointService;
import com.eaiselp.runtime.casestate.CaseStateService;
import com.eaiselp.runtime.casestate.CaseStatus;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Case 状态机 + 检查点人工锁 REST API（M2 SP-3，GRC 第一道防线）。
 *
 * <p>提供两类能力：
 * <ul>
 *   <li><b>状态流转</b>：POST /api/v1/cases/{caseId}/transit —— 基于 {@link CaseStatus}
 *       合法流转图校验后更新 t_case.status。</li>
 *   <li><b>检查点人工锁</b>：不可逆操作前创建 pending 检查点，人工 confirm/reject 决定是否放行。</li>
 * </ul>
 *
 * <p>权限（对齐 schema 已有权限码，不新增权限项以避免改 schema.sql）：
 * <ul>
 *   <li>状态流转 / 检查点创建 / 确认 / 拒绝：{@code case:checkpoint:confirm}（GRC 流程控制域）。</li>
 *   <li>检查点列表查询：{@code case:view}。</li>
 * </ul>
 *
 * <p>多租户隔离：tenant_id 由 MyBatis-Plus 拦截器自动注入，operator 从 JWT claims 取 username
 * （ES-003 §9.3，G13——不从请求参数取防伪造）。
 *
 * <p>限流：状态流转 / 检查点确认走 USER 维度令牌桶（防误触连点）。读类不限流。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CaseStateController {

    private final CaseStateService caseStateService;
    private final CheckpointService checkpointService;
    private final AuditService auditService;

    // ======================== 状态流转 ========================

    /** Case 状态流转。 */
    @PostMapping("/cases/{caseId}/transit")
    @RequirePermission("case:checkpoint:confirm")
    @RateLimit(name = "case-transit", key = RateLimit.KeyType.USER, capacity = 20, refillPerMin = 20)
    public R<String> transit(@PathVariable String caseId, @RequestBody TransitRequest req) {
        if (req == null || req.getTargetStatus() == null || req.getTargetStatus().isBlank()) {
            return R.fail(400, "targetStatus 不能为空");
        }
        CaseStatus target = CaseStatus.fromDbValue(req.getTargetStatus().trim());
        if (target == null) {
            return R.fail(400, "未知的 targetStatus: " + req.getTargetStatus()
                    + "（合法值: drafting/deriving/reviewing/testing/deploying/done）");
        }
        String operator = resolveOperator();
        // 非法流转抛 IllegalStateTransitionException(BizException) → GlobalExceptionHandler 转 R.fail(400, msg)
        caseStateService.transit(caseId, target, operator);
        // 审计：Case 状态流转（GRC 治理：状态变更可追溯）
        auditService.log("case_transit", "case", caseId,
                "{\"targetStatus\":\"" + target.dbValue() + "\",\"operator\":\"" + operator + "\"}");
        return R.ok(target.dbValue());
    }

    // ======================== 检查点（Case 维度） ========================

    /** 查 Case 的检查点列表（按请求时间倒序）。 */
    @GetMapping("/cases/{caseId}/checkpoints")
    @RequirePermission("case:view")
    public R<List<Checkpoint>> listCheckpoints(@PathVariable String caseId) {
        return R.ok(checkpointService.listByCaseId(caseId));
    }

    /** 创建检查点（不可逆操作前调用，状态默认 pending）。 */
    @PostMapping("/cases/{caseId}/checkpoints")
    @RequirePermission("case:checkpoint:confirm")
    @RateLimit(name = "checkpoint-create", key = RateLimit.KeyType.USER, capacity = 30, refillPerMin = 30)
    public R<Checkpoint> createCheckpoint(@PathVariable String caseId, @RequestBody CreateCheckpointRequest req) {
        if (req == null || req.getOperation() == null || req.getOperation().isBlank()) {
            return R.fail(400, "operation 不能为空");
        }
        // operation 建议来自 IRREVERSIBLE_OPS，但本层不强校验（上层业务编排负责语义合法性）
        Checkpoint cp = checkpointService.create(caseId, req.getOperation().trim(), req.getDerivationId());
        return R.ok(cp);
    }

    // ======================== 检查点确认/拒绝 ========================

    /** 确认检查点（pending → confirmed）。 */
    @PostMapping("/checkpoints/{id}/confirm")
    @RequirePermission("case:checkpoint:confirm")
    @RateLimit(name = "checkpoint-confirm", key = RateLimit.KeyType.USER, capacity = 30, refillPerMin = 30)
    public R<Void> confirm(@PathVariable Long id, @RequestBody CheckpointActionRequest req) {
        String operator = resolveOperator();
        String comment = req == null ? null : req.getComment();
        boolean ok = checkpointService.confirm(id, operator, comment);
        // 审计：检查点确认（不可逆操作放行，GRC 关键审计点）
        auditService.log("checkpoint_confirm", "checkpoint", String.valueOf(id),
                "{\"operator\":\"" + operator + "\",\"comment\":\"" + safeJson(comment) + "\"}",
                ok ? "success" : "failure",
                ok ? null : "检查点不存在或已处理");
        return ok ? R.ok() : R.fail(409, "检查点不存在或已处理（非 pending 状态）");
    }

    /** 拒绝检查点（pending → rejected）。 */
    @PostMapping("/checkpoints/{id}/reject")
    @RequirePermission("case:checkpoint:confirm")
    @RateLimit(name = "checkpoint-reject", key = RateLimit.KeyType.USER, capacity = 30, refillPerMin = 30)
    public R<Void> reject(@PathVariable Long id, @RequestBody CheckpointActionRequest req) {
        String operator = resolveOperator();
        String comment = req == null ? null : req.getComment();
        boolean ok = checkpointService.reject(id, operator, comment);
        // 审计：检查点拒绝（不可逆操作阻断，GRC 关键审计点）
        auditService.log("checkpoint_reject", "checkpoint", String.valueOf(id),
                "{\"operator\":\"" + operator + "\",\"comment\":\"" + safeJson(comment) + "\"}",
                ok ? "success" : "failure",
                ok ? null : "检查点不存在或已处理");
        return ok ? R.ok() : R.fail(409, "检查点不存在或已处理（非 pending 状态）");
    }

    // ======================== 辅助 ========================

    /**
     * 从 JWT claims 解析操作人标识（username），防客户端伪造（ES-003 §9.3 G13）。
     * 未登录回退 "anonymous"（正常情况下 JWT 拦截器已挡住，此处兜底防 NPE）。
     */
    private String resolveOperator() {
        JwtClaims claims = LoginUser.get();
        if (claims != null && claims.getUsername() != null) {
            return claims.getUsername();
        }
        Long uid = LoginUser.getUserId();
        return uid != null ? String.valueOf(uid) : "anonymous";
    }

    /** 转义 JSON 字符串中的特殊字符（comment 来自请求体，需防 JSON 注入）。 */
    private String safeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    // ======================== 请求体 DTO ========================

    @Data
    public static class TransitRequest {
        /** 目标状态（小写字符串：drafting/deriving/reviewing/testing/deploying/done）。 */
        private String targetStatus;
        /** 操作人（可选；为空时从 JWT claims 取 username，防伪造）。 */
        private String operator;
    }

    @Data
    public static class CreateCheckpointRequest {
        /** 操作标识（建议来自 IRREVERSIBLE_OPS，如 deploy_production）。 */
        private String operation;
        /** 关联派生记录 ID（追溯来源，可选）。 */
        private Long derivationId;
    }

    @Data
    public static class CheckpointActionRequest {
        /** 确认/拒绝备注（reject 时建议必填）。 */
        private String comment;
    }
}
