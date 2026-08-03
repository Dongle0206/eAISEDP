package com.eaiselp.adapter.spi;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 工单适配器 SPI（接 Jira / 禅道 / GitHub Issues / 等）。
 *
 * <p>EA 蓝图 §4.3 适配器体系第 4 类：企业商用需对接已有工单系统，
 * 让体系能创建/更新/查询工单，把检查点、缺陷、需求变更落进企业现有工具链。
 *
 * <p>遵循 P3 依赖单向：本接口定义在 adapter 模块，企业自研实现按 SPI 装配即可，
 * 不反向依赖上层。默认 stub 实现 {@code StubTicketAdapter} 默认不启用
 * （{@code eaiselp.adapter.ticket.enabled=true} 才装配）。
 */
public interface TicketAdapter extends Adapter {
    /**
     * 创建工单（需求 / Bug / 任务）。
     *
     * @param type        工单类型（business 不硬编码枚举，透传到具体 provider：story/bug/task/...）
     * @param title       标题
     * @param description 描述
     * @param attrs       扩展属性（assignee/priority/labels/sprint 等，provider 各自解释）
     * @return 创建后的工单 ID；失败返回 null
     */
    String createTicket(String type, String title, String description, Map<String, String> attrs);

    /**
     * 更新工单状态。
     *
     * @param ticketId 工单 ID
     * @param status   目标状态（open/in_progress/done/closed/...，provider 各自映射）
     * @return 是否更新成功
     */
    boolean updateStatus(String ticketId, String status);

    /**
     * 查询工单详情。
     *
     * @param ticketId 工单 ID
     * @return 工单信息；不存在返回 null
     */
    TicketInfo getTicket(String ticketId);

    /**
     * 列表查询。
     *
     * @param project 项目 key（provider 各自解释，可空表示全量）
     * @param status  状态过滤（可空表示不过滤）
     * @param limit   返回条数上限
     * @return 工单列表（无结果返回空表）
     */
    List<TicketInfo> listTickets(String project, String status, int limit);

    /** 工单信息。 */
    @Data
    @Builder
    class TicketInfo {
        private String id;
        private String type;
        private String title;
        private String status;
        private String assignee;
        private LocalDateTime createdAt;
    }
}
