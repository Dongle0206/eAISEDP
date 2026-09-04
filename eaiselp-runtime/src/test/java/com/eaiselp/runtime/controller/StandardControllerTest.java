package com.eaiselp.runtime.controller;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StandardController 工具函数单测（case-20260820 S3 评审补）。
 *
 * <p>仅测 package-private 静态工具 {@link StandardController#toJson}（DataAssetController
 * 复用同函数承载 tags），不拉起 Web 上下文——纯函数断言。</p>
 */
class StandardControllerTest {

    /** S3：空列表返回 "[]"（非 null）——PUT 全量编辑"提交空数组清空关联/标签"可落库。 */
    @Test
    void toJson空列表返回空数组字面量() {
        assertEquals("[]", StandardController.toJson(List.of()),
                "空列表必须返回 \"[]\"（S3：返回 null 会被 MP updateById 忽略，清空静默失效）");
    }

    /** S3：null（字段未传）仍返回 null——"不更新"语义保留，不误伤部分更新。 */
    @Test
    void toJson_null保留null不更新语义() {
        assertNull(StandardController.toJson(null), "null 入参返回 null（不更新语义）");
    }

    /** 正常列表序列化（含引号/反斜杠转义）回归。 */
    @Test
    void toJson_转义引号与反斜杠() {
        assertEquals("[\"a\\\"b\",\"c\\\\d\"]", StandardController.toJson(List.of("a\"b", "c\\d")),
                "元素内引号/反斜杠必须转义（parseCodes 严格解析兼容）");
        assertEquals("[\"STD-0001\"]", StandardController.toJson(List.of("STD-0001")));
    }
}
