package com.eaiselp.common.web;

import com.eaiselp.common.exception.BizException;
import com.eaiselp.common.result.R;
import com.eaiselp.common.result.ResultCode;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GlobalExceptionHandler 单测（case-20260824-技术债清偿 T5，平台-S6）。
 *
 * <p>验收口径：坏 JSON（{@code @RequestBody} 反序列化失败抛
 * {@link HttpMessageNotReadableException}）→ 400"请求体格式错误"，
 * <b>不得</b>落入兜底 handler 返回 50000（客户端错误被当服务端错误）。
 * 参数类型不匹配（{@link MethodArgumentTypeMismatchException}）同径归位 400。
 * 既有 BizException / 兜底路径回归。</p>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void 坏JSON_HttpMessageNotReadable_400而非50000() {
        HttpMessageNotReadableException e =
                new HttpMessageNotReadableException("JSON parse error: Unexpected character ('}'",
                        (org.springframework.http.HttpInputMessage) null);

        R<Void> r = handler.handleNotReadable(e);

        assertEquals(400, r.getCode(), "坏 JSON 归位 400（原走兜底 50000）");
        assertEquals("请求体格式错误", r.getMsg(), "固定文案，不回显解析器内部细节");
        assertNull(r.getData());
    }

    @Test
    void 参数类型不匹配_同径400指名参数() throws Exception {
        Method m = Sample.class.getMethod("echo", Long.class);
        MethodParameter param = new MethodParameter(m, 0);
        MethodArgumentTypeMismatchException e = new MethodArgumentTypeMismatchException(
                "abc", Long.class, "id", param, new RuntimeException("convert failed"));

        R<Void> r = handler.handleTypeMismatch(e);

        assertEquals(400, r.getCode());
        assertTrue(r.getMsg().contains("id"), "指名参数: " + r.getMsg());
    }

    // ==================== 既有路径回归（T5 不破坏原语义） ====================

    @Test
    void BizException_原样透传code与msg() {
        R<Void> r = handler.handleBiz(new BizException(400, "风险已存在: X"));
        assertEquals(400, r.getCode());
        assertEquals("风险已存在: X", r.getMsg());
    }

    @Test
    void 未知异常_兜底50000() {
        R<Void> r = handler.handleUnknown(new RuntimeException("boom"));
        assertEquals(ResultCode.INTERNAL_ERROR, r.getCode());
        assertEquals("服务暂时不可用，请稍后重试", r.getMsg());
    }

    /** 参数类型不匹配测试用载体（方法签名须含可解析参数）。 */
    static class Sample {
        public void echo(Long id) {
        }
    }
}
