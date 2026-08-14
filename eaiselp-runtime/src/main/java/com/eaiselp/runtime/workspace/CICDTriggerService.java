package com.eaiselp.runtime.workspace;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

/**
 * CI/CD 触发服务（生产级链路 Step 4：Git push → 触发构建部署）。
 *
 * <p><b>灵活适配企业级工具链</b>——通过 Webhook 通用协议适配所有主流 CI/CD 工具：</p>
 * <ul>
 *   <li><b>Jenkins</b>：Generic Webhook Trigger 插件接收 POST</li>
 *   <li><b>GitLab CI</b>：Pipeline Trigger API（或 push 事件自动触发）</li>
 *   <li><b>GitHub Actions</b>：repository_dispatch / push 事件自动触发</li>
 *   <li><b>Gitea Actions / Drone / Argo CD</b>：Webhook 接收</li>
 *   <li><b>任意支持 Webhook 的 CI/CD</b>：POST JSON 即可</li>
 * </ul>
 *
 * <p><b>配置驱动（application.yml）</b>：</p>
 * <pre>
 * eaiselp:
 *   cicd:
 *     webhook-url: ${CICD_WEBHOOK_URL:}       # CI/CD 工具的 Webhook 接收地址（空=不触发）
 *     webhook-token: ${CICD_WEBHOOK_TOKEN:}    # 认证 token（放 Header 或 body，视工具而定）
 *     timeout-ms: 10000                         # 触发请求超时
 * </pre>
 *
 * <p><b>触发时机</b>：编排完成后 Git push 成功 → 自动 POST Webhook → CI/CD 工具收到后执行构建/部署。</p>
 *
 * <p><b>降级策略</b>：Webhook 触发是异步通知，不等待构建完成（fire-and-forget）。
 * 触发失败只 log 不阻塞（产出已在 Git 仓库，CI/CD 可手动触发）。</p>
 */
@Slf4j
@Service
public class CICDTriggerService {

    @Value("${eaiselp.cicd.webhook-url:}")
    private String webhookUrl;

    @Value("${eaiselp.cicd.webhook-token:}")
    private String webhookToken;

    @Value("${eaiselp.cicd.timeout-ms:10000}")
    private int timeoutMs;

    private final RestClient httpClient = RestClient.builder().build();

    /**
     * 触发 CI/CD 构建（fire-and-forget Webhook）。
     *
     * <p>POST JSON body 格式（通用，各 CI/CD 工具按需消费）：</p>
     * <pre>
     * {
     *   "source": "eAISEDP",
     *   "event": "orchestration_complete",
     *   "caseId": "case-xxx",
     *   "requirement": "开发一个信息共享网页",
     *   "commitHash": "abc123...",
     *   "files": ["team-po/PRD.md", "team-dev/index.html", ...],
     *   "timestamp": "2026-08-14T10:30:00"
     * }
     * </pre>
     *
     * @param caseId     Case ID
     * @param commitHash Git commit hash（push 成功后）
     * @param files      工作区文件列表
     * @param requirement 原始需求
     * @return true=触发成功 / false=跳过或失败
     */
    public boolean triggerBuild(String caseId, String commitHash, java.util.List<String> files, String requirement) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.info("[CICD] 未配置 Webhook URL（CICD_WEBHOOK_URL），跳过触发。Git push 后 CI/CD 工具可自动感知。");
            return false;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("source", "eAISEDP");
        payload.put("event", "orchestration_complete");
        payload.put("caseId", caseId);
        payload.put("requirement", requirement != null ? requirement : "");
        payload.put("commitHash", commitHash != null ? commitHash : "");
        payload.put("files", files != null ? files : java.util.Collections.emptyList());
        payload.put("timestamp", java.time.LocalDateTime.now().toString());

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (webhookToken != null && !webhookToken.isBlank()) {
                // 通用 token 认证（Jenkins/GitLab/Gitea 均支持 Bearer 或自定义 Header）
                headers.setBearerAuth(webhookToken);
                headers.set("X-Webhook-Token", webhookToken); // 备用：部分工具用自定义 Header
            }

            log.info("[CICD] 触发 Webhook: {} caseId={} commit={}", webhookUrl, caseId,
                    commitHash != null ? commitHash.substring(0, Math.min(8, commitHash.length())) : "null");

            // fire-and-forget：POST 后不等待构建完成（CI/CD 构建可能耗时数分钟~数十分钟）
            String response = httpClient.post()
                    .uri(webhookUrl)
                    .headers(h -> h.addAll(headers))
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            log.info("[CICD] Webhook 触发成功 caseId={}, 响应: {}",
                    caseId, response != null ? response.substring(0, Math.min(200, response.length())) : "null");
            return true;

        } catch (Exception e) {
            log.error("[CICD] Webhook 触发失败 caseId={}, url={}", caseId, webhookUrl, e);
            return false;
        }
    }

    /** CI/CD Webhook 是否已配置。 */
    public boolean isConfigured() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }
}
