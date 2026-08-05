package com.eaiselp.capability.loader;

import com.eaiselp.capability.model.AgentDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CapabilityLoader 单元测试。
 *
 * <p>核心验证点：getAgent() 的短码兜底逻辑（D1 阻断修复）。
 * 不依赖真实文件系统，通过反射注入 agents map。</p>
 */
class CapabilityLoaderTest {

    private CapabilityLoader loader;
    private Map<String, AgentDefinition> agentsMap;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        loader = new CapabilityLoader(new com.eaiselp.capability.parser.MarkdownParser());
        // 反射注入 agents map（不触发 @PostConstruct 的文件加载）
        Field f = CapabilityLoader.class.getDeclaredField("agents");
        f.setAccessible(true);
        agentsMap = (Map<String, AgentDefinition>) f.get(loader);

        // 准备测试数据：注册 3 个角色（与生产一致，使用 team- 前缀）
        agentsMap.put("team-po", makeAgent("team-po", "opus"));
        agentsMap.put("team-dev", makeAgent("team-dev", "sonnet"));
        agentsMap.put("team-qa", makeAgent("team-qa", "haiku"));
    }

    private AgentDefinition makeAgent(String name, String model) {
        AgentDefinition def = new AgentDefinition();
        def.setName(name);
        def.setModel(model);
        return def;
    }

    // ===== 短码兜底（D1 修复核心）=====

    @Test
    @DisplayName("短码 po → 命中 team-po（兜底逻辑生效）")
    void getAgent_shortCode_po() {
        AgentDefinition def = loader.getAgent("po");
        assertNotNull(def, "短码 po 应兜底命中 team-po");
        assertEquals("team-po", def.getName());
    }

    @Test
    @DisplayName("短码 dev → 命中 team-dev")
    void getAgent_shortCode_dev() {
        AgentDefinition def = loader.getAgent("dev");
        assertNotNull(def);
        assertEquals("team-dev", def.getName());
    }

    @Test
    @DisplayName("短码 qa → 命中 team-qa")
    void getAgent_shortCode_qa() {
        AgentDefinition def = loader.getAgent("qa");
        assertNotNull(def);
        assertEquals("team-qa", def.getName());
    }

    // ===== 完整名（权威契约）=====

    @Test
    @DisplayName("完整名 team-po → 精确命中")
    void getAgent_fullName_teamPo() {
        AgentDefinition def = loader.getAgent("team-po");
        assertNotNull(def);
        assertEquals("team-po", def.getName());
        assertEquals("opus", def.getModel());
    }

    @Test
    @DisplayName("完整名 team-dev → 精确命中")
    void getAgent_fullName_teamDev() {
        AgentDefinition def = loader.getAgent("team-dev");
        assertNotNull(def);
        assertEquals("sonnet", def.getModel());
    }

    // ===== 边界 / 异常 =====

    @Test
    @DisplayName("不存在的角色名 → 返回 null")
    void getAgent_unknown() {
        assertNull(loader.getAgent("team-nonexistent"));
    }

    @Test
    @DisplayName("不存在的短码 → 返回 null（兜底也 miss）")
    void getAgent_unknownShortCode() {
        assertNull(loader.getAgent("nonexistent"));
    }

    @Test
    @DisplayName("null 入参 → 返回 null（防空指针）")
    void getAgent_null() {
        assertNull(loader.getAgent(null));
    }

    @Test
    @DisplayName("空字符串入参 → 返回 null")
    void getAgent_empty() {
        assertNull(loader.getAgent(""));
    }

    @Test
    @DisplayName("纯空格入参 → 返回 null")
    void getAgent_blank() {
        assertNull(loader.getAgent("   "));
    }

    @Test
    @DisplayName("大小写敏感：Team-po 不命中 team-po（精确匹配）")
    void getAgent_caseSensitive() {
        assertNull(loader.getAgent("Team-po"), "大小写敏感，不应兜底命中");
    }

    @Test
    @DisplayName("team-team-po 不会被二次加前缀（已有前缀不兜底）")
    void getAgent_alreadyPrefixed_miss() {
        assertNull(loader.getAgent("team-team-po"), "已带 team- 前缀的未知名不应二次兜底");
    }
}
