package com.eaiselp.runtime.workspace;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Paths;

/**
 * Git 落地服务（生产级链路 Step 2-3：文件系统 → Git commit → 远程 push）。
 *
 * <p>使用 JGit（纯 Java Git 实现，不依赖系统 git 命令）：</p>
 * <ol>
 *   <li>工作区目录 git init（如尚未初始化）</li>
 *   <li>git add . + git commit（自动提交消息含 Case ID + 时间戳）</li>
 *   <li>配置了远程仓库时 git push（支持 GitLab/GitHub/Gitea，用 token 认证）</li>
 * </ol>
 *
 * <p>配置项（application.yml）：</p>
 * <pre>
 * eaiselp:
 *   git:
 *     remote-url: ${GIT_REMOTE_URL:}     # 远程仓库 URL（空则只 commit 不 push）
 *     token: ${GIT_TOKEN:}                # 访问 token（Personal Access Token）
 *     author-name: eAISEDP-Bot
 *     author-email: bot@eaiselp.com
 * </pre>
 *
 * <p><b>降级策略</b>：Git 操作失败只 log 不抛异常（产出已在文件系统，Git 是增强而非必需）。</p>
 */
@Slf4j
@Service
public class GitService {

    @Value("${eaiselp.git.remote-url:}")
    private String remoteUrl;

    @Value("${eaiselp.git.token:}")
    private String token;

    @Value("${eaiselp.git.author-name:eAISEDP-Bot}")
    private String authorName;

    @Value("${eaiselp.git.author-email:bot@eaiselp.com}")
    private String authorEmail;

    /**
     * 对 Case 工作区执行 Git init + add + commit。
     *
     * @param caseId     Case ID
     * @param commitMsg  提交消息
     * @return commit hash（失败返回 null）
     */
    public String commitWorkspace(String caseId, String commitMsg) {
        File workDir = Paths.get(System.getProperty("user.dir"), "workspaces", caseId).toFile();
        // 尝试从 ArtifactFileService 的 workspaceRoot 配置推断路径
        // 但 ArtifactFileService 的 workspaceRoot 可能是相对路径，这里用进程工作目录 + workspaces
        if (!workDir.exists()) {
            log.warn("[Git] 工作区不存在: {}", workDir.getAbsolutePath());
            return null;
        }

        try {
            Git git;
            File gitDir = new File(workDir, ".git");
            if (gitDir.exists()) {
                git = Git.open(workDir);
                log.info("[Git] 打开已有仓库: {}", workDir.getAbsolutePath());
            } else {
                git = Git.init().setDirectory(workDir).call();
                log.info("[Git] 初始化新仓库: {}", workDir.getAbsolutePath());
            }

            // git add .
            git.add().addFilepattern(".").call();

            // git commit
            var commitResult = git.commit()
                    .setMessage(commitMsg)
                    .setAuthor(authorName, authorEmail)
                    .setCommitter(authorName, authorEmail)
                    .call();

            String commitHash = commitResult.getId().getName();
            log.info("[Git] Commit 成功: {} → {}", commitHash.substring(0, 8), commitMsg);

            git.close();
            return commitHash;

        } catch (Exception e) {
            log.error("[Git] Commit 失败 caseId={}", caseId, e);
            return null;
        }
    }

    /**
     * Push 工作区到远程仓库。
     *
     * <p>需要配置 eaiselp.git.remote-url 和 eaiselp.git.token。
     * 未配置远程地址时跳过（只 commit 不 push）。</p>
     *
     * @param caseId Case ID
     * @return true=push 成功 / false=跳过或失败
     */
    public boolean pushWorkspace(String caseId) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            log.info("[Git] 未配置远程仓库地址（GIT_REMOTE_URL），跳过 push。产出已在本地 Git 仓库。");
            return false;
        }

        File workDir = Paths.get(System.getProperty("user.dir"), "workspaces", caseId).toFile();
        if (!new File(workDir, ".git").exists()) {
            log.warn("[Git] 工作区未初始化 Git，无法 push: {}", caseId);
            return false;
        }

        try (Git git = Git.open(workDir)) {
            // 添加远程仓库（如不存在）
            var remoteConfig = git.getRepository().getConfig();
            String remote = remoteConfig.getString("remote", "origin", "url");
            if (remote == null) {
                git.remoteAdd()
                        .setName("origin")
                        .setUri(new org.eclipse.jgit.transport.URIish(remoteUrl))
                        .call();
                log.info("[Git] 添加远程仓库: {}", remoteUrl);
            }

            // push（用 token 认证，适配 GitLab/GitHub/Gitea）
            git.push()
                    .setRemote("origin")
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider("oauth2", token))
                    .setForce(false)
                    .call();

            log.info("[Git] Push 成功: {} → {}", caseId, remoteUrl);
            return true;

        } catch (Exception e) {
            log.error("[Git] Push 失败 caseId={}, remote={}", caseId, remoteUrl, e);
            return false;
        }
    }

    /** 远程仓库是否已配置。 */
    public boolean isRemoteConfigured() {
        return remoteUrl != null && !remoteUrl.isBlank() && token != null && !token.isBlank();
    }
}
