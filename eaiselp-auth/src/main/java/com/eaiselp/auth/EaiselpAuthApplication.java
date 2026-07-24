package com.eaiselp.auth;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@MapperScan("com.eaiselp.data.mapper")   // M2 Phase 1：扫 data 模块 mapper
@SpringBootApplication(scanBasePackages = {
        "com.eaiselp.auth",
        "com.eaiselp.common",
        "com.eaiselp.data"                // M2 Phase 1：让 data 的 @Service/@Configuration 被扫到
})
@EnableDiscoveryClient
public class EaiselpAuthApplication {
    public static void main(String[] args) {
        SpringApplication.run(EaiselpAuthApplication.class, args);
    }
}
