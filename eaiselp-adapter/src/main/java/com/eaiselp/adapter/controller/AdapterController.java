package com.eaiselp.adapter.controller;

import com.eaiselp.adapter.spi.Adapter;
import com.eaiselp.adapter.spi.AdapterFactory;
import com.eaiselp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

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
        return R.ok(s);
    }

    private Map<String, Object> info(Adapter a) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", a.getType()); m.put("provider", a.getProvider()); m.put("available", a.isAvailable());
        return m;
    }
}
