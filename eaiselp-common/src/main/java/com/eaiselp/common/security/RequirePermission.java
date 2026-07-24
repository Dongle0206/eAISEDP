package com.eaiselp.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限校验注解。标注在 Controller 方法或类上，PermissionInterceptor 拦截校验。
 * 多个权限码为「或」关系（任一满足即通过）；需「且」用 @RequiresPermission 重复标注（M3 支持）。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
    /** 权限码数组，如 {"user:view"} 或 {"case:view","case:derive"}（任一满足）。 */
    String[] value();
}
