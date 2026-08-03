package com.eaiselp.adapter.spi;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * CI/CD 适配器 SPI（接 Jenkins / GitHub Actions / GitLab CI / 等）。
 *
 * <p>EA 蓝图 §4.3 适配器体系第 5 类：企业商用需对接已有构建发布流水线，
 * 让体系在检查点能触发构建、回读状态、拉取日志做失败归因。
 *
 * <p>遵循 P3 依赖单向：接口定义在 adapter 模块，企业自研实现按 SPI 装配。
 * 默认 stub 实现 {@code StubCICDAdapter} 默认不启用
 * （{@code eaiselp.adapter.cicd.enabled=true} 才装配）。
 */
public interface CICDAdapter extends Adapter {
    /**
     * 触发构建。
     *
     * @param project 项目标识（Jenkins job / Actions repo / GitLab project，provider 各自解释）
     * @param branch  分支
     * @param params  构建参数（可空）
     * @return 构建实例 ID；失败返回 null
     */
    String triggerBuild(String project, String branch, Map<String, String> params);

    /**
     * 查询构建状态。
     *
     * @param buildId 构建实例 ID
     * @return 构建状态；不存在返回 null
     */
    BuildStatus getBuildStatus(String buildId);

    /**
     * 获取构建日志（尾部 maxLines 行）。
     *
     * @param buildId  构建实例 ID
     * @param maxLines 最多返回行数（provider 可按自身上限裁剪）
     * @return 日志文本；无日志返回空串
     */
    String getBuildLog(String buildId, int maxLines);

    /** 构建终态/中间态（统一抽象，provider 各自映射到原生状态名）。 */
    enum BuildState { PENDING, RUNNING, SUCCESS, FAILED, CANCELLED }

    /** 构建状态信息。 */
    @Data
    @Builder
    class BuildStatus {
        private String buildId;
        private BuildState state;
        private String url;
        private Long durationMs;
    }
}
