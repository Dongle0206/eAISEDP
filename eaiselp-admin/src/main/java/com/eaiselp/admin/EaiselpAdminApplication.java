package com.eaiselp.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = {"com.eaiselp.admin", "com.eaiselp.common"})
@EnableDiscoveryClient
@EnableFeignClients
public class EaiselpAdminApplication {
    public static void main(String[] args) {
        SpringApplication.run(EaiselpAdminApplication.class, args);
    }
}
