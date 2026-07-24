package com.eaiselp.runtime.context;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

@Data
@Builder
public class DerivationContext {
    private String task;
    private String stage;
    private String projectContext;
    private String experienceMemory;
    private Map<String, String> upstreamArtifacts;
    private String extraInstructions;
}
