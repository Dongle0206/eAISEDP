package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.data.entity.Case;
import com.eaiselp.data.mapper.CaseMapper;
import com.eaiselp.runtime.hierarchy.dto.ProjectDetailVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 项目服务实现（批4 扩展：详情聚合含原则清单 / 删除解除 Case 挂接）。
 */
@Service
@RequiredArgsConstructor
public class ProjectServiceImpl extends ServiceImpl<ProjectMapper, Project> implements ProjectService {

    private final CaseMapper caseMapper;
    private final ProjectPrincipleMapper projectPrincipleMapper;
    private final ArchitecturePrincipleMapper principleMapper;

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
        removeById(id);
    }

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
}
