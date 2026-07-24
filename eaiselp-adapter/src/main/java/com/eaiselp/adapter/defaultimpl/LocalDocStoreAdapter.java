package com.eaiselp.adapter.defaultimpl;

import com.eaiselp.adapter.spi.DocStoreAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
@ConditionalOnProperty(name = "eaiselp.adapter.docstore.provider", havingValue = "default", matchIfMissing = true)
public class LocalDocStoreAdapter implements DocStoreAdapter {

    @Value("${eaiselp.adapter.docstore.base-path:./data/docs}")
    private String basePath;

    @Override public String getType() { return "docstore"; }
    @Override public String getProvider() { return "default"; }
    @Override public boolean isAvailable() {
        try { Files.createDirectories(Paths.get(basePath)); return true; } catch (IOException e) { return false; }
    }

    @Override
    public String save(String key, String content, Map<String, String> metadata) {
        try { Path file = resolve(key);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
            return key;
        } catch (IOException e) { throw new RuntimeException("保存文档失败: " + key, e); }
    }

    @Override
    public String load(String key) {
        try { Path file = resolve(key);
            if (!Files.exists(file)) return null;
            return Files.readString(file);
        } catch (IOException e) { return null; }
    }

    @Override public boolean delete(String key) { try { return Files.deleteIfExists(resolve(key)); } catch (IOException e) { return false; } }
    @Override public boolean exists(String key) { return Files.exists(resolve(key)); }

    @Override
    public List<DocEntry> search(String query, Map<String, String> filters, int limit) {
        List<DocEntry> results = new ArrayList<>();
        Path root = Paths.get(basePath);
        if (!Files.exists(root)) return results;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                try { String content = Files.readString(p);
                    if (query == null || query.isEmpty() || content.contains(query)) {
                        DocEntry e = new DocEntry();
                        e.setKey(root.relativize(p).toString().replace('\\', '/'));
                        e.setSizeBytes(Files.size(p));
                        e.setSnippet(content.length() > 200 ? content.substring(0, 200) : content);
                        results.add(e);
                    }
                } catch (IOException ignore) {}
            });
        } catch (IOException e) { log.warn("[LocalDocStore] 搜索失败", e); }
        return results.stream().limit(limit).collect(Collectors.toList());
    }

    private Path resolve(String key) {
        String safe = key.replace("..", "").replace('\\', '/');
        return Paths.get(basePath, safe);
    }
}
