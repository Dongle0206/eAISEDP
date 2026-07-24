package com.eaiselp.data.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.IService;
import com.eaiselp.data.entity.Derivation;

import java.util.List;

/**
 * 派生记录服务接口（MyBatis-Plus IService 模式）。
 * M2 SP-1 补 listByCaseId：Case 详情页派生时间线查询用。
 */
public interface DerivationService extends IService<Derivation> {

    /** 按案例 ID 查派生记录列表（按创建时间倒序）。 */
    default List<Derivation> listByCaseId(String caseId) {
        return this.list(new LambdaQueryWrapper<Derivation>()
                .eq(Derivation::getCaseId, caseId)
                .orderByDesc(Derivation::getCreateTime));
    }
}
