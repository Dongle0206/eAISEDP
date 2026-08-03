package com.eaiselp.adapter.spi;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 即时通讯适配器 SPI（接钉钉 / 飞书 / 企业微信 / Slack / 等）。
 *
 * <p>EA 蓝图 §4.3 适配器体系第 6 类：企业商用需把检查点审批、阶段产物、
 * 关键通知推送到既有 IM 渠道，让体系融入企业现有协作流。
 *
 * <p>遵循 P3 依赖单向：接口定义在 adapter 模块，企业自研实现按 SPI 装配。
 * 默认 stub 实现 {@code StubIMAdapter} 默认不启用
 * （{@code eaiselp.adapter.im.enabled=true} 才装配）。
 */
public interface IMAdapter extends Adapter {
    /**
     * 发送消息到群/个人。
     *
     * @param target 目标标识（群 ID / 用户 ID / 手机号，provider 各自解释）
     * @param content 消息内容
     * @param msgType 消息类型（text/markdown/...，provider 各自映射）
     * @return 是否发送成功
     */
    boolean sendMessage(String target, String content, String msgType);

    /**
     * 发送卡片消息（检查点审批等带操作按钮的富消息）。
     *
     * @param target   目标标识
     * @param title    卡片标题
     * @param content  卡片正文
     * @param actions  操作按钮列表（provider 各自渲染）
     * @return 是否发送成功
     */
    boolean sendCard(String target, String title, String content, List<Action> actions);

    /** 卡片操作按钮。 */
    @Data
    @Builder
    class Action {
        private String label;
        private String url;
        /** actionType：link / callback / ...（provider 各自解释） */
        private String actionType;
    }
}
