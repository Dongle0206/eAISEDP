package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.runtime.hierarchy.dto.StrategyBoardVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 战略目标服务实现（批4 扩展：生命周期流转 / 关联拒删 / 看板聚合）。
 *
 * <p>聚合形态遵守 SE 经验沉淀 3 编码规约：标准 MP 查询 + 内存聚合，
 * 不写子查询/自定义 SQL（租户拦截器友好，R4）。</p>
 */
@Service
@RequiredArgsConstructor
public class StrategyServiceImpl extends ServiceImpl<StrategyMapper, Strategy> implements StrategyService {

    /** 合法生命周期路径（AC-F1.2：draft→active→achieved/archived；achieved→archived 收尾归档） */
    private static final Set<String> ALLOWED_TRANSITS = Set.of(
            "draft>active", "active>achieved", "active>archived", "achieved>archived");

    private final ProgramMapper programMapper;
    private final ProjectMapper projectMapper;

    @Override
    public Strategy transit(Long id, String targetStatus) {
        Strategy s = getById(id);
        if (s == null) {
            throw new BizException(404, "战略不存在: " + id);
        }
        if (targetStatus == null || !ALLOWED_TRANSITS.contains(s.getStatus() + ">" + targetStatus)) {
            throw new BizException(400, "非法生命周期流转: " + s.getStatus() + " → " + targetStatus
                    + "（合法路径：draft→active→achieved/archived）");
        }
        s.setStatus(targetStatus);
        updateById(s);
        return s;
    }

    @Override
    public void deleteWithProgramCheck(Long id) {
        Strategy s = getById(id);
        if (s == null) {
            throw new BizException(404, "战略不存在: " + id);
        }
        Long linked = programMapper.selectCount(new LambdaQueryWrapper<Program>()
                .eq(Program::getStrategyId, id));
        if (linked != null && linked > 0) {
            // AC-F1.3 文案一字不差（QA 断言锚点）
            throw new BizException(400, "存在关联项目群，请先解除关联");
        }
        removeById(id);
    }

    @Override
    public StrategyBoardVo board(Long id) {
        Strategy s = getById(id);
        if (s == null) {
            throw new BizException(404, "战略不存在: " + id);
        }
        StrategyBoardVo vo = new StrategyBoardVo();
        vo.setId(s.getId());
        vo.setTitle(s.getTitle());
        vo.setDescription(s.getDescription());
        vo.setStatus(s.getStatus());
        vo.setHorizon(s.getHorizon());
        vo.setOwner(s.getOwner());
        vo.setKpi(s.getKpi());

        List<Program> programs = programMapper.selectList(new LambdaQueryWrapper<Program>()
                .eq(Program::getStrategyId, id)
                .orderByAsc(Program::getId));
        List<StrategyBoardVo.ProgramItem> items = new ArrayList<>();
        if (!programs.isEmpty()) {
            // 单条批查全部成员项目，内存按群分组（防 N+1，R11）
            List<Long> programIds = programs.stream().map(Program::getId).toList();
            Map<Long, List<Project>> byProgram = projectMapper.selectList(
                            new LambdaQueryWrapper<Project>().in(Project::getProgramId, programIds))
                    .stream().collect(Collectors.groupingBy(Project::getProgramId));
            int sum = 0;
            for (Program p : programs) {
                List<Project> members = byProgram.getOrDefault(p.getId(), List.of());
                StrategyBoardVo.ProgramItem item = new StrategyBoardVo.ProgramItem();
                item.setId(p.getId());
                item.setName(p.getName());
                item.setStatus(p.getStatus());
                item.setProjectCount(members.size());
                int avg = avgProgress(members);
                item.setAvgProgress(avg);
                items.add(item);
                sum += avg;
            }
            vo.setAvgProgress((int) Math.floor(sum / (double) programs.size()));
        } else {
            vo.setAvgProgress(0);
        }
        vo.setPrograms(items);
        return vo;
    }

    /** 群内进度均值 ⌊Σprogress/n⌋（AC-F8.5；progress 为 null 的成员按 0 计，无成员为 0） */
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
