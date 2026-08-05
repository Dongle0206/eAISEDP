package com.eaiselp.common.web;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 全局配置（D1 阻断修复：雪花 ID Long→String）。
 *
 * <p>后端主键 id 使用 MyBatis-Plus {@code ASSIGN_ID}（雪花算法），其值域超出 JS Number 安全范围
 * （2^53-1 = 9007199254740991）。若以裸 JSON 数字返回，前端 {@code JSON.parse} 会丢失精度，
 * 导致列表点详情时传错 id → 404。</p>
 *
 * <p>修复：全局把所有 Long / long 序列化为字符串。这是雪花 ID + 前端 JS 的行业标配做法。
 * 前端拿到的就是 {@code "id":"2084826478478860312"}，彻底消除精度丢失。</p>
 *
 * <p>放在 common 模块，auth/runtime/admin 所有服务自动生效（Spring Boot 自动扫描）。</p>
 *
 * @see com.eaiselp.common.entity.BaseEntity#id (Long, ASSIGN_ID 雪花)
 */
@Configuration
public class JacksonLongConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule();
            // Long.class = 包装类型（实体字段、DTO），Long.TYPE = long 基本类型
            module.addSerializer(Long.class, ToStringSerializer.instance);
            module.addSerializer(Long.TYPE, ToStringSerializer.instance);
            builder.modulesToInstall(module);
        };
    }
}
