package com.eaiselp.data.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.IService;
import com.eaiselp.data.entity.Artifact;

import java.util.List;

/**
 * 产物服务接口。
 * M2 SP-1 补 listByCaseId：Case 详情页产物列表查询用。
 * getById(Long) 由 IService 默认提供，无需声明。
 */
public interface ArtifactService extends IService<Artifact> {

    /** 按案例 ID 查产物列表（按创建时间倒序）。 */
    default List<Artifact> listByCaseId(String caseId) {
        return this.list(new LambdaQueryWrapper<Artifact>()
                .eq(Artifact::getCaseId, caseId)
                .orderByDesc(Artifact::getCreateTime));
    }
}
