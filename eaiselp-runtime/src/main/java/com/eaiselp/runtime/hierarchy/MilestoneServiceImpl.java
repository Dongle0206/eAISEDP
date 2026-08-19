package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.data.entity.Milestone;
import com.eaiselp.data.mapper.MilestoneMapper;
import com.eaiselp.runtime.hierarchy.dto.MilestoneTimelineVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 里程碑服务实现（V5 F2 激活，case-20260818 T10）。
 *
 * <p><b>实现要点</b>：
 * <ul>
 *   <li><b>milestoneCode 生成</b>：租户内可见最大数字后缀 +1（MS-0001 形态），uk 冲突
 *       （逻辑删行占号不可见）时以已试后缀续推重试 3 次——"uk 兜底重试"语义（AC-F2.1）。</li>
 *   <li><b>legacy 两列不写</b>：programId/milestoneId 实体侧 updateStrategy=NEVER + 新建恒 null
 *       （MP NOT_NULL 插入策略不进 SQL），C8 收敛。</li>
 *   <li><b>撤销清空日期</b>：MP updateById 对 null 字段默认不更新——撤销/延期用
 *       LambdaUpdateWrapper 显式 set null（与 ProjectProgressService 显式 set 先例同构）。</li>
 *   <li><b>群聚合时间线</b>：群直属 + 成员项目两段标准查询后内存合并排序（不写跨 owner OR，
 *       两条查询各自命中 idx_ms_tenant_owner）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilestoneServiceImpl extends ServiceImpl<MilestoneMapper, Milestone> implements MilestoneService {

    private final ProjectMapper projectMapper;
    private final ProgramMapper programMapper;
    private final AuditService auditService;

    /** 审计/编号生成的 JSON 与文本工具（Jackson 静态单例，线程安全） */
    private static final com.fasterxml.jackson.databind.ObjectMapper OM =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @Override
    public Milestone create(Milestone ms) {
        validateForWrite(ms);
        // 状态机入口固定 planned（系统永不自动置 achieved/delayed，PRD §4.2.3）
        ms.setStatus(MilestoneStatus.PLANNED.dbValue());
        ms.setAchievedDate(null);
        // milestoneCode 服务端生成（uk 兜底重试 3 次）
        insertWithCodeRetry(ms);
        audit("milestone_create", ms, detail("create", null, ms.getStatus(),
                "owner", ms.getOwner(), "title", ms.getTitle()));
        return ms;
    }

    /**
     * 编辑里程碑（title/description/targetDate/owner/blocker/subprojects）。
     *
     * <p><b>S2 归属防挪窝</b>：ownerType/ownerId 不参与更新——请求携带与库值不一致的归属时
     * 400 拒绝（"里程碑归属不可变更"）；未携带（null）视为不修改，一律保持库值。防止持
     * {@code milestone:edit} 者把里程碑挪到越权/无关归属对象下。</p>
     */
    @Override
    public Milestone edit(Long id, Milestone patch) {
        Milestone exist = loadOr404(id);
        // S2：归属不可变更——先于 validateForWrite（其 owner 必填校验对"未携带"场景会误伤）
        if ((patch.getOwnerType() != null && !patch.getOwnerType().equals(exist.getOwnerType()))
                || (patch.getOwnerId() != null && !patch.getOwnerId().equals(exist.getOwnerId()))) {
            throw new BizException(400, "里程碑归属不可变更");
        }
        // 统一回填库值归属：后续校验与写入均沿用（请求缺省=不改，请求同值=幂等）
        patch.setOwnerType(exist.getOwnerType());
        patch.setOwnerId(exist.getOwnerId());
        validateForWrite(patch);
        Milestone next = new Milestone();
        next.setId(id);
        next.setOwnerType(exist.getOwnerType());
        next.setOwnerId(exist.getOwnerId());
        next.setTitle(patch.getTitle());
        next.setDescription(patch.getDescription());
        next.setTargetDate(patch.getTargetDate());
        next.setOwner(patch.getOwner());
        next.setBlocker(patch.getBlocker());
        next.setSubprojects(patch.getSubprojects());
        // status/achievedDate 不在此改（只走 transit）；legacy 两列不写（实体 updateStrategy=NEVER）
        updateById(next);
        audit("milestone_update", exist, detail("update", exist.getStatus(), exist.getStatus(),
                "owner", patch.getOwner(), "title", patch.getTitle()));
        return getById(id);
    }

    @Override
    public Milestone loadOr404(Long id) {
        Milestone ms = getById(id);
        if (ms == null) {
            throw new BizException(404, "里程碑不存在: " + id);
        }
        return ms;
    }

    @Override
    public Milestone transit(Long id, String target, LocalDate achievedDate) {
        MilestoneStatus to = MilestoneStatus.fromDbValue(target);
        if (to == null) {
            throw new BizException(400, "未知状态: " + target + "（合法值: planned/achieved/delayed）");
        }
        Milestone exist = loadOr404(id);
        MilestoneStatus from = MilestoneStatus.fromDbValue(exist.getStatus());
        if (from == null) {
            throw new BizException(400, "里程碑状态列脏数据: " + exist.getStatus());
        }
        if (!from.canTransitionTo(to)) {
            throw new BizException(400, "非法状态流转: " + from.dbValue() + "→" + to.dbValue());
        }
        // 必填项：target=achieved 缺 achievedDate 时默认当天（T10 任务书口径，可改）
        if (to == MilestoneStatus.ACHIEVED && achievedDate == null) {
            achievedDate = LocalDate.now();
        }
        boolean clearAchieved = to == MilestoneStatus.PLANNED && from == MilestoneStatus.ACHIEVED;
        // 显式 set：撤销清空 achieved_date（null 不进 updateById 的默认策略）；其余路径一并统一
        update(new LambdaUpdateWrapper<Milestone>()
                .eq(Milestone::getId, id)
                .set(Milestone::getStatus, to.dbValue())
                .set(Milestone::getAchievedDate, to == MilestoneStatus.ACHIEVED ? achievedDate : null));
        Map<String, Object> detail = detail("transit", from.dbValue(), to.dbValue(),
                "owner", exist.getOwner(), "title", exist.getTitle());
        if (clearAchieved) {
            detail.put("clearedAchievedDate", true);   // AC 契约：撤销审计含该标记
        }
        if (to == MilestoneStatus.ACHIEVED) {
            detail.put("achievedDate", achievedDate == null ? null : achievedDate.toString());
        }
        audit("milestone_transit", exist, toDetailJson(detail));
        return getById(id);
    }

    @Override
    public void remove(Long id) {
        Milestone exist = loadOr404(id);
        removeById(id);
        audit("milestone_delete", exist, detail("delete", exist.getStatus(), null,
                "owner", exist.getOwner(), "title", exist.getTitle()));
    }

    @Override
    public IPage<MilestoneTimelineVo> pageTimeline(String ownerType, Long ownerId, String status,
                                                   long page, long size) {
        LambdaQueryWrapper<Milestone> w = new LambdaQueryWrapper<Milestone>()
                .eq(ownerType != null && !ownerType.isBlank(), Milestone::getOwnerType, ownerType)
                .eq(ownerId != null, Milestone::getOwnerId, ownerId)
                .eq(status != null && !status.isBlank(), Milestone::getStatus, status)
                .orderByAsc(Milestone::getTargetDate);
        IPage<MilestoneTimelineVo> result = page(new Page<>(page, size), w).convert(this::toVo);
        return result;
    }

    @Override
    public List<MilestoneTimelineVo> programTimeline(Long programId) {
        Program program = programMapper.selectById(programId);
        if (program == null) {
            throw new BizException(404, "项目群不存在: " + programId);
        }
        // 成员项目（id+名 两列，防 N+1 由 map 承载）
        List<Project> members = projectMapper.selectList(new LambdaQueryWrapper<Project>()
                .eq(Project::getProgramId, programId));
        Map<Long, String> nameById = members.stream()
                .collect(Collectors.toMap(Project::getId, Project::getName, (a, b) -> a));
        nameById.put(programId, program.getName());
        // 群直属 + 成员项目两段标准查询（各自命中 idx_ms_tenant_owner）后内存合并
        List<Milestone> direct = list(new LambdaQueryWrapper<Milestone>()
                .eq(Milestone::getOwnerType, "program").eq(Milestone::getOwnerId, programId));
        List<Milestone> fromProjects = List.of();
        if (!members.isEmpty()) {
            fromProjects = list(new LambdaQueryWrapper<Milestone>()
                    .eq(Milestone::getOwnerType, "project")
                    .in(Milestone::getOwnerId, nameById.keySet().stream()
                            .filter(k -> !k.equals(programId)).toList()));
        }
        List<Milestone> merged = new ArrayList<>(direct.size() + fromProjects.size());
        merged.addAll(direct);
        merged.addAll(fromProjects);
        merged.sort(java.util.Comparator.comparing(Milestone::getTargetDate,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        return merged.stream().map(m -> {
            MilestoneTimelineVo vo = toVo(m);
            vo.setOwnerLevel(m.getOwnerType());   // 层级标签：program=群直属 / project=成员项目
            vo.setOwnerName(nameById.get(m.getOwnerId()));
            return vo;
        }).toList();
    }

    @Override
    public List<Long> plannedMilestoneIds(Long projectId) {
        return list(new LambdaQueryWrapper<Milestone>()
                        .select(Milestone::getId)
                        .eq(Milestone::getOwnerType, "project")
                        .eq(Milestone::getOwnerId, projectId)
                        .eq(Milestone::getStatus, MilestoneStatus.PLANNED.dbValue()))
                .stream().map(Milestone::getId).toList();
    }

    @Override
    public boolean isAchievableHint(Long projectId) {
        return !plannedMilestoneIds(projectId).isEmpty();
    }

    @Override
    public MilestoneTimelineVo toVo(Milestone ms) {
        MilestoneTimelineVo vo = new MilestoneTimelineVo();
        vo.setId(ms.getId());
        vo.setMilestoneCode(ms.getMilestoneCode());
        vo.setOwnerType(ms.getOwnerType());
        vo.setOwnerId(ms.getOwnerId());
        vo.setOwnerLevel(ms.getOwnerType());
        vo.setTitle(ms.getTitle());
        vo.setDescription(ms.getDescription());
        vo.setTargetDate(ms.getTargetDate());
        vo.setOwner(ms.getOwner());
        vo.setStatus(ms.getStatus());
        vo.setStatusColor(MilestoneTimelineVo.colorOf(ms.getStatus()));
        // 逾期=展示层实时判定（不改库，系统不自动置 delayed，AC-F2.3）
        vo.setOverdue(ms.getTargetDate() != null && ms.getTargetDate().isBefore(LocalDate.now())
                && MilestoneStatus.PLANNED.dbValue().equals(ms.getStatus()));
        vo.setAchievedDate(ms.getAchievedDate());
        vo.setBlocker(ms.getBlocker());
        vo.setSubprojects(ms.getSubprojects());
        vo.setCreateTime(ms.getCreateTime());
        return vo;
    }

    // ==================== 内部工具 ====================

    /** 写入前校验：title 必填 ≤200、ownerType 两值、归属对象存在（404 指名）。 */
    private void validateForWrite(Milestone ms) {
        if (ms == null || ms.getTitle() == null || ms.getTitle().isBlank()) {
            throw new BizException(400, "title 不能为空");
        }
        if (ms.getTitle().length() > 200) {
            throw new BizException(400, "title 长度不能超过 200 字符");
        }
        String ownerType = ms.getOwnerType();
        if (!"program".equals(ownerType) && !"project".equals(ownerType)) {
            throw new BizException(400, "ownerType 非法，应为 program/project");
        }
        Long ownerId = ms.getOwnerId();
        if (ownerId == null) {
            throw new BizException(400, "ownerId 不能为空");
        }
        if ("project".equals(ownerType)) {
            if (projectMapper.selectById(ownerId) == null) {
                throw new BizException(404, "归属项目不存在: " + ownerId);
            }
        } else if (programMapper.selectById(ownerId) == null) {
            throw new BizException(404, "归属项目群不存在: " + ownerId);
        }
    }

    /**
     * milestoneCode 生成 + uk 兜底重试（≤3 次）：可见最大数字后缀 seq 起，冲突则 seq+1 续推。
     *
     * <p>逻辑删行（select 不可见）仍占 uk——后缀续推可跳过占号，3 次重试覆盖常规脏数据面。</p>
     */
    private void insertWithCodeRetry(Milestone ms) {
        long seq = maxVisibleSuffix() + 1;
        DuplicateKeyException last = null;
        for (int attempt = 0; attempt <= 3; attempt++) {
            ms.setMilestoneCode(formatCode(seq + attempt));
            try {
                save(ms);
                return;
            } catch (DuplicateKeyException e) {
                last = e;
                log.warn("[Milestone] milestoneCode 冲突重试 {}/{}: code={}", attempt + 1, 3, ms.getMilestoneCode());
            }
        }
        throw new BizException(400, "里程碑编号生成冲突，请重试: " + ms.getMilestoneCode()
                + (last == null ? "" : " (" + last.getMessage() + ")"));
    }

    /** 租户内当前可见编号的最大数字后缀（MS-0042 → 42；无行/无合法后缀 → 0）。 */
    private long maxVisibleSuffix() {
        List<String> codes = list(new LambdaQueryWrapper<Milestone>().select(Milestone::getMilestoneCode))
                .stream().map(Milestone::getMilestoneCode).filter(Objects::nonNull).toList();
        long max = 0;
        for (String c : codes) {
            if (c.startsWith("MS-") && c.length() > 3) {
                try {
                    max = Math.max(max, Long.parseLong(c.substring(3)));
                } catch (NumberFormatException ignore) {
                    // 自定义格式编号不参与续推
                }
            }
        }
        return max;
    }

    private static String formatCode(long seq) {
        return seq > 9999 ? "MS-" + seq : String.format("MS-%04d", seq);
    }

    /** 审计 detail 组装（Jackson 序列化，防手拼 JSON 注入）。 */
    private static Map<String, Object> detail(String verb, String from, String to, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("verb", verb);
        if (from != null) {
            m.put("from", from);
        }
        if (to != null) {
            m.put("to", to);
        }
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /** 写审计（safeJson 语义：Jackson 序列化失败降级极简文本，不阻断业务）。 */
    private void audit(String action, Milestone ms, Object detailObj) {
        String detail;
        try {
            detail = detailObj instanceof String s ? s : OM.writeValueAsString(detailObj);
        } catch (Exception e) {
            detail = "{\"verb\":\"" + action + "\"}";
        }
        auditService.log(action, "milestone", String.valueOf(ms.getId()), detail);
    }

    /** Map→JSON（transit 路径二次加工 detail 后统一序列化；失败降级空对象）。 */
    private static String toDetailJson(Map<String, Object> detail) {
        try {
            return OM.writeValueAsString(detail);
        } catch (Exception e) {
            return "{}";
        }
    }
}
