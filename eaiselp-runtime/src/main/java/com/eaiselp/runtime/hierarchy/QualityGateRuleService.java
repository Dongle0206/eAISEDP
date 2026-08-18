package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 质量门禁规则服务接口（IService 模式，PRJ-002 阶段C/T11 基础层）。
 *
 * <p>通用 CRUD/启停由 IService 默认提供；规则快照（GateRuleSnapshot，编排包内 record）
 * 生成方法由门禁改造批次增量扩展（SE 决策 D-3：启动快照 + 无缓存直查）。</p>
 */
public interface QualityGateRuleService extends IService<QualityGateRule> {

    /**
     * name 租户内唯一校验（uk_gate_tenant_name，V4 r2）。
     *
     * @param name      规则名称
     * @param excludeId 编辑场景排除自身 ID（新建传 null）
     * @throws com.eaiselp.common.exception.BizException 409 同名规则已存在
     */
    void checkNameAvailable(String name, Long excludeId);

    /**
     * 查询启用中的门禁规则，可选按挂载阶段过滤，按 priority 升序（小者先）。
     *
     * <p>查询形态命中 V4 r2 索引 idx_gate_tenant_enabled(tenant_id, enabled, priority)；
     * stage 传 null/空 = 查全部启用规则（编排启动加载快照的主路径形态，D-3：
     * 每次一条 SELECT、不做运行期缓存，规则修改对"新编排"立即生效、对"在跑编排"零影响）。
     * 逻辑删/租户隔离由 @TableLogic 与拦截器自动保证。</p>
     *
     * @param stage 挂载阶段: post_dev / post_test / pre_deploy；null 或空串 = 不过滤
     * @return 启用规则列表（priority 升序）
     */
    default List<QualityGateRule> listEnabledByStage(String stage) {
        LambdaQueryWrapper<QualityGateRule> wrapper = new LambdaQueryWrapper<QualityGateRule>()
                .eq(QualityGateRule::getEnabled, 1);
        if (stage != null && !stage.isEmpty()) {
            wrapper.eq(QualityGateRule::getStage, stage);
        }
        return this.list(wrapper.orderByAsc(QualityGateRule::getPriority));
    }
}
