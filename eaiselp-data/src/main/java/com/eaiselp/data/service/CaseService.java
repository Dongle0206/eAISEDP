package com.eaiselp.data.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.eaiselp.data.entity.Case;

/**
 * Case 服务接口（MyBatis-Plus IService 模式）。
 *
 * <p>风格对齐 {@link DerivationService} / {@link ArtifactService}：业务便捷查询以
 * default 方法封装，避免上层硬编码 wrapper；通用 save/getById/removeById 由 IService 默认提供。</p>
 *
 * <p>注意：本类名为 {@code Case}（实体），与 JDK {@link java.lang.reflect.Method#getParameterCount()}
 * 等场景下 java.lang 的 {@code java.lang.Object} 无冲突；但命名 {@code Case} 易与
 * JDK 关键字 switch case 混淆，全限定名引用规避（M2 SP-1 落地）。</p>
 */
public interface CaseService extends IService<Case> {

    /**
     * 分页查询 Case，可选按 status 过滤，按 id 倒序（最新优先）。
     *
     * <p>status 为 null/空时不过滤（查全部），上层无需对特定状态写 if 分支。</p>
     *
     * @param page    分页对象（含 current/size）
     * @param status  Case 状态；为 null/空时不过滤
     * @return 分页结果
     */
    default IPage<Case> page(Page<Case> page, String status) {
        LambdaQueryWrapper<Case> wrapper = new LambdaQueryWrapper<Case>()
                .orderByDesc(Case::getId);
        if (status != null && !status.isEmpty()) {
            wrapper.eq(Case::getStatus, status);
        }
        return this.page(page, wrapper);
    }
}
