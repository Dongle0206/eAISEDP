package com.eaiselp.adapter.defaultimpl;

import com.eaiselp.adapter.spi.IMAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link IMAdapter} 的 stub 默认实现。
 *
 * <p>用途：未对接钉钉/飞书/企业微信/Slack 等真实 IM 渠道时占位，保证 SPI 链路完整、
 * 工厂可选注入不报"无 Bean"。所有方法记录 warn 后返回 false，不真实推送消息。
 *
 * <p>条件装配：仅当 {@code eaiselp.adapter.im.enabled=true} 时生效（默认不启用，
 * 企业接入真实 IM 时配 enabled=true 或直接提供自研 Bean 覆盖）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "eaiselp.adapter.im.enabled", havingValue = "true", matchIfMissing = false)
public class StubIMAdapter implements IMAdapter {

    @Override public String getType() { return "im"; }
    @Override public String getProvider() { return "stub"; }
    @Override public boolean isAvailable() { return false; }

    @Override
    public boolean sendMessage(String target, String content, String msgType) {
        log.warn("[IMAdapter-Stub] sendMessage 未实现: target={}, msgType={}", target, msgType);
        return false;
    }

    @Override
    public boolean sendCard(String target, String title, String content, List<Action> actions) {
        log.warn("[IMAdapter-Stub] sendCard 未实现: target={}, title={}", target, title);
        return false;
    }
}
