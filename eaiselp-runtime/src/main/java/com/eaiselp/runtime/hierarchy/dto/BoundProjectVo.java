package com.eaiselp.runtime.hierarchy.dto;

import lombok.Data;

/**
 * 原则维度已绑定项目条目（PRJ-002 批4 PrincipleController GET /{id}/projects 响应）。
 */
@Data
public class BoundProjectVo {

    private Long projectId;
    private String projectName;
    /** 项目级覆盖位（false = 本项目停用但保留绑定） */
    private Boolean enabled;
}
