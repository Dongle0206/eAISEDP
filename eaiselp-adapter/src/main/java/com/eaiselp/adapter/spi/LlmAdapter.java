package com.eaiselp.adapter.spi;

import lombok.Builder;
import lombok.Data;
import java.util.List;

public interface LlmAdapter extends Adapter {
    LlmResponse invoke(String model, String prompt, LlmOptions options);
    List<String> listModels();
    boolean validateModel(String model);

    @Data @Builder
    class LlmOptions {
        private Double temperature;
        private Integer maxTokens;
        private List<String> stop;
        private Long timeoutMs;
    }

    @Data @Builder
    class LlmResponse {
        private String content;
        private Integer inputTokens;
        private Integer outputTokens;
        private String model;
        private Long durationMs;
        private String finishReason;
    }
}
