package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.runtime.hierarchy.DependencyService;
import com.eaiselp.runtime.hierarchy.Project;
import com.eaiselp.runtime.hierarchy.ProjectDependency;
import com.eaiselp.runtime.hierarchy.ProjectService;
import com.eaiselp.runtime.hierarchy.dto.DependencyBoardVo;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 跨项目依赖管理 REST API（L2 层，case-20260818 T16，路径前缀
 * /api/v1/project-dependencies，契约=api-contracts §3）。
 *
 * <p>薄控制器：归一化创建（blocks 换向+note 前缀）/环预检 400 带路径/复活语义/blocked
 * 看板聚合全在 {@link DependencyService}（批A T11）。<b>批A 交接点</b>：环路径
 * id→项目名映射在本层做（Service 层以 id 表达，Controller 批查 t_project 换名拼装）。</p>
 *
 * <p>权限（V5 seed 1049~1051）：读 {@code dependency:view}、建 {@code dependency:create}、
 * 改/删 {@code dependency:edit}（C7：细粒度"依赖双方项目经理"不可判定，按 seed 口径）。
 * 写审计（dependency_create/update/delete 含复活 revived:true）在 Service。L2 关闭 →
 * LayerGuardInterceptor 43002（T20）。</p>
 */
@RestController
@RequestMapping("/api/v1/project-dependencies")
@RequiredArgsConstructor
public class DependencyController {

    /** 环拒绝文案前缀（与 DependencyServiceImpl.precheckCycle 一致，本层据此识别并换名） */
    private static final String CYCLE_REJECT_PREFIX = "依赖成环，禁止登记：";

    private final DependencyService dependencyService;
    private final ProjectService projectService;

    // ------------------------------------------------------------------
    // CRUD
    // ------------------------------------------------------------------

    /**
     * 登记依赖边（AC-F3.1/F3.3）：归一化在 Service；成环 400 的路径文案在本层
     * 由 id 换项目名（批A 交接点，C5=400 非 409）。
     */
    @PostMapping
    @RequirePermission("dependency:create")
    public R<EdgeVo> create(@RequestBody EdgeSaveRequest req) {
        try {
            ProjectDependency edge = dependencyService.register(
                    req.getFromProjectId(), req.getToProjectId(), req.getDependencyType(), req.getRemark());
            return R.ok(toVo(edge));
        } catch (BizException e) {
            return R.fail(e.getCode(), enrichCyclePathWithNames(e.getMessage()));
        }
    }

    /** 边列表分页（projectId 作为 from 或 to 命中均可返回，行内方向字段自明；type 过滤）。 */
    @GetMapping
    @RequirePermission("dependency:view")
    public R<IPage<EdgeVo>> page(@RequestParam(defaultValue = "1") long page,
                                 @RequestParam(defaultValue = "20") long size,
                                 @RequestParam(required = false) Long projectId,
                                 @RequestParam(required = false) String type) {
        IPage<ProjectDependency> edges = dependencyService.pageEdges(projectId, type, page, size);
        Map<Long, String> names = projectNames(collectIds(edges.getRecords()));
        return R.ok(edges.convert(e -> toVo(e, names)));
    }

    /** 编辑边（dependencyType/remark；方向不可改——改向=删旧建新；改强边同样过环预检）。 */
    @PutMapping("/{id}")
    @RequirePermission("dependency:edit")
    public R<EdgeVo> update(@PathVariable Long id, @RequestBody EdgeSaveRequest req) {
        try {
            ProjectDependency edge = dependencyService.edit(id, req.getDependencyType(), req.getRemark());
            return R.ok(toVo(edge));
        } catch (BizException e) {
            return R.fail(e.getCode(), enrichCyclePathWithNames(e.getMessage()));
        }
    }

    /** 逻辑删（C7 seed 权限口径）。 */
    @DeleteMapping("/{id}")
    @RequirePermission("dependency:edit")
    public R<Void> delete(@PathVariable Long id) {
        dependencyService.remove(id);
        return R.ok();
    }

    // ------------------------------------------------------------------
    // 看板与环检测
    // ------------------------------------------------------------------

    /** blocked 看板（AC-F3.2/F3.4，展示层实时判定不落库；交付即自动解除）。 */
    @GetMapping("/board")
    @RequirePermission("dependency:view")
    public R<DependencyBoardVo> board() {
        return R.ok(dependencyService.board());
    }

    /**
     * 新边环预检（查询语义，成环也 200，前端登记表单实时提示用）；
     * pathDisplay 在本层换项目名（批A 交接点）。
     */
    @GetMapping("/cycle-check")
    @RequirePermission("dependency:view")
    public R<DependencyService.CycleCheckVo> cycleCheck(@RequestParam Long from, @RequestParam Long to) {
        DependencyService.CycleCheckVo vo = dependencyService.cycleCheck(from, to);
        if (vo.wouldCycle()) {
            return R.ok(new DependencyService.CycleCheckVo(true, vo.cyclePathIds(),
                    pathDisplay(vo.cyclePathIds())));
        }
        return R.ok(vo);
    }

    /** 全图体检（可见可治，正常返回空）；环路径同样换项目名展示。 */
    @GetMapping("/full-check")
    @RequirePermission("dependency:view")
    public R<DependencyService.FullCheckVo> fullCheck() {
        DependencyService.FullCheckVo vo = dependencyService.fullCheck();
        return R.ok(new DependencyService.FullCheckVo(vo.cycleCount(), vo.cycles().stream()
                .map(c -> new DependencyService.FullCheckVo.CyclePath(c.pathIds(), pathDisplay(c.pathIds())))
                .toList()));
    }

    // ------------------------------------------------------------------
    // 响应 DTO 与内部工具
    // ------------------------------------------------------------------

    /**
     * 边响应行（api-contracts §3：结构同 POST 响应 data）——归一化存储形态 + origType
     * 解析还原（C1）+ 双端项目名 + 展示名，统一依赖方视角。
     */
    @Data
    public static class EdgeVo {
        private Long id;
        /** 依赖方（blocks 录入换向后 = 被阻塞方） */
        private Long fromProjectId;
        private Long toProjectId;
        /** depends_on / relates_to（归一化存储值） */
        private String dependencyType;
        /** blocks / depends_on / relates_to（note 前缀解析还原） */
        private String origType;
        private String fromProjectName;
        private String toProjectName;
        /** "项目B → 项目A（硬阻塞）"式展示名 */
        private String displayName;
        /** 备注（API 语义名，V5 列 note） */
        private String remark;
        private java.time.LocalDateTime createTime;
    }

    /** 登记请求（入参三值 blocks/depends_on/relates_to；编辑时仅 type/remark 有效，方向不可改）。 */
    @Data
    public static class EdgeSaveRequest {
        private Long fromProjectId;
        private Long toProjectId;
        private String dependencyType;
        private String remark;
    }

    /** 实体 → 响应行（批查双端名）。 */
    private EdgeVo toVo(ProjectDependency e) {
        return toVo(e, projectNames(List.of(e.getFromProjectId(), e.getToProjectId())));
    }

    private static EdgeVo toVo(ProjectDependency e, Map<Long, String> names) {
        EdgeVo vo = new EdgeVo();
        vo.setId(e.getId());
        vo.setFromProjectId(e.getFromProjectId());
        vo.setToProjectId(e.getToProjectId());
        vo.setDependencyType(e.getDependencyType());
        String origType = DependencyService.parseOrigType(e.getDependencyType(), e.getNote());
        vo.setOrigType(origType);
        String fromName = names.getOrDefault(e.getFromProjectId(), String.valueOf(e.getFromProjectId()));
        String toName = names.getOrDefault(e.getToProjectId(), String.valueOf(e.getToProjectId()));
        vo.setFromProjectName(fromName);
        vo.setToProjectName(toName);
        String kind = "blocks".equals(origType) ? "硬阻塞"
                : "relates_to".equals(e.getDependencyType()) ? "关联" : "依赖";
        vo.setDisplayName(fromName + " → " + toName + "（" + kind + "）");
        vo.setRemark(DependencyService.parseRemark(e.getNote()));   // 剥离 [orig:blocks] 存储前缀
        vo.setCreateTime(e.getCreateTime());
        return vo;
    }

    /** 环拒绝 400 文案：路径段 id→项目名（批A 交接点；非环文案/解析失败原样返回）。 */
    private String enrichCyclePathWithNames(String message) {
        if (message == null || !message.startsWith(CYCLE_REJECT_PREFIX)) {
            return message;
        }
        String path = message.substring(CYCLE_REJECT_PREFIX.length());
        try {
            List<Long> ids = java.util.Arrays.stream(path.split("→"))
                    .map(String::trim).map(Long::parseLong).toList();
            return CYCLE_REJECT_PREFIX + pathDisplay(ids);
        } catch (Exception e) {
            return message;   // 非 id 路径（脏数据/并发形态）原样返回，不硬错
        }
    }

    /** id 路径 → 项目名路径（"项目A→项目B→项目A"；已删项目降退 id 展示）。 */
    private String pathDisplay(List<Long> ids) {
        Map<Long, String> names = projectNames(ids);
        return ids.stream()
                .map(id -> names.getOrDefault(id, String.valueOf(id)))
                .collect(Collectors.joining("→"));
    }

    /** 批查项目名（跨租户被拦截器过滤 → 查不到即降退 id；一次性批查防 N+1）。 */
    private Map<Long, String> projectNames(Collection<Long> ids) {
        List<Long> distinct = ids == null ? List.of()
                : ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return projectService.listByIds(distinct).stream()
                .filter(p -> p.getId() != null && p.getName() != null)
                .collect(Collectors.toMap(Project::getId, Project::getName, (a, b) -> a));
    }

    private static List<Long> collectIds(List<ProjectDependency> edges) {
        return edges.stream()
                .flatMap(e -> java.util.stream.Stream.of(e.getFromProjectId(), e.getToProjectId()))
                .filter(Objects::nonNull)
                .toList();
    }
}
