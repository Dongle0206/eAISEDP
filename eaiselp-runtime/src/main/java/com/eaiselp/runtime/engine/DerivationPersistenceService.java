package com.eaiselp.runtime.engine;

import com.eaiselp.data.entity.Artifact;
import com.eaiselp.data.entity.Derivation;
import com.eaiselp.data.service.ArtifactService;
import com.eaiselp.data.service.DerivationService;
import com.eaiselp.runtime.task.DerivationTaskIdHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 派生结果落库服务（独立 Bean，承载 @Transactional）。
 *
 * <p>设计原因（SE 技术方案 §3.2 方案 a）：@Transactional 通过 Spring AOP 代理生效，
 * 若把落库方法写在 {@link DerivationEngine} 内部由 this 自调用，事务将完全失效（不报错但不生效）。
 * 因此抽到独立 Bean，由 DerivationEngine 注入并外部调用，事务才能真正原子化包裹
 * t_derivation + t_artifact 的写入。</p>
 *
 * <p>字段映射严格按 SE 技术方案 §6.2 / §6.3：
 * <ul>
 *   <li>DerivationResult.output / ProducedArtifact.content 不入库（content 走 M2 外部存储，§3.5）</li>
 *   <li>stage / modelTier / cost / errorMsg / retryCount / startedAt 当前 DerivationResult 无对应字段，留 NULL（§3.6）</li>
 *   <li>retry_count 列为 NOT NULL DEFAULT 0，entity 是包装类型 Integer，null 时 MP 不进 SQL，DB 用列默认值</li>
 *   <li>produced_artifacts 落产物摘要 JSON（仅 type/role），避免正文重复入库</li>
 * </ul>
 */
@Slf4j
@Service
public class DerivationPersistenceService {

    private final DerivationService derivationService;
    private final ArtifactService artifactService;

    public DerivationPersistenceService(DerivationService derivationService,
                                        ArtifactService artifactService) {
        this.derivationService = derivationService;
        this.artifactService = artifactService;
    }

    /**
     * 落库派生记录与产物（原子化）。
     *
     * <p>调用方（DerivationEngine）负责 try-catch Throwable 不重抛（§3.3 / §3.4 决策），
     * 本方法只管把数据写进去；事务由 @Transactional 保证 derivation + artifacts 要么全进要么全不进。</p>
     *
     * <p><b>M2-DFX 异步化分支（SE §5.3 D-4 落地）</b>：检查 {@link DerivationTaskIdHolder#get()}：
     * <ul>
     *   <li>非空（异步路径，{@code DerivationAsyncRunner.deriveAsync} 已 set taskId）：
     *       taskId 即 createPending 预占行的主键，走 {@code updateById}（UPDATE）回填该行；
     *       artifacts 仍走 INSERT（产物行本就独立于 derivation 行，且 t_artifact 无 update 语义）。</li>
     *   <li>为空（同步测试路径 / 老代码直接调 engine.derive）：保持原 {@code save}（INSERT，ASSIGN_ID 回填），
     *       <b>零回归</b>，现有 DerivationEngine 单测不受影响。</li>
     * </ul>
     *
     * @param result 派生结果（内存对象，含 artifacts 列表）
     */
    @Transactional(rollbackFor = Exception.class)
    public void persist(DerivationEngine.DerivationResult result) {
        Long preassignedId = DerivationTaskIdHolder.get();
        boolean asyncPath = preassignedId != null;

        // 1. 构建并保存 Derivation
        Derivation d = new Derivation();
        if (asyncPath) {
            // 异步路径：复用 createPending 预占的主键，走 UPDATE 回填结果字段
            d.setId(preassignedId);
        }
        d.setCaseId(result.getCaseId());
        d.setRole(result.getRole());
        d.setModel(result.getModel());
        // stage / modelTier / cost / retryCount / startedAt / errorMsg：DerivationResult 当前无字段，留 NULL（§3.6）
        d.setInputTokens(result.getInputTokens());
        d.setOutputTokens(result.getOutputTokens());
        d.setStatus(result.getStatus());
        d.setExperience(result.getExperience());
        d.setDurationMs(result.getDurationMs());
        d.setFinishedAt(result.getFinishedAt());
        // produced_artifacts：落产物摘要 JSON（仅 type/role），避免正文重复落库（§3.5）
        d.setProducedArtifacts(summarizeArtifacts(result.getArtifacts()));
        if (asyncPath) {
            // UPDATE 预占行（id 已在 createPending 阶段插入；此处补写 success 结果字段）
            derivationService.updateById(d);
        } else {
            // 同步路径：INSERT（ASSIGN_ID 回填 d.id）—— 老逻辑，零回归
            derivationService.save(d);
        }

        // 2. 构建并批量保存 Artifacts（derivation_id 关联）
        //    产物行本就独立于 derivation 行（每次派生新产物），无论同步/异步都走 INSERT。
        List<DerivationEngine.ProducedArtifact> artifacts = result.getArtifacts();
        if (artifacts != null && !artifacts.isEmpty()) {
            List<Artifact> arts = new ArrayList<>(artifacts.size());
            for (DerivationEngine.ProducedArtifact pa : artifacts) {
                Artifact a = new Artifact();
                a.setCaseId(pa.getCaseId());
                a.setRole(pa.getRole());
                a.setType(pa.getType());
                a.setDerivationId(d.getId());     // 异步路径=preassignedId；同步路径=save 回填的新 id
                // M2-F 过程资产完善（P10）：填充 content + frontmatter + docKey + contractKey
                a.setContent(pa.getContent());
                a.setDocKey(pa.getCaseId() + "-" + pa.getType() + "-" + d.getId());
                a.setContractKey(pa.getType());
                // frontmatter：结构化元数据（version/review_status/generated_by/model）
                a.setFrontmatter(buildFrontmatter(pa, result));
                a.setStage(result.getStatus());
                arts.add(a);
            }
            artifactService.saveBatch(arts);
        }
    }

    /**
     * 产物摘要 JSON：[{"type":"prd","role":"team-po"},...]。
     * M1.2 不引 Jackson，用字符串拼接；M2 改 ObjectMapper（SE §1.6.1 改动 3 注释）。
     */
    private String summarizeArtifacts(List<DerivationEngine.ProducedArtifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < artifacts.size(); i++) {
            DerivationEngine.ProducedArtifact pa = artifacts.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"type\":\"").append(pa.getType())
              .append("\",\"role\":\"").append(pa.getRole()).append("\"}");
        }
        return sb.append("]").toString();
    }

    /**
     * 构建产物 frontmatter（结构化元数据，JSON 格式）。
     * 包含：version / review_status / generated_by / model / tokens
     */
    private String buildFrontmatter(DerivationEngine.ProducedArtifact pa, DerivationEngine.DerivationResult result) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"version\":\"1.0\"");
        sb.append(",\"review_status\":\"draft\"");
        sb.append(",\"generated_by\":\"").append(pa.getRole()).append("\"");
        sb.append(",\"model\":\"").append(result.getModel() != null ? result.getModel() : "").append("\"");
        sb.append(",\"input_tokens\":").append(result.getInputTokens() != null ? result.getInputTokens() : 0);
        sb.append(",\"output_tokens\":").append(result.getOutputTokens() != null ? result.getOutputTokens() : 0);
        sb.append("}");
        return sb.toString();
    }
}
