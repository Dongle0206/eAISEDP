package com.eaiselp.runtime.governance;

import com.eaiselp.common.security.RequirePermission;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L3 收口 RBAC/SWITCH/幂等契约测试（case-20260821 T18，SE §9.1 锚点 21/22/23——
 * AC-RBAC.1/2/3/5 + AC-RBAC.4 机制面 + AC-SWITCH.1；对齐 V6
 * GovernanceRbacSwitchContractTest 先例，纯静态/反射断言零 Spring 上下文）。
 *
 * <p>锚点 24（403 矩阵 API 级）与锚点 23 的列表空/直查 404（QA API 级）由 QA 集成
 * 用例承接——本类承载可静态断言的机制面：注解矩阵、seed 契约与幂等写法、
 * 租户拦截器/LayerGuard 注册面。</p>
 */
class L3RbacSwitchContractTest {

    // ==================== 锚点 21：三域 Controller 权限注解矩阵（AC-RBAC.1/2/3） ====================

    /**
     * 20 端点 → @RequirePermission 逐格对照 api-contracts 附表（防 Dev 漏挂）：
     * R1/R3/R7=risk:view；R2=risk:create；R4/R5/R6=risk:edit；
     * C1/C3=compliance:view；C2=compliance:create；C4/C5=compliance:edit；
     * B1/B3/B8=bizcase:view；B2=bizcase:create；B4/B5/B6=bizcase:edit；<b>B7=bizcase:approve</b>。
     */
    @Test
    void TC_L1_三域Controller权限注解与契约矩阵一致() {
        Map<String, String> riskExpected = new LinkedHashMap<>();
        riskExpected.put("page", "risk:view");
        riskExpected.put("create", "risk:create");
        riskExpected.put("get", "risk:view");
        riskExpected.put("update", "risk:edit");
        riskExpected.put("delete", "risk:edit");
        riskExpected.put("transit", "risk:edit");
        riskExpected.put("dashboard", "risk:view");

        Map<String, String> complianceExpected = new LinkedHashMap<>();
        complianceExpected.put("page", "compliance:view");
        complianceExpected.put("create", "compliance:create");
        complianceExpected.put("get", "compliance:view");
        complianceExpected.put("update", "compliance:edit");
        complianceExpected.put("delete", "compliance:edit");

        Map<String, String> bizcaseExpected = new LinkedHashMap<>();
        bizcaseExpected.put("page", "bizcase:view");
        bizcaseExpected.put("create", "bizcase:create");
        bizcaseExpected.put("get", "bizcase:view");
        bizcaseExpected.put("update", "bizcase:edit");
        bizcaseExpected.put("delete", "bizcase:edit");
        bizcaseExpected.put("updateDecisionNote", "bizcase:edit");
        bizcaseExpected.put("transit", "bizcase:approve");       // §0.3-2：四类流转统一挂 approve（AC-F2.9）
        bizcaseExpected.put("portfolio", "bizcase:view");

        Map<Class<?>, Map<String, String>> matrix = new LinkedHashMap<>();
        matrix.put(com.eaiselp.runtime.controller.RiskController.class, riskExpected);
        matrix.put(com.eaiselp.runtime.controller.ComplianceCheckController.class, complianceExpected);
        matrix.put(com.eaiselp.runtime.controller.BusinessCaseController.class, bizcaseExpected);

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
        assertEquals(20, endpoints, "三域端点合计恰 20（R7+C5+B8），实际 " + endpoints);
    }

    /** B7 独立原子专项：transit 必须挂 bizcase:approve 而非 edit（创建与审批分离，AC-F2.9）。 */
    @Test
    void TC_L1b_案例transit挂approve独立原子_PM全403机制面() {
        for (Method m : com.eaiselp.runtime.controller.BusinessCaseController.class.getDeclaredMethods()) {
            if (m.getAnnotation(PostMapping.class) != null
                    && Arrays.toString(m.getAnnotation(PostMapping.class).value()).contains("{id}/transit")) {
                assertEquals("bizcase:approve", m.getAnnotation(RequirePermission.class).value()[0]);
                return;
            }
        }
        fail("未找到 B7 transit 端点方法");
    }

    // ==================== 锚点 22：V7 seed 契约与幂等写法（AC-RBAC.5） ====================

    private static String readClasspath(String path) {
        try (var in = L3RbacSwitchContractTest.class.getResourceAsStream(path)) {
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

    @Test
    void TC_L2a_V7迁移seed契约_10原子33授权行_幂等写法_零ALTER() {
        String v7 = readClasspath("/db/migration/V7__l3_close.sql");

        // 幂等：三表 IF NOT EXISTS；零 ALTER；seed 全部 INSERT IGNORE（重放行数不变，AC-RBAC.5）
        for (String table : new String[]{"t_risk", "t_compliance_check", "t_business_case"}) {
            assertTrue(v7.contains("CREATE TABLE IF NOT EXISTS `" + table + "`"),
                    table + " 必须 IF NOT EXISTS 建表（幂等）");
        }
        assertFalse(v7.toUpperCase().contains("ALTER TABLE"), "V7 必须零 ALTER（PRD §6.2 迁移兼容）");
        for (String stmt : statements(v7, "INSERT INTO `t_permission`")) {
            assertTrue(stmt.contains("INSERT IGNORE"), "t_permission seed 必须 INSERT IGNORE（幂等重放）");
        }
        for (String stmt : statements(v7, "INSERT INTO `t_role_permission`")) {
            assertTrue(stmt.contains("INSERT IGNORE"), "t_role_permission seed 必须 INSERT IGNORE（幂等重放）");
        }

        // 10 权限原子：id 恰为 1071~1080，code 集合 = risk/compliance ×view/create/edit + bizcase×view/create/edit/approve
        Set<Integer> permIds = new TreeSet<>();
        Set<String> permCodes = new TreeSet<>();
        for (String stmt : statements(v7, "INSERT IGNORE INTO `t_permission`")) {
            Matcher m = Pattern.compile("\\((\\d{4}),\\s*0,\\s*'(\\w+:\\w+)'").matcher(stmt);
            while (m.find()) {
                permIds.add(Integer.parseInt(m.group(1)));
                permCodes.add(m.group(2));
            }
        }
        Set<Integer> expectedIds = new TreeSet<>();
        for (int i = 1071; i <= 1080; i++) {
            expectedIds.add(i);
        }
        assertEquals(expectedIds, permIds, "权限原子 id 必须恰为 1071~1080（V7 seed 契约）");
        Set<String> expectedCodes = new TreeSet<>();
        for (String d : new String[]{"risk", "compliance"}) {
            for (String a : new String[]{"view", "create", "edit"}) {
                expectedCodes.add(d + ":" + a);
            }
        }
        expectedCodes.add("bizcase:view");
        expectedCodes.add("bizcase:create");
        expectedCodes.add("bizcase:edit");
        expectedCodes.add("bizcase:approve");
        assertEquals(expectedCodes, permCodes, "权限原子 code 集合 = 三域 10 原子（含 approve 独立原子）");

        // 33 授权行：id 恰为 2170~2202，分布 role1×10/role2×10/role3×7/role4×3/role5×3
        Set<Integer> grantIds = new TreeSet<>();
        Map<Integer, Set<Integer>> rolePerms = new HashMap<>();
        for (String stmt : statements(v7, "INSERT IGNORE INTO `t_role_permission`")) {
            Matcher g = Pattern.compile("\\((\\d{4}),\\s*(\\d),\\s*(\\d{3,4})\\)").matcher(stmt);
            while (g.find()) {
                int grantId = Integer.parseInt(g.group(1));
                if (grantId < 2170 || grantId > 2202) {
                    continue; // 防御：段内意外数字不干扰
                }
                grantIds.add(grantId);
                rolePerms.computeIfAbsent(Integer.parseInt(g.group(2)), k -> new TreeSet<>())
                        .add(Integer.parseInt(g.group(3)));
            }
        }
        Set<Integer> expectedGrantIds = new TreeSet<>();
        for (int i = 2170; i <= 2202; i++) {
            expectedGrantIds.add(i);
        }
        assertEquals(expectedGrantIds, grantIds, "授权行 id 必须恰为 2170~2202（33 行）");
        assertEquals(10, rolePerms.get(1).size(), "platform_admin 全量 10 原子（AC-RBAC.1）");
        assertEquals(10, rolePerms.get(2).size(), "tenant_admin 全量 10 原子（GRC/Strategy 兼任，裁决 Q5）");
        assertEquals(7, rolePerms.get(3).size(), "PM 7 原子（risk×3 + compliance:view + bizcase×3）");
        assertEquals(3, rolePerms.get(4).size(), "engineer 三域 view");
        assertEquals(3, rolePerms.get(5).size(), "executive 三域 view");
        // 利益冲突隔离（AC-RBAC.2 机制面）：PM 无 compliance create/edit、无 bizcase:approve（1075/1076/1080）
        assertFalse(rolePerms.get(3).containsAll(Set.of(1075, 1076, 1080)),
                "PM 不得持有 compliance:create/edit 与 bizcase:approve");
        assertTrue(rolePerms.get(3).containsAll(Set.of(1072, 1073, 1078, 1079)),
                "PM 持 risk create/edit + bizcase create/edit（一线上报+起草）");
        // 只读角色（role4/5）仅三域 view 原子
        Set<Integer> viewOnly = Set.of(1071, 1074, 1077);
        assertTrue(viewOnly.containsAll(rolePerms.get(4)), "engineer 仅 view 原子，实际 " + rolePerms.get(4));
        assertTrue(viewOnly.containsAll(rolePerms.get(5)), "executive 仅 view 原子，实际 " + rolePerms.get(5));
    }

    @Test
    void TC_L2b_H2测试schema与V7的seed同id集合() {
        String h2 = readClasspath("/schema-h2.sql");

        Set<Integer> h2PermIds = new TreeSet<>();
        for (String stmt : statements(h2, "MERGE INTO t_permission")) {
            Matcher m = Pattern.compile("\\((\\d{4}),\\s*0,\\s*'\\w+:\\w+'").matcher(stmt);
            while (m.find()) {
                h2PermIds.add(Integer.parseInt(m.group(1)));
            }
        }
        for (int i = 1071; i <= 1080; i++) {
            assertTrue(h2PermIds.contains(i), "H2 seed 缺权限原子 " + i + "（测试环境与 V7 契约漂移）");
        }

        Set<Integer> h2GrantIds = new TreeSet<>();
        for (String stmt : statements(h2, "MERGE INTO t_role_permission")) {
            Matcher g = Pattern.compile("\\((\\d{4}),\\s*\\d,\\s*\\d{3,4}\\)").matcher(stmt);
            while (g.find()) {
                int id = Integer.parseInt(g.group(1));
                if (id >= 2170 && id <= 2202) {
                    h2GrantIds.add(id);
                }
            }
        }
        assertEquals(33, h2GrantIds.size(), "H2 seed 授权行必须 33 行（2170~2202）");

        // H2 三表结构同步（t_risk/t_compliance_check/t_business_case 建表存在）
        for (String table : new String[]{"t_risk", "t_compliance_check", "t_business_case"}) {
            assertTrue(h2.contains("CREATE TABLE IF NOT EXISTS " + table),
                    "H2 缺三表简化版建表：" + table);
        }
    }

    // ==================== 锚点 23：跨租户隔离与不限层机制面（AC-RBAC.4 / AC-SWITCH.1） ====================

    /**
     * AC-RBAC.4 机制面：三表均为租户级业务表——不进 EaiselpTenantHandler.IGNORE_TABLES
     * （租户拦截器自动注入 tenant_id，聚合手写 SQL 同样被改写）；禁 @InterceptorIgnore 由 G13 承载。
     */
    @Test
    @SuppressWarnings("unchecked")
    void TC_L3a_三表不进租户拦截器忽略清单() throws Exception {
        Class<?> handler = Class.forName("com.eaiselp.common.tenant.EaiselpTenantHandler");
        Field f = handler.getDeclaredField("IGNORE_TABLES");
        f.setAccessible(true);
        String[] ignoreTables = (String[]) f.get(null);
        for (String table : new String[]{"t_risk", "t_compliance_check", "t_business_case"}) {
            for (String ignore : ignoreTables) {
                assertFalse(ignore.equalsIgnoreCase(table),
                        table + " 不得进 IGNORE_TABLES（租户隔离阻断级，P11/G13）");
            }
        }
    }

    /** AC-SWITCH.1：三域前缀不被 LayerGuard L2/L3 前缀命中（不限层=前缀不注册即不拦）。 */
    @Test
    @SuppressWarnings("unchecked")
    void TC_L3b_三域前缀不被LayerGuard拦截() throws Exception {
        Class<?> guard = Class.forName("com.eaiselp.runtime.hierarchy.LayerGuardInterceptor");
        List<String> prefixes = new ArrayList<>();
        for (String fieldName : new String[]{"L3_PREFIXES", "L2_PREFIXES"}) {
            Field f = guard.getDeclaredField(fieldName);
            f.setAccessible(true);
            prefixes.addAll((List<String>) f.get(null));
        }
        assertFalse(prefixes.isEmpty(), "前置：前缀清单非空（反射读取失败会先抛异常）");
        List<String> uris = List.of("/api/v1/risks", "/api/v1/risks/dashboard", "/api/v1/risks/1/transit",
                "/api/v1/compliance-checks", "/api/v1/compliance-checks/1",
                "/api/v1/business-cases", "/api/v1/business-cases/portfolio",
                "/api/v1/business-cases/1/transit");
        for (String uri : uris) {
            for (String p : prefixes) {
                assertFalse(uri.startsWith(p),
                        uri + " 不得被 LayerGuard 拦截，但命中已注册前缀 " + p + "（AC-SWITCH.1 不限层）");
            }
        }
    }

    /** AC-F1.15 机制面：看板/组合无任何写语义端点映射（三 Controller 无 POST/PUT/DELETE 同路径）。 */
    @Test
    void TC_L3c_聚合端点纯只读_无写语义映射() {
        for (Method m : com.eaiselp.runtime.controller.RiskController.class.getDeclaredMethods()) {
            if (m.getName().equals("dashboard")) {
                assertNotNull(m.getAnnotation(GetMapping.class), "dashboard 必须仅 GET");
                assertNull(m.getAnnotation(PostMapping.class));
                assertNull(m.getAnnotation(PutMapping.class));
                assertNull(m.getAnnotation(DeleteMapping.class));
            }
        }
        for (Method m : com.eaiselp.runtime.controller.BusinessCaseController.class.getDeclaredMethods()) {
            if (m.getName().equals("portfolio")) {
                assertNotNull(m.getAnnotation(GetMapping.class), "portfolio 必须仅 GET");
                assertNull(m.getAnnotation(PostMapping.class));
                assertNull(m.getAnnotation(PutMapping.class));
                assertNull(m.getAnnotation(DeleteMapping.class));
            }
        }
    }
}
