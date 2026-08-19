package com.eaiselp.runtime.controller;

import com.eaiselp.common.exception.BizException;
import com.eaiselp.runtime.hierarchy.DoraMetricsService;
import com.eaiselp.runtime.hierarchy.dto.DoraBoardVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DoraMetricsController 单测（case-20260818 T19，薄控制器）。
 *
 * <p>验证：参数原样透传 Service（聚合/缓存/校验全在 T14）、BizException
 * （参数 400/scopeId 404）原样上抛 GlobalExceptionHandler → R.fail 业务码。</p>
 */
@ExtendWith(MockitoExtension.class)
class DoraMetricsControllerTest {

    @Mock DoraMetricsService doraMetricsService;

    @InjectMocks DoraMetricsController controller;

    @Test
    void dora_参数透传_periodDays缺省null由Service兜底() {
        DoraBoardVo vo = new DoraBoardVo();
        when(doraMetricsService.dora("program", 1L, null)).thenReturn(vo);

        var result = controller.dora("program", 1L, null);

        assertEquals(0, result.getCode());
        assertSame(vo, result.getData());
        verify(doraMetricsService).dora("program", 1L, null);
    }

    @Test
    void dora_全档透传() {
        DoraBoardVo vo = new DoraBoardVo();
        when(doraMetricsService.dora("all", null, 90)).thenReturn(vo);

        assertSame(vo, controller.dora("all", null, 90).getData());
        verify(doraMetricsService).dora("all", null, 90);
    }

    @Test
    void dora_参数非法400_原样上抛() {
        when(doraMetricsService.dora("tenant", null, null))
                .thenThrow(new BizException(400, "scope 非法，应为 project/program/all"));

        BizException ex = assertThrows(BizException.class, () -> controller.dora("tenant", null, null));
        assertEquals(400, ex.getCode());
        assertEquals("scope 非法，应为 project/program/all", ex.getMessage());
    }

    @Test
    void dora_scope缺scopeId_400_原样上抛() {
        when(doraMetricsService.dora("project", null, 7))
                .thenThrow(new BizException(400, "scope=project/program 时 scopeId 必填"));

        BizException ex = assertThrows(BizException.class, () -> controller.dora("project", null, 7));
        assertEquals("scope=project/program 时 scopeId 必填", ex.getMessage());
    }

    @Test
    void dora_scopeId不存在404_原样上抛() {
        when(doraMetricsService.dora("project", 999L, 30))
                .thenThrow(new BizException(404, "项目不存在: 999"));

        BizException ex = assertThrows(BizException.class, () -> controller.dora("project", 999L, 30));
        assertEquals(404, ex.getCode());
    }
}
