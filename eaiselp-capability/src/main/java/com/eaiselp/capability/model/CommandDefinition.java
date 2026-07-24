package com.eaiselp.capability.model;

import lombok.Data;
import java.util.Map;

@Data
public class CommandDefinition {
    private String description;
    private String argumentHint;
    private String skills;
    private String prompt;
    private Map<String, Object> frontmatter;
    private String sourcePath;
    private String name;
}
