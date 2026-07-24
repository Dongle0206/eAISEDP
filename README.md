# eAISEDP Backend

> 企业级多 Agent 协同运作体系（AISOps）承载平台 —— 后端微服务集群。
>
> 商业化产品，承载整套体系 L3→L1 端到端运作，支持多租户（SaaS + 私有化混合），dogfooding（用体系造平台自己）。

## 架构

```
eAISEDP-system   体系配置仓库（21 角色 + 25 skills）—— clone 到 ../agents-config
eAISEDP          后端主仓库（本仓库，8 微服务 + 1 公共模块）
eAISEDP-web      前端仓库（HTML+JS+jQuery）
```

## 微服务

| 层 | 服务 | 端口 | 职责 |
|---|---|---|---|
| L5 | eaiselp-gateway | 8080 | API 网关 |
| L5 | eaiselp-auth | 8085 | 认证授权 |
| L4 | eaiselp-runtime | 8081 | **核心**：派生引擎 |
| L3 | eaiselp-capability | 8082 | 体系 markdown 加载 |
| L2 | eaiselp-data | 8083 | case/产物/里程碑 |
| L1 | eaiselp-adapter | 8084 | 适配器 SPI |
| L1 | eaiselp-observability | 8086 | 可观测 |
| 管理 | eaiselp-admin | 8087 | 管理后台 |
| 公共 | eaiselp-common | - | DTO/多租户/异常 |

## 快速开始

```bash
# 1. 启动基础设施
docker compose up -d

# 2. 编译（需 JDK 17+，开发用 JDK 26）
set JAVA_HOME=D:\工具\jdk-26.0.1
mvn clean compile -DskipTests

# 3. 启动服务
# 先启动 capability/data/adapter，再 runtime，最后 gateway
```

## 核心铁律

1. **承载不重写**：角色/skill 全从 markdown 加载
2. **唯一调度入口**：所有派生必经 DerivationEngine
3. **多租户隔离**：全表 tenant_id + 拦截器自动注入
4. **适配器 SPI**：企业接入零代码侵入

详见 DESIGN.md。
