package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.runtime.hierarchy.dto.ProgramVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 项目群服务实现（批4 扩展：进度均值聚合 / 详情聚合 / 删除解除成员项目挂接）。
 */
@Service
@RequiredArgsConstructor
public class ProgramServiceImpl extends ServiceImpl<ProgramMapper, Program> implements ProgramService {

    private final ProjectMapper projectMapper;

    @Override
    public IPage<ProgramVo> pageWithProgress(long page, long size, String status, Long strategyId) {
        LambdaQueryWrapper<Program> wrapper = new LambdaQueryWrapper<Program>()
                .orderByDesc(Program::getId);
        if (status != null && !status.isEmpty()) {
            wrapper.eq(Program::getStatus, status);
        }
        if (strategyId != null) {
            wrapper.eq(Program::getStrategyId, strategyId);
        }
        IPage<Program> result = page(new Page<>(page, size), wrapper);
        Map<Long, List<Project>> byProgram = loadMembersBatch(result.getRecords());
        return result.convert(p -> toVo(p, byProgram.getOrDefault(p.getId(), List.of()), false));
    }

    @Override
    public ProgramVo detail(Long id) {
        Program p = getById(id);
        if (p == null) {
            throw new BizException(404, "项目群不存在: " + id);
        }
        List<Project> members = projectMapper.selectList(new LambdaQueryWrapper<Project>()
                .eq(Project::getProgramId, id)
                .orderByAsc(Project::getPriority)
                .orderByAsc(Project::getId));
        return toVo(p, members, true);
    }

    @Override
    public void deleteWithUnlink(Long id) {
        Program p = getById(id);
        if (p == null) {
            throw new BizException(404, "项目群不存在: " + id);
        }
        // AC-F2.3：成员项目 program_id 置空（项目保留、其 Case 与进度不受影响），再逻辑删本群
        projectMapper.update(null, new LambdaUpdateWrapper<Project>()
                .eq(Project::getProgramId, id)
                .set(Project::getProgramId, null));
        removeById(id);
    }

    /** 页内项目群 → 成员项目批量加载（单条 IN 查询，防 N+1，SE §11 R11） */
    private Map<Long, List<Project>> loadMembersBatch(List<Program> programs) {
        if (programs == null || programs.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = programs.stream().map(Program::getId).toList();
        return projectMapper.selectList(new LambdaQueryWrapper<Project>()
                        .in(Project::getProgramId, ids))
                .stream().collect(Collectors.groupingBy(Project::getProgramId));
    }

    /** Program → ProgramVo（withProjects=false 为列表形态，projects 置 null） */
    private ProgramVo toVo(Program p, List<Project> members, boolean withProjects) {
        ProgramVo vo = new ProgramVo();
        vo.setId(p.getId());
        vo.setStrategyId(p.getStrategyId());
        vo.setName(p.getName());
        vo.setCharter(p.getCharter());
        vo.setStatus(p.getStatus());
        vo.setStartDate(p.getStartDate());
        vo.setEndDate(p.getEndDate());
        vo.setPgmManager(p.getPgmManager());
        vo.setCreateTime(p.getCreateTime());
        vo.setUpdateTime(p.getUpdateTime());
        vo.setProjectCount(members.size());
        vo.setAvgProgress(avgProgress(members));
        if (withProjects) {
            vo.setProjects(members.stream().map(this::toItem).toList());
        }
        return vo;
    }

    private ProgramVo.ProjectItem toItem(Project prj) {
        ProgramVo.ProjectItem item = new ProgramVo.ProjectItem();
        item.setId(prj.getId());
        item.setName(prj.getName());
        item.setStatus(prj.getStatus());
        item.setPriority(prj.getPriority());
        item.setProgress(prj.getProgress() == null ? 0 : prj.getProgress());
        item.setCaseTotal(prj.getCaseTotal() == null ? 0 : prj.getCaseTotal());
        item.setCaseDone(prj.getCaseDone() == null ? 0 : prj.getCaseDone());
        return item;
    }

    /** 群内进度均值 ⌊Σprogress/n⌋（AC-F8.5；null 按 0 计，无成员为 0） */
    private int avgProgress(List<Project> members) {
        if (members.isEmpty()) {
            return 0;
        }
        int sum = members.stream()
                .mapToInt(p -> p.getProgress() == null ? 0 : p.getProgress())
                .sum();
        return (int) Math.floor(sum / (double) members.size());
    }
}
