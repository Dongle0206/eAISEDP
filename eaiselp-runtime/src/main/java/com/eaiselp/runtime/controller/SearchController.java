package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaiselp.common.ratelimit.RateLimit;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.data.entity.Artifact;
import com.eaiselp.data.service.ArtifactService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 全文检索 REST API（M3-4）。
 *
 * <p>提供对 Artifact 的关键词搜索：{@code GET /api/v1/search?q=关键词&page=1&size=10}，
 * 命中条件为 content 或 title 包含关键词（MyBatis-Plus {@code like}，对应 SQL {@code LIKE '%kw%' OR}）。
 *
 * <p><b>返回投影</b>：不返回 content 全文，仅返回摘要（前 {@link #SUMMARY_LIMIT} 字符）+
 * title + type + role + caseId，避免大字段全量回传导致响应膨胀（SE 列表接口规范）。
 *
 * <p>权限：需 {@code artifact:view}（与产物查看口径一致，对齐 DashboardController）。
 *
 * <p>限流：搜索为高频读接口，按用户维度限 60 次/分（防恶意/误操作打爆 like 全表扫描，
 * SE §4.2.3 通用配置；content 全表扫描开销大，限流尤为重要）。
 *
 * <p>性能提示（已知技术债）：底层 {@link ArtifactService#search} 用 {@code LIKE '%kw%'}，
 * 大表性能差；M4 计划加全文索引或换 ES，到时仅替换 Service 实现，本 Controller 无感。
 *
 * <p>多租户隔离（ES-003 §9.3 P11，G13）：查询经 MyBatis-Plus 租户拦截器
 * 自动注入 tenant_id 过滤，搜索结果严格限定在当前租户范围内。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    /** 摘要最大字符数（content 前 200 字符，避免大字段全量回传）。 */
    private static final int SUMMARY_LIMIT = 200;

    private final ArtifactService artifactService;

    /**
     * 全文检索 Artifact（content + title 匹配）。
     *
     * @param q    关键词（必填，空串/缺失 → 返回 400）
     * @param page 页码（默认 1）
     * @param size 每页条数（默认 10）
     * @return 命中产物摘要分页（不含 content 全文）
     */
    @GetMapping
    @RequirePermission("artifact:view")
    @RateLimit(name = "search", key = RateLimit.KeyType.USER,
            capacity = 60, refillPerMin = 60,
            message = "搜索请求过于频繁，请稍后再试")
    public R<IPage<ArtifactSearchVo>> search(@RequestParam("q") String q,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "10") int size) {
        if (q == null || q.trim().isEmpty()) {
            return R.fail(400, "搜索关键词 q 不能为空");
        }
        IPage<Artifact> hit = artifactService.search(q, page, size);
        // 投影：content 全文 → 摘要（前 200 字符），剔除大字段；复用原分页元信息
        IPage<ArtifactSearchVo> result = hit.convert(this::toVo);
        return R.ok(result);
    }

    /** Artifact → 摘要 VO（content 裁剪到 SUMMARY_LIMIT 字符）。 */
    private ArtifactSearchVo toVo(Artifact a) {
        ArtifactSearchVo vo = new ArtifactSearchVo();
        vo.setId(a.getId());
        vo.setCaseId(a.getCaseId());
        vo.setRole(a.getRole());
        vo.setType(a.getType());
        vo.setTitle(a.getTitle());
        String content = a.getContent();
        if (content != null && content.length() > SUMMARY_LIMIT) {
            vo.setSummary(content.substring(0, SUMMARY_LIMIT));
        } else {
            vo.setSummary(content);
        }
        vo.setCreateTime(a.getCreateTime());
        return vo;
    }

    /**
     * 搜索结果摘要 VO。
     *
     * <p>仅暴露列表展示需要的字段：title + type + role + caseId + content 摘要，
     * 不含 content 全文 / frontmatter / docKey 等大字段或内部字段。
     */
    @Data
    public static class ArtifactSearchVo {
        private Long id;
        private String caseId;
        private String role;
        private String type;
        private String title;
        /** content 前 200 字符摘要。 */
        private String summary;
        private LocalDateTime createTime;
    }
}
