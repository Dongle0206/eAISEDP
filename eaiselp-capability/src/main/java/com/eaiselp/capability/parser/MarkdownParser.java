package com.eaiselp.capability.parser;

import com.eaiselp.capability.model.AgentDefinition;
import com.eaiselp.capability.model.CommandDefinition;
import com.eaiselp.capability.model.SkillDefinition;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Markdown 体系文件解析器：YAML frontmatter + 正文 prompt。平台不重写体系内容，只解析。 */
@Component
public class MarkdownParser {

    private static final Pattern FRONTMATTER_PATTERN =
            Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$", Pattern.DOTALL);

    public ParseResult parse(Path file) throws Exception {
        return parseContent(Files.readString(file));
    }

    public ParseResult parseContent(String content) {
        Matcher m = FRONTMATTER_PATTERN.matcher(content);
        if (m.matches()) {
            String yaml = m.group(1);
            String body = m.group(2).trim();
            Yaml yamlParser = new Yaml();
            Map<String, Object> frontmatter = yamlParser.load(yaml);
            if (frontmatter == null) frontmatter = new HashMap<>();
            return new ParseResult(frontmatter, body);
        }
        return new ParseResult(new HashMap<>(), content.trim());
    }

    public AgentDefinition parseAgent(Path file) throws Exception {
        ParseResult r = parse(file);
        AgentDefinition def = new AgentDefinition();
        def.setFrontmatter(r.frontmatter);
        def.setPrompt(r.body);
        def.setName((String) r.frontmatter.get("name"));
        def.setDescription((String) r.frontmatter.get("description"));
        def.setModel((String) r.frontmatter.get("model"));
        def.setColor((String) r.frontmatter.get("color"));
        def.setSourcePath(file.toString());
        return def;
    }

    public SkillDefinition parseSkill(Path file) throws Exception {
        ParseResult r = parse(file);
        SkillDefinition def = new SkillDefinition();
        def.setFrontmatter(r.frontmatter);
        def.setPrompt(r.body);
        def.setName((String) r.frontmatter.get("name"));
        def.setDescription((String) r.frontmatter.get("description"));
        def.setModel((String) r.frontmatter.get("model"));
        def.setSourcePath(file.toString());
        return def;
    }

    public CommandDefinition parseCommand(Path file) throws Exception {
        ParseResult r = parse(file);
        CommandDefinition def = new CommandDefinition();
        def.setFrontmatter(r.frontmatter);
        def.setPrompt(r.body);
        def.setDescription((String) r.frontmatter.get("description"));
        def.setArgumentHint((String) r.frontmatter.get("argument-hint"));
        def.setSkills((String) r.frontmatter.get("skills"));
        String fileName = file.getFileName().toString();
        def.setName(fileName.substring(0, fileName.length() - 3));
        def.setSourcePath(file.toString());
        return def;
    }

    public static class ParseResult {
        public final Map<String, Object> frontmatter;
        public final String body;
        public ParseResult(Map<String, Object> frontmatter, String body) {
            this.frontmatter = frontmatter;
            this.body = body;
        }
    }
}
