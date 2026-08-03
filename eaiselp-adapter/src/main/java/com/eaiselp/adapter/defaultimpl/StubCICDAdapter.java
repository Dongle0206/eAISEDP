package com.eaiselp.adapter.defaultimpl;

import com.eaiselp.adapter.spi.CICDAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@link CICDAdapter} 的 stub 默认实现。
 *
 * <p>用途：未对接 Jenkins/GitHub Actions/GitLab CI 等真实流水线时占位，保证 SPI 链路完整、
 * 工厂可选注入不报"无 Bean"。所有方法记录 warn 后返回 null/空串，不真实触发构建。
 *
 * <p>条件装配：仅当 {@code eaiselp.adapter.cicd.enabled=true} 时生效（默认不启用，
 * 企业接入真实 CI/CD 时配 enabled=true 或直接提供自研 Bean 覆盖）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "eaiselp.adapter.cicd.enabled", havingValue = "true", matchIfMissing = false)
public class StubCICDAdapter implements CICDAdapter {

    @Override public String getType() { return "cicd"; }
    @Override public String getProvider() { return "stub"; }
    @Override public boolean isAvailable() { return false; }

    @Override
    public String triggerBuild(String project, String branch, Map<String, String> params) {
        log.warn("[CICDAdapter-Stub] triggerBuild 未实现: project={}, branch={}", project, branch);
        return null;
    }

    @Override
    public BuildStatus getBuildStatus(String buildId) {
        log.warn("[CICDAdapter-Stub] getBuildStatus 未实现: buildId={}", buildId);
        return null;
    }

    @Override
    public String getBuildLog(String buildId, int maxLines) {
        log.warn("[CICDAdapter-Stub] getBuildLog 未实现: buildId={}, maxLines={}", buildId, maxLines);
        return "";
    }
}
