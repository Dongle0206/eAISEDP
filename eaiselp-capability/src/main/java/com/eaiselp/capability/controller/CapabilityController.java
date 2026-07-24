package com.eaiselp.capability.controller;

import com.eaiselp.capability.loader.CapabilityLoader;
import com.eaiselp.capability.model.AgentDefinition;
import com.eaiselp.capability.model.CommandDefinition;
import com.eaiselp.capability.model.SkillDefinition;
import com.eaiselp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/capability")
@RequiredArgsConstructor
public class CapabilityController {

    private final CapabilityLoader loader;

    @GetMapping("/overview")
    public R<Map<String, Object>> overview() {
        Map<String, Object> m = new HashMap<>();
        m.put("agentCount", loader.listAgents().size());
        m.put("skillCount", loader.listSkills().size());
        m.put("commandCount", loader.listCommands().size());
        m.put("systemVersion", loader.getSystemVersion());
        return R.ok(m);
    }

    @GetMapping("/agents")
    public R<Collection<AgentDefinition>> listAgents() { return R.ok(loader.listAgents()); }

    @GetMapping("/agents/{name}")
    public R<AgentDefinition> getAgent(@PathVariable String name) {
        AgentDefinition def = loader.getAgent(name);
        if (def == null) return R.fail("角色不存在: " + name);
        return R.ok(def);
    }

    @GetMapping("/skills")
    public R<Collection<SkillDefinition>> listSkills() { return R.ok(loader.listSkills()); }

    @GetMapping("/skills/{name}")
    public R<SkillDefinition> getSkill(@PathVariable String name) {
        SkillDefinition def = loader.getSkill(name);
        if (def == null) return R.fail("skill 不存在: " + name);
        return R.ok(def);
    }

    @GetMapping("/commands")
    public R<Collection<CommandDefinition>> listCommands() { return R.ok(loader.listCommands()); }

    @PostMapping("/reload")
    public R<String> reload() {
        loader.load();
        return R.ok("reloaded, version=" + loader.getSystemVersion());
    }
}
