package com.eaiselp.common.tenant;

/** 租户上下文（ThreadLocal）。商业化多租户隔离基础。 */
public class TenantContext {
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();
    public static final long SYSTEM_TENANT = 0L;

    public static void set(Long tenantId) { CURRENT.set(tenantId); }
    public static Long get() {
        Long t = CURRENT.get();
        return t == null ? SYSTEM_TENANT : t;
    }
    public static void clear() { CURRENT.remove(); }
    public static boolean isSystem() { return get() == SYSTEM_TENANT; }
}
