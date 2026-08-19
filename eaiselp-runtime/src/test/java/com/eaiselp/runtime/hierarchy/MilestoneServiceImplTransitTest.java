package com.eaiselp.runtime.hierarchy;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.data.entity.Milestone;
import com.eaiselp.data.mapper.MilestoneMapper;
import com.eaiselp.runtime.hierarchy.dto.MilestoneTimelineVo;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * MilestoneServiceImpl 状态流转单测（case-20260818 T10，AC-F2.2/F2.3 核心）。
 *
 * <p>覆盖：达成日期缺省当天 / 非法流转 400（achieved→delayed）/ 撤销清空达成日期
 * （LambdaUpdateWrapper 显式 set null + 审计 clearedAchievedDate:true）/ 幂等 /
 * 逾期展示层标记（toVo.overdue，系统不改状态）。</p>
 *
 * <p>注：Mapper mock 字段名必须为 baseMapper——ServiceImpl.baseMapper 字段注入按名匹配
 * （多个 BaseMapper 子类型 mock 同时存在时按字段名消歧）。</p>
 */
@ExtendWith(MockitoExtension.class)
class MilestoneServiceImplTransitTest {

    @Mock MilestoneMapper baseMapper;
    @Mock ProjectMapper projectMapper;
    @Mock ProgramMapper programMapper;
    @Mock AuditService auditService;

    @InjectMocks
    MilestoneServiceImpl service;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Milestone.class);
    }

    @org.junit.jupiter.api.BeforeEach
    void injectBaseMapper() throws Exception {
        // ServiceImpl.baseMapper（protected 泛型字段）显式注入（同 DependencyServiceTest 说明）
        java.lang.reflect.Field f = MilestoneServiceImpl.class.getSuperclass().getDeclaredField("baseMapper");
        f.setAccessible(true);
        f.set(service, baseMapper);
    }

    private static Milestone ms(Long id, String status) {
        Milestone m = new Milestone();
        m.setId(id);
        m.setStatus(status);
        m.setOwnerType("project");
        m.setOwnerId(202L);
        m.setTitle("接口联调完成");
        m.setTargetDate(LocalDate.now().plusDays(3));
        return m;
    }

    /** 归属对象占位（S2 edit 测试：validateForWrite 的存在性校验只需非 null）。 */
    private static Project project(Long id) {
        Project p = new Project();
        p.setId(id);
        return p;
    }

    /** 确认达成：缺 achievedDate → 默认当天；update 显式 set status+achieved_date（AC-F2.2）。 */
    @Test
    void transit_确认达成_日期缺省当天() {
        Milestone planned = ms(5001L, "planned");
        when(baseMapper.selectById(5001L)).thenReturn(planned);
        when(baseMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        service.transit(5001L, "achieved", null);

        ArgumentCaptor<LambdaUpdateWrapper<Milestone>> captor = wrapperCaptor();
        verify(baseMapper).update(isNull(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        assertTrue(sqlSet.contains("status"), "set status");
        assertTrue(sqlSet.contains("achieved_date"), "set achieved_date");
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("achieved"),
                "目标状态 achieved");
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(LocalDate.now()),
                "缺省达成日期=当天（T10 口径：默认当天可改）");
        verify(auditService).log(eq("milestone_transit"), eq("milestone"), eq("5001"), anyString());
    }

    /** 撤销：achieved→planned 清空达成日期；审计 detail 含 clearedAchievedDate:true（AC 契约）。 */
    @Test
    void transit_撤销清空达成日期() {
        Milestone achieved = ms(5001L, "achieved");
        achieved.setAchievedDate(LocalDate.now().minusDays(1));
        when(baseMapper.selectById(5001L)).thenReturn(achieved);
        when(baseMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        service.transit(5001L, "planned", null);

        ArgumentCaptor<LambdaUpdateWrapper<Milestone>> captor = wrapperCaptor();
        verify(baseMapper).update(isNull(), captor.capture());
        assertTrue(captor.getValue().getSqlSet().contains("achieved_date"),
                "撤销必须显式 set achieved_date=null（updateById 对 null 默认不更新）");
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(null),
                "达成日期被置空");
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq("milestone_transit"), eq("milestone"), eq("5001"), detail.capture());
        assertTrue(detail.getValue().contains("\"clearedAchievedDate\":true"), "审计含撤销标记");
        assertTrue(detail.getValue().contains("\"from\":\"achieved\""));
        assertTrue(detail.getValue().contains("\"to\":\"planned\""));
    }

    /** 非法流转：achieved→delayed 400，文案与 api-contracts 契约一致。 */
    @Test
    void transit_非法流转400() {
        when(baseMapper.selectById(5001L)).thenReturn(ms(5001L, "achieved"));
        BizException ex = assertThrows(BizException.class, () -> service.transit(5001L, "delayed", null));
        assertEquals(400, ex.getCode());
        assertEquals("非法状态流转: achieved→delayed", ex.getMessage());
        verify(baseMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    /** 未知目标状态 400 / 里程碑不存在 404。 */
    @Test
    void transit_未知状态与404() {
        // 未知目标在加载前即被拒——该 stub 不参与本断言路径，lenient 防 strict-stubs 误报
        lenient().when(baseMapper.selectById(5001L)).thenReturn(ms(5001L, "planned"));
        BizException unknown = assertThrows(BizException.class,
                () -> service.transit(5001L, "done", null));
        assertEquals(400, unknown.getCode());

        when(baseMapper.selectById(9999L)).thenReturn(null);
        BizException notFound = assertThrows(BizException.class,
                () -> service.transit(9999L, "achieved", null));
        assertEquals(404, notFound.getCode());
    }

    /** 幂等：planned→planned 合法（并发重试语义），不报错。 */
    @Test
    void transit_自身幂等合法() {
        when(baseMapper.selectById(5001L)).thenReturn(ms(5001L, "planned"));
        when(baseMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        assertDoesNotThrow(() -> service.transit(5001L, "planned", null));
    }

    // ===== S2 归属防挪窝：edit 不接受 ownerType/ownerId 变更 =====

    /** S2：请求携带不同 ownerId → 400"里程碑归属不可变更"，不落库。 */
    @Test
    void edit_归属变更拒绝400() {
        when(baseMapper.selectById(5001L)).thenReturn(ms(5001L, "planned"));
        Milestone patch = ms(5001L, null);
        patch.setOwnerId(999L);   // 挪窝尝试：project 202 → 999

        BizException ex = assertThrows(BizException.class, () -> service.edit(5001L, patch));
        assertEquals(400, ex.getCode());
        assertEquals("里程碑归属不可变更", ex.getMessage());
        verify(baseMapper, never()).updateById(any(Milestone.class));
    }

    /** S2：ownerType 挪窝（project→program）同样 400。 */
    @Test
    void edit_归属类型变更拒绝400() {
        when(baseMapper.selectById(5001L)).thenReturn(ms(5001L, "planned"));
        Milestone patch = ms(5001L, null);
        patch.setOwnerType("program");   // 同 id 不同类型也是变更

        BizException ex = assertThrows(BizException.class, () -> service.edit(5001L, patch));
        assertEquals(400, ex.getCode());
        assertEquals("里程碑归属不可变更", ex.getMessage());
    }

    /** S2：归属缺省（null）→ 保持库值写入；title 等普通字段正常更新。 */
    @Test
    void edit_归属缺省保持库值() {
        Milestone exist = ms(5001L, "planned");
        when(baseMapper.selectById(5001L)).thenReturn(exist);   // loadOr404 + 收尾 getById 共用
        when(projectMapper.selectById(202L)).thenReturn(project(202L));
        when(baseMapper.updateById(any(Milestone.class))).thenReturn(1);
        Milestone patch = new Milestone();
        patch.setTitle("改期后的标题");   // ownerType/ownerId 均缺省

        service.edit(5001L, patch);

        ArgumentCaptor<Milestone> captor = ArgumentCaptor.forClass(Milestone.class);
        verify(baseMapper).updateById(captor.capture());
        assertEquals("project", captor.getValue().getOwnerType(), "归属保持库值 project");
        assertEquals(202L, captor.getValue().getOwnerId(), "归属保持库值 202");
        assertEquals("改期后的标题", captor.getValue().getTitle());
    }

    /** S2：归属同值（幂等重放）不视为变更，正常放行。 */
    @Test
    void edit_归属同值幂等放行() {
        when(baseMapper.selectById(5001L)).thenReturn(ms(5001L, "planned"));
        when(projectMapper.selectById(202L)).thenReturn(project(202L));
        when(baseMapper.updateById(any(Milestone.class))).thenReturn(1);
        Milestone patch = ms(5001L, null);   // ownerType=project / ownerId=202 与库值相同
        patch.setTitle("同值归属重放");

        assertDoesNotThrow(() -> service.edit(5001L, patch));
        verify(baseMapper).updateById(any(Milestone.class));
    }

    /** 逾期展示层标记：targetDate<今天 且 planned → overdue=true；系统不改状态（AC-F2.3）。 */
    @Test
    void toVo_逾期黄角标不改状态() {
        Milestone overdue = ms(5001L, "planned");
        overdue.setTargetDate(LocalDate.now().minusDays(2));
        MilestoneTimelineVo vo = service.toVo(overdue);
        assertTrue(vo.getOverdue(), "逾期 planned → 黄角标");
        assertEquals("planned", vo.getStatus(), "展示层判定不改库");
        assertEquals("blue", vo.getStatusColor());

        Milestone achieved = ms(5002L, "achieved");
        achieved.setTargetDate(LocalDate.now().minusDays(30));   // 逾期目标日但已达成 → 不角标
        assertFalse(service.toVo(achieved).getOverdue());
        assertEquals("green", service.toVo(achieved).getStatusColor());

        Milestone delayed = ms(5003L, "delayed");
        assertEquals("red", service.toVo(delayed).getStatusColor());
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<LambdaUpdateWrapper<Milestone>> wrapperCaptor() {
        return ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
    }
}
