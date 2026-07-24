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
import static org.mockito.Mockito.when;

/**
 * DerivationEngine 落库主流程单测（真实 H2 + 真实 DerivationPersistenceService）。
 *
 * <p>覆盖 SE 技术方案 §1.7.4 的 TC-1 / TC-2 / TC-4（落库成功 / LLM 失败 / 多 artifact）。
 * TC-3（落库失败）见 {@link DerivationEnginePersistenceFailureTest}（用 @MockBean 替换
 * DerivationPersistenceService，不能与本类共存于同一 Spring 上下文）。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class DerivationEngineTest {

    @Autowired DerivationEngine engine;
    @Autowired DerivationService derivationService;   // 用来 query 验证落库结果
    @Autowired ArtifactService artifactService;

    @MockBean AdapterFactory adapterFactory;          // mock LLM 调用入口
    @MockBean ContextAssembler contextAssembler;      // mock prompt 装配
    @MockBean LlmAdapter llmAdapter;                  // mock 具体 LLM 实现

    private AgentDefinition agent;
    private DerivationContext ctx;

    @BeforeEach
    void setUp() {
        // 清表（IService 的全表删；MyBatis-Plus remove(null) 生成 DELETE FROM）
        derivationService.remove(null);
        artifactService.remove(null);

        // 默认 mock：adapterFactory 返回 mock 的 llmAdapter，assembler 返回固定 prompt
        // M2 SP-6：engine 改走 getLlmAdapter(tier) / resolveModel(tier)（按 tier 路由），故 stub 新重载；
        // resolveModel 原样透传 tier（=sonnet），保持本测试"落库主流程"语义不变（路由解析由 adapter 单测覆盖）。
        when(adapterFactory.getLlmAdapter()).thenReturn(llmAdapter);
        when(adapterFactory.getLlmAdapter(org.mockito.ArgumentMatchers.anyString())).thenReturn(llmAdapter);
        when(adapterFactory.resolveModel(org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(contextAssembler.assemble(any(), any())).thenReturn("fake prompt");

        // 关键：测试里手动 set tenant，绕过 TenantContextFilter（无 HTTP 请求）
        // TenantContext.get() 返回非 0 时 EaiselpTenantHandler 不 ignore，更接近生产
        TenantContext.set(1L);

        // 构造最小 AgentDefinition（derive 只用 name / model / prompt）
        agent = new AgentDefinition();
        agent.setName("team-po");
        agent.setModel("sonnet");
        agent.setPrompt("你是一个产品经理");

        ctx = DerivationContext.builder().task("写 PRD").stage("plan").build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** TC-1：派生成功 → t_derivation 1 条 + t_artifact 1 条；result.status=success。 */
    @Test
    void TC1_派生成功_落库验证() {
        when(llmAdapter.invoke(any(), any(), any()))
            .thenReturn(LlmAdapter.LlmResponse.builder()
                    .content("PRD 正文\n## 本次经验沉淀\nxxx").inputTokens(100).outputTokens(50).build());

        DerivationEngine.DerivationResult r = engine.derive(agent, "写 PRD", "case-test-1", ctx);

        assertEquals("success", r.getStatus());
        assertEquals(1, derivationService.count(), "t_derivation 应有 1 条记录");
        assertEquals(1, artifactService.count(), "t_artifact 应有 1 条记录");

        com.eaiselp.data.entity.Derivation d = derivationService.list().get(0);
        assertEquals("case-test-1", d.getCaseId());
        assertEquals("team-po", d.getRole());
        assertEquals("sonnet", d.getModel());
        assertEquals(100, d.getInputTokens());
        assertEquals(50, d.getOutputTokens());
        assertNotNull(d.getProducedArtifacts(), "produced_artifacts 摘要 JSON 应非空");
        assertTrue(d.getProducedArtifacts().contains("\"type\":\"prd\""), "摘要应含 type=prd");

        com.eaiselp.data.entity.Artifact a = artifactService.list().get(0);
        assertEquals("case-test-1", a.getCaseId());
        assertEquals("prd", a.getType());
        assertNotNull(a.getDerivationId(), "artifact.derivation_id 应已关联");
        assertEquals(d.getId(), a.getDerivationId(), "artifact.derivation_id 应等于 derivation.id");
    }

    /** TC-2：LLM 失败 → 不入库（t_derivation count=0）+ 异常抛给上层。 */
    @Test
    void TC2_LLM失败_不入库() {
        when(llmAdapter.invoke(any(), any(), any())).thenThrow(new RuntimeException("LLM down"));

        assertThrows(RuntimeException.class,
                () -> engine.derive(agent, "写 PRD", "case-test-2", ctx));

        assertEquals(0, derivationService.count(), "LLM 失败不应落库任何记录");
        assertEquals(0, artifactService.count());
    }

    /**
     * TC-4：多 artifact 场景 → t_derivation 1 条 + t_artifact N 条；derivation_id 全部正确关联。
     *
     * <p>注：当前 DerivationEngine.extractArtifacts（§1.6.1 现状）对单次 content 只产出 1 个 artifact
     * （Collections.singletonList）。本 case 不改 extractArtifacts 逻辑，因此验证"单 artifact 落库
     * 链路完整"作为多 artifact 的最小子集。M2 增强 extractArtifacts 支持多块解析后，本用例扩 N。</p>
     */
    @Test
    void TC4_多artifact场景() {
        when(llmAdapter.invoke(any(), any(), any()))
            .thenReturn(LlmAdapter.LlmResponse.builder()
                    .content("多块内容\n## 本次经验沉淀\nyyy").inputTokens(200).outputTokens(80).build());

        DerivationEngine.DerivationResult r = engine.derive(agent, "多块任务", "case-test-4", ctx);

        assertEquals("success", r.getStatus());
        assertEquals(1, derivationService.count());
        // 当前 extractArtifacts 产出 1 个（M1.2 范围内验证关联正确性）
        assertEquals(1, artifactService.count());

        // 验证 derivation_id 关联：所有 artifact 的 derivation_id 应等于刚插入的 derivation.id
        com.eaiselp.data.entity.Derivation d = derivationService.list().get(0);
        for (com.eaiselp.data.entity.Artifact a : artifactService.list()) {
            assertNotNull(a.getDerivationId(), "artifact.derivation_id 不应为 null");
            assertEquals(d.getId(), a.getDerivationId(),
                    "artifact.derivation_id 必须等于 derivation.id（原子化关联）");
        }
    }
}
