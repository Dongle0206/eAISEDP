package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.extension.service.IService;
import com.eaiselp.runtime.hierarchy.dto.ProjectDetailVo;

/**
 * 项目服务接口（IService 模式，PRJ-002 阶段C/T09 基础层 + 批4 Controller 支撑扩展）。
 *
 * <p>通用 CRUD 由 IService 默认提供；详情聚合（含已绑定原则清单）、删除时解除
 * Case 挂接等业务方法由本接口扩展。进度三列的写入仅走 ProjectProgressService
 * 全量重算（AC-F3.2：实体 updateStrategy=NEVER + Service/Controller 忽略双保险）。</p>
 */
public interface ProjectService extends IService<Project> {

    /**
     * 项目详情聚合（SE §8.2 GET /api/v1/projects/{id}）：基础字段 + 进度三列（只读）
     * + 已绑定原则清单（绑定行 ∩ 原则未删，含启停态与项目级覆盖位）。
     *
     * @param id 项目 ID
     * @return 详情 VO
     * @throws com.eaiselp.common.exception.BizException 404 项目不存在（含跨租户被拦截器过滤）
     */
    ProjectDetailVo detail(Long id);

    /**
     * 删除项目：成员 Case 的 project_id 置空（Case 保留，回落场景C 不关联形态）
     * 后项目逻辑删。
     *
     * <p>进度三列随项目行一同逻辑删，无需再触发汇总重算（SE §3.3 删除时解除 Case 挂接）。</p>
     *
     * @param id 项目 ID
     * @throws com.eaiselp.common.exception.BizException 404 项目不存在
     */
    void deleteWithUnlinkCase(Long id);
}
