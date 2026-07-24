# eAISEDP Platform 设计方案（v2.0 · 微服务 + 适配器架构）

> 承载 AISOps 体系（21 角色 + 25 skills + 三层架构）的运行时平台。
> 三大约束：① 保留企业已有资产 ② 适配代价最低 ③ 支持新建+渐进演进。
>
> **平台定位**：商业化产品（不是工具）。承载整套体系 L3→L1 端到端运作，支持多租户（SaaS + 私有化混合），自带 dogfooding（用体系造平台自己）。

---

## 0. 关键约束（贯穿全设计）

| 约束 | 架构落地 |
|---|---|
| **保留已有资产** | 平台不自带基础设施，所有外部对接走**适配器 SPI**；企业已有 Jira/GitLab/Jenkins 不动，平台适配它们 |
| **适配代价最低** | 适配器按需启用，企业工具零改动；先跑通核心再逐个接适配器 |
| **新建+渐进演进** | 新企业用默认外部依赖（MySQL+MinIO+Git）零配置起步；老企业逐步把默认适配器换成企业适配器 |
| **微服务架构** | Spring Cloud 微服务集群，每服务独立部署/扩容 |
| **前后端分离** | 后端 REST API + 前端 HTML+JS+jQuery（基础技术）|
| **体系独立演进** | 体系配置独立 Git 仓库（eAISEDP-system），后端 submodule 引用，平台加载不重写 |

---

## 1. 三仓库结构

```
GitHub:
├── eAISEDP-system   体系配置仓库（agents-config/，21 角色 + 25 skills + 编排方法论）
├── eAISEDP          后端主仓库（Spring Cloud 微服务集群）
│   └── eAISEDP-system/ ← submodule 指向上面仓库
└── eAISEDP-web      前端仓库（HTML+JS+jQuery）

本地工程：
D:\AI\mywork\
├── agents-config/        体系配置（已从 eAISEDP-system clone）
├── platform/             后端工程（推送到 eAISEDP）
│   ├── DESIGN.md         本文件
│   └── [微服务模块]
└── _manual/              手册与架构图
```

---

## 2. 五层架构（适配器模式调整版）

```
┌─────────────────────────────────────────────────────┐
│ L5 接入层  Web 控制台(jQuery+HTML) / IDE 插件 / API   │
├─────────────────────────────────────────────────────┤
│ L4 编排运行时  派生引擎/状态机/门禁/检查点/计量        │ ← 核心
├─────────────────────────────────────────────────────┤
│ L3 能力层  体系 markdown 加载/热更新（不重写）         │
├─────────────────────────────────────────────────────┤
│ L2 数据层  case/产物/里程碑/追溯（data-contract 承载）│
├─────────────────────────────────────────────────────┤
│ L1 基础设施层  适配器 SPI（默认实现 + 企业实现可换）   │ ← 关键
│   ├── GitAdapter        默认:本地Git  企业:GitLab/Gitea
│   ├── LlmAdapter        默认:GLM      企业:多供应商路由
│   ├── DocStoreAdapter   默认:MinIO    企业:企业文档系统
│   ├── TicketAdapter     默认:内置工单  企业:Jira/Plane
│   ├── CiCdAdapter       默认:无       企业:Jenkins/GitLabCI
│   ├── ImAdapter         默认:无       企业:钉钉/企微
│   └── McpAdapter        默认:无       企业:MCP 工具集
└─────────────────────────────────────────────────────┘
       ↑ 所有适配器走 SPI，企业按需启用，平台核心不感知具体实现
```

---

## 3. 技术选型

| 层 | 技术 | 理由 |
|---|---|---|
| **后端** | Java 17+ + Spring Boot 3.2 + Spring Cloud 2023 + Spring Cloud Alibaba（Nacos）+ MyBatis-Plus | 微服务企业标配 |
| **状态机** | Spring Statemachine | 表达阶段流程/门禁/检查点 |
| **LLM** | LangChain4j 或 Spring AI | Java LLM 框架 |
| **前端** | HTML + 原生 JS + jQuery 3 + Bootstrap 5 + Handlebars + ECharts | 基础技术栈 |
| **数据库** | MySQL 8（结构化）| 企业标配 |
| **文档存储** | MinIO（对象存储，S3 兼容）| 开源 |
| **代码仓库** | Git（默认）/ GitLab（企业适配器）| |
| **注册中心** | Nacos | Spring Cloud 生态 |
| **可观测** | Prometheus + Grafana + Micrometer | Java 原生集成 |
| **部署** | Docker Compose（M1/M2）→ K8s（规模化）| 分阶段 |

---

## 4. 微服务拆分（8 + 1 公共）

```
eAISEDP 后端微服务集群：
├── eaiselp-gateway          API 网关（Spring Cloud Gateway，路由+鉴权+限流）
├── eaiselp-auth             认证授权（多租户用户/角色/权限，可接 LDAP/SSO）
├── eaiselp-runtime          【核心】L4 编排运行时（派生引擎/状态机/门禁/检查点）
├── eaiselp-capability       L3 能力加载（体系 markdown 加载/热更新/版本）
├── eaiselp-data             L2 数据服务（case/产物/里程碑/追溯）
├── eaiselp-adapter          L1 适配器服务（SPI + 默认实现 + 企业实现注册）
├── eaiselp-observability    可观测服务（埋点采集/指标聚合）
├── eaiselp-admin            管理后台服务（体系管理/适配器配置/治理）
└── eaiselp-common           公共模块（DTO/工具/异常/多租户/常量）
```

**服务间通信**：OpenFeign（同步）+ RabbitMQ/RocketMQ（异步事件）。

---

## 5. 适配器 SPI 设计（保留已有资产的关键）

```java
public interface Adapter {
    String getType();          // git/llm/docstore/ticket/cicd/im/mcp
    String getProvider();      // default/gitlab/gitea/glm/openai...
    boolean isAvailable();
}

public interface GitAdapter extends Adapter { ... }
public interface LlmAdapter extends Adapter { ... }
public interface DocStoreAdapter extends Adapter { ... }
// + TicketAdapter / CiCdAdapter / ImAdapter / McpAdapter
```

**配置驱动切换**（application.yml）：
```yaml
adapter:
  git:
    provider: default       # default / gitlab / gitea
  llm:
    provider: glm           # glm / openai / azure
  docstore:
    provider: default       # default(MinIO) / confluence
  ticket:
    provider: none          # M3 启用
```

**核心**：切换适配器只改配置，平台核心代码零改动。企业接入零代码侵入。

---

## 6. 多租户模型（混合模式）

- 所有业务表加 `tenant_id` + MyBatis-Plus 多租户拦截器自动注入
- 配置开关：`eaiselp.tenant.mode=shared/saas`（默认）/ `private`（私有化）
- TenantContext（ThreadLocal）+ TenantContextFilter（请求解析）
- 商业化：一个平台多个企业租户，数据严格隔离

---

## 7. 计费策略（M1/M2 暂不计费仅采集）

- M1/M2 不做计费，但所有派生都埋点：tenant_id + role + case_id + token + cost
- t_quota 表（token/case/派生/存储配额）为未来计费打基础
- 计费规则留接口，未来加配置即可启用

---

## 8. Dogfooding（用体系造平台自己）

平台开发本身用 AISOps 体系 L3→L1 端到端跑：
```
L3：EA 出平台架构蓝图 + GRC 治理平台开发合规
   ↓ 全部产出存进平台自己
L2：PgM 把"平台开发"拆成项目群 + Steward 治理平台数据
   ↓ 全部产出存进平台自己
L1：14 角色按 case 流水线造平台（PO 出 PRD/SE 出方案/Dev 写代码/Reviewer 评审/Ops 部署）
   ↓ 全部产出存进平台自己
最终：平台开发完成 = 平台自己用自己跑通了 L3→L1 全链路 = 商业化交付的可信背书
```

---

## 9. 里程碑

- **M1.0**：最小运行时（capability + runtime + adapter + data），手调派生跑通
- **M1.1**：启动 dogfooding（用体系造平台 L3→L1）
- **M2**：核心闭环 + 多租户 + Web 工作台
- **M3-M6**：数据层完整/协同层/治理层/增强（MCP+IDE 插件）

---

## 10. 核心铁律

1. **承载不重写**：平台代码不含角色名/skill 名/流程阶段硬编码，全部从 markdown 加载
2. **唯一调度入口**：所有角色派生必经 DerivationEngine
3. **多租户隔离**：所有业务表带 tenant_id，MyBatis-Plus 拦截器自动注入
4. **适配器 SPI**：外部对接走接口，企业实现按需启用，配置驱动切换
5. **dogfooding**：平台开发本身用体系 L3→L1 端到端跑，产出全部入平台自己
