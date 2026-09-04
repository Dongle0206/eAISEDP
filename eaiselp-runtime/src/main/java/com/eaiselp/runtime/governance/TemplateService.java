package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.eaiselp.runtime.governance.dto.TemplateVo;

import java.util.List;

/**
 * 模板库服务接口（V6 F1.2，case-20260820 T3；契约=api-contracts §2 T1~T6）。
 *
 * <p>CRUD（uk(tenant,type,name) 三例语义/类型开放字典不校验/原地升版版本必变更/启停）+
 * 占位符实时提取。本期内仅管理，不做编排注入（PRD §7-1 范围外）。</p>
 */
public interface TemplateService extends IService<Template> {

    /**
     * 创建模板（enabled 默认 1）。
     *
     * <p>type/name/version/content 必填；type 开放字典不校验枚举（P6 裁决）；
     * uk(tenant,type,name) 冲突 → 400"已存在"（AC-F1.9）。审计 template_create。</p>
     */
    Template create(Template template);

    /**
     * 编辑模板（原地升版，AC-F1.12）。
     *
     * <p>version 必须 ≠ 当前值（相同 → 400"版本必须变更"，不比较大小）；旧版本仅存审计
     * detail（template_update 含 oldVersion）；uk 冲突 400 兜底。审计 template_update。</p>
     */
    Template edit(Long id, Template patch);

    /** 逻辑删。审计 template_delete。 */
    void remove(Long id);

    /** 详情 Vo（全字段 + placeholders 实时提取清单）。跨租户/不存在 → 404。 */
    TemplateVo detailVo(Long id);

    /** 实体详情（跨租户/不存在 → 404）。 */
    Template loadOr404(Long id);

    /** 启停（T6，形态对齐 gate-rules PUT /{id}/enabled 先例）。审计 template_status。 */
    Template toggleEnabled(Long id, Integer enabled);

    /**
     * 列表筛选（T1，AC-F1.10/F1.12）：templateType（精确匹配，含自定义值）/ keyword（name LIKE）/
     * includeDisabled（缺省 0=仅 enabled=1；停用默认隐藏、勾选可见）。分页 createTime 倒序。
     */
    IPage<TemplateVo> pageFilter(String templateType, String keyword, Integer includeDisabled,
                                 long page, long size);

    /** 实体→Vo（列表形态：placeholderCount 实时统计，不带 content 清单）。 */
    TemplateVo toVo(Template template);

    /** 实体→详情 Vo（带 placeholders 完整清单 + content 全文）。 */
    TemplateVo toDetailVo(Template template);

    /** 从 content 实时提取合法 {{标识符}} 占位符清单（去重排序；无 → 空列表，AC-F1.11）。 */
    List<String> extractPlaceholders(String content);
}
