package com.eaiselp.runtime.governance;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.eaiselp.runtime.governance.dto.DataAssetVo;

import java.util.List;

/**
 * 数据资产目录服务接口（V6 F2.1，case-20260820 T4；契约=api-contracts §3 A1~A5）。
 *
 * <p>CRUD（uk(tenant,system,name)/双枚举校验/四维筛选）+ 详情聚合（关联质量规则数与各规则
 * 最近结果，支撑 AC-F2.6 Then）+ 删除联动（资产逻辑删 → 关联规则同步逻辑删，AC-F2.7）。</p>
 */
public interface DataAssetService extends IService<DataAsset> {

    /**
     * 创建资产（A2）。
     *
     * <p>assetName/systemName/assetType/sensitivity 必填；assetType/sensitivity 枚举校验
     * （非法 400 指名字段与合法值集合，AC-F2.2）；uk(tenant,system,name) 冲突 400（AC-F2.1）。
     * 审计 asset_create。</p>
     */
    DataAsset create(DataAsset asset);

    /** 编辑资产（A4，校验同 A2）。审计 asset_update。 */
    DataAsset edit(Long id, DataAsset patch);

    /**
     * 逻辑删（A5，AC-F2.7 联动）：资产逻辑删 → 关联质量规则同步逻辑删
     * （idx_dqr_tenant_asset 定位）；审计 asset_delete detail 含 ruleIds 数组可辨识。
     */
    void remove(Long id);

    /** 详情 Vo（A3：全字段 + rules 聚合区——规则数与各规则最近结果）。跨租户/不存在 → 404。 */
    DataAssetVo detailVo(Long id);

    /** 实体详情（跨租户/不存在 → 404）。 */
    DataAsset loadOr404(Long id);

    /**
     * 列表筛选（A1，AC-F2.3/F2.4 四维）：assetType/sensitivity（枚举值精确）/
     * tag（单选命中，JSON 内存过滤）/ keyword（assetName+systemName LIKE）。分页 createTime 倒序。
     */
    IPage<DataAssetVo> pageFilter(String assetType, String sensitivity, String tag, String keyword,
                                  long page, long size);

    /** 实体→Vo（tags JSON 解析；解析失败容忍降级空列表）。 */
    DataAssetVo toVo(DataAsset asset);

    /** 汇总标签候选（供前端筛选器聚合，无独立标签管理页，AC-F2.4）。 */
    List<String> aggregateTags();
}
