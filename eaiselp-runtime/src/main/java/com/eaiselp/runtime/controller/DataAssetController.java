package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaiselp.common.result.R;
import com.eaiselp.common.security.RequirePermission;
import com.eaiselp.runtime.governance.DataAsset;
import com.eaiselp.runtime.governance.DataAssetService;
import com.eaiselp.runtime.governance.dto.DataAssetVo;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
/**
 * 数据资产目录 REST API（case-20260820 T4，路径前缀 /api/v1/data-assets，契约=api-contracts §3）。
 *
 * <p><b>不限层</b>（AC-SWITCH.1）：不注册 LayerGuard。薄控制器：必填/枚举/uk 校验全在
 * {@link DataAssetService}。A5 删除联动（关联质量规则同步逻辑删）与 A3 聚合在 Service。</p>
 *
 * <p>权限（V6 seed 1065~1067）：读 {@code asset:view}、建 {@code asset:create}、
 * 改/删 {@code asset:edit}。写审计（asset_create/update/delete）在 Service。</p>
 */
@RestController
@RequestMapping("/api/v1/data-assets")
@RequiredArgsConstructor
public class DataAssetController {

    private final DataAssetService assetService;

    /** 列表筛选（A1 四维）：assetType / sensitivity / tag（单选命中）/ keyword（name+system LIKE）。 */
    @GetMapping
    @RequirePermission("asset:view")
    public R<IPage<DataAssetVo>> page(@RequestParam(defaultValue = "1") long page,
                                      @RequestParam(defaultValue = "20") long size,
                                      @RequestParam(required = false) String assetType,
                                      @RequestParam(required = false) String sensitivity,
                                      @RequestParam(required = false) String tag,
                                      @RequestParam(required = false) String keyword) {
        return R.ok(assetService.pageFilter(assetType, sensitivity, tag, keyword, page, size));
    }

    /** 创建资产（A2）：双枚举校验非法 400 指名字段与合法值集合（AC-F2.2）。 */
    @PostMapping
    @RequirePermission("asset:create")
    public R<DataAssetVo> create(@RequestBody AssetSaveRequest req) {
        DataAsset created = assetService.create(toEntity(req));
        return R.ok(assetService.toVo(created));
    }

    /** 详情（A3）：全字段 + rules 聚合区（关联规则数与各规则最近结果，AC-F2.6 Then）。 */
    @GetMapping("/{id}")
    @RequirePermission("asset:view")
    public R<DataAssetVo> get(@PathVariable Long id) {
        return R.ok(assetService.detailVo(id));
    }

    /** 编辑资产（A4，校验同 A2）。 */
    @PutMapping("/{id}")
    @RequirePermission("asset:edit")
    public R<DataAssetVo> update(@PathVariable Long id, @RequestBody AssetSaveRequest req) {
        DataAsset updated = assetService.edit(id, toEntity(req));
        return R.ok(assetService.toVo(updated));
    }

    /** 逻辑删（A5，AC-F2.7 联动）：关联质量规则同步逻辑删，审计 detail 含 ruleIds。 */
    @DeleteMapping("/{id}")
    @RequirePermission("asset:edit")
    public R<Void> delete(@PathVariable Long id) {
        assetService.remove(id);
        return R.ok();
    }

    // ------------------------------------------------------------------
    // 请求 DTO 与内部工具
    // ------------------------------------------------------------------

    /** 资产创建/编辑请求（契约 §3 A2）。 */
    @Data
    public static class AssetSaveRequest {
        private String assetName;
        private String systemName;
        /** database / table / api / report / file */
        private String assetType;
        private String owner;
        /** public / internal / sensitive / confidential */
        private String sensitivity;
        private String description;
        /** 标签列表（自由值） */
        private List<String> tags;
    }

    /** 请求 → 实体（tags List → JSON 数组 String 承载，同关联数组先例）。 */
    private static DataAsset toEntity(AssetSaveRequest req) {
        DataAsset a = new DataAsset();
        a.setAssetName(req.getAssetName());
        a.setSystemName(req.getSystemName());
        a.setAssetType(req.getAssetType());
        a.setOwner(req.getOwner());
        a.setSensitivity(req.getSensitivity());
        a.setDescription(req.getDescription());
        a.setTags(StandardController.toJson(req.getTags()));
        return a;
    }
}
