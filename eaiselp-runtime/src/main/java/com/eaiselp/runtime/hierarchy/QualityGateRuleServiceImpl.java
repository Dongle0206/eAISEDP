package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eaiselp.common.exception.BizException;
import org.springframework.stereotype.Service;

/**
 * 质量门禁规则服务实现（批4 扩展：name 租户内唯一校验；
 * listEnabledByStage 为接口 default 方法，无需实现体）。
 */
@Service
public class QualityGateRuleServiceImpl
        extends ServiceImpl<QualityGateRuleMapper, QualityGateRule> implements QualityGateRuleService {

    @Override
    public void checkNameAvailable(String name, Long excludeId) {
        long count = count(new LambdaQueryWrapper<QualityGateRule>()
                .eq(QualityGateRule::getName, name)
                .ne(excludeId != null, QualityGateRule::getId, excludeId));
        if (count > 0) {
            // uk_gate_tenant_name 冲突转业务码 409（防止 DB 唯一键直抛 500）
            throw new BizException(409, "门禁规则名称已存在: " + name + "（租户内唯一）");
        }
    }
}
