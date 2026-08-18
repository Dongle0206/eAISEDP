package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.extension.service.IService;
import com.eaiselp.runtime.hierarchy.dto.BoundProjectVo;

import java.util.List;

/**
 * 架构原则服务接口（IService 模式，PRJ-002 阶段C/T10 基础层 + 批4 Controller 支撑扩展）。
 *
 * <p>通用 CRUD/启停由 IService 默认提供；code 租户内唯一校验（uk 冲突→409）、
 * 原则维度项目绑定管理（查绑定/全量覆盖）、删除同步清理 t_project_principle 绑定
 * 等业务方法由本接口扩展。</p>
 */
public interface PrincipleService extends IService<ArchitecturePrinciple> {

    /**
     * code 租户内唯一校验（AC-F5.1）。
     *
     * @param code      原则编号（如 P11）
     * @param excludeId 编辑场景排除自身 ID（新建传 null）
     * @throws com.eaiselp.common.exception.BizException 409 同 code 原则已存在（uk_principle_code）
     */
    void checkCodeAvailable(String code, Long excludeId);

    /**
     * 查询原则已绑定的项目清单（原则维度，GET /api/v1/principles/{id}/projects）。
     *
     * @param principleId 原则 ID
     * @return 绑定项目列表（projectId/projectName/项目级 enabled 覆盖位）
     * @throws com.eaiselp.common.exception.BizException 404 原则不存在
     */
    List<BoundProjectVo> boundProjects(Long principleId);

    /**
     * 原则-项目绑定全量覆盖（PUT /api/v1/principles/{id}/projects）：
     * 删旧插新（uk_project_principle 幂等），目标项目集之外的既有绑定行全部移除。
     *
     * @param principleId 原则 ID
     * @param projectIds  目标项目 ID 全集（null 视为空集 = 清空该原则全部绑定）
     * @throws com.eaiselp.common.exception.BizException 404 原则或任一目标项目不存在（跨租户同 404）
     */
    void bindProjects(Long principleId, List<Long> projectIds);

    /**
     * 删除原则（AC-F5.x）：同步反向清理 t_project_principle 绑定行（WHERE principle_id=?，
     * 关联表方案的意义所在，DBA D2）后逻辑删原则。
     *
     * @param id 原则 ID
     * @throws com.eaiselp.common.exception.BizException 404 原则不存在
     */
    void deleteWithCleanup(Long id);
}
