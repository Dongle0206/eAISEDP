package com.eaiselp.runtime.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RiskController 纯函数单测（case-20260824-技术债清偿 T3，L3-S4）。
 *
 * <p>验收口径：{@link RiskController#toJsonRelated} 改静态 ObjectMapper 序列化后——
 * 控制字符（\u0000-\u001F）、引号、反斜杠、中文等全字符集入参正确转义，round-trip 无损
 * （服务端 parseRelatedObjects 用同款 Jackson databind 反序列化，此处以 Jackson 解回验证）。
 * 原 StringBuilder 手工拼接只转义 \\ 与 " 两字符，控制字符会产出非法 JSON 落库
 * （详情解析恒走容忍降级空列表 = 静默丢关联）。</p>
 */
class RiskControllerTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private static RiskController.RelatedObjectItem item(String type, String id) {
        RiskController.RelatedObjectItem it = new RiskController.RelatedObjectItem();
        it.setType(type);
        it.setId(id);
        return it;
    }

    /** Jackson 解回（与服务端 parseRelatedObjects 同解析器语义）。 */
    private static RiskController.RelatedObjectItem[] parse(String json) throws Exception {
        return OM.readValue(json, RiskController.RelatedObjectItem[].class);
    }

    @Test
    void null入参_返回null保留不更新语义() {
        assertNull(RiskController.toJsonRelated(null));
    }

    @Test
    void 空列表_返回空数组字面量_可落库清空关联() {
        assertEquals("[]", RiskController.toJsonRelated(List.of()));
    }

    @Test
    void 控制字符入参_正确转义_产出合法JSON() throws Exception {
        // \u0001（SOH 控制字符）+ 引号 + 反斜杠 + 换行 —— 手工拼接时代的必坏组合
        String evil = "a\"\u0001\nb\\c";
        String json = RiskController.toJsonRelated(List.of(item(evil, evil)));

        assertNotNull(json);
        RiskController.RelatedObjectItem[] parsed = parse(json);
        assertEquals(1, parsed.length);
        assertEquals(evil, parsed[0].getType(), "控制字符 round-trip 无损（T3 核心）");
        assertEquals(evil, parsed[0].getId());
    }

    @Test
    void 中文与Unicode_正常序列化() throws Exception {
        String json = RiskController.toJsonRelated(
                List.of(item("case", "case-测试-🚀"), item("program", "123")));
        RiskController.RelatedObjectItem[] parsed = parse(json);
        assertEquals(2, parsed.length);
        assertEquals("case-测试-🚀", parsed[0].getId());
        assertEquals("123", parsed[1].getId());
    }

    @Test
    void null字段_空串兜底_历史落库语义不变() throws Exception {
        String json = RiskController.toJsonRelated(List.of(item(null, null)));
        // 既有语义：null → ""（而非 JSON null），兼容历史行为
        RiskController.RelatedObjectItem[] parsed = parse(json);
        assertEquals("", parsed[0].getType());
        assertEquals("", parsed[0].getId());
    }
}
