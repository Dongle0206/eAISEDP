package com.eaiselp.adapter.spi;

import java.util.List;
import java.util.Map;

public interface DocStoreAdapter extends Adapter {
    String save(String key, String content, Map<String, String> metadata);
    String load(String key);
    boolean delete(String key);
    boolean exists(String key);
    List<DocEntry> search(String query, Map<String, String> filters, int limit);

    @lombok.Data
    class DocEntry {
        private String key;
        private String title;
        private Map<String, String> metadata;
        private Long sizeBytes;
        private String snippet;
    }
}
