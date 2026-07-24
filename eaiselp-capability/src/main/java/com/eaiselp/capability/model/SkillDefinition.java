package com.eaiselp.capability.model;

import lombok.Data;
import java.util.Map;

@Data
public class SkillDefinition {
    private String name;
    private String description;
    private String model;
    private String prompt;
    private Map<String, Object> frontmatter;
    private String sourcePath;
}
