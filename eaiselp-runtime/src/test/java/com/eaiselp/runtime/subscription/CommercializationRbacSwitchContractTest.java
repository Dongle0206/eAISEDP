package com.eaiselp.runtime.subscription;

import com.eaiselp.common.result.ResultCode;
import com.eaiselp.common.security.JwtClaims;
import com.eaiselp.common.security.LoginUser;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.data.service.PermissionService;
import com.eaiselp.data.service.subscription.TenantQueryService;
import com.eaiselp.runtime.controller.CostCenterController;
import com.eaiselp.runtime.controller.IncidentController;
import com.eaiselp.runtime.controller.InvoiceController;
import com.eaiselp.runtime.controller.PlanController;
import com.eaiselp.runtime.controller.PlatformTenantController;
import com.eaiselp.runtime.security.PermissionInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 商用化收口契约测试（case-20260823 T19，评审 D2 补交；对齐 L3RbacSwitchContractTest /
 * GovernanceRbacSwitchContractTest 先例，纯静态/反射 + 真拦截器行为断言，零 Spring 上下文）。
 *
 * <p>覆盖 SE §9.1 锚点 18~21：RBAC 反射矩阵（锚点 18）、V8 seed 契约与 H2 防漂移（锚点 19）、
 * 403 矩阵行为面（锚点 20，经真实 {@link PermissionInterceptor} + 按角色 mock 的
 * PermissionService——角色→权限码取 PRD §4.9 权威矩阵）、AC-G3 不限层（锚点 21）。
 * PlatformTenantController 走 claims 显式校验（U1/U2 先例形态），其 403 矩阵由
 * TC_C5 直调 Controller 承载（对齐 TenantControllerTest 先例）。</p>
 */
class CommercializationRbacSwitchContractTest {

    // ==================== 锚点 18：五新 Controller 权限注解矩阵（AC-G1/G2） ====================

    /**
     * 16 端点 → 权限原子逐格对照 PRD §4.9 矩阵（防 Dev 漏挂/错挂）：
     * P1/P3=plan:view；P2=plan:create；P4=plan:edit；
     * I1/I2=bill:view；I3/I4=bill:manage；
     * N1/N3=incident:view；N2=incident:create；N4=incident:edit；
     * C1/C2/C3=cost:view；平台租户列表=claims 显式 platform_admin（U1/U2 先例，非注解原子）。
     */
    @Test
    void TC_C1_五新Controller权限注解与V8seed原子矩阵一致() {
        Map<String, String> planExpected = new LinkedHashMap<>();
        planExpected.put("page", "plan:view");
        planExpected.put("get", "plan:view");
        planExpected.put("create", "plan:create");
        planExpected.put("update", "plan:edit");

        Map<String, String> invoiceExpected = new LinkedHashMap<>();
        invoiceExpected.put("page", "bill:view");
        invoiceExpected.put("get", "bill:view");
        invoiceExpected.put("generate", "bill:manage");
        invoiceExpected.put("transit", "bill:manage");

        Map<String, String> incidentExpected = new LinkedHashMap<>();
        incidentExpected.put("page", "incident:view");
        incidentExpected.put("get", "incident:view");
        incidentExpected.put("create", "incident:create");
        incidentExpected.put("update", "incident:edit");

        Map<String, String> costExpected = new LinkedHashMap<>();
        costExpected.put("summary", "cost:view");
        costExpected.put("invoices", "cost:view");
        costExpected.put("invoiceDetail", "cost:view");

        Map<Class<?>, Map<String, String>> matrix = new LinkedHashMap<>();
        matrix.put(PlanController.class, planExpected);
        matrix.put(InvoiceController.class, invoiceExpected);
        matrix.put(IncidentController.class, incidentExpected);
        matrix.put(CostCenterController.class, costExpected);

        int endpoints = 0;
        for (Map.Entry<Class<?>, Map<String, String>> e : matrix.entrySet()) {
            Class<?> ctrl = e.getKey();
            // G14：/api/v1/ 前缀
            RequestMapping base = ctrl.getAnnotation(RequestMapping.class);
            assertNotNull(base, ctrl.getSimpleName() + " 缺类级 @RequestMapping");
            assertTrue(base.value().length > 0 && base.value()[0].startsWith("/api/v1/"),
                    ctrl.getSimpleName() + " 路径前缀必须 /api/v1/（G14），实际 " + Arrays.toString(base.value()));

            Set<String> seen = new HashSet<>();
            for (Method m : ctrl.getDeclaredMethods()) {
                if (m.getAnnotation(GetMapping.class) == null && m.getAnnotation(PostMapping.class) == null
                        && m.getAnnotation(PutMapping.class) == null
                        && m.getAnnotation(DeleteMapping.class) == null) {
                    continue; // 非端点方法（DTO/工具）不检查
                }
                endpoints++;
                seen.add(m.getName());
                String expected = e.getValue().get(m.getName());
                assertNotNull(expected, ctrl.getSimpleName() + "#" + m.getName() + " 不在契约矩阵（新端点须补矩阵）");
                RequirePermission rp = m.getAnnotation(RequirePermission.class);
                assertNotNull(rp, ctrl.getSimpleName() + "#" + m.getName() + " 缺 @RequirePermission（漏挂=匿名越权面）");
                assertEquals(1, rp.value().length, ctrl.getSimpleName() + "#" + m.getName() + " 应恰好一个权限原子");
                assertEquals(expected, rp.value()[0],
                        ctrl.getSimpleName() + "#" + m.getName() + " 权限原子与契约矩阵不符");
            }
            assertEquals(e.getValue().keySet(), seen,
                    ctrl.getSimpleName() + " 端点方法集合与矩阵不一致（缺失或多余）");
        }
        assertEquals(15, endpoints, "四注解域端点合计恰 15（P4+I4+N4+C3），实际 " + endpoints);

        // PlatformTenantController：claims 显式校验形态（U1/U2 先例——编排者裁决不新增权限原子）
        RequestMapping base = PlatformTenantController.class.getAnnotation(RequestMapping.class);
        assertNotNull(base, "PlatformTenantController 缺类级 @RequestMapping");
        assertEquals("/api/v1/tenants", base.value()[0]);
        List<Method> ptEndpoints = new ArrayList<>();
        for (Method m : PlatformTenantController.class.getDeclaredMethods()) {
            if (m.getAnnotation(GetMapping.class) != null || m.getAnnotation(PostMapping.class) != null
                    || m.getAnnotation(PutMapping.class) != null || m.getAnnotation(DeleteMapping.class) != null) {
                ptEndpoints.add(m);
            }
        }
        assertEquals(1, ptEndpoints.size(), "平台租户端点恰 1 个（GET /api/v1/tenants）");
        Method page = ptEndpoints.get(0);
        assertEquals("page", page.getName());
        assertNotNull(page.getAnnotation(GetMapping.class), "平台租户列表必须仅 GET（只读零审计）");
        assertNull(page.getAnnotation(PostMapping.class));
        assertNull(page.getAnnotation(PutMapping.class));
        assertNull(page.getAnnotation(DeleteMapping.class));
        assertNull(page.getAnnotation(RequirePermission.class),
                "平台租户列表走 claims 显式校验（U1/U2 先例），不应挂权限注解原子——行为矩阵见 TC_C5");
    }

    // ==================== 锚点 19：V8 seed 契约 + H2 防漂移（AC-G1，先例 TC_R4b） ====================

    private static String readClasspath(String path) {
        try (var in = CommercializationRbacSwitchContractTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "classpath 资源缺失：" + path);
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("读取失败：" + path, ex);
        }
    }

    /** 按 ; 粗切 SQL，返回含关键字的语句段（seed 段为单行 VALUES，注释行分号影响可接受）。 */
    private static List<String> statements(String sql, String keyword) {
        List<String> hits = new ArrayList<>();
        for (String stmt : sql.split(";")) {
            if (stmt.contains(keyword)) {
                hits.add(stmt);
            }
        }
        return hits;
    }

    /** V8 → (grantId → [roleId, permId]) 全量授权三元组（区间守卫 2203~2220）。 */
    private static Map<Integer, List<Integer>> parseGrants(String sql, String keyword) {
        Map<Integer, List<Integer>> grants = new TreeMap<>();
        for (String stmt : statements(sql, keyword)) {
            Matcher g = Pattern.compile("\\((\\d{4}),\\s*(\\d),\\s*(\\d{3,4})\\)").matcher(stmt);
            while (g.find()) {
                int grantId = Integer.parseInt(g.group(1));
                if (grantId < 2203 || grantId > 2220) {
                    continue; // 防御：段内意外数字不干扰
                }
                grants.put(grantId, List.of(Integer.parseInt(g.group(2)), Integer.parseInt(g.group(3))));
            }
        }
        return grants;
    }

    @Test
    void TC_C2a_V8迁移seed契约_9原子18授权行_分布94311_幂等写法() {
        String v8 = readClasspath("/db/migration/V8__commercialization.sql");

        // 幂等：三新表 IF NOT EXISTS + seed 全 INSERT IGNORE（重放行数不变，AC-RBAC.5 同源）
        for (String table : new String[]{"t_plan", "t_invoice", "t_incident"}) {
            assertTrue(v8.contains("CREATE TABLE IF NOT EXISTS `" + table + "`"),
                    table + " 必须 IF NOT EXISTS 建表（幂等）");
        }
        for (String stmt : statements(v8, "INSERT INTO `t_permission`")) {
            assertTrue(stmt.contains("INSERT IGNORE"), "t_permission seed 必须 INSERT IGNORE（幂等重放）");
        }
        for (String stmt : statements(v8, "INSERT INTO `t_role_permission`")) {
            assertTrue(stmt.contains("INSERT IGNORE"), "t_role_permission seed 必须 INSERT IGNORE（幂等重放）");
        }
        for (String stmt : statements(v8, "INSERT INTO `t_plan`")) {
            assertTrue(stmt.contains("INSERT IGNORE"), "t_plan seed 必须 INSERT IGNORE（幂等重放）");
        }

        // 9 权限原子：id 恰为 1081~1089，code 集合 = plan/bill/cost/incident 九原子
        Map<Integer, String> permById = new TreeMap<>();
        for (String stmt : statements(v8, "INSERT IGNORE INTO `t_permission`")) {
            Matcher m = Pattern.compile("\\((\\d{4}),\\s*0,\\s*'(\\w+:\\w+)'").matcher(stmt);
            while (m.find()) {
                permById.put(Integer.parseInt(m.group(1)), m.group(2));
            }
        }
        Set<Integer> expectedIds = new TreeSet<>();
        for (int i = 1081; i <= 1089; i++) {
            expectedIds.add(i);
        }
        assertEquals(expectedIds, permById.keySet(), "权限原子 id 必须恰为 1081~1089（V8 seed 契约）");
        assertEquals(new TreeSet<>(List.of("plan:view", "plan:create", "plan:edit", "bill:view", "bill:manage",
                "cost:view", "incident:view", "incident:create", "incident:edit")),
                new TreeSet<>(permById.values()), "权限原子 code 集合 = 商用化 9 原子（PRD §4.9）");

        // 18 授权行：id 恰为 2203~2220，分布 role1×9/role2×4/role3×3/role4×1/role5×1
        Map<Integer, List<Integer>> grants = parseGrants(v8, "INSERT IGNORE INTO `t_role_permission`");
        Set<Integer> expectedGrantIds = new TreeSet<>();
        for (int i = 2203; i <= 2220; i++) {
            expectedGrantIds.add(i);
        }
        assertEquals(expectedGrantIds, grants.keySet(), "授权行 id 必须恰为 2203~2220（18 行）");
        Map<Integer, Integer> roleCount = new TreeMap<>();
        Map<Integer, Set<String>> roleCodes = new TreeMap<>();
        for (List<Integer> rp : grants.values()) {
            roleCount.merge(rp.get(0), 1, Integer::sum);
            roleCodes.computeIfAbsent(rp.get(0), k -> new TreeSet<>()).add(permById.get(rp.get(1)));
        }
        assertEquals(Map.of(1, 9, 2, 4, 3, 3, 4, 1, 5, 1), roleCount, "授权分布 = 9/4/3/1/1（PRD §4.9）");

        // 角色矩阵逐格（PRD §4.9 权威矩阵 = 编排者裁决 Q5：费用是管理层信息，executive 无 cost:view）
        assertEquals(new TreeSet<>(permById.values()), roleCodes.get(1), "platform_admin 9 项全量");
        assertEquals(new TreeSet<>(List.of("cost:view", "incident:view", "incident:create", "incident:edit")),
                roleCodes.get(2), "tenant_admin = cost:view + incident×3");
        assertEquals(new TreeSet<>(List.of("incident:view", "incident:create", "incident:edit")),
                roleCodes.get(3), "project_manager = incident×3（无 cost:view——裁决 Q5）");
        assertEquals(new TreeSet<>(List.of("incident:view")), roleCodes.get(4), "engineer 只读 incident:view");
        assertEquals(new TreeSet<>(List.of("incident:view")), roleCodes.get(5), "executive 只读 incident:view");

        // seed 冒烟（QA 标注 4 / 编排者必查 6）：官方三档恰 3 行，id 101~103，custom 不在 seed
        Set<Integer> planSeedIds = new TreeSet<>();
        for (String stmt : statements(v8, "INSERT IGNORE INTO `t_plan`")) {
            Matcher m = Pattern.compile("\\((\\d{3}),\\s*0,\\s*'(\\w+)'").matcher(stmt);
            while (m.find()) {
                planSeedIds.add(Integer.parseInt(m.group(1)));
            }
        }
        assertEquals(new TreeSet<>(List.of(101, 102, 103)), planSeedIds, "套餐 seed 恰 3 行（starter/pro/enterprise）");

        // D-9 承载列防回归：t_plan 建表块必须含 edition_override（评审 D1 修复落点）
        for (String stmt : statements(v8, "CREATE TABLE IF NOT EXISTS `t_plan`")) {
            assertTrue(stmt.contains("`edition_override` VARCHAR(16) DEFAULT NULL"),
                    "V8 t_plan 建表块必须含 edition_override 列（D-9：custom 套餐生效档位，评审 D1）");
        }
    }

    @Test
    void TC_C2b_H2测试schema与V8的seed三元组一致() {
        String v8 = readClasspath("/db/migration/V8__commercialization.sql");
        String h2 = readClasspath("/schema-h2.sql");

        // H2 权限 seed：id→code 与 V8 全量一致（防漂移，先例 TC_R4b）
        Map<Integer, String> v8Perm = new TreeMap<>();
        for (String stmt : statements(v8, "INSERT IGNORE INTO `t_permission`")) {
            Matcher m = Pattern.compile("\\((\\d{4}),\\s*0,\\s*'(\\w+:\\w+)'").matcher(stmt);
            while (m.find()) {
                v8Perm.put(Integer.parseInt(m.group(1)), m.group(2));
            }
        }
        Map<Integer, String> h2Perm = new TreeMap<>();
        for (String stmt : statements(h2, "MERGE INTO t_permission")) {
            Matcher m = Pattern.compile("\\((\\d{4}),\\s*0,\\s*'(\\w+:\\w+)'").matcher(stmt);
            while (m.find()) {
                h2Perm.put(Integer.parseInt(m.group(1)), m.group(2));
            }
        }
        for (int i = 1081; i <= 1089; i++) {
            assertTrue(h2Perm.containsKey(i), "H2 seed 缺权限原子 " + i + "（测试环境与 V8 契约漂移）");
            assertEquals(v8Perm.get(i), h2Perm.get(i), "权限原子 " + i + " 的 code 在 H2 与 V8 不一致");
        }

        // H2 授权 seed：(id, role_id, permission_id) 三元组与 V8 全量一致（防漂移核心）
        Map<Integer, List<Integer>> v8Grants = parseGrants(v8, "INSERT IGNORE INTO `t_role_permission`");
        Map<Integer, List<Integer>> h2Grants = parseGrants(h2, "MERGE INTO t_role_permission");
        assertEquals(18, h2Grants.size(), "H2 seed 授权行必须 18 行（2203~2220）");
        assertEquals(v8Grants, h2Grants, "H2 与 V8 授权三元组 (id, role, perm) 必须逐行一致");

        // H2 套餐 seed：3 行 id 与 V8 一致（seed 冒烟测试侧镜像）
        Set<Integer> h2PlanIds = new TreeSet<>();
        for (String stmt : statements(h2, "MERGE INTO t_plan")) {
            Matcher m = Pattern.compile("\\((\\d{3}),\\s*0,\\s*'(\\w+)'").matcher(stmt);
            while (m.find()) {
                h2PlanIds.add(Integer.parseInt(m.group(1)));
            }
        }
        assertEquals(new TreeSet<>(List.of(101, 102, 103)), h2PlanIds, "H2 套餐 seed 与 V8 同为三档 101~103");

        // H2 t_plan 建表含 edition_override（评审 D1 修复落点——实体全列 SELECT 的 H2 前提）
        for (String stmt : statements(h2, "CREATE TABLE IF NOT EXISTS t_plan")) {
            assertTrue(stmt.contains("edition_override"),
                    "H2 t_plan 建表块必须含 edition_override 列（与 V8 同步，评审 D1）");
        }
    }

    // ==================== 锚点 21：AC-G3 不限层（LayerGuard 零拦截，先例 TC_S1） ====================

    /** AC-G3 机制面：商用化五前缀不被 LayerGuard L2/L3 前缀命中（不限层=前缀不注册即不拦）。 */
    @Test
    @SuppressWarnings("unchecked")
    void TC_C3_商用化五前缀不被LayerGuard拦截() throws Exception {
        Class<?> guard = Class.forName("com.eaiselp.runtime.hierarchy.LayerGuardInterceptor");
        List<String> prefixes = new ArrayList<>();
        for (String fieldName : new String[]{"L3_PREFIXES", "L2_PREFIXES"}) {
            Field f = guard.getDeclaredField(fieldName);
            f.setAccessible(true);
            prefixes.addAll((List<String>) f.get(null));
        }
        assertFalse(prefixes.isEmpty(), "前置：前缀清单非空（反射读取失败会先抛异常）");
        List<String> uris = List.of("/api/v1/plans", "/api/v1/plans/1",
                "/api/v1/invoices", "/api/v1/invoices/generate", "/api/v1/invoices/1/transit",
                "/api/v1/incidents", "/api/v1/incidents/1",
                "/api/v1/cost-center", "/api/v1/cost-center/summary", "/api/v1/cost-center/invoices/1",
                "/api/v1/tenants");
        for (String uri : uris) {
            for (String p : prefixes) {
                assertFalse(uri.startsWith(p),
                        uri + " 不得被 LayerGuard 拦截，但命中已注册前缀 " + p + "（AC-G3 不限层）");
            }
        }
    }

    // ==================== 锚点 20：403 矩阵行为面（真实 PermissionInterceptor） ====================

    /** PRD §4.9 角色→权限码权威矩阵（与 TC_C2a 的 V8 seed 分布互为独立锚点）。 */
    private static List<String> codesOfRole(int roleId) {
        switch (roleId) {
            case 1: return List.of("plan:view", "plan:create", "plan:edit", "bill:view", "bill:manage",
                    "cost:view", "incident:view", "incident:create", "incident:edit");
            case 2: return List.of("cost:view", "incident:view", "incident:create", "incident:edit");
            case 3: return List.of("incident:view", "incident:create", "incident:edit");
            case 4:
            case 5: return List.of("incident:view");
            default: throw new IllegalArgumentException("roleId " + roleId);
        }
    }

    /** 四注解域端点（bean 传 null 服务——preHandle 不调方法体，HandlerMethod 仅取注解元数据）。 */
    private static List<HandlerMethod> endpointHandlers() {
        List<HandlerMethod> handlers = new ArrayList<>();
        for (Object bean : Arrays.asList(
                new PlanController(null),
                new InvoiceController(null),
                new IncidentController(null),
                new CostCenterController(null, null))) {
            for (Method m : bean.getClass().getDeclaredMethods()) {
                if (m.getAnnotation(GetMapping.class) != null || m.getAnnotation(PostMapping.class) != null
                        || m.getAnnotation(PutMapping.class) != null || m.getAnnotation(DeleteMapping.class) != null) {
                    handlers.add(new HandlerMethod(bean, m));
                }
            }
        }
        return handlers;
    }

    /**
     * 真实 PermissionInterceptor × 15 端点 × 5 角色 逐格断言（锚点 20，AC-G1/G2 机制面）：
     * plan/bill 仅 role1；cost:view=role1+2；incident create/edit=role1/2/3；incident:view 全角色。
     */
    @Test
    void TC_C4_权限拦截器403矩阵_15端点逐角色() throws Exception {
        // 期望矩阵（端点方法名 → 允许角色集合；方法名在四 Controller 内唯一，作断言键）
        Map<String, Set<Integer>> allowedRoles = new LinkedHashMap<>();
        for (String m : new String[]{"page", "get", "create", "update"}) {
            allowedRoles.put("PlanController#" + m, new HashSet<>(Set.of(1)));
        }
        for (String m : new String[]{"page", "get", "generate", "transit"}) {
            allowedRoles.put("InvoiceController#" + m, new HashSet<>(Set.of(1)));
        }
        allowedRoles.put("IncidentController#page", new HashSet<>(Set.of(1, 2, 3, 4, 5)));
        allowedRoles.put("IncidentController#get", new HashSet<>(Set.of(1, 2, 3, 4, 5)));
        allowedRoles.put("IncidentController#create", new HashSet<>(Set.of(1, 2, 3)));
        allowedRoles.put("IncidentController#update", new HashSet<>(Set.of(1, 2, 3)));
        for (String m : new String[]{"summary", "invoices", "invoiceDetail"}) {
            allowedRoles.put("CostCenterController#" + m, new HashSet<>(Set.of(1, 2)));
        }

        List<HandlerMethod> handlers = endpointHandlers();
        assertEquals(15, handlers.size(), "四注解域端点合计恰 15");

        for (HandlerMethod hm : handlers) {
            RequirePermission rp = hm.getMethodAnnotation(RequirePermission.class);
            assertNotNull(rp, hm.getMethod().getName() + " 缺 @RequirePermission");
            String key = hm.getBeanType().getSimpleName() + "#" + hm.getMethod().getName();
            Set<Integer> expected = allowedRoles.get(key);
            assertNotNull(expected, key + " 不在期望矩阵（新端点须补矩阵）");

            for (int roleId = 1; roleId <= 5; roleId++) {
                boolean pass = invokeInterceptor(hm, roleId);
                assertEquals(expected.contains(roleId), pass,
                        key + " 角色 " + roleId + "（" + rp.value()[0] + "）拦截判定与 PRD §4.9 矩阵不符");
            }
        }
    }

    /** 未登录（无 claims）：有注解端点一律拦截且 HTTP 401（40101 通道，机制面）。 */
    @Test
    void TC_C4b_未登录_注解端点401拦截() throws Exception {
        LoginUser.clear();
        for (HandlerMethod hm : endpointHandlers()) {
            HttpServletRequest req = mock(HttpServletRequest.class);
            when(req.getMethod()).thenReturn("GET");
            HttpServletResponse resp = mock(HttpServletResponse.class);
            when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

            PermissionInterceptor interceptor = new PermissionInterceptor(mock(PermissionService.class));
            assertFalse(interceptor.preHandle(req, resp, hm), "未登录必须拦截");
            verify(resp).setStatus(401);
            verify(resp, never()).setStatus(403);
        }
    }

    /** 以角色 roleId 走真实拦截器：返回 preHandle 判定（true=放行 / false=403 已写出）。 */
    private boolean invokeInterceptor(HandlerMethod hm, int roleId) throws Exception {
        LoginUser.set(JwtClaims.builder().userId(1L).username("op").tenantId(0L)
                .roles(List.of("role" + roleId)).build());
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.getRoleIdsByUserId(1L)).thenReturn(List.of((long) roleId));
        when(permissionService.getPermissionCodesByRoleIds(anyCollection()))
                .thenAnswer(inv -> codesOfRole(roleId));

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("GET");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        boolean pass = new PermissionInterceptor(permissionService).preHandle(req, resp, hm);
        if (!pass) {
            org.mockito.ArgumentCaptor<Integer> status =
                    org.mockito.ArgumentCaptor.forClass(Integer.class);
            verify(resp).setStatus(status.capture());
            assertEquals(403, status.getValue(), "拒绝必须 HTTP 403（40301）");
        }
        return pass;
    }

    // ==================== 锚点 20（claims 域）：平台租户列表 403 矩阵（先例 TenantControllerTest） ====================

    /** PlatformTenantController claims 矩阵：PA 200；TA/PM/engineer/executive 40301；未登录 40101。 */
    @Test
    void TC_C5_平台租户列表_claims角色矩阵() {
        TenantQueryService tenantQueryService = mock(TenantQueryService.class);
        when(tenantQueryService.pageTenants(any(), anyLong(), anyLong()))
                .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());
        PlatformTenantController controller = new PlatformTenantController(tenantQueryService);

        // platform_admin → 200（code 0），查询放行
        loginAs(0L, "platform_admin");
        var ok = controller.page(1, 20, null);
        assertEquals(ResultCode.SUCCESS, ok.getCode(), "platform_admin 查平台租户列表 → 200");
        assertNotNull(ok.getData());
        verify(tenantQueryService, times(1)).pageTenants(null, 1L, 20L);

        // TA/PM/engineer/executive → 40301，零查询（先拒绝后查库）
        for (String role : new String[]{"tenant_admin", "project_manager", "engineer", "executive"}) {
            loginAs(1L, role);
            var r = controller.page(1, 20, null);
            assertEquals(ResultCode.FORBIDDEN, r.getCode(), role + " 查平台租户列表 → 40301");
            assertNull(r.getData());
        }
        verify(tenantQueryService, times(1)).pageTenants(any(), anyLong(), anyLong());

        // 未登录 → 40101
        LoginUser.clear();
        var anon = controller.page(1, 20, null);
        assertEquals(ResultCode.UNAUTHORIZED, anon.getCode());
        verify(tenantQueryService, times(1)).pageTenants(any(), anyLong(), anyLong());
    }

    private void loginAs(Long tenantId, String... roles) {
        LoginUser.set(JwtClaims.builder()
                .userId(1L).username("op").tenantId(tenantId).roles(List.of(roles)).build());
    }

    @AfterEach
    void clearThreadLocal() {
        // 必须 clear() 而非 set(null)：LoginUser.set 会同步注入 TenantContext（多租户隔离桥），
        // set(null) 只清 claims 不清 TenantContext——本类 TC_C5 以 tenantId=1L 登录过四角色，
        // 泄漏会使后续共享主线程的 Spring 测试类被租户拦截器改写（AND tenant_id=1）。
        LoginUser.clear();
    }
}
