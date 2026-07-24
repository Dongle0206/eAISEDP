package com.eaiselp.adapter.defaultimpl;

import com.eaiselp.adapter.spi.GitAdapter;
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
@ConditionalOnProperty(name = "eaiselp.adapter.git.provider", havingValue = "default", matchIfMissing = true)
public class LocalGitAdapter implements GitAdapter {

    @Value("${eaiselp.adapter.git.base-path:./data/repos}")
    private String basePath;

    @Override public String getType() { return "git"; }
    @Override public String getProvider() { return "default"; }
    @Override public boolean isAvailable() { return Files.exists(Paths.get(basePath)); }

    @Override
    public String readFile(String repo, String path) {
        try { Path full = resolve(repo, path);
            if (!Files.exists(full)) return null;
            return Files.readString(full);
        } catch (IOException e) { return null; }
    }

    @Override
    public void writeFile(String repo, String path, String content) {
        try { Path full = resolve(repo, path);
            Files.createDirectories(full.getParent());
            Files.writeString(full, content);
        } catch (IOException e) { throw new RuntimeException("写文件失败", e); }
    }

    @Override public boolean exists(String repo, String path) { return Files.exists(resolve(repo, path)); }

    @Override
    public List<String> listFiles(String repo, String pathPattern) {
        Path repoPath = Paths.get(basePath, repo);
        if (!Files.exists(repoPath)) return Collections.emptyList();
        String glob = pathPattern == null ? "**/*.md" : pathPattern;
        try (Stream<Path> walk = Files.walk(repoPath, 10)) {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
            return walk.filter(Files::isRegularFile)
                       .filter(p -> matcher.matches(repoPath.relativize(p)))
                       .map(p -> repoPath.relativize(p).toString().replace('\\', '/'))
                       .sorted().collect(Collectors.toList());
        } catch (IOException e) { return Collections.emptyList(); }
    }

    @Override public String commit(String repo, String message, List<String> files) { return "local-" + System.currentTimeMillis(); }
    @Override public void pull(String repo) {}
    @Override public String getCurrentCommit(String repo) { return "local-" + System.currentTimeMillis(); }

    private Path resolve(String repo, String path) { return Paths.get(basePath, repo, path); }
}
