package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.JwtClaims;
import com.eaiselp.common.security.LoginUser;
import com.eaiselp.data.entity.Quota;
import com.eaiselp.data.entity.Tenant;
import com.eaiselp.data.entity.User;
import com.eaiselp.data.mapper.QuotaMapper;
import com.eaiselp.data.mapper.RoleMapper;
import com.eaiselp.data.mapper.TenantMapper;
import com.eaiselp.data.mapper.UserMapper;
import com.eaiselp.data.mapper.UserRoleMapper;
import com.eaiselp.data.mapper.vo.UserRoleView;
import com.eaiselp.data.audit.AuditService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 租户自助管理 REST API（#23 租户注册 + #24 租户自配 LLM Key）。
 *
 * <p><b>自助注册（#23）</b>：企业客户无需平台管理员介入，提交企业名+管理员账号即开通：
 * 创建租户 → 创建 tenant_admin 用户 → 初始化当月配额 → 立即可登录使用。</p>
 *
 * <p><b>自配 LLM Key（#24）</b>：租户管理员配置自己的 LLM API Key，
 * 派生消耗走租户自己的额度（平台不垫付 token 费用）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tenant")
@RequiredArgsConstructor
public class TenantController {

    private final TenantMapper tenantMapper;
    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final QuotaMapper quotaMapper;
    private final AuditService auditService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);

    /**
     * 租户自助注册（白名单接口，无需 token）。
     *
     * <p>流程：企业名+管理员账号+密码 → 创建租户（trial 版）→ 创建 tenant_admin 用户
     * → 初始化当月配额（试用额度 50 次派生 / 50万 token）→ 返回成功可登录。</p>
     */
    @PostMapping("/register")
    public R<Map<String, Object>> register(@RequestBody RegisterRequest req) {
        // 参数校验
        if (req.getTenantName() == null || req.getTenantName().isBlank()
                || req.getAdminUsername() == null || req.getAdminUsername().isBlank()
                || req.getPassword() == null || req.getPassword().length() < 6) {
            return R.fail(400, "企业名/管理员账号必填，密码至少 6 位");
        }
        // 管理员账号全局查重
        Long dup = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, req.getAdminUsername())).longValue();
        if (dup > 0) return R.fail(409, "管理员账号已存在: " + req.getAdminUsername());

        // 1. 创建租户（trial 版，30 天有效）
        Tenant t = new Tenant();
        t.setTenantCode("t-" + System.currentTimeMillis() % 100000000L);
        t.setTenantName(req.getTenantName());
        t.setDeployMode("saas");
        t.setEdition("trial");
        t.setStatus("active");
        t.setExpireTime(LocalDateTime.now().plusDays(30));
        t.setContactName(req.getAdminUsername());
        tenantMapper.insert(t);

        // 2. 创建 tenant_admin 用户
        User admin = new User();
        admin.setTenantId(t.getId());
        admin.setUsername(req.getAdminUsername());
        admin.setPassword(passwordEncoder.encode(req.getPassword()));
        admin.setDisplayName(req.getAdminUsername());
        admin.setStatus("active");
        userMapper.insert(admin);

        // 3. 分配 tenant_admin 角色
        var role = roleMapper.selectOne(new LambdaQueryWrapper<com.eaiselp.data.entity.Role>()
                .eq(com.eaiselp.data.entity.Role::getRoleCode, "tenant_admin"));
        if (role != null) {
            com.eaiselp.data.entity.UserRole ur = new com.eaiselp.data.entity.UserRole();
            ur.setTenantId(t.getId());
            ur.setUserId(admin.getId());
            ur.setRoleId(role.getId());
            userRoleMapper.insert(ur);
        }

        // 4. 初始化当月配额（试用额度）
        Quota q = new Quota();
        q.setTenantId(t.getId());
        q.setPeriod(LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")));
        q.setDerivationLimit(50);
        q.setTokenLimit(500000L);
        q.setDerivationUsed(0);
        q.setTokenUsed(0L);
        quotaMapper.insert(q);

        auditService.log("tenant_register", "tenant", String.valueOf(t.getId()),
                "{\"tenantName\":\"" + req.getTenantName() + "\",\"admin\":\"" + req.getAdminUsername() + "\"}");

        log.info("[Tenant] 自助注册成功: {} (admin={}, 租户ID={})", req.getTenantName(), req.getAdminUsername(), t.getId());
        return R.ok(Map.of(
                "tenantId", t.getId(),
                "tenantCode", t.getTenantCode(),
                "adminUsername", req.getAdminUsername(),
                "message", "注册成功，请使用管理员账号登录"));
    }

    /**
     * 配置租户 LLM Key（#24：租户自付 token 费）。
     *
     * <p>仅 tenant_admin 可配。配置后该租户的派生走自己的 LLM 额度。</p>
     */
    @PutMapping("/llm-key")
    public R<Void> setLlmKey(@RequestBody LlmKeyRequest req) {
        JwtClaims claims = LoginUser.get();
        if (claims == null || claims.getTenantId() == null) return R.fail(401, "未登录");
        if (req.getApiKey() == null || req.getApiKey().isBlank()) return R.fail(400, "apiKey 不能为空");

        Tenant t = tenantMapper.selectById(claims.getTenantId());
        if (t == null) return R.fail(404, "租户不存在");
        t.setLlmProvider(req.getProvider() != null ? req.getProvider() : "glm");
        t.setLlmApiKey(req.getApiKey());
        tenantMapper.updateById(t);

        auditService.log("tenant_llm_key_set", "tenant", String.valueOf(t.getId()),
                "{\"provider\":\"" + t.getLlmProvider() + "\"}");
        log.info("[Tenant] 租户 {} 配置 LLM Key: provider={}", t.getId(), t.getLlmProvider());
        return R.ok();
    }

    /** 查询当前租户 LLM 配置状态（Key 脱敏显示）。 */
    @GetMapping("/llm-key")
    public R<Map<String, Object>> getLlmKey() {
        JwtClaims claims = LoginUser.get();
        if (claims == null || claims.getTenantId() == null) return R.fail(401, "未登录");
        Tenant t = tenantMapper.selectById(claims.getTenantId());
        if (t == null) return R.fail(404, "租户不存在");
        String key = t.getLlmApiKey();
        String masked = (key == null || key.isBlank()) ? null
                : key.substring(0, Math.min(6, key.length())) + "****";
        return R.ok(Map.of(
                "provider", t.getLlmProvider() != null ? t.getLlmProvider() : "glm",
                "configured", key != null && !key.isBlank(),
                "maskedKey", masked != null ? masked : ""));
    }

    @Data
    public static class RegisterRequest {
        private String tenantName;
        private String adminUsername;
        private String password;
    }

    @Data
    public static class LlmKeyRequest {
        /** glm / deepseek */
        private String provider;
        private String apiKey;
    }
}
