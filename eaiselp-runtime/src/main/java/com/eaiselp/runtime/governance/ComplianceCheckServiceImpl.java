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
import com.eaiselp.runtime.governance.dto.ComplianceCheckVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 合规检查服务实现（case-20260821 T3，V7 F1.2 手动登记制）。
 *
 * <p><b>要点</b>：
 * <ul>
 *   <li><b>custom↔frameworkName 双向联动</b>（AC-F1.9 四例）：custom 缺名 400 / custom 带名
 *       200 / 非 custom 带名 400（防脏数据）——编辑走 LambdaUpdateWrapper 显式 set
 *       framework_name=null（custom→标准框架切换时必须清列，PUT 全量语义）。</li>
 *   <li><b>result 覆盖式单值当前态</b>（AC-F1.10）：不建历史表，旧值唯一留痕 = 审计
 *       compliance_update detail 含 oldResult→newResult + 证据（同 V6 质量规则先例）。</li>
 *   <li><b>逾期口径</b>（AC-F1.11，D-10）：recheckDate&lt;今天展示层红标，<b>na 不豁免</b>；
 *       overdueOnly 筛选同口径 SQL 化。</li>
 *   <li><b>checkDate 缺省</b>：V7 列可空——登记未填时应用层取当天。</li>
 *   <li><b>uk 兜底</b>：DuplicateKeyException → 400 "检查项已存在: xxx"（统一形态）。</li>
 * </ul></p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComplianceCheckServiceImpl extends ServiceImpl<ComplianceCheckMapper, ComplianceCheck>
        implements ComplianceCheckService {

    private final AuditService auditService;

    // ==================== CRUD（C1~C5） ====================

    @Override
    public ComplianceCheck create(ComplianceCheck check) {
        validateForWrite(check);
        if (check.getCheckDate() == null) {
            check.setCheckDate(LocalDate.now());
        }
        try {
            save(check);
        } catch (DuplicateKeyException e) {
            throw new BizException(400, "检查项已存在: " + check.getCheckName());
        }
        Map<String, Object> detail = writeDetail(check);
        detail.put("operator", operatorName());
        audit("compliance_create", check.getId(), detail);
        return check;
    }

    @Override
    public ComplianceCheck edit(Long id, ComplianceCheck patch) {
        ComplianceCheck exist = loadOr404(id);
        validateForWrite(patch);
        if (patch.getCheckDate() == null) {
            // 编辑未填 checkDate → 保留库中现值（缺省取当天仅 create 登记 semantics——
            // 编辑静默改写检查日期会破坏合规取证时点，保数据完整性优先）
            patch.setCheckDate(exist.getCheckDate());
        }
        try {
            update(new LambdaUpdateWrapper<ComplianceCheck>()
                    .eq(ComplianceCheck::getId, id)
                    .set(ComplianceCheck::getCheckName, patch.getCheckName())
                    .set(ComplianceCheck::getFramework, patch.getFramework())
                    // custom→标准框架切换必须清列（非 custom 带名在校验层已 400，此处显式 set
                    // 承载"清空"语义——MP updateById 忽略 null 会让脏数据残留）
                    .set(ComplianceCheck::getFrameworkName, patch.getFrameworkName())
                    .set(ComplianceCheck::getClauseRef, patch.getClauseRef())
                    .set(ComplianceCheck::getDescription, patch.getDescription())
                    .set(ComplianceCheck::getResult, patch.getResult())
                    .set(ComplianceCheck::getEvidenceNote, patch.getEvidenceNote())
                    .set(ComplianceCheck::getCheckDate, patch.getCheckDate())
                    .set(ComplianceCheck::getRecheckDate, patch.getRecheckDate())
                    .set(ComplianceCheck::getOwner, patch.getOwner()));
        } catch (DuplicateKeyException e) {
            throw new BizException(400, "检查项已存在: " + patch.getCheckName());
        }
        // 审计 detail 含 oldResult→newResult + 证据（覆盖式历史唯一留痕，AC-F1.10/AC-AUDIT.1）
        Map<String, Object> detail = writeDetail(patch);
        detail.put("oldResult", exist.getResult());
        detail.put("newResult", patch.getResult());
        detail.put("oldEvidenceNote", exist.getEvidenceNote());
        detail.put("operator", operatorName());
        audit("compliance_update", id, detail);
        return getById(id);
    }

    @Override
    public void remove(Long id) {
        ComplianceCheck exist = loadOr404(id);
        removeById(id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("checkName", exist.getCheckName());
        detail.put("framework", exist.getFramework());
        detail.put("clauseRef", exist.getClauseRef());
        detail.put("result", exist.getResult());
        detail.put("operator", operatorName());
        audit("compliance_delete", id, detail);
    }

    @Override
    public ComplianceCheckVo detailVo(Long id) {
        return toVo(loadOr404(id));
    }

    @Override
    public ComplianceCheck loadOr404(Long id) {
        ComplianceCheck check = getById(id);
        if (check == null) {
            throw new BizException(404, "检查项不存在: " + id);
        }
        return check;
    }

    // ==================== 查询（C1） ====================

    @Override
    public IPage<ComplianceCheckVo> pageFilter(String framework, String result, Boolean overdueOnly,
                                               String keyword, long page, long size) {
        LambdaQueryWrapper<ComplianceCheck> w = new LambdaQueryWrapper<>();
        if (framework != null && !framework.isBlank()) {
            w.eq(ComplianceCheck::getFramework, framework.trim());
        }
        if (result != null && !result.isBlank()) {
            w.eq(ComplianceCheck::getResult, result.trim());
        }
        if (Boolean.TRUE.equals(overdueOnly)) {
            // SQL 口径（AC-F1.11/D-10）：recheck_date < CURRENT_DATE——na 不豁免（统一口径）
            w.lt(ComplianceCheck::getRecheckDate, LocalDate.now());
        }
        if (keyword != null && !keyword.isBlank()) {
            w.like(ComplianceCheck::getCheckName, keyword);
        }
        // 默认 id DESC（PRD 未锁排序，QA 不做排序断言）
        w.orderByDesc(ComplianceCheck::getId);
        return page(new Page<>(page, size), w).convert(this::toVo);
    }

    @Override
    public ComplianceCheckVo toVo(ComplianceCheck check) {
        ComplianceCheckVo vo = new ComplianceCheckVo();
        vo.setId(check.getId());
        vo.setCheckName(check.getCheckName());
        vo.setFramework(check.getFramework());
        vo.setFrameworkName(check.getFrameworkName());
        vo.setClauseRef(check.getClauseRef());
        vo.setDescription(check.getDescription());
        vo.setResult(check.getResult());
        vo.setEvidenceNote(check.getEvidenceNote());
        vo.setCheckDate(check.getCheckDate());
        vo.setRecheckDate(check.getRecheckDate());
        vo.setOwner(check.getOwner());
        vo.setOverdue(isOverdue(check));
        vo.setCreateBy(check.getCreateBy());
        vo.setCreateTime(check.getCreateTime());
        vo.setUpdateTime(check.getUpdateTime());
        return vo;
    }

    // ==================== 内部工具 ====================

    /**
     * 写入前校验（AC-F1.8/F1.9/F1.10）：checkName/framework/result 必填与枚举 +
     * custom↔frameworkName 双向联动四例。
     */
    private void validateForWrite(ComplianceCheck check) {
        if (check == null) {
            throw new BizException(400, "请求体不能为空");
        }
        requireText(check.getCheckName(), "checkName");
        if (check.getCheckName().length() > 200) {
            throw new BizException(400, "checkName 长度不能超过 200 字符");
        }
        requireText(check.getFramework(), "framework");
        ComplianceFramework fw = ComplianceFramework.fromDbValue(check.getFramework());
        if (fw == null) {
            throw new BizException(400, "framework 非法: " + check.getFramework()
                    + "（应为 " + ComplianceFramework.legalValues() + "）");
        }
        // custom↔frameworkName 双向联动（AC-F1.9 四例）
        if (fw.isCustom()) {
            if (check.getFrameworkName() == null || check.getFrameworkName().isBlank()) {
                throw new BizException(400, "frameworkName 必填（framework=custom 时必须填写自定义框架名）");
            }
        } else if (check.getFrameworkName() != null && !check.getFrameworkName().isBlank()) {
            throw new BizException(400, "frameworkName 必须为空（framework=" + fw.dbValue()
                    + " 非 custom 时不得携带自定义框架名，防脏数据）");
        }
        requireText(check.getResult(), "result");
        if (ComplianceResult.fromDbValue(check.getResult()) == null) {
            throw new BizException(400, "result 非法: " + check.getResult()
                    + "（应为 " + ComplianceResult.legalValues() + "）");
        }
    }

    /** 逾期服务端判定（D-10，AC-F1.11）：recheckDate&lt;今天（na 不豁免）；空日期恒 false。 */
    static boolean isOverdue(ComplianceCheck check) {
        return check.getRecheckDate() != null && check.getRecheckDate().isBefore(LocalDate.now());
    }

    private Map<String, Object> writeDetail(ComplianceCheck check) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("checkName", check.getCheckName());
        detail.put("framework", check.getFramework());
        detail.put("clauseRef", check.getClauseRef());
        detail.put("result", check.getResult());
        detail.put("evidenceNote", check.getEvidenceNote());
        return detail;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(400, field + " 不能为空");
        }
    }

    private static String operatorName() {
        JwtClaims claims = LoginUser.get();
        return claims != null ? claims.getUsername() : null;
    }

    private void audit(String action, Long id, Object detail) {
        String json;
        try {
            json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(detail);
        } catch (Exception e) {
            json = "{}";
        }
        auditService.log(action, "compliance", String.valueOf(id), json);
    }
}
