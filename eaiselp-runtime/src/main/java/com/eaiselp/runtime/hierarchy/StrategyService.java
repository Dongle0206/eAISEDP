package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.extension.service.IService;
import com.eaiselp.runtime.hierarchy.dto.StrategyBoardVo;

/**
 * 战略目标服务接口（IService 模式，PRJ-002 阶段C/T07 基础层 + 批4 Controller 支撑扩展）。
 *
 * <p>通用 CRUD 由 IService 默认提供；生命周期流转、有关联项目群拒删、board 聚合
 * 由本接口扩展（业务失败抛 {@link com.eaiselp.common.exception.BizException}，
 * 由 GlobalExceptionHandler 统一转 R.fail）。</p>
 */
public interface StrategyService extends IService<Strategy> {

    /**
     * 生命周期流转（AC-F1.2）：draft→active→achieved/archived 校验。
     *
     * <p>合法路径：draft→active、active→achieved、active→archived、achieved→archived；
     * 其余（含同态重复流转、archived 后任何流转）拒绝。</p>
     *
     * @param id           战略 ID
     * @param targetStatus 目标状态：active / achieved / archived
     * @return 更新后的实体（含新 status）
     * @throws com.eaiselp.common.exception.BizException 404 战略不存在；400 非法流转
     */
    Strategy transit(Long id, String targetStatus);

    /**
     * 删除前校验并逻辑删（AC-F1.3）：存在关联项目群（未删，任意状态）→ 400 拒绝。
     *
     * @param id 战略 ID
     * @throws com.eaiselp.common.exception.BizException 404 战略不存在；400 存在关联项目群
     */
    void deleteWithProgramCheck(Long id);

    /**
     * 战略看板聚合（AC-F8.5 双层进度均值）：KPI + 关联项目群列表（各含项目数/群内进度均值）
     * + 顶层均值 ⌊Σ群均值/m⌋。聚合不落库、展示层实时计算；成员项目单条批查防 N+1（R11）。
     *
     * @param id 战略 ID
     * @return 看板 VO
     * @throws com.eaiselp.common.exception.BizException 404 战略不存在
     */
    StrategyBoardVo board(Long id);
}
