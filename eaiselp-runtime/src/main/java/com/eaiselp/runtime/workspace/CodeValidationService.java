package com.eaiselp.runtime.workspace;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 产出代码验证服务（核心价值闭环 #1：AI 产出必须验证后才能交付）。
 *
 * <p><b>分层验证策略</b>（按可用工具自动降级）：</p>
 * <ul>
 *   <li><b>HTML</b>：结构校验（DOCTYPE/闭合标签/head-body 完整性）</li>
 *   <li><b>JavaScript</b>：优先 Node.js {@code node --check} 真实语法校验；
 *       无 Node 时降级为括号平衡 + 关键字检查</li>
 *   <li><b>Python</b>：优先 {@code python -m py_compile}；无 Python 降级括号平衡</li>
 *   <li><b>Java</b>：public 类名与文件名匹配 + 括号平衡</li>
 *   <li><b>CSS/SQL/其他</b>：非空 + 基本格式检查</li>
 * </ul>
 *
 * <p>验证结果汇总：通过文件数 / 失败文件数 + 每个失败文件的具体原因。
 * 验证不阻塞主流程（编排照常完成），结果供前端展示与人工判断。</p>
 */
@Slf4j
@Service
public class CodeValidationService {

    private final ArtifactFileService artifactFileService;

    public CodeValidationService(ArtifactFileService artifactFileService) {
        this.artifactFileService = artifactFileService;
    }

    /** 验证结果。 */
    @lombok.Data
    public static class ValidationResult {
        private String caseId;
        private int totalFiles;
        private int passedFiles;
        private int failedFiles;
        private List<FileCheck> checks = new ArrayList<>();
        private boolean allPassed;
        private String validatedAt;
    }

    @lombok.Data
    public static class FileCheck {
        private String path;
        private String type;
        private boolean passed;
        private String message;
        private String validator; // 用了哪个验证器（node/python/structural）
    }

    /**
     * 验证 Case 工作区的所有代码文件。
     */
    public ValidationResult validateWorkspace(String caseId) {
        ValidationResult result = new ValidationResult();
        result.setCaseId(caseId);
        result.setValidatedAt(java.time.LocalDateTime.now().toString());

        Path caseDir = artifactFileService.getCaseWorkspace(caseId);
        if (!Files.isDirectory(caseDir)) {
            result.setAllPassed(false);
            FileCheck fc = new FileCheck();
            fc.setPath("(workspace)");
            fc.setType("none");
            fc.setPassed(false);
            fc.setMessage("工作区不存在");
            result.getChecks().add(fc);
            return result;
        }

        List<Path> codeFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(caseDir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !p.toString().contains(".git"))
                .filter(this::isCodeFile)
                .sorted()
                .forEach(codeFiles::add);
        } catch (IOException e) {
            log.error("[Validation] 遍历工作区失败 caseId={}", caseId, e);
        }

        result.setTotalFiles(codeFiles.size());
        for (Path file : codeFiles) {
            FileCheck check = validateFile(caseDir, file);
            result.getChecks().add(check);
            if (check.isPassed()) result.setPassedFiles(result.getPassedFiles() + 1);
            else result.setFailedFiles(result.getFailedFiles() + 1);
        }
        result.setAllPassed(result.getFailedFiles() == 0 && result.getTotalFiles() > 0);

        log.info("[Validation] Case={} 验证完成: {}/{} 通过",
                caseId, result.getPassedFiles(), result.getTotalFiles());
        return result;
    }

    private boolean isCodeFile(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        return name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".js")
                || name.endsWith(".css") || name.endsWith(".java") || name.endsWith(".py")
                || name.endsWith(".sql") || name.endsWith(".sh") || name.equals("dockerfile");
    }

    private FileCheck validateFile(Path caseDir, Path file) {
        FileCheck check = new FileCheck();
        check.setPath(caseDir.relativize(file).toString().replace('\\', '/'));
        String fileName = file.getFileName().toString().toLowerCase();

        try {
            String content = Files.readString(file);
            if (fileName.endsWith(".html") || fileName.endsWith(".htm")) {
                validateHtml(check, content);
            } else if (fileName.endsWith(".js")) {
                validateJs(check, file, content);
            } else if (fileName.endsWith(".py")) {
                validatePython(check, file, content);
            } else if (fileName.endsWith(".java")) {
                validateJava(check, file, content);
            } else if (fileName.endsWith(".css")) {
                validateCss(check, content);
            } else {
                // sql/sh/dockerfile 等：非空检查
                check.setPassed(content != null && !content.isBlank());
                check.setMessage(check.isPassed() ? "内容非空" : "文件为空");
                check.setValidator("basic");
            }
        } catch (IOException e) {
            check.setPassed(false);
            check.setMessage("读取失败: " + e.getMessage());
        }
        return check;
    }

    // ===== HTML 结构校验 =====
    private void validateHtml(FileCheck check, String content) {
        check.setType("html");
        check.setValidator("structural");
        List<String> problems = new ArrayList<>();

        String lower = content.toLowerCase();
        if (!lower.contains("<!doctype html")) problems.add("缺少 DOCTYPE 声明");
        if (!lower.contains("<html")) problems.add("缺少 <html> 标签");
        if (!lower.contains("</html>")) problems.add("<html> 未闭合");
        if (!lower.contains("<head") || !lower.contains("</head>")) problems.add("<head> 不完整");
        if (!lower.contains("<body") || !lower.contains("</body>")) problems.add("<body> 不完整");

        // 常见标签闭合检查
        for (String tag : new String[]{"div", "span", "script", "title", "p"}) {
            int open = countOccurrences(lower, "<" + tag + " ") + countOccurrences(lower, "<" + tag + ">")
                    + countOccurrences(lower, "<" + tag + "\n");
            int close = countOccurrences(lower, "</" + tag + ">");
            if (open > close) problems.add("<" + tag + "> 标签未闭合 (" + open + " 开 / " + close + " 闭)");
        }

        if (problems.isEmpty()) {
            check.setPassed(true);
            check.setMessage("HTML 结构完整");
        } else {
            check.setPassed(false);
            check.setMessage(String.join("; ", problems));
        }
    }

    // ===== JS 验证 =====
    private void validateJs(FileCheck check, Path file, String content) {
        check.setType("javascript");
        // 优先 Node.js 真实语法校验
        if (isToolAvailable("node", "--version")) {
            check.setValidator("node");
            try {
                Process p = new ProcessBuilder("node", "--check", file.toAbsolutePath().toString())
                        .redirectErrorStream(true).start();
                String output = new String(p.getInputStream().readAllBytes());
                int code = p.waitFor();
                if (code == 0) {
                    check.setPassed(true);
                    check.setMessage("Node.js 语法校验通过");
                } else {
                    check.setPassed(false);
                    check.setMessage("语法错误: " + output.lines().findFirst().orElse("unknown"));
                }
            } catch (Exception e) {
                fallbackBracketCheck(check, content);
            }
        } else {
            fallbackBracketCheck(check, content);
        }
    }

    // ===== Python 验证 =====
    private void validatePython(FileCheck check, Path file, String content) {
        check.setType("python");
        if (isToolAvailable("python", "--version")) {
            check.setValidator("python");
            try {
                Process p = new ProcessBuilder("python", "-m", "py_compile", file.toAbsolutePath().toString())
                        .redirectErrorStream(true).start();
                String output = new String(p.getInputStream().readAllBytes());
                int code = p.waitFor();
                if (code == 0) {
                    check.setPassed(true);
                    check.setMessage("Python 编译校验通过");
                } else {
                    check.setPassed(false);
                    check.setMessage("语法错误: " + output.lines().findFirst().orElse("unknown"));
                }
            } catch (Exception e) {
                fallbackBracketCheck(check, content);
            }
        } else {
            fallbackBracketCheck(check, content);
        }
    }

    // ===== Java 验证 =====
    private void validateJava(FileCheck check, Path file, String content) {
        check.setType("java");
        check.setValidator("structural");
        List<String> problems = new ArrayList<>();

        // public 类名必须与文件名一致
        String fileName = file.getFileName().toString().replace(".java", "");
        Matcher m = Pattern.compile("public\\s+(?:class|interface|enum)\\s+(\\w+)").matcher(content);
        if (m.find() && !m.group(1).equals(fileName)) {
            problems.add("public 类名 " + m.group(1) + " 与文件名 " + fileName + " 不一致");
        }

        // 括号平衡
        if (!isBracketBalanced(content)) problems.add("大括号不平衡");

        if (problems.isEmpty()) {
            check.setPassed(true);
            check.setMessage("Java 结构检查通过（类名匹配 + 括号平衡）");
        } else {
            check.setPassed(false);
            check.setMessage(String.join("; ", problems));
        }
    }

    // ===== CSS 验证 =====
    private void validateCss(FileCheck check, String content) {
        check.setType("css");
        check.setValidator("structural");
        if (!isBracketBalanced(content)) {
            check.setPassed(false);
            check.setMessage("CSS 大括号不平衡");
        } else {
            check.setPassed(true);
            check.setMessage("CSS 结构检查通过");
        }
    }

    // ===== 兜底：括号平衡检查 =====
    private void fallbackBracketCheck(FileCheck check, String content) {
        check.setValidator("bracket");
        if (isBracketBalanced(content)) {
            check.setPassed(true);
            check.setMessage("括号平衡检查通过（降级验证，建议安装 Node/Python 获得更强校验）");
        } else {
            check.setPassed(false);
            check.setMessage("大括号不平衡");
        }
    }

    private boolean isBracketBalanced(String content) {
        int depth = 0;
        boolean inString = false;
        char stringChar = 0;
        for (char c : content.toCharArray()) {
            if (inString) {
                if (c == stringChar) inString = false;
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                inString = true;
                stringChar = c;
            } else if (c == '{') depth++;
            else if (c == '}') depth--;
            if (depth < 0) return false;
        }
        return depth == 0 && !inString;
    }

    private int countOccurrences(String s, String sub) {
        int count = 0, idx = 0;
        while ((idx = s.indexOf(sub, idx)) != -1) { count++; idx += sub.length(); }
        return count;
    }

    private boolean isToolAvailable(String cmd, String arg) {
        try {
            Process p = new ProcessBuilder(cmd, arg).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
