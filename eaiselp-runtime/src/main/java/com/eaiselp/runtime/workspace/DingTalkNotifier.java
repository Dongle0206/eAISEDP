package com.eaiselp.runtime.workspace;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 钉钉群机器人通知（#28：IM 适配器首个真实实现）。
 *
 * <p>配置 {@code eaiselp.notify.dingtalk-webhook}（钉钉群机器人 Webhook 地址）后，
 * 编排完成/审批等待自动推送到钉钉群。未配置则跳过（静默降级）。</p>
 *
 * <p>Webhook 配置方式：钉钉群 → 群设置 → 智能群助手 → 添加机器人 → 自定义（安全设置选"自定义关键词"，填"eAISEDP"）。</p>
 */
@Slf4j
@Service
public class DingTalkNotifier {

    @Value("${eaiselp.notify.dingtalk-webhook:}")
    private String webhookUrl;

    private final RestClient httpClient = RestClient.builder().build();

    /** 编排完成通知。 */
    public void notifyOrchestrationDone(String caseId, int success, int total, boolean allPassed) {
        String emoji = allPassed ? "✅" : success > 0 ? "⚠️" : "❌";
        send(emoji + " eAISEDP 编排完成\n\n"
                + "Case: " + caseId + "\n"
                + "步骤: " + success + "/" + total + " 成功\n"
                + "产出验证: " + (allPassed ? "全部通过" : "部分失败，请到工作区查看")
                + "\n\n[登录平台查看](http://localhost:8080/login.html)");
    }

    /** 审批等待通知（不可逆操作人工锁）。 */
    public void notifyAwaitingApproval(String caseId, Long checkpointId) {
        send("🔒 eAISEDP 部署审批等待\n\n"
                + "Case: " + caseId + "\n"
                + "检查点: #" + checkpointId + "\n"
                + "部署为不可逆操作，需要人工审批\n"
                + "30 分钟无审批自动跳过\n\n"
                + "[去审批](http://localhost:8080/login.html)");
    }

    private void send(String markdownText) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.debug("[DingTalk] 未配置 Webhook，跳过通知");
            return;
        }
        try {
            // 钉钉自定义机器人 markdown 消息格式
            Map<String, Object> payload = Map.of(
                    "msgtype", "markdown",
                    "markdown", Map.of("title", "eAISEDP", "text", markdownText));
            String resp = httpClient.post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            log.info("[DingTalk] 通知已发送: {}", resp != null ? resp.substring(0, Math.min(100, resp.length())) : "ok");
        } catch (Exception e) {
            log.warn("[DingTalk] 通知失败（不阻塞）: {}", e.getMessage());
        }
    }

    public boolean isConfigured() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }
}
