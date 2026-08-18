package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 项目-原则关联服务接口（IService 模式，PRJ-002 阶段C 基础层）。
 *
 * <p>通用 CRUD 由 IService 默认提供，支撑 ProjectService 原则绑定全量替换
 * （删旧插新，uk_project_principle 幂等）与 PrincipleService 删除时反向清理绑定
 * （WHERE principle_id=?，DBA D2 关联表方案的意义所在）。</p>
 */
public interface ProjectPrincipleService extends IService<ProjectPrinciple> {
}
