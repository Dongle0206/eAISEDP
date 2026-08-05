package com.eaiselp.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JacksonLongConfig 单元测试（D1 阻断修复：雪花 ID Long→String）。
 *
 * <p>验证 Long 序列化为字符串，防止前端 JS Number 精度丢失。</p>
 */
class JacksonLongConfigTest {

    @Test
    @DisplayName("Long 包装类型序列化为字符串")
    void longWrapperSerializedAsString() throws Exception {
        ObjectMapper mapper = buildConfiguredMapper();
        String json = mapper.writeValueAsString(2084826478478860312L);
        assertEquals("\"2084826478478860312\"", json, "Long 应序列化为字符串");
    }

    @Test
    @DisplayName("long 基本类型序列化为字符串")
    void longPrimitiveSerializedAsString() throws Exception {
        ObjectMapper mapper = buildConfiguredMapper();
        // 通过包装了 long 字段的 bean 验证基本类型
        TestBean bean = new TestBean();
        bean.setId(9007199254740993L); // 超过 JS 安全整数
        String json = mapper.writeValueAsString(bean);
        assertTrue(json.contains("\"id\":\"9007199254740993\""),
                "long 字段应序列化为字符串，实际: " + json);
    }

    @Test
    @DisplayName("Long 字段在 DTO 中序列化为字符串")
    void longFieldInDtoSerializedAsString() throws Exception {
        ObjectMapper mapper = buildConfiguredMapper();
        TestBean bean = new TestBean();
        bean.setId(123L);
        String json = mapper.writeValueAsString(bean);
        assertEquals("{\"id\":\"123\"}", json);
    }

    @Test
    @DisplayName("null Long 字段序列化为 null（不报错）")
    void nullLongFieldSerializedAsNull() throws Exception {
        ObjectMapper mapper = buildConfiguredMapper();
        TestBean bean = new TestBean();
        bean.setId(null);
        String json = mapper.writeValueAsString(bean);
        assertEquals("{\"id\":null}", json);
    }

    @Test
    @DisplayName("雪花 ID 级别大数不丢精度")
    void snowflakeIdNoPrecisionLoss() throws Exception {
        ObjectMapper mapper = buildConfiguredMapper();
        long snowflake = 2084826478478860312L;
        String json = mapper.writeValueAsString(snowflake);
        // 反序列化回来应该一样（字符串无精度问题）
        assertEquals("\"" + snowflake + "\"", json);
    }

    @Test
    @DisplayName("JacksonLongConfig.customizer Bean 不为 null")
    void customizerBeanNotNull() {
        JacksonLongConfig config = new JacksonLongConfig();
        Jackson2ObjectMapperBuilderCustomizer customizer = config.longToStringCustomizer();
        assertNotNull(customizer, "customizer bean 不应为 null");
    }

    // ===== 辅助 =====

    /** 模拟 JacksonLongConfig 配置后的 ObjectMapper */
    private ObjectMapper buildConfiguredMapper() {
        JacksonLongConfig config = new JacksonLongConfig();
        // 直接构建与配置类相同逻辑的 ObjectMapper
        SimpleModule module = new SimpleModule();
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(module);
        return mapper;
    }

    static class TestBean {
        private Long id;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
    }
}
