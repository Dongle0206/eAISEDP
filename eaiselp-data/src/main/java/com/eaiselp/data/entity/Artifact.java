package com.eaiselp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.eaiselp.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_artifact")
public class Artifact extends BaseEntity {
    private String caseId;
    private String role;
    private String stage;
    private String type;
    private String title;
    private String content;
    private String docKey;
    private String frontmatter;
    private Long derivationId;
    private String contractKey;
}
