package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eaiselp.common.exception.BizException;
import com.eaiselp.data.audit.AuditService;
import com.eaiselp.runtime.governance.dto.TemplateVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板库服务实现（V6 F1.2，case-20260820 T3）。
 *
 * <p><b>模型要点</b>：
 * <ul>
 *   <li><b>uk(tenant,type,name)</b>：DuplicateKeyException → 400"模板已存在: {type}/{name}"
 *       （统一形态，AC-F1.9 三例）；同型同名不同版本也拒（单行当前版，version 不进 uk）。</li>
 *   <li><b>类型开放字典</b>：templateType 不做枚举校验（P6 裁决，前端预置+自定义）。</li>
 *   <li><b>原地升版</b>：edit 时 version 必须 ≠ 当前值（400"版本必须变更"，不比较大小），
 *       旧版本号写审计 detail.oldVersion（AC-F1.12"旧版本仅存审计"）。</li>
 *   <li><b>占位符实时提取</b>：正则 {@code \{\{[A-Za-z0-9_]+\}\}} 去重（TreeSet）排序，
 *       不落库（DBA §2.2；AC-F1.11 无占位符 → 空清单合法）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateServiceImpl extends ServiceImpl<TemplateMapper, Template> implements TemplateService {

    private final AuditService auditService;

    /** 合法占位符：{{标识符}}，标识符限字母数字下划线（PRD §4.1.2） */
    static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([A-Za-z0-9_]+)\\}\\}");

    /** 审计序列化复用（O2 评审：对齐 Standard/DataAsset/DataQualityRule 静态 OM 先例，不再每次 new） */
    private static final com.fasterxml.jackson.databind.ObjectMapper OM =
            new com.fasterxml.jackson.databind.ObjectMapper();

    // ==================== CRUD（T1~T6） ====================

    @Override
    public Template create(Template template) {
        validateForWrite(template);
        template.setEnabled(1);
        try {
            save(template);
        } catch (DuplicateKeyException e) {
            throw new BizException(400, "模板已存在: " + template.getTemplateType() + "/" + template.getTemplateName());
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("templateType", template.getTemplateType());
        detail.put("templateName", template.getTemplateName());
        detail.put("version", template.getVersion());
        detail.put("enabled", template.getEnabled());
        detail.put("placeholders", extractPlaceholders(template.getContent()));
        audit("template_create", template.getId(), detail);
        return template;
    }

    @Override
    public Template edit(Long id, Template patch) {
        Template exist = loadOr404(id);
        validateForWrite(patch);
        // 原地升版：version 必须 ≠ 当前值（相同 400，不比较大小，AC-F1.12）
        if (patch.getVersion().equals(exist.getVersion())) {
            throw new BizException(400, "版本必须变更（当前 " + exist.getVersion() + "，原地升版不允许同版本保存）");
        }
        String oldVersion = exist.getVersion();
        Template next = new Template();
        next.setId(id);
        next.setTemplateType(patch.getTemplateType());
        next.setTemplateName(patch.getTemplateName());
        next.setVersion(patch.getVersion());
        next.setContent(patch.getContent());
        try {
            updateById(next);
        } catch (DuplicateKeyException e) {
            throw new BizException(400, "模板已存在: " + patch.getTemplateType() + "/" + patch.getTemplateName());
        }
        // 旧版本内容不落库，唯一留痕 = 审计 detail（oldVersion→newVersion，AC-F1.12）
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("templateType", next.getTemplateType());
        detail.put("templateName", next.getTemplateName());
        detail.put("oldVersion", oldVersion);
        detail.put("newVersion", next.getVersion());
        audit("template_update", id, detail);
        return getById(id);
    }

    @Override
    public void remove(Long id) {
        Template exist = loadOr404(id);
        removeById(id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("templateType", exist.getTemplateType());
        detail.put("templateName", exist.getTemplateName());
        detail.put("version", exist.getVersion());
        audit("template_delete", id, detail);
    }

    @Override
    public TemplateVo detailVo(Long id) {
        return toDetailVo(loadOr404(id));
    }

    @Override
    public Template loadOr404(Long id) {
        Template template = getById(id);
        if (template == null) {
            throw new BizException(404, "模板不存在: " + id);
        }
        return template;
    }

    @Override
    public Template toggleEnabled(Long id, Integer enabled) {
        Template exist = loadOr404(id);
        if (enabled == null || (enabled != 0 && enabled != 1)) {
            throw new BizException(400, "enabled 非法: " + enabled + "（应为 0 或 1）");
        }
        Template next = new Template();
        next.setId(id);
        next.setEnabled(enabled);
        updateById(next);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("templateType", exist.getTemplateType());
        detail.put("templateName", exist.getTemplateName());
        detail.put("enabled", enabled);
        audit("template_status", id, detail);
        return getById(id);
    }

    // ==================== 查询（T1） ====================

    @Override
    public IPage<TemplateVo> pageFilter(String templateType, String keyword, Integer includeDisabled,
                                        long page, long size) {
        LambdaQueryWrapper<Template> w = new LambdaQueryWrapper<>();
        if (templateType != null && !templateType.isBlank()) {
            // 类型精确匹配（含自定义值，开放字典，AC-F1.10）
            w.eq(Template::getTemplateType, templateType.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            w.like(Template::getTemplateName, keyword);
        }
        // 停用默认隐藏；includeDisabled=1 可见（AC-F1.12）
        if (includeDisabled == null || includeDisabled != 1) {
            w.eq(Template::getEnabled, 1);
        }
        w.orderByDesc(Template::getCreateTime);
        IPage<Template> entityPage = page(new Page<>(page, size), w);
        return entityPage.convert(this::toVo);
    }

    @Override
    public TemplateVo toVo(Template template) {
        TemplateVo vo = new TemplateVo();
        vo.setId(template.getId());
        vo.setTemplateType(template.getTemplateType());
        vo.setTemplateName(template.getTemplateName());
        vo.setVersion(template.getVersion());
        vo.setEnabled(template.getEnabled());
        vo.setPlaceholderCount(extractPlaceholders(template.getContent()).size());
        vo.setCreateBy(template.getCreateBy());
        vo.setCreateTime(template.getCreateTime());
        vo.setUpdateTime(template.getUpdateTime());
        return vo;
    }

    @Override
    public TemplateVo toDetailVo(Template template) {
        TemplateVo vo = toVo(template);
        vo.setContent(template.getContent());
        vo.setPlaceholders(extractPlaceholders(template.getContent()));
        return vo;
    }

    @Override
    public List<String> extractPlaceholders(String content) {
        TreeSet<String> set = new TreeSet<>();
        if (content == null || content.isBlank()) {
            return List.of();
        }
        Matcher m = PLACEHOLDER_PATTERN.matcher(content);
        while (m.find()) {
            set.add(m.group(1));
        }
        return List.copyOf(set);
    }

    // ==================== 内部工具 ====================

    /** 写入前校验：type/name/version/content 必填（type 开放字典不校验枚举，P6 裁决）。 */
    private void validateForWrite(Template template) {
        if (template == null) {
            throw new BizException(400, "请求体不能为空");
        }
        requireText(template.getTemplateType(), "templateType");
        requireText(template.getTemplateName(), "templateName");
        if (template.getTemplateName().length() > 200) {
            throw new BizException(400, "templateName 长度不能超过 200 字符");
        }
        requireText(template.getVersion(), "version");
        requireText(template.getContent(), "content");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(400, field + " 不能为空");
        }
    }

    private void audit(String action, Long id, Object detail) {
        String json;
        try {
            json = OM.writeValueAsString(detail);
        } catch (Exception e) {
            json = "{}";
        }
        auditService.log(action, "template", String.valueOf(id), json);
    }
}
