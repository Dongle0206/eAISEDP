package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.common.security.JwtClaims;
import com.eaiselp.common.security.LoginUser;
import com.eaiselp.common.tenant.TenantContext;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.runtime.hierarchy.dto.DependencyBoardVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 跨项目依赖服务实现（V5 F3，case-20260818 T11）。
 *
 * <p><b>收敛点落地（tasks.md §0）</b>：
 * <ul>
 *   <li><b>C1 归一化</b>：blocks 换向存 from=被阻塞方、dependency_type=depends_on；
 *       原始类型由 note 前缀 {@code [orig:blocks]} 承载，读取经
 *       {@link DependencyService#parseOrigType} 还原（失败默认 depends_on 文案）。</li>
 *   <li><b>C2 复活语义</b>：uk 四列不含删除位 → 删后重登撞 DuplicateKey 时走
 *       {@link ProjectDependencyMapper#selectDeletedEdge}（自定义 SQL 绕过 @TableLogic
 *       select 过滤）查逻辑删行，命中 UPDATE 复活（is_deleted=0、刷新 note/update_by，
 *       审计 detail 标 revived:true）；无命中才 400 唯一冲突（附既有活跃行 id）。</li>
 *   <li><b>C5 错误码</b>：环检测拒绝=400（非 409），文案携带成环路径 + WARN 日志留痕。</li>
 * </ul>
 *
 * <p><b>blocked 判定</b>（展示层实时不落库）：一次查全部活跃边 + 涉项目状态快照，
 * 内存判定强依赖对端 status∉{delivered,closed}；relates_to 不进任何判定（AC-F3.4）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DependencyServiceImpl extends ServiceImpl<ProjectDependencyMapper, ProjectDependency>
        implements DependencyService {

    private final ProjectMapper projectMapper;
    private final AuditService auditService;

    /** 入参依赖类型三值（P6 领域字典；blocks 仅入参存在，落库归一 depends_on） */
    private static final Set<String> INPUT_TYPES = Set.of("blocks", "depends_on", "relates_to");

    /** 强依赖存储值（blocked 判定与环检测的唯一谓词） */
    private static final String STRONG = "depends_on";

    /** 被依赖项目视为"未交付"的状态之外集合（∉ 即阻塞） */
    private static final Set<String> DELIVERED_LIKE = Set.of("delivered", "closed");

    private static final com.fasterxml.jackson.databind.ObjectMapper OM =
            new com.fasterxml.jackson.databind.ObjectMapper();

    // ==================== 登记（归一化 + 环预检 + 复活） ====================

    @Override
    public ProjectDependency register(Long fromProjectId, Long toProjectId, String dependencyType, String remark) {
        validateInput(fromProjectId, toProjectId, dependencyType);
        requireProject(fromProjectId);
        requireProject(toProjectId);

        // C1 归一化：blocks 换向（"A 阻塞 B" = B 依赖 A）+ note 前缀承载原始类型
        boolean origBlocks = "blocks".equals(dependencyType);
        Long storedFrom = origBlocks ? toProjectId : fromProjectId;
        Long storedTo = origBlocks ? fromProjectId : toProjectId;
        String storedType = origBlocks ? STRONG : dependencyType;
        String storedNote = origBlocks ? "[orig:blocks]" + (remark == null ? "" : remark) : remark;

        // 环预检（仅强边；relates_to 豁免不进图，AC-F3.4）
        if (STRONG.equals(storedType)) {
            precheckCycle(storedFrom, storedTo);
        }

        ProjectDependency edge = new ProjectDependency();
        edge.setFromProjectId(storedFrom);
        edge.setToProjectId(storedTo);
        edge.setDependencyType(storedType);
        edge.setNote(storedNote);
        try {
            save(edge);
        } catch (DuplicateKeyException e) {
            // C2 复活语义：uk 不含删除位 → 优先查同 (tenant,from,to,type) 逻辑删行复活
            return reviveOrConflict(storedFrom, storedTo, storedType, storedNote);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("origType", dependencyType);
        detail.put("normalizedFrom", storedFrom);
        detail.put("normalizedTo", storedTo);
        detail.put("fromProjectId", fromProjectId);
        detail.put("toProjectId", toProjectId);
        detail.put("dependencyType", storedType);
        audit("dependency_create", edge.getId(), detail);
        return edge;
    }

    /** C2：DuplicateKey 后的复活/冲突分叉——复活成功返回复用 id 的边，否则 400 指名既有 id。 */
    private ProjectDependency reviveOrConflict(Long storedFrom, Long storedTo, String storedType, String storedNote) {
        Long tenantId = TenantContext.get();
        ProjectDependency deleted = baseMapper.selectDeletedEdge(tenantId, storedFrom, storedTo, storedType);
        if (deleted != null) {
            String operator = resolveOperator();
            int updated = baseMapper.reviveEdge(tenantId, storedFrom, storedTo, storedType, storedNote, operator);
            if (updated > 0) {
                ProjectDependency revived = getById(deleted.getId());
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("revived", true);
                detail.put("fromProjectId", storedFrom);
                detail.put("toProjectId", storedTo);
                detail.put("dependencyType", storedType);
                log.info("[Dependency] 逻辑删行复活 id={} from={} to={} type={}",
                        deleted.getId(), storedFrom, storedTo, storedType);
                audit("dependency_create", revived == null ? deleted.getId() : revived.getId(), detail);
                return revived != null ? revived : deleted;
            }
            // 并发已复活（update 影响行数 0）：按活行冲突处理，下落
        }
        ProjectDependency active = list(new LambdaQueryWrapper<ProjectDependency>()
                .eq(ProjectDependency::getFromProjectId, storedFrom)
                .eq(ProjectDependency::getToProjectId, storedTo)
                .eq(ProjectDependency::getDependencyType, storedType))
                .stream().findFirst().orElse(null);
        throw new BizException(400, "同对项目同类型依赖已存在（blocks 与 depends_on 同向视为同一强依赖）：id="
                + (active != null ? active.getId() : "未知"));
    }

    @Override
    public ProjectDependency edit(Long id, String dependencyType, String remark) {
        ProjectDependency exist = loadOr404(id);
        if (dependencyType == null || !INPUT_TYPES.contains(dependencyType)) {
            throw new BizException(400, "dependencyType 非法，应为 blocks/depends_on/relates_to");
        }
        boolean origBlocks = "blocks".equals(dependencyType);
        String storedType = origBlocks ? STRONG : dependencyType;
        String storedNote = origBlocks ? "[orig:blocks]" + (remark == null ? "" : remark) : remark;
        // 类型变化到强边时同样过环预检（方向不可改，弱→强可能闭合成环）
        if (STRONG.equals(storedType) && !STRONG.equals(exist.getDependencyType())) {
            precheckCycle(exist.getFromProjectId(), exist.getToProjectId());
        }
        update(new LambdaUpdateWrapper<ProjectDependency>()
                .eq(ProjectDependency::getId, id)
                .set(ProjectDependency::getDependencyType, storedType)
                .set(ProjectDependency::getNote, storedNote));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("fromType", exist.getDependencyType());
        detail.put("toType", storedType);
        detail.put("origType", dependencyType);
        audit("dependency_update", id, detail);
        return getById(id);
    }

    @Override
    public void remove(Long id) {
        ProjectDependency exist = loadOr404(id);
        removeById(id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("fromProjectId", exist.getFromProjectId());
        detail.put("toProjectId", exist.getToProjectId());
        detail.put("dependencyType", exist.getDependencyType());
        audit("dependency_delete", id, detail);
    }

    @Override
    public ProjectDependency loadOr404(Long id) {
        ProjectDependency edge = getById(id);
        if (edge == null) {
            throw new BizException(404, "依赖不存在: " + id);
        }
        return edge;
    }

    @Override
    public IPage<ProjectDependency> pageEdges(Long projectId, String type, long page, long size) {
        LambdaQueryWrapper<ProjectDependency> w = new LambdaQueryWrapper<ProjectDependency>()
                .eq(type != null && !type.isBlank(), ProjectDependency::getDependencyType, type)
                .orderByDesc(ProjectDependency::getCreateTime);
        if (projectId != null) {
            // 作为 from 或 to 命中均返回（行内方向字段自明）；标准 or 查询两条等值（拦截器友好）
            w.and(q -> q.eq(ProjectDependency::getFromProjectId, projectId)
                    .or()
                    .eq(ProjectDependency::getToProjectId, projectId));
        }
        return page(new Page<>(page, size), w);
    }

    // ==================== blocked 看板（展示层实时，不落库） ====================

    @Override
    public DependencyBoardVo board() {
        List<ProjectDependency> edges = list();
        DependencyBoardVo vo = new DependencyBoardVo();
        DependencyBoardVo.Stats stats = new DependencyBoardVo.Stats();
        stats.setEdgeCount(edges.size());
        if (edges.isEmpty()) {
            stats.setTotalProjects(0);
            stats.setBlockedCount(0);
            vo.setStats(stats);
            vo.setProjects(List.of());
            return vo;
        }
        // 涉项目状态快照（一次批查，防 N+1）
        Set<Long> projectIds = new LinkedHashSet<>();
        edges.forEach(e -> {
            projectIds.add(e.getFromProjectId());
            projectIds.add(e.getToProjectId());
        });
        Map<Long, Project> projects = projectMapper.selectBatchIds(projectIds).stream()
                .collect(Collectors.toMap(Project::getId, p -> p));

        Map<Long, List<ProjectDependency>> waitingByFrom = edges.stream()
                .collect(Collectors.groupingBy(ProjectDependency::getFromProjectId));
        Map<Long, List<ProjectDependency>> responsibleByTo = edges.stream()
                .collect(Collectors.groupingBy(ProjectDependency::getToProjectId));

        List<DependencyBoardVo.ProjectCard> cards = new ArrayList<>();
        int blockedCount = 0;
        for (Long pid : projectIds.stream().sorted().toList()) {
            Project p = projects.get(pid);
            DependencyBoardVo.ProjectCard card = new DependencyBoardVo.ProjectCard();
            card.setProjectId(pid);
            card.setProjectName(p != null ? p.getName() : "未知项目");
            card.setStatus(p != null ? p.getStatus() : null);
            List<String> blockedSources = new ArrayList<>();
            for (ProjectDependency e : waitingByFrom.getOrDefault(pid, List.of())) {
                if (!STRONG.equals(e.getDependencyType())) {
                    continue;   // relates_to 不参与判定（AC-F3.4）
                }
                Project upstream = projects.get(e.getToProjectId());
                String upstreamStatus = upstream != null ? upstream.getStatus() : null;
                if (upstreamStatus == null || !DELIVERED_LIKE.contains(upstreamStatus)) {
                    String upstreamName = upstream != null ? upstream.getName() : String.valueOf(e.getToProjectId());
                    blockedSources.add("被 " + upstreamName + " 阻塞：" + upstreamName + " 未交付");
                }
            }
            card.setBlocked(!blockedSources.isEmpty());
            card.setBlockedSources(blockedSources);
            if (!blockedSources.isEmpty()) {
                blockedCount++;
            }
            card.setWaitingFor(waitingByFrom.getOrDefault(pid, List.of()).stream()
                    .map(e -> toEdgeItem(e, projects, true)).toList());
            card.setResponsibleFor(responsibleByTo.getOrDefault(pid, List.of()).stream()
                    .map(e -> toEdgeItem(e, projects, false)).toList());
            cards.add(card);
        }
        stats.setTotalProjects(projectIds.size());
        stats.setBlockedCount(blockedCount);
        vo.setStats(stats);
        vo.setProjects(cards);
        return vo;
    }

    /** 边→看板条目（origType 由 note 解析还原，C1；对端已物理缺失时名称降退 id 展示）。 */
    private DependencyBoardVo.EdgeItem toEdgeItem(ProjectDependency e, Map<Long, Project> projects, boolean waiting) {
        DependencyBoardVo.EdgeItem item = new DependencyBoardVo.EdgeItem();
        item.setEdgeId(e.getId());
        Long peerId = waiting ? e.getToProjectId() : e.getFromProjectId();
        Project peer = projects.get(peerId);
        item.setToProjectId(peerId);
        item.setToProjectName(peer != null ? peer.getName() : String.valueOf(peerId));
        item.setDependencyType(e.getDependencyType());
        String origType = DependencyService.parseOrigType(e.getDependencyType(), e.getNote());
        item.setOrigType(origType);
        item.setDisplayName("blocks".equals(origType) ? "受阻" : "依赖");
        item.setRemark(DependencyService.parseRemark(e.getNote()));   // 剥离 [orig:blocks] 存储前缀
        return item;
    }

    // ==================== 环检测入口 ====================

    @Override
    public CycleCheckVo cycleCheck(Long from, Long to) {
        if (from == null || to == null) {
            throw new BizException(400, "from/to 不能为空");
        }
        if (from.equals(to)) {
            return new CycleCheckVo(true, List.of(from, from), from + "→" + from);
        }
        try {
            List<long[]> strongEdges = strongEdges();
            return DependencyCycleDetector.wouldCycle(strongEdges, from, to)
                    .map(path -> new CycleCheckVo(true, path, pathDisplay(path)))
                    .orElseGet(() -> new CycleCheckVo(false, List.of(), null));
        } catch (DependencyCycleDetector.GraphSizeExceededException e) {
            throw new BizException(400, e.getMessage());
        }
    }

    @Override
    public FullCheckVo fullCheck() {
        try {
            List<List<Long>> cycles = DependencyCycleDetector.findCycles(strongEdges());
            return new FullCheckVo(cycles.size(), cycles.stream()
                    .map(path -> new FullCheckVo.CyclePath(path, pathDisplay(path)))
                    .toList());
        } catch (DependencyCycleDetector.GraphSizeExceededException e) {
            throw new BizException(400, e.getMessage());
        }
    }

    /** 当前活跃强边集合（relates_to 豁免不进图）。 */
    private List<long[]> strongEdges() {
        return list(new LambdaQueryWrapper<ProjectDependency>()
                        .select(ProjectDependency::getFromProjectId, ProjectDependency::getToProjectId)
                        .eq(ProjectDependency::getDependencyType, STRONG))
                .stream()
                .filter(e -> e.getFromProjectId() != null && e.getToProjectId() != null)
                .map(e -> new long[]{e.getFromProjectId(), e.getToProjectId()})
                .toList();
    }

    /** 登记前环预检：成环 → 400 带路径（C5）+ WARN 日志（含路径，PRD §6.6 观测点）。 */
    private void precheckCycle(Long storedFrom, Long storedTo) {
        DependencyCycleDetector.wouldCycle(strongEdges(), storedFrom, storedTo).ifPresent(path -> {
            String display = pathDisplay(path);
            log.warn("[Dependency] 依赖成环拒绝登记: from={}, to={}, 路径={}", storedFrom, storedTo, display);
            throw new BizException(400, "依赖成环，禁止登记：" + display);
        });
    }

    /** 路径展示文案：id→id→id（项目名映射由 Controller/前端层补充，Service 层以 id 表达）。 */
    private static String pathDisplay(List<Long> path) {
        return path.stream().map(String::valueOf).collect(Collectors.joining("→"));
    }

    // ==================== 校验与工具 ====================

    private void validateInput(Long fromProjectId, Long toProjectId, String dependencyType) {
        if (dependencyType == null || !INPUT_TYPES.contains(dependencyType)) {
            throw new BizException(400, "dependencyType 非法，应为 blocks/depends_on/relates_to");
        }
        if (fromProjectId == null || toProjectId == null) {
            throw new BizException(400, "fromProjectId/toProjectId 不能为空");
        }
        if (fromProjectId.equals(toProjectId)) {
            throw new BizException(400, "禁止自依赖：from 与 to 不能相同");
        }
    }

    private void requireProject(Long projectId) {
        if (projectMapper.selectById(projectId) == null) {
            throw new BizException(404, "项目不存在: " + projectId);
        }
    }

    /** 从 JWT claims 解析操作人（防伪造，ES-003 §9.3 G13；无上下文回退 anonymous）。 */
    private static String resolveOperator() {
        JwtClaims claims = LoginUser.get();
        if (claims != null && claims.getUsername() != null) {
            return claims.getUsername();
        }
        Long uid = LoginUser.getUserId();
        return uid != null ? String.valueOf(uid) : "anonymous";
    }

    private void audit(String action, Long id, Object detail) {
        String json;
        try {
            json = OM.writeValueAsString(detail);
        } catch (Exception e) {
            json = "{}";
        }
        auditService.log(action, "dependency", String.valueOf(id), json);
    }
}
