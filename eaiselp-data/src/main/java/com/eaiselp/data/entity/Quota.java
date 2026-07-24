package com.eaiselp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_quota")
public class Quota extends BaseEntity {
    private String period;
    private Long tokenLimit;
    private Long tokenUsed;
    private Integer caseLimit;
    private Integer caseUsed;
    private Integer derivationLimit;
    private Integer derivationUsed;
    private Long storageLimitMb;
    private Long storageUsedMb;
    private BigDecimal monthlyCost;
    private Integer alertThreshold;
}
