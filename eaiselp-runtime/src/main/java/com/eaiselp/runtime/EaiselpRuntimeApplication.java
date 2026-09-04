package com.eaiselp.runtime;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

// QA 20260818 缺陷修复（P0，上报 Dev 复核）：annotationClass=Mapper.class 必须显式指定——
// hierarchy 包内除六个 *Mapper 外还有 StrategyService 等 Service 接口，整包扫描会把它们也注册成
// MapperFactoryBean（bean 名 qualityGateRuleService 等），与 @Service 实现类构成同类型双 bean，
// 按类型注入即 NoUniqueBeanDefinitionException → Spring 上下文启动失败（生产启动同样失败）。
// 该缺陷此前被 test profile 的 Flyway/H2 冲突掩盖（上下文更早失败）。四包内全部 17 个 mapper
// 均带 @Mapper（已逐一核验），annotationClass 收窄对 mapper 注册零行为变化。
@MapperScan(
        annotationClass = Mapper.class,
        basePackages = {          // M2 SP-6 扩展：adapter 模块新增 ModelRoutingMapper（ModelRoutingService 放 adapter 内部，ES-003 §9.2/P3），一并扫描
                "com.eaiselp.data.mapper",
                "com.eaiselp.adapter.routing.mapper",
                "com.eaiselp.runtime.orchestration",   // #15 编排持久化 OrchestrationRecordMapper
                "com.eaiselp.runtime.hierarchy",       // PRJ-002 T04：三层贯通六实体 Mapper（Strategy/Program/Project/ArchitecturePrinciple/QualityGateRule/ProjectPrinciple）
                "com.eaiselp.runtime.governance"       // case-20260820 T1：L2 治理收口四域 Mapper（Standard/Template/DataAsset/DataQualityRule，D-1 新包）
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
