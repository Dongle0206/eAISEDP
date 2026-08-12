package com.eaiselp.runtime.controller;

import com.eaiselp.data.entity.Checkpoint;
import com.eaiselp.data.service.ArtifactService;
import com.eaiselp.data.service.CaseService;
import com.eaiselp.data.service.CheckpointService;
import com.eaiselp.data.service.DerivationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * DashboardController 单测。
 *
 * <p>覆盖看板 3 个接口的数据聚合逻辑：</p>
 * <ul>
 *   <li>overview：Case 总数 + 状态分布 + 派生总数 + token + 产物 + 待审检查点</li>
 *   <li>caseStats：6 状态齐全（零计数补 0）</li>
 *   <li>derivationStats：角色分组统计透传</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DashboardControllerTest {

    @Mock CaseService caseService;
    @Mock DerivationService derivationService;
    @Mock ArtifactService artifactService;
    @Mock CheckpointService checkpointService;

    @InjectMocks DashboardController controller;

    @Test
    void overview_正常聚合() {
        // groupBy 返回 2 个状态有数据
        when(caseService.listMaps(any())).thenReturn(List.of(
                Map.of("status", "drafting", "cnt", 3),
                Map.of("status", "done", "cnt", 5)
        ));
        // 派生统计
        when(derivationService.countAndTokensByRole()).thenReturn(List.of(
                Map.of("role", "team-po", "count", 4, "totalTokens", 1000),
                Map.of("role", "team-dev", "count", 6, "totalTokens", 2000)
        ));
        when(artifactService.count()).thenReturn(20L);
        when(checkpointService.count(any())).thenReturn(2L);

        var result = controller.overview();

        assertEquals(0, result.getCode());
        var vo = result.getData();
        assertEquals(8, vo.getCaseTotal(), "3 drafting + 5 done = 8");
        assertEquals(10, vo.getDerivationTotal(), "4 + 6 = 10");
        assertEquals(3000, vo.getTokenTotal(), "1000 + 2000 = 3000");
        assertEquals(20, vo.getArtifactTotal());
        assertEquals(2, vo.getCheckpointPending());
    }

    @Test
    void overview_空数据_零值() {
        when(caseService.listMaps(any())).thenReturn(List.of());
        when(derivationService.countAndTokensByRole()).thenReturn(List.of());
        when(artifactService.count()).thenReturn(0L);
        when(checkpointService.count(any())).thenReturn(0L);

        var result = controller.overview();

        assertEquals(0, result.getCode());
        var vo = result.getData();
        assertEquals(0, vo.getCaseTotal());
        assertEquals(0, vo.getDerivationTotal());
        assertEquals(0, vo.getTokenTotal());
    }

    @Test
    void caseStats_6状态齐全_零计数补0() {
        when(caseService.listMaps(any())).thenReturn(List.of(
                Map.of("status", "deriving", "cnt", 2)
        ));

        var result = controller.caseStats();

        assertEquals(0, result.getCode());
        var stats = result.getData();
        // 6 个枚举值必须齐全
        assertTrue(stats.containsKey("drafting"));
        assertTrue(stats.containsKey("deriving"));
        assertTrue(stats.containsKey("reviewing"));
        assertTrue(stats.containsKey("testing"));
        assertTrue(stats.containsKey("deploying"));
        assertTrue(stats.containsKey("done"));
        assertEquals(2L, stats.get("deriving"));
        assertEquals(0L, stats.get("drafting"), "零计数状态补 0");
        assertEquals(0L, stats.get("done"));
    }

    @Test
    void caseStats_全部空_6个零() {
        when(caseService.listMaps(any())).thenReturn(List.of());

        var result = controller.caseStats();

        assertEquals(6, result.getData().size());
        result.getData().values().forEach(v -> assertEquals(0L, v));
    }

    @Test
    void derivationStats_透传service结果() {
        List<Map<String, Object>> mockStats = List.of(
                Map.of("role", "team-po", "count", 10, "totalTokens", 5000)
        );
        when(derivationService.countAndTokensByRole()).thenReturn(mockStats);

        var result = controller.derivationStats();

        assertEquals(0, result.getCode());
        assertEquals(1, result.getData().size());
        assertEquals("team-po", result.getData().get(0).get("role"));
    }
}
