package com.eaiselp.runtime;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan({               // M2 SP-6 扩展：adapter 模块新增 ModelRoutingMapper（ModelRoutingService 放 adapter 内部，ES-003 §9.2/P3），一并扫描
        "com.eaiselp.data.mapper",
        "com.eaiselp.adapter.routing.mapper"
})
@SpringBootApplication(scanBasePackages = {
        "com.eaiselp.runtime",
        "com.eaiselp.common",
        "com.eaiselp.capability",
        "com.eaiselp.adapter",
        "com.eaiselp.data"        // M1.2 新增：让 data 模块的 @Service / @Configuration 被 Spring 扫到
})
@EnableDiscoveryClient
@EnableFeignClients
@EnableScheduling   // 迁移自 EaiselpCapabilityApplication（capability CapabilityLoader 有 @Scheduled 30s 热重载）
public class EaiselpRuntimeApplication {
    public static void main(String[] args) {
        SpringApplication.run(EaiselpRuntimeApplication.class, args);
    }
}
