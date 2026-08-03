package com.eaiselp.data.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.eaiselp.data.entity.Artifact;

import java.util.List;

/**
 * 产物服务接口。
 * M2 SP-1 补 listByCaseId：Case 详情页产物列表查询用。
 * M3-4 补 search：Artifact 全文检索（content + title 匹配）。
 * getById(Long) 由 IService 默认提供，无需声明。
 */
public interface ArtifactService extends IService<Artifact> {

    /** 按案例 ID 查产物列表（按创建时间倒序）。 */
    default List<Artifact> listByCaseId(String caseId) {
        return this.list(new LambdaQueryWrapper<Artifact>()
                .eq(Artifact::getCaseId, caseId)
                .orderByDesc(Artifact::getCreateTime));
    }

    /**
     * 全文检索：content 或 title 命中关键词的产物分页查询（M3-4）。
     *
     * <p>实现：{@code content LIKE %kw% OR title LIKE %kw%}，按 createTime 倒序。
     * <p><b>性能提示（已知技术债）</b>：content 当前为 MEDIUMTEXT，{@code LIKE '%kw%'}
     * 走全表扫描，大表性能差。M3 阶段先用 like 保证可用，M4 计划加全文索引
     * （MySQL FULLTEXT / ngram）或切换 ES，到时仅需替换本方法实现，调用方无感。
     *
     * @param keyword 关键词（null/空串 → 返回空页，不查库）
     * @param page    页码（1 起）
     * @param size    每页条数
     * @return 命中产物分页（含 content 全文，调用方按需裁剪为摘要）
     */
    default IPage<Artifact> search(String keyword, int page, int size) {
        Page<Artifact> p = new Page<>(page, size);
        if (keyword == null || keyword.trim().isEmpty()) {
            return p;
        }
        String kw = keyword.trim();
        // 注意 or() 嵌套写法：保证 title/content 是独立 OR 组，不与后续可能追加的条件错位。
        LambdaQueryWrapper<Artifact> wrapper = new LambdaQueryWrapper<Artifact>()
                .and(w -> w.like(Artifact::getContent, kw).or().like(Artifact::getTitle, kw))
                .orderByDesc(Artifact::getCreateTime);
        return this.page(p, wrapper);
    }
}
