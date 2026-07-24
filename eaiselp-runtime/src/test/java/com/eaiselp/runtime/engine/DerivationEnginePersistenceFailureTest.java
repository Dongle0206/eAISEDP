package com.eaiselp.runtime.engine;

import com.eaiselp.adapter.spi.AdapterFactory;
import com.eaiselp.adapter.spi.LlmAdapter;
import com.eaiselp.capability.model.AgentDefinition;
import com.eaiselp.common.tenant.TenantContext;
import com.eaiselp.data.service.ArtifactService;
import com.eaiselp.data.service.DerivationService;
import com.eaiselp.runtime.context.ContextAssembler;
import com.eaiselp.runtime.context.DerivationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * TC-3：落库失败 → 派生结果仍返回（不被 DB 抖动拖垮）。
 *
 * <p>SE §5.7 选项 c：用 @MockBean(DerivationPersistenceService.class) 替换真实 Bean，
 * 让 persist() 抛异常，验证 DerivationEngine 的 try-catch Throwable 兜底生效。</p>
 *
 * <p>独立测试类的原因：@MockBean 是 Spring 上下文级的替换，与 DerivationEngineTest 的真实
 * DerivationPersistenceService 不能共用同一上下文（Spring Boot Test 会为不同 @MockBean 配置
 * 缓存不同的 context）。分开后两类各自有独立上下文，互不干扰。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class DerivationEnginePersistenceFailureTest {

    @Autowired DerivationEngine engine;

    // TC-3 核心：替换真实 DerivationPersistenceService，让 persist 抛异常
    @MockBean DerivationPersistenceService persistenceService;

    @MockBean AdapterFactory adapterFactory;
    @MockBean ContextAssembler contextAssembler;
    @MockBean LlmAdapter llmAdapter;

    // 引入但本类不直接断言（仅证明真实 Service 不在上下文中，由 @MockBean 替换）
    @Autowired(required = false) DerivationService derivationService;
    @Autowired(required = false) ArtifactService artifactService;

    @BeforeEach
    void setUp() {
        // M2 SP-6：engine 改走 getLlmAdapter(tier) / resolveModel(tier)（按 tier 路由），故 stub 新重载
        when(adapterFactory.getLlmAdapter()).thenReturn(llmAdapter);
        when(adapterFactory.getLlmAdapter(org.mockito.ArgumentMatchers.anyString())).thenReturn(llmAdapter);
        when(adapterFactory.resolveModel(org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(contextAssembler.assemble(any(), any())).thenReturn("fake prompt");
        TenantContext.set(1L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * TC-3：落库失败（persistenceService.persist 抛 RuntimeException）→
     * result.status 仍为 success（高价值派生结果不被 DB 失败拖垮）。
     */
    @Test
    void TC3_落库失败_派生结果仍返回() {
        when(llmAdapter.invoke(any(), any(), any()))
            .thenReturn(LlmAdapter.LlmResponse.builder()
                    .content("PRD 内容\n## 本次经验沉淀\nxxx").inputTokens(100).outputTokens(50).build());
        // 模拟落库失败（DB down / 任何 Throwable）
        doThrow(new RuntimeException("DB down"))
            .when(persistenceService).persist(any());

        AgentDefinition agent = new AgentDefinition();
        agent.setName("team-po");
        agent.setModel("sonnet");
        agent.setPrompt("你是一个产品经理");
        DerivationContext ctx = DerivationContext.builder().task("写 PRD").stage("plan").build();

        // 关键断言：不抛异常，派生结果正常返回
        DerivationEngine.DerivationResult r = engine.derive(agent, "写 PRD", "case-test-3", ctx);
        assertEquals("success", r.getStatus(), "落库失败时派生结果仍应返回 success");
        assertEquals("case-test-3", r.getCaseId());
        assertEquals("team-po", r.getRole());
        assertNotNull(r.getArtifacts(), "artifacts 仍应存在于内存结果中");
    }
}
