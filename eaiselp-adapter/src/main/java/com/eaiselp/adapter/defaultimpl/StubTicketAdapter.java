package com.eaiselp.adapter.defaultimpl;

import com.eaiselp.adapter.spi.TicketAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

/**
 * {@link TicketAdapter} 的 stub 默认实现。
 *
 * <p>用途：未对接 Jira/禅道/GitHub Issues 等真实工单系统时占位，保证 SPI 链路完整、
 * 工厂可选注入不报"无 Bean"。所有方法记录 warn 后返回 null/false/空表，不真实落库。
 *
 * <p>条件装配：仅当 {@code eaiselp.adapter.ticket.enabled=true} 时生效（默认不启用，
 * 企业接入真实工单系统时配 enabled=true 或直接提供自研 Bean 覆盖）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "eaiselp.adapter.ticket.enabled", havingValue = "true", matchIfMissing = false)
public class StubTicketAdapter implements TicketAdapter {

    @Override public String getType() { return "ticket"; }
    @Override public String getProvider() { return "stub"; }
    @Override public boolean isAvailable() { return false; }

    @Override
    public String createTicket(String type, String title, String description, Map<String, String> attrs) {
        log.warn("[TicketAdapter-Stub] createTicket 未实现: type={}, title={}", type, title);
        return null;
    }

    @Override
    public boolean updateStatus(String ticketId, String status) {
        log.warn("[TicketAdapter-Stub] updateStatus 未实现: ticketId={}, status={}", ticketId, status);
        return false;
    }

    @Override
    public TicketInfo getTicket(String ticketId) {
        log.warn("[TicketAdapter-Stub] getTicket 未实现: ticketId={}", ticketId);
        return null;
    }

    @Override
    public java.util.List<TicketInfo> listTickets(String project, String status, int limit) {
        log.warn("[TicketAdapter-Stub] listTickets 未实现: project={}, status={}, limit={}", project, status, limit);
        return Collections.emptyList();
    }
}
