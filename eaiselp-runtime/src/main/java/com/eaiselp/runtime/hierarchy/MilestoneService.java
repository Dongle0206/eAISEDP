package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.eaiselp.data.entity.Milestone;
import com.eaiselp.runtime.hierarchy.dto.MilestoneTimelineVo;

import java.time.LocalDate;
import java.util.List;

/**
 * 里程碑服务接口（V5 F2 激活，case-20260818 T10；契约=api-contracts §2）。
 *
 * <p>CRUD（milestoneCode 服务端生成 + 两级归属校验）+ transit 统一入口（状态机/达成日期默认
 * 当天/撤销清空）+ 逾期展示层标记（不改库）+ 群聚合时间线 + 达成提示辅助判定（供 T22）。
 * legacy 两列（program_id/milestone_id）一律不写；subprojects 群级多选仅展示不参与自动判定。</p>
 */
public interface MilestoneService extends IService<Milestone> {

    /**
     * 创建里程碑（状态固定 planned）。
     *
     * <p>milestoneCode 缺省服务端生成 {@code MS-}+租户内自增序（uk 兜底重试 3 次）；
     * ownerType/ownerId 归属存在性校验；title 必填 ≤200。审计 milestone_create。</p>
     *
     * @throws com.eaiselp.common.exception.BizException 400（title 空/超长、ownerType 非法）/ 404（归属对象不存在）
     */
    Milestone create(Milestone ms);

    /**
     * 编辑里程碑（body 同创建，不含 status/achievedDate——状态变更只走 transit）。审计 milestone_update。
     *
     * @throws com.eaiselp.common.exception.BizException 400/404 同 {@link #create}
     */
    Milestone edit(Long id, Milestone patch);

    /** 详情（跨租户/不存在 → 404 不泄露存在性）。 */
    Milestone loadOr404(Long id);

    /**
     * 状态流转统一入口（AC-F2.2/F2.3）。
     *
     * <p>MilestoneStatus 状态机校验（非法 400 "非法状态流转: achieved→delayed"）；target=achieved
     * 时 achievedDate 缺省当天；achieved→planned 撤销清空达成日期（审计 detail 含
     * clearedAchievedDate:true）；流转到自身=幂等合法。系统永不自动置 achieved/delayed——
     * 逾期仅 {@link #toVo} 的 overdue 展示层标记。审计 milestone_transit。</p>
     *
     * @param id            里程碑 ID
     * @param target        目标状态（planned/achieved/delayed）
     * @param achievedDate  达成日期（target=achieved 时可空=默认当天）
     * @throws com.eaiselp.common.exception.BizException 400（未知状态/非法流转/状态列脏数据）/ 404
     */
    Milestone transit(Long id, String target, LocalDate achievedDate);

    /**
     * 删除（逻辑删）。审计 milestone_delete。
     */
    void remove(Long id);

    /**
     * 分页时间线（ownerType/ownerId/status 过滤，命中 idx_ms_tenant_owner，按 targetDate 升序）。
     * Vo.overdue = targetDate&lt;今天 且 planned（展示层黄角标，不改库，AC-F2.3）。
     */
    IPage<MilestoneTimelineVo> pageTimeline(String ownerType, Long ownerId, String status, long page, long size);

    /**
     * 项目群聚合时间线（AC-F2.6）：群直属 + 成员项目全部里程碑合并，按 targetDate 排序，
     * ownerLevel 标签区分层级（program/project），ownerName 为归属对象名。
     *
     * @throws com.eaiselp.common.exception.BizException 404 项目群不存在
     */
    List<MilestoneTimelineVo> programTimeline(Long programId);

    /**
     * 项目是否存在 planned 里程碑（达成提示判定的一半；另一半 case_total/case_done 由 T22 复用
     * 既有汇总字段——零新口径）。空列表 = 不可提示。
     */
    List<Long> plannedMilestoneIds(Long projectId);

    /** {@link #plannedMilestoneIds} 非空的便捷判定（供 T22 achievementHint）。 */
    boolean isAchievableHint(Long projectId);

    /** 实体→时间线 Vo（overdue/statusColor 派生）。 */
    MilestoneTimelineVo toVo(Milestone ms);
}
