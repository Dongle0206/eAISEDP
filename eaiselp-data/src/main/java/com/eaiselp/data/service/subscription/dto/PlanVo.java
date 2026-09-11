package com.eaiselp.data.service.subscription.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 套餐 VO（P1~P4 出参；字段名按 V8 列名映射——enabled Boolean（差异定稿 D-1，非 status），
 * features 结构化回显为 Map（服务端已校验结构，前端不裸编辑 JSON）。
 */
@Data
@Builder
public class PlanVo {
    private Long id;
    private String code;
    private String name;
    private BigDecimal monthlyPrice;
    private BigDecimal tokenOveragePrice;
    private String slaLevel;
    /** true=在架 / false=下架（V8 enabled 列语义） */
    private Boolean enabled;
    /** custom 套餐的目标 edition；非 custom 为 null */
    private String editionOverride;
    /** 功能开关结构化回显（解析自 features JSON；解析失败回显原始文本键 __raw 兜底） */
    private Map<String, Object> features;
    private String createBy;
    private String createTime;
}
