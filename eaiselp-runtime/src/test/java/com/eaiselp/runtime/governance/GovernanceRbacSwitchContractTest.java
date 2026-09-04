package com.eaiselp.runtime.governance;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import com.eaiselp.common.security.RequirePermission;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RBAC/SWITCH 契约测试（case-20260820 QA 补充，覆盖此前无自动化的 AC-RBAC.1/.2 机制面、
 * AC-RBAC.4 seed 契约、AC-SWITCH.1 前缀豁免）。
 *
 * <p>纯静态/反射断言，零 Spring 上下文：
 * <ul>
 *   <li>TC-S1（AC-SWITCH.1）：LayerGuardInterceptor 的 L2/L3 前缀清单不得命中四域与订阅 URI
 *       （不限层=前缀不注册即不拦，PRD §1.3/技术方案 §8.2）；</li>
 *   <li>TC-R1（AC-RBAC.1/.2 机制面）：四域 Controller 全部端点方法必挂 @RequirePermission，
 *       且值与 SE §6 映射一致（GET→view、POST 无{id}→create、POST 含{id}→edit、PUT/DELETE→edit），
 *       路径前缀符合 G14（/api/v1/）——防注解漏挂导致越权；</li>
 *   <li>TC-R4a（AC-RBAC.4）：V6 迁移 seed 契约——12 权限原子 id 1059~1070、36 授权行 id 2134~2169、
 *       分布 role1×12/role2×12/role3×4/role4×4/role5×4、只读角色仅挂 view 原子、
 *       幂等写法（IF NOT EXISTS + INSERT IGNORE）与零 ALTER；</li>
 *   <li>TC-R4b：H2 测试 schema（schema-h2.sql）的 seed 与 V6 同 id 集合（防两处漂移）。</li>
 * </ul></p>
 */
class GovernanceRbacSwitchContractTest {

    private static final List<String> GOVERNANCE_URIS = List.of(
            "/api/v1/standards", "/api/v1/standards/1",
            "/api/v1/templates", "/api/v1/templates/1/enabled",
            "/api/v1/data-assets", "/api/v1/data-assets/1",
            "/api/v1/data-quality-rules", "/api/v1/data-quality-rules/1/check-results",
            "/api/v1/tenant/subscription", "/api/v1/tenant/1/subscription");

    // ==================== AC-SWITCH.1：不限层（LayerGuard 零拦截） ====================

    @Test
    @SuppressWarnings("unchecked")
    void TC_S1_四域与订阅URI不被LayerGuard前缀命中() throws Exception {
        Class<?> guard = Class.forName("com.eaiselp.runtime.hierarchy.LayerGuardInterceptor");
        List<String> prefixes = new ArrayList<>();
        for (String fieldName : new String[]{"L3_PREFIXES", "L2_PREFIXES"}) {
            Field f = guard.getDeclaredField(fieldName);
            f.setAccessible(true);
            prefixes.addAll((List<String>) f.get(null));
        }
        assertFalse(prefixes.isEmpty(), "前置：前缀清单非空（反射读取失败会先抛异常）");
        for (String uri : GOVERNANCE_URIS) {
            for (String p : prefixes) {
                assertFalse(uri.startsWith(p),
                        uri + " 不得被 LayerGuard 拦截，但命中已注册前缀 " + p + "（AC-SWITCH.1 不限层）");
            }
        }
    }

    // ==================== AC-RBAC.1/.2 机制面：四域权限注解契约 ====================

    /** 四域 Controller：全部端点方法挂 @RequirePermission 且值与 SE §6 映射一致；路径前缀 G14。 */
    @Test
    void TC_R1_四域Controller权限注解与SE映射一致() {
        Map<Class<?>, String> domains = new LinkedHashMap<>();
        domains.put(com.eaiselp.runtime.controller.StandardController.class, "standard");
        domains.put(com.eaiselp.runtime.controller.TemplateController.class, "template");
        domains.put(com.eaiselp.runtime.controller.DataAssetController.class, "asset");
        domains.put(com.eaiselp.runtime.controller.DataQualityRuleController.class, "dqrule");

        int checked = 0;
        for (Map.Entry<Class<?>, String> e : domains.entrySet()) {
            Class<?> ctrl = e.getKey();
            String domain = e.getValue();

            // G14：/api/v1/ 前缀
            RequestMapping base = ctrl.getAnnotation(RequestMapping.class);
            assertNotNull(base, ctrl.getSimpleName() + " 缺类级 @RequestMapping");
            assertTrue(base.value().length > 0 && base.value()[0].startsWith("/api/v1/"),
                    ctrl.getSimpleName() + " 路径前缀必须 /api/v1/（G14），实际 " + Arrays.toString(base.value()));

            for (Method m : ctrl.getDeclaredMethods()) {
                GetMapping get = m.getAnnotation(GetMapping.class);
                PostMapping post = m.getAnnotation(PostMapping.class);
                PutMapping put = m.getAnnotation(PutMapping.class);
                DeleteMapping del = m.getAnnotation(DeleteMapping.class);
                if (get == null && post == null && put == null && del == null) {
                    continue; // 非端点方法（DTO/工具）不检查
                }
                checked++;

                String expected;
                if (get != null) {
                    expected = domain + ":view";
                } else if (post != null) {
                    boolean subAction = Arrays.stream(post.value()).anyMatch(p -> p.contains("{id}"));
                    expected = domain + (subAction ? ":edit" : ":create");
                } else {
                    expected = domain + ":edit"; // PUT/DELETE
                }

                RequirePermission rp = m.getAnnotation(RequirePermission.class);
                assertNotNull(rp, ctrl.getSimpleName() + "#" + m.getName() + " 缺 @RequirePermission（漏挂=匿名越权面）");
                assertEquals(1, rp.value().length,
                        ctrl.getSimpleName() + "#" + m.getName() + " 应恰好一个权限原子（或关系数组）");
                assertEquals(expected, rp.value()[0],
                        ctrl.getSimpleName() + "#" + m.getName() + " 权限原子与 SE §6 映射不符");
            }
        }
        assertTrue(checked >= 22, "四域端点方法合计应 ≥22（S6+T6+A5+Q6 实际 " + checked + "），反射遍历异常时兜底");
    }

    // ==================== AC-RBAC.4：V6 seed 契约与幂等写法 ====================

    private static String readClasspath(String path) {
        try (var in = GovernanceRbacSwitchContractTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "classpath 资源缺失：" + path);
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("读取失败：" + path, ex);
        }
    }

    /** 按 ; 粗切 SQL，返回含关键字的语句段（忽略注释行的分号影响可接受——seed 段为单行 VALUES）。 */
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
    void TC_R4a_V6迁移seed契约_12原子36授权行_幂等写法_零ALTER() {
        String v6 = readClasspath("/db/migration/V6__l2_governance_close.sql");

        // 幂等：四表 IF NOT EXISTS；零 ALTER（对既有表零结构变更，PRD §6.2）
        for (String table : new String[]{"t_standard", "t_template", "t_data_asset", "t_data_quality_rule"}) {
            assertTrue(v6.contains("CREATE TABLE IF NOT EXISTS `" + table + "`"),
                    table + " 必须 IF NOT EXISTS 建表（幂等）");
        }
        assertFalse(v6.toUpperCase().contains("ALTER TABLE"), "V6 必须零 ALTER（PRD §6.2 迁移兼容）");

        // 12 权限原子：id 恰为 1059~1070，code 集合 = 四域 × view/create/edit
        //（含关键字的语句段合并提取——不依赖段条数，注释段与 INSERT 可能无分号分隔）
        Set<Integer> permIds = new TreeSet<>();
        Set<String> permCodes = new TreeSet<>();
        for (String stmt : statements(v6, "INSERT IGNORE INTO `t_permission`")) {
            Matcher m = Pattern.compile("\\((\\d{4}),\\s*0,\\s*'(\\w+:\\w+)'").matcher(stmt);
            while (m.find()) {
                permIds.add(Integer.parseInt(m.group(1)));
                permCodes.add(m.group(2));
            }
        }
        Set<Integer> expectedIds = new TreeSet<>();
        for (int i = 1059; i <= 1070; i++) {
            expectedIds.add(i);
        }
        assertEquals(expectedIds, permIds, "权限原子 id 必须恰为 1059~1070（PRD §4.4 seed 契约）");
        Set<String> expectedCodes = new TreeSet<>();
        for (String d : new String[]{"standard", "template", "asset", "dqrule"}) {
            for (String a : new String[]{"view", "create", "edit"}) {
                expectedCodes.add(d + ":" + a);
            }
        }
        assertEquals(expectedCodes, permCodes, "权限原子 code 集合 = 四域 × 三动作");

        // 36 授权行：id 恰为 2134~2169，分布 role1×12/role2×12/role3×4/role4×4/role5×4
        //（含关键字的语句段合并提取——注释段与 INSERT 无分号分隔，不依赖段条数）
        Set<Integer> grantIds = new TreeSet<>();
        Map<Integer, Integer> roleCount = new TreeMap<>();
        Map<Integer, Set<Integer>> rolePerms = new HashMap<>();
        for (String stmt : statements(v6, "INSERT IGNORE INTO `t_role_permission`")) {
            Matcher g = Pattern.compile("\\((\\d{4}),\\s*(\\d),\\s*(\\d{3,4})\\)").matcher(stmt);
            while (g.find()) {
                int grantId = Integer.parseInt(g.group(1));
                if (grantId < 2134 || grantId > 2169) {
                    continue; // 防御：段内意外数字不干扰
                }
                int roleId = Integer.parseInt(g.group(2));
                int permId = Integer.parseInt(g.group(3));
                grantIds.add(grantId);
                roleCount.merge(roleId, 1, Integer::sum);
                rolePerms.computeIfAbsent(roleId, k -> new TreeSet<>()).add(permId);
            }
        }
        Set<Integer> expectedGrantIds = new TreeSet<>();
        for (int i = 2134; i <= 2169; i++) {
            expectedGrantIds.add(i);
        }
        assertEquals(expectedGrantIds, grantIds, "授权行 id 必须恰为 2134~2169（36 行）");
        assertEquals(Map.of(1, 12, 2, 12, 3, 4, 4, 4, 5, 4), roleCount,
                "授权分布 = platform_admin×12 / tenant_admin×12 / PM、engineer、executive 各×4");
        // 只读角色（role3/4/5）仅挂四域 view 原子（PRD §4.4 矩阵）
        Set<Integer> viewOnlyPerms = Set.of(1059, 1062, 1065, 1068);
        for (int roleId = 3; roleId <= 5; roleId++) {
            assertTrue(viewOnlyPerms.containsAll(rolePerms.get(roleId)),
                    "role" + roleId + " 仅可挂 view 原子，实际 " + rolePerms.get(roleId) + "（AC-RBAC.2 只读矩阵）");
        }
        // 管理角色（role1/2）全量 12 原子
        for (int roleId = 1; roleId <= 2; roleId++) {
            assertEquals(expectedIds, rolePerms.get(roleId), "role" + roleId + " 必须全量 12 原子（AC-RBAC.1）");
        }
    }

    @Test
    void TC_R4b_H2测试schema与V6的seed同id集合() {
        String h2 = readClasspath("/schema-h2.sql");

        Set<Integer> h2PermIds = new TreeSet<>();
        for (String stmt : statements(h2, "MERGE INTO t_permission")) {
            Matcher m = Pattern.compile("\\((\\d{4}),\\s*0,\\s*'\\w+:\\w+'").matcher(stmt);
            while (m.find()) {
                h2PermIds.add(Integer.parseInt(m.group(1)));
            }
        }
        for (int i = 1059; i <= 1070; i++) {
            assertTrue(h2PermIds.contains(i), "H2 seed 缺权限原子 " + i + "（测试环境与 V6 契约漂移）");
        }

        Set<Integer> h2GrantIds = new TreeSet<>();
        for (String stmt : statements(h2, "MERGE INTO t_role_permission")) {
            Matcher g = Pattern.compile("\\((\\d{4}),\\s*\\d,\\s*\\d{3,4}\\)").matcher(stmt);
            while (g.find()) {
                int id = Integer.parseInt(g.group(1));
                if (id >= 2134 && id <= 2169) {
                    h2GrantIds.add(id);
                }
            }
        }
        assertEquals(36, h2GrantIds.size(), "H2 seed 授权行必须 36 行（2134~2169）");
    }
}
