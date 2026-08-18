package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.runtime.hierarchy.dto.BoundProjectVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 架构原则服务实现（批4 扩展：code 唯一校验 / 原则维度项目绑定管理 / 删除清绑定）。
 */
@Service
@RequiredArgsConstructor
public class PrincipleServiceImpl
        extends ServiceImpl<ArchitecturePrincipleMapper, ArchitecturePrinciple> implements PrincipleService {

    private final ProjectPrincipleMapper projectPrincipleMapper;
    private final ProjectMapper projectMapper;

    @Override
    public void checkCodeAvailable(String code, Long excludeId) {
        Long count = count(new LambdaQueryWrapper<ArchitecturePrinciple>()
                .eq(ArchitecturePrinciple::getCode, code)
                .ne(excludeId != null, ArchitecturePrinciple::getId, excludeId));
        if (count != null && count > 0) {
            // AC-F5.1：uk_principle_code 冲突转业务码 409
            throw new BizException(409, "原则编号已存在: " + code + "（租户内唯一）");
        }
    }

    @Override
    public List<BoundProjectVo> boundProjects(Long principleId) {
        ArchitecturePrinciple ap = getById(principleId);
        if (ap == null) {
            throw new BizException(404, "原则不存在: " + principleId);
        }
        List<ProjectPrinciple> bindings = projectPrincipleMapper.selectList(
                new LambdaQueryWrapper<ProjectPrinciple>()
                        .eq(ProjectPrinciple::getPrincipleId, principleId));
        if (bindings.isEmpty()) {
            return List.of();
        }
        List<Long> projectIds = bindings.stream().map(ProjectPrinciple::getProjectId).toList();
        // 项目已删的绑定行为脏数据（正常路径删除项目时已清理），展示时静默跳过
        Map<Long, Project> projects = projectMapper.selectBatchIds(projectIds).stream()
                .collect(Collectors.toMap(Project::getId, Function.identity()));
        List<BoundProjectVo> result = new ArrayList<>();
        for (ProjectPrinciple b : bindings) {
            Project p = projects.get(b.getProjectId());
            if (p == null) {
                continue;
            }
            BoundProjectVo vo = new BoundProjectVo();
            vo.setProjectId(p.getId());
            vo.setProjectName(p.getName());
            vo.setEnabled(b.getEnabled() == null || b.getEnabled() == 1);
            result.add(vo);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindProjects(Long principleId, List<Long> projectIds) {
        ArchitecturePrinciple ap = getById(principleId);
        if (ap == null) {
            throw new BizException(404, "原则不存在: " + principleId);
        }
        // 去重保序（重复 ID 幂等）；校验目标项目存在（跨租户被拦截器过滤 → 404 不泄露存在性）
        Set<Long> target = projectIds == null ? Set.of() : new LinkedHashSet<>(projectIds);
        if (!target.isEmpty()) {
            Long found = projectMapper.selectCount(new LambdaQueryWrapper<Project>()
                    .in(Project::getId, target));
            if (found == null || found != target.size()) {
                throw new BizException(404, "存在无效或不属于本租户的项目，绑定被拒绝");
            }
        }
        // 全量覆盖 = 删旧插新（uk_project_principle 幂等，T09/T10 契约）
        projectPrincipleMapper.delete(new LambdaQueryWrapper<ProjectPrinciple>()
                .eq(ProjectPrinciple::getPrincipleId, principleId));
        for (Long pid : target) {
            ProjectPrinciple pp = new ProjectPrinciple();
            pp.setProjectId(pid);
            pp.setPrincipleId(principleId);
            pp.setEnabled(1);   // 项目级覆盖位默认启用（DBA D2 契约）
            projectPrincipleMapper.insert(pp);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteWithCleanup(Long id) {
        ArchitecturePrinciple ap = getById(id);
        if (ap == null) {
            throw new BizException(404, "原则不存在: " + id);
        }
        // 反向清理绑定（WHERE principle_id=?，关联表方案的意义所在，DBA D2）
        projectPrincipleMapper.delete(new LambdaQueryWrapper<ProjectPrinciple>()
                .eq(ProjectPrinciple::getPrincipleId, id));
        removeById(id);
    }
}
