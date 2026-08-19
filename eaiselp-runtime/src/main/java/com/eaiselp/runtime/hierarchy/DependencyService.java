package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.eaiselp.runtime.hierarchy.dto.DependencyBoardVo;

import java.util.List;

/**
 * 跨项目依赖服务接口（V5 F3，case-20260818 T11；契约=api-contracts §3）。
 *
 * <p>创建归一化（blocks 换向→depends_on + note 前缀 [orig:blocks]，C1）+ 硬校验 +
 * 环预检（400 带路径，C5）+ DuplicateKey→复活/唯一冲突翻译（C2）+ blocked 看板聚合
 * （展示层实时不落库）+ 全图体检。</p>
 */
public interface DependencyService extends IService<ProjectDependency> {

    /**
     * 登记依赖边（AC-F3.1/F3.3）。
     *
     * <p>归一化：blocks 换向存 from=被阻塞方/to=阻塞方、type=depends_on、note 前缀
     * {@code [orig:blocks]}；depends_on/relates_to 原样。硬校验：from=to 400 自依赖、
     * 两端存在 404、类型枚举 400。强边过环预检（成环 400+路径提示+WARN 日志）。
     * DuplicateKey → 查逻辑删行：命中复活（is_deleted=0，审计 detail 含 revived:true，
     * id 复用）；未命中 400 唯一冲突（提示既有 id）。</p>
     *
     * @param fromProjectId  依赖方（blocks 录入时=阻塞方 A，换向后为被阻塞方）
     * @param toProjectId    被依赖方（blocks 录入时=被阻塞方 B）
     * @param dependencyType blocks / depends_on / relates_to（入参三值，落库归一）
     * @param remark         备注（C1：blocks 时前缀化存 note 列）
     * @return 登记成功（或复活）的边（归一化后存储形态）
     */
    ProjectDependency register(Long fromProjectId, Long toProjectId, String dependencyType, String remark);

    /**
     * 编辑边（dependencyType/remark；方向不可改——改向=删旧建新）。编辑同样过环预检。
     * 审计 dependency_update。
     */
    ProjectDependency edit(Long id, String dependencyType, String remark);

    /** 逻辑删（seed 权限口径 C7）。审计 dependency_delete。 */
    void remove(Long id);

    /** 详情（跨租户/不存在 → 404）。 */
    ProjectDependency loadOr404(Long id);

    /**
     * 边列表分页（projectId 作为 from 或 to 命中均可返回；type 过滤）。
     */
    IPage<ProjectDependency> pageEdges(Long projectId, String type, long page, long size);

    /**
     * blocked 看板聚合（AC-F3.2/F3.4，展示层实时不落库）。
     *
     * <p>一次查全部活跃边 + 涉项目状态快照内存判定：强依赖对端 status∉{delivered,closed}→
     * blocked + 阻塞链文案"被 Q 阻塞：Q 未交付"；relates_to 不计。交付即自动解除（刷新生效）。</p>
     */
    DependencyBoardVo board();

    /**
     * 新边环预检（查询语义，成环也 200，前端登记表单实时提示用）。
     *
     * @return wouldCycle + cyclePathIds + pathDisplay（"项目A→项目B→项目A"）
     */
    CycleCheckVo cycleCheck(Long from, Long to);

    /**
     * 全图体检（可见可治，正常返回空）。
     *
     * @return cycleCount + cycles（环序列按最小节点旋转规范化去重）
     */
    FullCheckVo fullCheck();

    /** 解析 note 列还原原始登记类型（C1：[orig:blocks] 前缀 → blocks；失败默认按存储类型文案）。 */
    static String parseOrigType(String dependencyType, String note) {
        if (note != null && note.startsWith("[orig:blocks]")) {
            return "blocks";
        }
        return dependencyType == null ? "depends_on" : dependencyType;
    }

    /** 解析 note 列还原用户备注（剥离 [orig:blocks] 存储前缀，API 语义名 remark 的取值口径）。 */
    static String parseRemark(String note) {
        if (note == null) {
            return null;
        }
        return note.startsWith("[orig:blocks]") ? note.substring("[orig:blocks]".length()) : note;
    }

    /** 环预检结果（api-contracts §3 cycle-check 契约）。 */
    record CycleCheckVo(boolean wouldCycle, List<Long> cyclePathIds, String pathDisplay) {
    }

    /** 全图体检结果（api-contracts §3 full-check 契约）。 */
    record FullCheckVo(int cycleCount, List<CyclePath> cycles) {
        public record CyclePath(List<Long> pathIds, String pathDisplay) {
        }
    }
}
