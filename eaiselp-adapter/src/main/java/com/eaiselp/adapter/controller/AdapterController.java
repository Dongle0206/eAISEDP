package com.eaiselp.adapter.controller;

import com.eaiselp.adapter.spi.Adapter;
import com.eaiselp.adapter.spi.AdapterFactory;
import com.eaiselp.adapter.spi.CICDAdapter;
import com.eaiselp.adapter.spi.IMAdapter;
import com.eaiselp.adapter.spi.MCPAdapter;
import com.eaiselp.adapter.spi.TicketAdapter;
import com.eaiselp.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * 适配器状态端点。EA 蓝图 §4.3 扩展：状态视图补充 Ticket/CICD/IM/MCP 4 个企业适配器。
 *
 * <p>这 4 个是企业可选能力：default 方法在未装配时抛 UnsupportedOperationException、
 * 装配后但全不可用时 get* 返回 null。status 用 {@link #infoOptional} 统一兜底，
 * 未启用时输出 {@code available=false} 占位，不让 /status 整体 500。
 */
@Slf4j
@RestController
@RequestMapping("/api/adapter")
@RequiredArgsConstructor
public class AdapterController {
    private final AdapterFactory factory;

    @GetMapping("/status")
    public R<Map<String, Object>> status() {
        Map<String, Object> s = new HashMap<>();
        s.put("git", info(factory.getGitAdapter()));
        s.put("llm", info(factory.getLlmAdapter()));
        s.put("docstore", info(factory.getDocStoreAdapter()));
        s.put("ticket", infoOptional(TicketAdapter.class.getSimpleName(), factory::getTicketAdapter));
        s.put("cicd", infoOptional(CICDAdapter.class.getSimpleName(), factory::getCICDAdapter));
        s.put("im", infoOptional(IMAdapter.class.getSimpleName(), factory::getIMAdapter));
        s.put("mcp", infoOptional(MCPAdapter.class.getSimpleName(), factory::getMCPAdapter));
        return R.ok(s);
    }

    private Map<String, Object> info(Adapter a) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", a.getType()); m.put("provider", a.getProvider()); m.put("available", a.isAvailable());
        return m;
    }

    /** 企业可选适配器：未装配（抛 UnsupportedOperationException）或返回 null 时输出 available=false 占位。 */
    private Map<String, Object> infoOptional(String name, java.util.function.Supplier<Adapter> supplier) {
        Map<String, Object> m = new HashMap<>();
        try {
            Adapter a = supplier.get();
            if (a == null) {
                m.put("type", name.toLowerCase());
                m.put("provider", null);
                m.put("available", false);
            } else {
                m.put("type", a.getType());
                m.put("provider", a.getProvider());
                m.put("available", a.isAvailable());
            }
        } catch (UnsupportedOperationException e) {
            m.put("type", name.toLowerCase());
            m.put("provider", null);
            m.put("available", false);
        }
        return m;
    }
}
