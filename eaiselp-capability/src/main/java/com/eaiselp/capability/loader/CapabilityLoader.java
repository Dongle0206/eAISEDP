package com.eaiselp.capability.loader;

import com.eaiselp.capability.model.AgentDefinition;
import com.eaiselp.capability.model.CommandDefinition;
import com.eaiselp.capability.model.SkillDefinition;
import com.eaiselp.capability.parser.MarkdownParser;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** 体系加载器：从 eAISEDP-system 仓库加载 markdown，热更新 30s。 */
@Slf4j
@Component
public class CapabilityLoader {

    @Value("${eaiselp.system.path:./eAISEDP-system}")
    private String systemPath;
    @Value("${eaiselp.system.auto-refresh:true}")
    private boolean autoRefresh;

    private final MarkdownParser parser;
    private final Map<String, AgentDefinition> agents = new ConcurrentHashMap<>();
    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();
    private final Map<String, CommandDefinition> commands = new ConcurrentHashMap<>();
    private volatile String systemVersion;

    public CapabilityLoader(MarkdownParser parser) {
        this.parser = parser;
    }

    @PostConstruct
    public void init() { load(); }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void refreshCheck() {
        if (!autoRefresh) return;
        String newVersion = computeVersion();
        if (!Objects.equals(newVersion, systemVersion)) {
            log.info("[Capability] 体系变更: {} → {}, 重载", systemVersion, newVersion);
            load();
        }
    }

    public synchronized void load() {
        long start = System.currentTimeMillis();
        Path root = Paths.get(systemPath);
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            log.warn("[Capability] 体系路径不存在: {}", systemPath);
            return;
        }
        Map<String, AgentDefinition> na = new ConcurrentHashMap<>();
        Map<String, SkillDefinition> ns = new ConcurrentHashMap<>();
        Map<String, CommandDefinition> nc = new ConcurrentHashMap<>();
        loadAgents(root, na);
        loadSkills(root, ns);
        loadCommands(root, nc);
        this.agents.clear(); this.agents.putAll(na);
        this.skills.clear(); this.skills.putAll(ns);
        this.commands.clear(); this.commands.putAll(nc);
        this.systemVersion = computeVersion();
        log.info("[Capability] 加载完成: {} 角色 + {} skills + {} 命令, version={}, 耗时 {}ms",
                agents.size(), skills.size(), commands.size(), systemVersion,
                System.currentTimeMillis() - start);
    }

    private void loadAgents(Path root, Map<String, AgentDefinition> target) {
        Path d = root.resolve("agents");
        if (!Files.exists(d)) return;
        try (Stream<Path> s = Files.list(d)) {
            s.filter(p -> p.toString().endsWith(".md"))
             .filter(p -> p.getFileName().toString().startsWith("team-"))
             .forEach(p -> { try { AgentDefinition def = parser.parseAgent(p);
                 if (def.getName() != null) target.put(def.getName(), def);
             } catch (Exception e) { log.warn("[Capability] 解析角色失败 {}: {}", p, e.getMessage()); }});
        } catch (IOException e) { log.error("[Capability] 加载 agents 失败", e); }
    }

    private void loadSkills(Path root, Map<String, SkillDefinition> target) {
        Path d = root.resolve("skills");
        if (!Files.exists(d)) return;
        try (Stream<Path> s = Files.list(d)) {
            s.filter(Files::isDirectory).forEach(dir -> {
                Path sm = dir.resolve("SKILL.md");
                if (Files.exists(sm)) { try { SkillDefinition def = parser.parseSkill(sm);
                    if (def.getName() != null) target.put(def.getName(), def);
                } catch (Exception e) { log.warn("[Capability] 解析 skill 失败 {}: {}", sm, e.getMessage()); } }
            });
        } catch (IOException e) { log.error("[Capability] 加载 skills 失败", e); }
    }

    private void loadCommands(Path root, Map<String, CommandDefinition> target) {
        Path d = root.resolve("commands");
        if (!Files.exists(d)) return;
        try (Stream<Path> s = Files.list(d)) {
            s.filter(p -> p.toString().endsWith(".md")).forEach(p -> {
                try { CommandDefinition def = parser.parseCommand(p);
                    if (def.getName() != null) target.put(def.getName(), def);
                } catch (Exception e) { log.warn("[Capability] 解析命令失败 {}: {}", p, e.getMessage()); }
            });
        } catch (IOException e) { log.error("[Capability] 加载 commands 失败", e); }
    }

    private String computeVersion() {
        try {
            Path root = Paths.get(systemPath);
            if (!Files.exists(root)) return "empty";
            long[] sum = {0};
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".md"))
                    .forEach(p -> { try { sum[0] += Files.getLastModifiedTime(p).toMillis(); } catch (IOException ignore) {} });
            }
            return "fp-" + Long.toHexString(sum[0]);
        } catch (Exception e) { return "unknown"; }
    }

    /**
     * 按名称查找角色定义。
     *
     * <p>支持两种 key 形态（防御性兼容，DFX 容错）：</p>
     * <ul>
     *   <li>完整注册名：{@code team-po}、{@code team-dev}（frontmatter name 字段，权威）</li>
     *   <li>短码别名：{@code po}、{@code dev}（前端下拉框可能传短码，自动补 {@code team-} 前缀兜底）</li>
     * </ul>
     * <p>注意：短码兜底仅为过渡容错，前端应统一使用 {@code team-} 前缀（权威契约）。
     * 若短码和完整名都 miss，返回 null。</p>
     */
    public AgentDefinition getAgent(String name) {
        if (name == null || name.isBlank()) return null;
        AgentDefinition def = agents.get(name);
        if (def != null) return def;
        // 短码兜底：未命中且不以 team- 开头，尝试补前缀
        if (!name.startsWith("team-")) {
            return agents.get("team-" + name);
        }
        return null;
    }
    public SkillDefinition getSkill(String name) { return skills.get(name); }
    public CommandDefinition getCommand(String name) { return commands.get(name); }
    public Collection<AgentDefinition> listAgents() { return Collections.unmodifiableCollection(agents.values()); }
    public Collection<SkillDefinition> listSkills() { return Collections.unmodifiableCollection(skills.values()); }
    public Collection<CommandDefinition> listCommands() { return Collections.unmodifiableCollection(commands.values()); }
    public String getSystemVersion() { return systemVersion; }
    public boolean isLoaded() { return !agents.isEmpty() || !skills.isEmpty(); }
}
