package com.eaiselp.common.result;

import lombok.Data;
import java.io.Serializable;

/** 统一返回结果。所有 REST API 返回此格式。 */
@Data
public class R<T> implements Serializable {
    private Integer code;
    private String msg;
    private T data;
    private long timestamp = System.currentTimeMillis();

    public static <T> R<T> ok() { return ok(null); }
    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.setCode(0); r.setMsg("success"); r.setData(data);
        return r;
    }
    public static <T> R<T> fail(String msg) { return fail(500, msg); }
    public static <T> R<T> fail(int code, String msg) {
        R<T> r = new R<>();
        r.setCode(code); r.setMsg(msg);
        return r;
    }
}
