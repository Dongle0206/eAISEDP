package com.eaiselp.runtime.workspace;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 产出文件落地服务（生产级链路 Step 1：AI 产出 → 文件系统）。
 *
 * <p>从 LLM 的 Markdown 产出中解析代码块和文件路径标注，按目录结构写入 Case 工作区。
 * 工作区根目录由 {@code eaiselp.workspace.root} 配置，每个 Case 一个子目录。</p>
 *
 * <p><b>LLM 产出格式约定</b>（Dev 角色产出示例）：</p>
 * <pre>
 * ## 文件清单
 *
 * ### index.html
 * ```html
 * &lt;!DOCTYPE html&gt;...
 * ```
 *
 * ### src/app.js
 * ```javascript
 * function init() { ... }
 * ```
 * </pre>
 *
 * <p>解析器同时支持两种格式：</p>
 * <ol>
 *   <li><b>显式文件路径标注</b>：{@code ### 文件路径} 后跟代码块 → 精确文件名</li>
 *   <li><b>语言推断</b>：无路径标注的裸代码块 → 按语言推断文件名（html→index.html, css→style.css, js→app.js, java→Main.java, py→main.py）</li>
 * </ol>
 */
@Slf4j
@Service
public class ArtifactFileService {

    @Value("${eaiselp.workspace.root:./workspaces}")
    private String workspaceRoot;

    /** 匹配 "### 文件路径" 后跟代码块的格式 */
    private static final Pattern FILE_BLOCK_PATTERN = Pattern.compile(
            "###\\s*([\\w./-]+\\.[a-zA-Z]+)\\s*\\n```\\w*\\n(.*?)```",
            Pattern.DOTALL);

    /** 匹配裸代码块（无文件路径标注） */
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
            "```(\\w*)\\n(.*?)```",
            Pattern.DOTALL);

    /** 语言→默认文件名映射 */
    private static final java.util.Map<String, String> LANG_FILE_MAP = java.util.Map.ofEntries(
            java.util.Map.entry("html", "index.html"),
            java.util.Map.entry("htm", "index.html"),
            java.util.Map.entry("css", "style.css"),
            java.util.Map.entry("javascript", "app.js"),
            java.util.Map.entry("js", "app.js"),
            java.util.Map.entry("java", "Main.java"),
            java.util.Map.entry("python", "main.py"),
            java.util.Map.entry("py", "main.py"),
            java.util.Map.entry("sql", "schema.sql"),
            java.util.Map.entry("yaml", "config.yaml"),
            java.util.Map.entry("yml", "config.yaml"),
            java.util.Map.entry("json", "config.json"),
            java.util.Map.entry("xml", "config.xml"),
            java.util.Map.entry("sh", "deploy.sh"),
            java.util.Map.entry("bash", "deploy.sh"),
            java.util.Map.entry("dockerfile", "Dockerfile")
    );

    /**
     * 把 LLM 产出写入 Case 工作区。
     *
     * <p>每个角色产出按角色名分目录存放：</p>
     * <pre>
     * workspaces/
     *   {caseId}/
     *     team-po/PRD.md              ← PO 产出整体存为 Markdown
     *     team-dev/                   ← Dev 产出按代码块拆分
     *       index.html
     *       src/app.js
     *     team-ops/                   ← Ops 产出
     *       deploy.sh
     *       Dockerfile
     * </pre>
     *
     * @param caseId Case ID
     * @param role   角色名（team-po / team-dev / ...）
     * @param output LLM 产出全文（Markdown）
     * @return 写入的文件列表（相对路径）
     */
    public List<String> writeToWorkspace(String caseId, String role, String output) {
        if (output == null || output.isBlank()) {
            log.warn("[Workspace] 产出为空，跳过 caseId={}, role={}", caseId, role);
            return List.of();
        }

        Path caseDir = Paths.get(workspaceRoot, caseId);
        Path roleDir = caseDir.resolve(role);
        List<String> writtenFiles = new ArrayList<>();

        try {
            Files.createDirectories(roleDir);

            // 1. 始终把完整产出存为 {role}.md（保底，确保内容不丢）
            String mdFileName = role + ".md";
            Files.writeString(roleDir.resolve(mdFileName), output);
            writtenFiles.add(role + "/" + mdFileName);

            // 2. 解析代码块，按文件路径写入
            List<ExtractedFile> files = extractFiles(output, role);
            for (ExtractedFile ef : files) {
                Path filePath = roleDir.resolve(ef.path).normalize();
                // 安全检查：防止路径穿越（../../etc/passwd）
                if (!filePath.startsWith(roleDir)) {
                    log.warn("[Workspace] 路径穿越拦截: {} → 跳过", ef.path);
                    continue;
                }
                Files.createDirectories(filePath.getParent());
                Files.writeString(filePath, ef.content);
                writtenFiles.add(role + "/" + ef.path);
                log.info("[Workspace] 写入文件: {}/{}", role, ef.path);
            }

            log.info("[Workspace] Case={} role={} 写入 {} 个文件（含 1 个 .md 保底 + {} 个代码文件）",
                    caseId, role, writtenFiles.size(), files.size());

        } catch (IOException e) {
            log.error("[Workspace] 写入失败 caseId={}, role={}", caseId, role, e);
        }

        return writtenFiles;
    }

    /**
     * 从 Markdown 产出中提取文件列表。
     *
     * <p>优先匹配"### 文件路径 + 代码块"格式，其次按裸代码块的语言推断文件名。</p>
     */
    List<ExtractedFile> extractFiles(String output, String role) {
        List<ExtractedFile> files = new ArrayList<>();

        // 1. 先提取有显式路径标注的代码块
        Matcher fileBlockMatcher = FILE_BLOCK_PATTERN.matcher(output);
        java.util.Set<String> usedPaths = new java.util.HashSet<>();
        while (fileBlockMatcher.find()) {
            String path = fileBlockMatcher.group(1).trim();
            String content = fileBlockMatcher.group(2);
            if (!usedPaths.contains(path)) {
                files.add(new ExtractedFile(path, content));
                usedPaths.add(path);
            }
        }

        // 2. 如果没有显式路径标注，按裸代码块的语言推断文件名
        if (files.isEmpty()) {
            Matcher codeBlockMatcher = CODE_BLOCK_PATTERN.matcher(output);
            int index = 1;
            while (codeBlockMatcher.find()) {
                String lang = codeBlockMatcher.group(1).toLowerCase();
                String content = codeBlockMatcher.group(2);
                String fileName = LANG_FILE_MAP.getOrDefault(lang, "snippet_" + index + ".txt");
                // 如果有同名文件（多个 html 块），加序号
                if (usedPaths.contains(fileName)) {
                    fileName = "snippet_" + index + "." + (lang.isEmpty() ? "txt" : lang);
                }
                files.add(new ExtractedFile(fileName, content));
                usedPaths.add(fileName);
                index++;
            }
        }

        return files;
    }

    /** 获取 Case 工作区根目录路径。 */
    public Path getCaseWorkspace(String caseId) {
        return Paths.get(workspaceRoot, caseId);
    }

    /** 工作区是否存在（是否已有产出落地）。 */
    public boolean exists(String caseId) {
        return Files.isDirectory(Paths.get(workspaceRoot, caseId));
    }

    /** 列出 Case 工作区的所有文件（相对路径）。 */
    public List<String> listFiles(String caseId) {
        Path caseDir = Paths.get(workspaceRoot, caseId);
        if (!Files.isDirectory(caseDir)) return List.of();
        List<String> files = new ArrayList<>();
        try (var walk = Files.walk(caseDir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !p.getFileName().toString().equals(".git") && !p.startsWith(caseDir.resolve(".git")))
                .forEach(p -> files.add(caseDir.relativize(p).toString().replace('\\', '/')));
        } catch (IOException e) {
            log.error("[Workspace] 列出文件失败 caseId={}", caseId, e);
        }
        return files;
    }

    /** 提取的文件。 */
    static class ExtractedFile {
        final String path;   // 相对于角色目录的路径（如 src/app.js）
        final String content;
        ExtractedFile(String path, String content) {
            this.path = path;
            this.content = content;
        }
    }
}
