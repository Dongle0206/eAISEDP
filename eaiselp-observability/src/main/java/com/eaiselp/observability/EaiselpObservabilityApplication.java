package com.eaiselp.observability;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = {"com.eaiselp.observability", "com.eaiselp.common"})
@EnableDiscoveryClient
public class EaiselpObservabilityApplication {
    public static void main(String[] args) {
        SpringApplication.run(EaiselpObservabilityApplication.class, args);
    }
}
