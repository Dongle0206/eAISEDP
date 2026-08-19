package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.data.entity.Case;
import com.eaiselp.data.mapper.CaseMapper;
import com.eaiselp.runtime.hierarchy.dto.DependencyBoardVo;
import com.eaiselp.runtime.hierarchy.dto.ProjectDetailVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 项目服务实现（批4 扩展：详情聚合含原则清单 / 删除解除 Case 挂接；
 * case-20260818 T22 增量：删除联动清依赖边 + 详情 achievementHint/依赖区块）。
 *
 * <p><b>T22 两块增量均为展示/清理辅助，零新口径</b>：</p>
 * <ul>
 *   <li><b>achievementHint（AC-F2.4/F2.5）</b>：eligible = caseTotal&gt;0 && caseDone==caseTotal
 *       && 存在 planned 里程碑——数据源复用既有汇总三列 + MilestoneService 判定，
 *       空项目（total=0）或未全 done → null（不出现提示条；提示是唯一联动，确认永远人工）。</li>
 *   <li><b>依赖区块（AC-F3.5 展示入口）</b>：waitingFor（from=本项目）/responsibleFor
 *       （to=本项目）两组边，<b>对方项目已删除的边不出现在内</b>（批查 t_project 过滤）。</li>
 *   <li>两块计算异常均 try-catch 降级 null，不阻塞详情主渲染（PRD §6.3）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectServiceImpl extends ServiceImpl<ProjectMapper, Project> implements ProjectService {

    private final CaseMapper caseMapper;
    private final ProjectPrincipleMapper projectPrincipleMapper;
    private final ArchitecturePrincipleMapper principleMapper;
    /** T22：里程碑达成判定（isAchievableHint/plannedMilestoneIds）+ 依赖边数据源 */
    private final MilestoneService milestoneService;
    private final ProjectDependencyMapper projectDependencyMapper;

    @Override
    public ProjectDetailVo detail(Long id) {
        Project p = getById(id);
        if (p == null) {
            throw new BizException(404, "项目不存在: " + id);
        }
        ProjectDetailVo vo = new ProjectDetailVo();
        vo.setId(p.getId());
        vo.setProgramId(p.getProgramId());
        vo.setName(p.getName());
        vo.setDescription(p.getDescription());
        vo.setStatus(p.getStatus());
        vo.setPriority(p.getPriority());
        vo.setProgress(p.getProgress() == null ? 0 : p.getProgress());
        vo.setCaseTotal(p.getCaseTotal() == null ? 0 : p.getCaseTotal());
        vo.setCaseDone(p.getCaseDone() == null ? 0 : p.getCaseDone());
        vo.setCreateTime(p.getCreateTime());
        vo.setUpdateTime(p.getUpdateTime());
        vo.setPrinciples(loadPrinciples(id));
        // T22：达成提示 + 依赖区块（各自降级 null，不阻塞详情主渲染，PRD §6.3）
        vo.setAchievementHint(loadAchievementHint(id, vo.getCaseTotal(), vo.getCaseDone()));
        vo.setDependencies(loadDependencySection(id));
        return vo;
    }

    @Override
    public void deleteWithUnlinkCase(Long id) {
        Project p = getById(id);
        if (p == null) {
            throw new BizException(404, "项目不存在: " + id);
        }
        // 成员 Case 解除挂接（Case 保留，回落场景C；进度随项目行逻辑删，无需重算）
        caseMapper.update(null, new LambdaUpdateWrapper<Case>()
                .eq(Case::getProjectId, id)
                .set(Case::getProjectId, null));
        // 原则绑定行随项目删除清理（关联表双向清理语义，DBA D2）
        projectPrincipleMapper.delete(new LambdaQueryWrapper<ProjectPrinciple>()
                .eq(ProjectPrinciple::getProjectId, id));
        // T22（AC-F3.5）：依赖边联动逻辑删——from=id 或 to=id 的全部边（同 t_project_principle
        // 先例）。uk 不含删除位 → 同对重登走 C2 复活语义，删除可逆。
        projectDependencyMapper.delete(new LambdaQueryWrapper<ProjectDependency>()
                .eq(ProjectDependency::getFromProjectId, id)
                .or()
                .eq(ProjectDependency::getToProjectId, id));
        removeById(id);
    }

    // ==================== T22：达成提示与依赖区块（降级 null） ====================

    /**
     * 里程碑达成提示（AC-F2.4/F2.5）：eligible = 全部 Case 已完成（total&gt;0 且 done==total）
     * 且存在 planned 里程碑；否则 null（空项目/未全完成均不出提示条）。
     */
    private ProjectDetailVo.AchievementHint loadAchievementHint(Long projectId, int caseTotal, int caseDone) {
        try {
            if (caseTotal <= 0 || caseDone != caseTotal) {
                return null;   // AC-F2.5：空项目与未全完成不出现提示条
            }
            List<Long> plannedIds = milestoneService.plannedMilestoneIds(projectId);
            if (plannedIds.isEmpty()) {
                return null;
            }
            ProjectDetailVo.AchievementHint hint = new ProjectDetailVo.AchievementHint();
            hint.setEligible(true);
            hint.setMilestoneIds(plannedIds);
            hint.setMessage("项目全部 Case 已完成，可达成里程碑");
            return hint;
        } catch (Exception e) {
            log.warn("[Project] achievementHint 计算失败（降级 null 不阻塞详情）projectId={}", projectId, e);
            return null;
        }
    }

    /**
     * 项目详情依赖区块（AC-F3.5 展示入口）：waitingFor（from=本项目）/responsibleFor
     * （to=本项目）两组边；对方项目已删除的边过滤（批查 ∩ 存活项目）。
     */
    private ProjectDetailVo.DependencySection loadDependencySection(Long projectId) {
        try {
            List<ProjectDependency> edges = projectDependencyMapper.selectList(
                    new LambdaQueryWrapper<ProjectDependency>()
                            .eq(ProjectDependency::getFromProjectId, projectId)
                            .or()
                            .eq(ProjectDependency::getToProjectId, projectId));
            if (edges.isEmpty()) {
                ProjectDetailVo.DependencySection section = new ProjectDetailVo.DependencySection();
                section.setWaitingFor(List.of());
                section.setResponsibleFor(List.of());
                return section;
            }
            // 对端存活项目名（selectBatchIds 自动过滤逻辑删——对方已删的边据此过滤）
            Set<Long> peerIds = new LinkedHashSet<>();
            edges.forEach(e -> peerIds.add(e.getToProjectId()));
            edges.forEach(e -> peerIds.add(e.getFromProjectId()));
            peerIds.remove(projectId);
            Map<Long, Project> peers = peerIds.isEmpty() ? Map.of()
                    : projectMapper().selectBatchIds(peerIds).stream()
                            .collect(Collectors.toMap(Project::getId, Function.identity()));
            List<DependencyBoardVo.EdgeItem> waitingFor = new ArrayList<>();
            List<DependencyBoardVo.EdgeItem> responsibleFor = new ArrayList<>();
            for (ProjectDependency e : edges) {
                Long peerId = projectId.equals(e.getFromProjectId()) ? e.getToProjectId() : e.getFromProjectId();
                Project peer = peers.get(peerId);
                if (peer == null) {
                    continue;   // 对方项目已删除的边不出现（api-contracts §6）
                }
                DependencyBoardVo.EdgeItem item = toEdgeItem(e, peer);
                if (projectId.equals(e.getFromProjectId())) {
                    waitingFor.add(item);
                } else {
                    responsibleFor.add(item);
                }
            }
            ProjectDetailVo.DependencySection section = new ProjectDetailVo.DependencySection();
            section.setWaitingFor(waitingFor);
            section.setResponsibleFor(responsibleFor);
            return section;
        } catch (Exception e) {
            log.warn("[Project] 依赖区块计算失败（降级 null 不阻塞详情）projectId={}", projectId, e);
            return null;
        }
    }

    /** 边 → 区块条目（结构同 DependencyBoardVo.EdgeItem；origType 由 note 解析还原，C1）。 */
    private static DependencyBoardVo.EdgeItem toEdgeItem(ProjectDependency e, Project peer) {
        DependencyBoardVo.EdgeItem item = new DependencyBoardVo.EdgeItem();
        item.setEdgeId(e.getId());
        item.setToProjectId(peer.getId());
        item.setToProjectName(peer.getName());
        item.setDependencyType(e.getDependencyType());
        String origType = DependencyService.parseOrigType(e.getDependencyType(), e.getNote());
        item.setOrigType(origType);
        item.setDisplayName("blocks".equals(origType) ? "受阻" : "依赖");   // 与看板 toEdgeItem 同口径
        item.setRemark(DependencyService.parseRemark(e.getNote()));
        return item;
    }

    // ==================== 既有详情聚合 ====================

    /** 项目已绑定原则清单：绑定行 ∩ 原则未删（selectBatchIds 自动过滤逻辑删），展示当前启停态 */
    private List<ProjectDetailVo.PrincipleItem> loadPrinciples(Long projectId) {
        List<ProjectPrinciple> bindings = projectPrincipleMapper.selectList(
                new LambdaQueryWrapper<ProjectPrinciple>().eq(ProjectPrinciple::getProjectId, projectId));
        if (bindings.isEmpty()) {
            return List.of();
        }
        List<Long> principleIds = bindings.stream().map(ProjectPrinciple::getPrincipleId).toList();
        Map<Long, ArchitecturePrinciple> principles = principleMapper.selectBatchIds(principleIds)
                .stream().collect(Collectors.toMap(ArchitecturePrinciple::getId, Function.identity()));
        return bindings.stream()
                .filter(b -> principles.containsKey(b.getPrincipleId()))
                .map(b -> {
                    ArchitecturePrinciple ap = principles.get(b.getPrincipleId());
                    ProjectDetailVo.PrincipleItem item = new ProjectDetailVo.PrincipleItem();
                    item.setId(ap.getId());
                    item.setCode(ap.getCode());
                    item.setTitle(ap.getTitle());
                    item.setEnforceLevel(ap.getEnforceLevel());
                    item.setEnabled(ap.getEnabled() != null && ap.getEnabled() == 1);
                    item.setProjectEnabled(b.getEnabled() == null || b.getEnabled() == 1);
                    return item;
                })
                .toList();
    }

    /** ServiceImpl.baseMapper（ProjectMapper）访问（selectBatchIds 批查对端项目）。 */
    private ProjectMapper projectMapper() {
        return baseMapper;
    }
}
