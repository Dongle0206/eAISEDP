package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 工程标准 Mapper（V6 F1.1，case-20260820 T2；批B T12/T13 增补手写 SQL）。
 *
 * <p>标准 MP 形态：CRUD/筛选走 BaseMapper + LambdaWrapper（租户拦截器自动注入）；
 * relatedPrincipleCodes/relatedGateNames 为 JSON 列，按原则/门禁筛选在 Service 内存过滤
 * （方言绑定+索引无效，SE §4.2 口径，同 AdrMapper 先例）。发布事务的 FOR UPDATE 锁旧
 * published 行（T12）走 LambdaWrapper {@code last("LIMIT 1 FOR UPDATE")}，无需手写。</p>
 */
@Mapper
public interface StandardMapper extends BaseMapper<Standard> {

    /**
     * D-9 已删除占位查询（case-20260820 T13，AC-F1.7）：旁路 @TableLogic 显式
     * {@code is_deleted IN (0,1)}，供 gateName 反查展示"已删除"行——标准逻辑删后
     * 门禁规则页"已关联标准"条目需变"已删除"占位（MP 标准方法自动过滤逻辑删行会使其
     * 直接消失）。
     *
     * <p><b>口径钉死</b>（SE §4.5 翻译表）：status 固定 published——门禁关联展示/打回解析
     * 一律按 published 过滤（draft 标准发布前不出现在门禁侧，AC-F1.3）；relatedGateNames
     * 为 JSON 列不进 SQL，命中过滤在 Service 内存完成（ADR §4.2 口径）。</p>
     *
     * <p><b>租户隔离（G13）</b>：手写 @Select 仍被 MyBatis-Plus 租户拦截器改写
     * （tenant_id 自动注入），与 D-9 设计一致，不违 G13。</p>
     *
     * <p><b>deleted 列映射说明</b>：BaseEntity.deleted 标注 {@code select=false}（不进 MP
     * 生成列清单），本 SQL 以 {@code is_deleted AS deleted} 别名让驼峰自动映射回填该字段
     * （is_deleted 原列映射到不存在的 isDeleted 属性被忽略），Service 据此标
     * {@code StandardVo.deleted=true} 占位。</p>
     *
     * @return 现行 published 标准全量候选行（含逻辑删行；tenant 内，量级 ≤1000 行 PRD §6.5）
     */
    @Select("SELECT t.*, t.is_deleted AS deleted FROM t_standard t "
            + "WHERE t.status = 'published' AND t.is_deleted IN (0, 1)")
    List<Standard> selectPublishedWithDeletedForGateRef();
}
