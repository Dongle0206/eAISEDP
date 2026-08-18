package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.eaiselp.runtime.hierarchy.dto.ProgramVo;

/**
 * 项目群服务接口（IService 模式，PRJ-002 阶段C/T08 基础层 + 批4 Controller 支撑扩展）。
 *
 * <p>通用 CRUD 由 IService 默认提供；进度均值聚合（批量防 N+1）、详情聚合、
 * 删除时成员项目 program_id 置空等业务方法由本接口扩展。</p>
 */
public interface ProgramService extends IService<Program> {

    /**
     * 分页查询项目群，附带实时聚合（AC-F8.5）：每群成员项目数 + 群内进度均值 ⌊Σprogress/n⌋。
     *
     * <p>成员项目单条批查（program_id IN 页内群 id）后内存分组，防 N+1（SE §11 R11）；
     * 聚合不落库（PRD §7.11 展示层实时聚合）。</p>
     *
     * @param page       页码（1 起）
     * @param size       页大小
     * @param status     状态过滤（planning/active/suspended/closed；null/空 = 全部）
     * @param strategyId 关联战略过滤（null = 全部，含未关联战略的群）
     * @return 分页结果（records 为 ProgramVo，projects 为 null）
     */
    IPage<ProgramVo> pageWithProgress(long page, long size, String status, Long strategyId);

    /**
     * 项目群详情聚合（AC-F2.2）：章程全文 + 成员项目列表（含进度三列只读）+ 项目数 + 进度均值。
     *
     * @param id 项目群 ID
     * @return 详情 VO
     * @throws com.eaiselp.common.exception.BizException 404 项目群不存在（含跨租户被拦截器过滤）
     */
    ProgramVo detail(Long id);

    /**
     * 删除项目群（AC-F2.3）：成员项目 program_id 置空（项目保留可访问）后本群逻辑删。
     *
     * <p>置空用 LambdaUpdateWrapper 标准形态（拦截器友好）；二次确认由前端承担。</p>
     *
     * @param id 项目群 ID
     * @throws com.eaiselp.common.exception.BizException 404 项目群不存在
     */
    void deleteWithUnlink(Long id);
}
