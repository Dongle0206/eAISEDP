package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.common.security.JwtClaims;
import com.eaiselp.common.security.LoginUser;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.runtime.governance.dto.DataQualityRuleVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 数据质量规则服务实现（V6 F2.2，case-20260820 T5）。
 *
 * <p><b>要点</b>：
 * <ul>
 *   <li><b>校验链</b>（AC-F2.5/F2.7）：assetId 存在且未逻辑删（MP 逻辑删下 selectById 对已删
 *       行返回 null → 统一 400"资产不存在或已删除"）；checkType 枚举 400 指名；
 *       threshold ∈ [0,100]（null/越界 400，边界 0/100 合法）。</li>
 *   <li><b>登记覆盖式更新</b>（AC-F2.6）：LambdaUpdateWrapper 显式 set last_* 四列（含
 *       actualValue 置 null 的场景）；旧值快照进审计 detail（旧值→新值 + 登记人），
 *       单值当前态不落历史行。</li>
 *   <li><b>资产摘要防 N+1</b>：列表页记录按 distinct assetId selectBatchIds 一次装配。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataQualityRuleServiceImpl extends ServiceImpl<DataQualityRuleMapper, DataQualityRule>
        implements DataQualityRuleService {

    private final DataAssetMapper assetMapper;
    private final AuditService auditService;

    private static final com.fasterxml.jackson.databind.ObjectMapper OM =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .findAndRegisterModules()                                  // JSR310：审计 detail 含 LocalDateTime（最近检查时间）
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** 阈值=百分比达标线区间（AC-F2.5：边界 0 与 100 合法，100.5/−1 → 400） */
    private static final BigDecimal THRESHOLD_MIN = BigDecimal.ZERO;
    private static final BigDecimal THRESHOLD_MAX = BigDecimal.valueOf(100);

    // ==================== CRUD（Q1~Q5） ====================

    @Override
    public DataQualityRule create(DataQualityRule rule) {
        validateForWrite(rule);
        rule.setLastResult(null);
        rule.setLastActualValue(null);
        rule.setLastCheckTime(null);
        rule.setLastCheckRemark(null);
        try {
            save(rule);
        } catch (DuplicateKeyException e) {
            throw new BizException(400, "质量规则已存在: " + rule.getRuleName());
        }
        audit("dqrule_create", rule.getId(), writeDetail(rule));
        return rule;
    }

    @Override
    public DataQualityRule edit(Long id, DataQualityRule patch) {
        loadOr404(id);
        validateForWrite(patch);
        DataQualityRule next = new DataQualityRule();
        next.setId(id);
        next.setRuleName(patch.getRuleName());
        next.setAssetId(patch.getAssetId());
        next.setCheckType(patch.getCheckType());
        next.setThreshold(patch.getThreshold());
        try {
            updateById(next);
        } catch (DuplicateKeyException e) {
            throw new BizException(400, "质量规则已存在: " + patch.getRuleName());
        }
        // last_* 不在编辑改写（只走登记端点 Q6）
        audit("dqrule_update", id, writeDetail(getById(id)));
        return getById(id);
    }

    @Override
    public void remove(Long id) {
        DataQualityRule exist = loadOr404(id);
        removeById(id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ruleName", exist.getRuleName());
        detail.put("assetId", exist.getAssetId());
        detail.put("checkType", exist.getCheckType());
        detail.put("threshold", exist.getThreshold());
        audit("dqrule_delete", id, detail);
    }

    @Override
    public DataQualityRuleVo detailVo(Long id) {
        DataQualityRuleVo vo = toVo(loadOr404(id));
        fillAssetSummary(List.of(vo));
        return vo;
    }

    @Override
    public DataQualityRule loadOr404(Long id) {
        DataQualityRule rule = getById(id);
        if (rule == null) {
            throw new BizException(404, "质量规则不存在: " + id);
        }
        return rule;
    }

    // ==================== 登记最近检查结果（Q6，AC-F2.6） ====================

    @Override
    public DataQualityRuleVo registerCheckResult(Long id, String result, BigDecimal actualValue,
                                                 LocalDateTime checkTime, String remark) {
        if (result == null || result.isBlank()
                || !("pass".equals(result) || "fail".equals(result))) {
            throw new BizException(400, "result 非法: " + result + "（应为 pass/fail，登记人判定）");
        }
        DataQualityRule exist = loadOr404(id);
        LocalDateTime when = (checkTime != null) ? checkTime : LocalDateTime.now();

        // 旧值快照（"旧值不覆盖审计"——历史唯一留痕=审计 detail，AC-F2.6）
        Map<String, Object> old = new LinkedHashMap<>();
        old.put("lastResult", exist.getLastResult());
        old.put("lastActualValue", exist.getLastActualValue());
        old.put("lastCheckTime", exist.getLastCheckTime());
        old.put("lastCheckRemark", exist.getLastCheckRemark());

        // 覆盖式更新 last_* 四列（单值当前态；actualValue/remark 显式 set，可置空）
        update(new LambdaUpdateWrapper<DataQualityRule>()
                .eq(DataQualityRule::getId, id)
                .set(DataQualityRule::getLastResult, result)
                .set(DataQualityRule::getLastActualValue, actualValue)
                .set(DataQualityRule::getLastCheckTime, when)
                .set(DataQualityRule::getLastCheckRemark, remark));

        Map<String, Object> now = new LinkedHashMap<>();
        now.put("lastResult", result);
        now.put("lastActualValue", actualValue);
        now.put("lastCheckTime", when);
        now.put("lastCheckRemark", remark);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ruleName", exist.getRuleName());
        detail.put("old", old);
        detail.put("new", now);
        JwtClaims claims = LoginUser.get();
        detail.put("operator", claims != null ? claims.getUsername() : null);   // 登记人（AC-F2.6）
        audit("dqrule_check_result", id, detail);

        DataQualityRuleVo vo = toVo(getById(id));
        fillAssetSummary(List.of(vo));
        return vo;
    }

    // ==================== 查询（Q1） ====================

    @Override
    public IPage<DataQualityRuleVo> pageFilter(String checkType, String lastResult, Long assetId,
                                               String keyword, long page, long size) {
        LambdaQueryWrapper<DataQualityRule> w = new LambdaQueryWrapper<>();
        if (checkType != null && !checkType.isBlank()) {
            w.eq(DataQualityRule::getCheckType, checkType.trim());
        }
        if (lastResult != null && !lastResult.isBlank()) {
            w.eq(DataQualityRule::getLastResult, lastResult.trim());
        }
        if (assetId != null) {
            w.eq(DataQualityRule::getAssetId, assetId);
        }
        if (keyword != null && !keyword.isBlank()) {
            w.like(DataQualityRule::getRuleName, keyword);
        }
        w.orderByDesc(DataQualityRule::getCreateTime);
        IPage<DataQualityRule> entityPage = page(new Page<>(page, size), w);
        IPage<DataQualityRuleVo> result = entityPage.convert(this::toVo);
        fillAssetSummary(result.getRecords());   // distinct assetId 批量装配，防 N+1
        return result;
    }

    @Override
    public DataQualityRuleVo toVo(DataQualityRule rule) {
        DataQualityRuleVo vo = new DataQualityRuleVo();
        vo.setId(rule.getId());
        vo.setRuleName(rule.getRuleName());
        vo.setAssetId(rule.getAssetId());
        vo.setCheckType(rule.getCheckType());
        vo.setThreshold(rule.getThreshold());
        vo.setLastResult(rule.getLastResult());
        vo.setLastActualValue(rule.getLastActualValue());
        vo.setLastCheckTime(rule.getLastCheckTime());
        vo.setLastCheckRemark(rule.getLastCheckRemark());
        vo.setCreateBy(rule.getCreateBy());
        vo.setCreateTime(rule.getCreateTime());
        vo.setUpdateTime(rule.getUpdateTime());
        return vo;
    }

    // ==================== 内部工具 ====================

    /** 写入前校验：必填 + assetId 存在且未删 + checkType 枚举 + threshold 区间（AC-F2.5/F2.7）。 */
    private void validateForWrite(DataQualityRule rule) {
        if (rule == null) {
            throw new BizException(400, "请求体不能为空");
        }
        requireText(rule.getRuleName(), "ruleName");
        if (rule.getAssetId() == null) {
            throw new BizException(400, "assetId 不能为空");
        }
        requireText(rule.getCheckType(), "checkType");
        if (CheckType.fromDbValue(rule.getCheckType()) == null) {
            throw new BizException(400, "checkType 非法: " + rule.getCheckType()
                    + "（应为 " + CheckType.legalValues() + "）");
        }
        if (rule.getThreshold() == null) {
            throw new BizException(400, "threshold 不能为空");
        }
        if (rule.getThreshold().compareTo(THRESHOLD_MIN) < 0
                || rule.getThreshold().compareTo(THRESHOLD_MAX) > 0) {
            throw new BizException(400, "threshold 越界: " + rule.getThreshold() + "（应为 0~100 百分比，边界 0 与 100 合法）");
        }
        // assetId 存在且未逻辑删（MP 逻辑删下已删资产 selectById 返回 null，AC-F2.7"被删资产 id 再用于新建规则 → 400"）
        DataAsset asset = assetMapper.selectById(rule.getAssetId());
        if (asset == null) {
            throw new BizException(400, "资产不存在或已删除: " + rule.getAssetId());
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(400, field + " 不能为空");
        }
    }

    /** 资产摘要批量装配（distinct assetId 一次 selectBatchIds，防 N+1）。 */
    private void fillAssetSummary(List<DataQualityRuleVo> vos) {
        List<Long> assetIds = vos.stream().map(DataQualityRuleVo::getAssetId)
                .filter(Objects::nonNull).distinct().toList();
        if (assetIds.isEmpty()) {
            return;
        }
        Map<Long, DataAsset> assetMap = assetMapper.selectBatchIds(assetIds).stream()
                .collect(Collectors.toMap(DataAsset::getId, Function.identity(), (a, b) -> a));
        for (DataQualityRuleVo vo : vos) {
            DataAsset a = vo.getAssetId() != null ? assetMap.get(vo.getAssetId()) : null;
            if (a != null) {
                vo.setAssetName(a.getAssetName());
                vo.setAssetSystemName(a.getSystemName());
            }
        }
    }

    private Map<String, Object> writeDetail(DataQualityRule rule) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ruleName", rule.getRuleName());
        detail.put("assetId", rule.getAssetId());
        detail.put("checkType", rule.getCheckType());
        detail.put("threshold", rule.getThreshold());
        return detail;
    }

    private void audit(String action, Long id, Object detail) {
        String json;
        try {
            json = OM.writeValueAsString(detail);
        } catch (Exception e) {
            json = "{}";
        }
        auditService.log(action, "dqrule", String.valueOf(id), json);
    }
}
