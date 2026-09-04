package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.runtime.governance.dto.DataAssetVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 数据资产目录服务实现（V6 F2.1，case-20260820 T4）。
 *
 * <p><b>要点</b>：
 * <ul>
 *   <li><b>uk(tenant,system,name)</b>：DuplicateKeyException → 400"资产已存在: {system}/{name}"
 *       （统一形态，AC-F2.1 同系统同名拒、跨系统同名合法——uk 三列承载）。</li>
 *   <li><b>枚举校验</b>：AssetType/Sensitivity.fromDbValue 返回 null → 400 指名字段与合法值
 *       集合（AC-F2.2"含明确字段错误提示"）。</li>
 *   <li><b>标签筛选</b>：JSON 列内存过滤（同 ADR §4.2 口径，H2 兼容防方言绑定）；
 *       标签候选由本服务聚合（自然扩展，无独立管理页，AC-F2.4）。</li>
 *   <li><b>删除联动</b>（AC-F2.7）：事务内资产逻辑删 + 关联规则同步逻辑删
 *       （DataQualityRuleMapper.delete(assetId 等值 wrapper)——MP 逻辑删），
 *       审计 asset_delete detail 含 ruleIds 数组可辨识。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataAssetServiceImpl extends ServiceImpl<DataAssetMapper, DataAsset> implements DataAssetService {

    /** 关联规则定位/聚合走 Mapper（不走规则 Service，避免与 DataQualityRuleServiceImpl 循环依赖） */
    private final DataQualityRuleMapper dqRuleMapper;
    private final AuditService auditService;

    private static final com.fasterxml.jackson.databind.ObjectMapper OM =
            new com.fasterxml.jackson.databind.ObjectMapper();

    // ==================== CRUD（A1~A5） ====================

    @Override
    public DataAsset create(DataAsset asset) {
        validateForWrite(asset);
        try {
            save(asset);
        } catch (DuplicateKeyException e) {
            throw new BizException(400, "资产已存在: " + asset.getSystemName() + "/" + asset.getAssetName());
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("assetName", asset.getAssetName());
        detail.put("systemName", asset.getSystemName());
        detail.put("assetType", asset.getAssetType());
        detail.put("sensitivity", asset.getSensitivity());
        detail.put("owner", asset.getOwner());
        detail.put("tags", parseTags(asset.getTags()));
        audit("asset_create", asset.getId(), detail);
        return asset;
    }

    @Override
    public DataAsset edit(Long id, DataAsset patch) {
        loadOr404(id);
        validateForWrite(patch);
        DataAsset next = new DataAsset();
        next.setId(id);
        next.setAssetName(patch.getAssetName());
        next.setSystemName(patch.getSystemName());
        next.setAssetType(patch.getAssetType());
        next.setOwner(patch.getOwner());
        next.setSensitivity(patch.getSensitivity());
        next.setDescription(patch.getDescription());
        next.setTags(patch.getTags());
        try {
            updateById(next);
        } catch (DuplicateKeyException e) {
            throw new BizException(400, "资产已存在: " + patch.getSystemName() + "/" + patch.getAssetName());
        }
        // S3（评审）：MP updateById 忽略 null 字段——PUT 提交 owner/description 置空（null）
        // 无法落库、回显仍旧值。可空文本字段为 null 时用 UpdateWrapper 显式 set null 补清空
        // （空串 "" 走上方 updateById 正常覆盖；tags 清空由 Controller toJson([])→"[]" 落库；
        // wrapper 走 baseMapper.update 仍被租户拦截器注入 tenant_id，与 updateById 同隔离级别）
        LambdaUpdateWrapper<DataAsset> nullPatch = new LambdaUpdateWrapper<DataAsset>()
                .eq(DataAsset::getId, id);
        boolean hasNullText = false;
        if (patch.getOwner() == null) {
            nullPatch.set(DataAsset::getOwner, null);
            hasNullText = true;
        }
        if (patch.getDescription() == null) {
            nullPatch.set(DataAsset::getDescription, null);
            hasNullText = true;
        }
        if (hasNullText) {
            update(nullPatch);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("assetName", next.getAssetName());
        detail.put("systemName", next.getSystemName());
        detail.put("assetType", next.getAssetType());
        detail.put("sensitivity", next.getSensitivity());
        detail.put("owner", next.getOwner());
        detail.put("tags", parseTags(next.getTags()));
        audit("asset_update", id, detail);
        return getById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id) {
        DataAsset exist = loadOr404(id);
        // 联动定位：idx_dqr_tenant_asset 命中（租户拦截器自动注入 tenant_id，G13）
        List<DataQualityRule> rules = dqRuleMapper.selectList(new LambdaQueryWrapper<DataQualityRule>()
                .eq(DataQualityRule::getAssetId, id));
        List<Long> ruleIds = rules.stream().map(DataQualityRule::getId).toList();
        removeById(id);
        if (!ruleIds.isEmpty()) {
            // 关联质量规则同步逻辑删（MP 逻辑删 UPDATE is_deleted=1，AC-F2.7）
            dqRuleMapper.delete(new LambdaQueryWrapper<DataQualityRule>()
                    .eq(DataQualityRule::getAssetId, id));
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("assetName", exist.getAssetName());
        detail.put("systemName", exist.getSystemName());
        detail.put("sensitivity", exist.getSensitivity());
        detail.put("ruleIds", ruleIds);   // 联动删除可辨识（AC-F2.7 detail 断言点）
        detail.put("cascadedRuleCount", ruleIds.size());
        audit("asset_delete", id, detail);
    }

    @Override
    public DataAssetVo detailVo(Long id) {
        DataAsset exist = loadOr404(id);
        DataAssetVo vo = toVo(exist);
        // 聚合区（A3）：关联规则数 + 各规则最近结果（idx_dqr_tenant_asset 命中，AC-F2.6 Then）
        List<DataQualityRule> rules = dqRuleMapper.selectList(new LambdaQueryWrapper<DataQualityRule>()
                .eq(DataQualityRule::getAssetId, id)
                .orderByDesc(DataQualityRule::getCreateTime));
        DataAssetVo.RulesAggregation agg = new DataAssetVo.RulesAggregation();
        agg.setCount(rules.size());
        agg.setItems(rules.stream().map(r -> {
            DataAssetVo.RuleBrief b = new DataAssetVo.RuleBrief();
            b.setRuleId(r.getId());
            b.setRuleName(r.getRuleName());
            b.setCheckType(r.getCheckType());
            b.setThreshold(r.getThreshold());
            b.setLastResult(r.getLastResult());
            b.setLastActualValue(r.getLastActualValue());
            b.setLastCheckTime(r.getLastCheckTime());
            return b;
        }).toList());
        vo.setRules(agg);
        return vo;
    }

    @Override
    public DataAsset loadOr404(Long id) {
        DataAsset asset = getById(id);
        if (asset == null) {
            throw new BizException(404, "资产不存在: " + id);
        }
        return asset;
    }

    // ==================== 查询（A1） ====================

    @Override
    public IPage<DataAssetVo> pageFilter(String assetType, String sensitivity, String tag, String keyword,
                                         long page, long size) {
        LambdaQueryWrapper<DataAsset> w = new LambdaQueryWrapper<>();
        if (assetType != null && !assetType.isBlank()) {
            w.eq(DataAsset::getAssetType, assetType.trim());
        }
        if (sensitivity != null && !sensitivity.isBlank()) {
            w.eq(DataAsset::getSensitivity, sensitivity.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            w.and(q -> q.like(DataAsset::getAssetName, kw).or().like(DataAsset::getSystemName, kw));
        }
        w.orderByDesc(DataAsset::getCreateTime);
        IPage<DataAsset> entityPage = page(new Page<>(page, size), w);
        IPage<DataAssetVo> result = entityPage.convert(this::toVo);
        // 标签单选命中：JSON 列内存过滤（同 ADR §4.2 口径——JSON_CONTAINS 方言绑定，H2 兼容）
        if (tag != null && !tag.isBlank()) {
            String target = tag.trim();
            List<DataAssetVo> filtered = result.getRecords().stream()
                    .filter(vo -> vo.getTags() != null && vo.getTags().contains(target))
                    .toList();
            result.setRecords(filtered);
            result.setTotal(filtered.size());
        }
        return result;
    }

    @Override
    public DataAssetVo toVo(DataAsset asset) {
        DataAssetVo vo = new DataAssetVo();
        vo.setId(asset.getId());
        vo.setAssetName(asset.getAssetName());
        vo.setSystemName(asset.getSystemName());
        vo.setAssetType(asset.getAssetType());
        vo.setOwner(asset.getOwner());
        vo.setSensitivity(asset.getSensitivity());
        vo.setDescription(asset.getDescription());
        vo.setTags(parseTags(asset.getTags()));
        vo.setCreateBy(asset.getCreateBy());
        vo.setCreateTime(asset.getCreateTime());
        vo.setUpdateTime(asset.getUpdateTime());
        return vo;
    }

    @Override
    public List<String> aggregateTags() {
        Set<String> tags = new LinkedHashSet<>();
        for (DataAsset a : list(new LambdaQueryWrapper<DataAsset>()
                .select(DataAsset::getTags).isNotNull(DataAsset::getTags))) {
            tags.addAll(parseTags(a.getTags()));
        }
        return new ArrayList<>(tags);
    }

    // ==================== 内部工具 ====================

    /** 写入前校验：必填 + 双枚举（AC-F2.2 指名字段与合法值集合）。 */
    private void validateForWrite(DataAsset asset) {
        if (asset == null) {
            throw new BizException(400, "请求体不能为空");
        }
        requireText(asset.getAssetName(), "assetName");
        requireText(asset.getSystemName(), "systemName");
        requireText(asset.getAssetType(), "assetType");
        requireText(asset.getSensitivity(), "sensitivity");
        if (AssetType.fromDbValue(asset.getAssetType()) == null) {
            throw new BizException(400, "assetType 非法: " + asset.getAssetType()
                    + "（应为 " + AssetType.legalValues() + "）");
        }
        if (Sensitivity.fromDbValue(asset.getSensitivity()) == null) {
            throw new BizException(400, "sensitivity 非法: " + asset.getSensitivity()
                    + "（应为 " + Sensitivity.legalValues() + "）");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(400, field + " 不能为空");
        }
    }

    /** tags JSON 数组解析（null/坏 JSON → 空列表容忍，不 500）。 */
    static List<String> parseTags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            String[] arr = OM.readValue(json, String[].class);
            return arr == null ? List.of() : Arrays.stream(arr).filter(Objects::nonNull).toList();
        } catch (Exception e) {
            log.warn("[DataAsset] tags 解析失败（容忍降级空列表）: {}", json);
            return List.of();
        }
    }

    private void audit(String action, Long id, Object detail) {
        String json;
        try {
            json = OM.writeValueAsString(detail);
        } catch (Exception e) {
            json = "{}";
        }
        auditService.log(action, "asset", String.valueOf(id), json);
    }
}
