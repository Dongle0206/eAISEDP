package com.eaiselp.data.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.IService;
import com.eaiselp.data.entity.Derivation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 派生记录服务接口（MyBatis-Plus IService 模式）。
 * M2 SP-1 补 listByCaseId：Case 详情页派生时间线查询用。
 * M2 SP-7 补月度量查询：配额校验（H）+ 看板派生统计（G）共用。
 */
public interface DerivationService extends IService<Derivation> {

    /** 按案例 ID 查派生记录列表（按创建时间倒序）。 */
    default List<Derivation> listByCaseId(String caseId) {
        return this.list(new LambdaQueryWrapper<Derivation>()
                .eq(Derivation::getCaseId, caseId)
                .orderByDesc(Derivation::getCreateTime));
    }

    /**
     * 统计某时间点之后创建的派生记录数（M2 SP-7 配额校验：当月派生次数）。
     *
     * <p>多租户：MyBatis-Plus 租户拦截器自动注入 tenant_id 过滤（ES-003 §9.3，G13）。
     *
     * @param since 起始时间（含），通常为当月 1 日 0 点
     * @return 派生记录条数；无数据返回 0
     */
    default long countSince(LocalDateTime since) {
        return this.count(new LambdaQueryWrapper<Derivation>()
                .ge(Derivation::getCreateTime, since));
    }

    /**
     * 统计某时间点之后派生的 token 消耗总和（input + output，M2 SP-7 配额校验：当月 token）。
     *
     * <p>用 {@code selectMaps} 单行单列取聚合值，避免 ServiceImpl 引入自定义 SQL。
     * token 估算 = SUM(input_tokens) + SUM(output_tokens)。
     *
     * <p>多租户：同 {@link #countSince}，租户拦截器自动过滤。
     *
     * @param since 起始时间（含），通常为当月 1 日 0 点
     * @return token 总消耗；无数据返回 0
     */
    default long sumTokensSince(LocalDateTime since) {
        QueryWrapper<Derivation> qw = new QueryWrapper<Derivation>()
                .select("IFNULL(SUM(input_tokens),0)+IFNULL(SUM(output_tokens),0) AS total")
                .ge("create_time", since);
        Map<String, Object> row = this.getMap(qw);
        if (row == null || row.isEmpty()) {
            return 0L;
        }
        Object total = row.values().iterator().next();
        if (total == null) {
            return 0L;
        }
        // MP getMap 列类型按 DB 驱动返回，可能是 Number 也可能是 String（H2/MySQL 差异），统一转 long
        return Long.parseLong(total.toString());
    }

    /**
     * 按角色分组统计派生次数 + token 消耗（M2 SP-7 看板 G：derivation-stats）。
     *
     * <p>返回行结构：role → {count, totalTokens}。
     * count = 该角色派生记录条数；totalTokens = 该角色 SUM(input+output)。
     *
     * <p>多租户：租户拦截器自动过滤（G13）。
     *
     * @return 按角色聚合的统计行列表（每行含 role / count / totalTokens 三个 key）
     */
    default List<Map<String, Object>> countAndTokensByRole() {
        QueryWrapper<Derivation> qw = new QueryWrapper<Derivation>()
                .select("role",
                        "COUNT(*) AS count",
                        "IFNULL(SUM(input_tokens),0)+IFNULL(SUM(output_tokens),0) AS totalTokens")
                .groupBy("role");
        return this.listMaps(qw);
    }

    /**
     * 按角色分组统计派生次数 + token 消耗（指定时间起，M2 SP-7 看板可按月/全量）。
     *
     * <p>多租户：租户拦截器自动过滤（G13）。
     *
     * @param since 起始时间（含），null 表示全量
     * @return 按角色聚合的统计行列表
     */
    default List<Map<String, Object>> countAndTokensByRoleSince(LocalDateTime since) {
        QueryWrapper<Derivation> qw = new QueryWrapper<Derivation>()
                .select("role",
                        "COUNT(*) AS count",
                        "IFNULL(SUM(input_tokens),0)+IFNULL(SUM(output_tokens),0) AS totalTokens")
                .groupBy("role");
        if (since != null) {
            qw.ge("create_time", since);
        }
        return this.listMaps(qw);
    }
}
