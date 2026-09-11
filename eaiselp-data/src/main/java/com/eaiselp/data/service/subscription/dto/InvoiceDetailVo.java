package com.eaiselp.data.service.subscription.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 账单详情 VO（I2/C3 出参）= InvoiceVo 全字段 + detail JSON 解析回显（附 A 结构：
 * 套餐五字段快照 + tokenUsed/tokenLimit/tokenOverageTokens/ktokenBilled/derivationCount/
 * caseCount/storageUsedMb + firstMonthProration 四元组）。detail 任何位置禁止出现
 * t_derivation.cost（§8.4 红线，QA 序列化 grep 断言）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class InvoiceDetailVo extends InvoiceVo {
    /** detail JSON 解析后的结构化回显（解析失败回显 null） */
    private Map<String, Object> detail;
}
