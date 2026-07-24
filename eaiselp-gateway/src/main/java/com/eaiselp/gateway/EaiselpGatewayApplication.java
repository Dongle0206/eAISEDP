package com.eaiselp.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = {"com.eaiselp.gateway", "com.eaiselp.common"})
@EnableDiscoveryClient
public class EaiselpGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(EaiselpGatewayApplication.class, args);
    }
}
