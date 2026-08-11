package com.eaiselp.runtime.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 产物类型映射配置（SP-4：清除 P6 硬编码）。
 *
 * <p><b>背景</b>：DerivationEngine 原先在 {@code guessType(role)} 方法里硬编码了
 * 13 个 {@code team-*} 角色名到产物类型的 switch 映射，违反 EA 蓝图 §7
 * <b>P6「平台零角色硬编码（铁律 1 的工程落地）」</b>——平台代码不应硬编码任何角色名，
 * 角色全由 agents-config markdown 加载。一旦新增/改名角色必须改 Java 源码重发版，
 * 与平台核心承诺冲突。</p>
 *
 * <p><b>修复</b>：把映射抽到 {@code application.yml} 的 {@code eaiselp.artifact.type-mapping}
 * 下。新增角色只需改 yml（重启即生效），不动 Java 源码。</p>
 *
 * <p>对应 application.yml：</p>
 * <pre>
 * eaiselp:
 *   artifact:
 *     default-type: other           # 未匹配时的兜底类型
 *     type-mapping:                 # 角色名 → 产物类型
 *       team-po: prd
 *       team-ux: design
 *       team-se: tech-design
 *       team-ba: tasks
 *       team-dev: code
 *       team-reviewer: review
 *       team-security: review
 *       team-qa: test
 *       team-performance: perf
 *       team-ops: deploy
 *       team-pm: tracking
 * </pre>
 *
 * @see com.eaiselp.runtime.engine.DerivationEngine
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "eaiselp.artifact")
public class ArtifactTypeMappingProperties {

    /** 角色名 → 产物类型 映射表（key = agent name，如 team-po；value = 产物类型，如 prd）。 */
    private Map<String, String> typeMapping = new HashMap<>();

    /** 未匹配时的兜底产物类型（默认 other，对应原 guessType 的 default 分支）。 */
    private String defaultType = "other";
}
