# 技术方案 — DerivationEngine 持久化（case-20260722-DerivationEngine持久化）

| 字段 | 值 |
|---|---|
| 编号 | case-20260722-DerivationEngine持久化 |
| 标题 | DerivationEngine 派生结果落库 + data 模块 Service 层补全 + IMP-003 清理 |
| 里程碑 | M1.2（runtime 引入 data 的第一步，M2 数据接入前置） |
| 日期 | 2026-07-21 |
| 作者 | team-se（L1 系统工程师 / Tech Lead） |
| 上游约束 | ADR-001 P1-P5 / ES-001 §1-§5 / ES-002 §1-§4 / CLAUDE.md §4 |
| 适用角色 | L1-Dev（执行改造）/ L1-Reviewer（按 ES-001 §6 checklist 验收）/ L1-QA（按 §4 回归步骤验证） |
| 产物落盘 | 本文档本身（ES-002 §1.2 SE 必落盘规则，已 Write 落盘 + Test-Path 自检） |

---

## 0. 背景与目标

### 0.1 触发原因

`eaiselp-runtime/src/main/java/com/eaiselp/runtime/engine/DerivationEngine.java:49` 有 TODO：

```java
// 6. 构建结果（M1.0 简化，M1.1 接 data 持久化）
```

M1.0 阶段派生引擎只把结果构建成内存对象 `DerivationResult` 直接返回，**不落库**。M1.1 已完成 ADR-001 落地（library/service 边界），现在 data 模块已是合规 library，本 case 完成"runtime 引入 data 模块 + DerivationEngine 落库"。

### 0.2 本 case 的 6 个目标

| # | 目标 | 关联 TODO / IMP |
|---|---|---|
| G-1 | 派生结果（DerivationResult + ProducedArtifact）真实落 MySQL（t_derivation + t_artifact） | DerivationEngine.java:49 TODO |
| G-2 | 顺手补 data 模块 Service 层（M2 必需，目前只有 Mapper 层） | M2 前置 |
| G-3 | 清理 data 模块 application.yml 的 nacos 配置残留（library 化时漏清） | IMP-003 |
| G-4 | runtime 引入 eaiselp-data 依赖 | M2 接入第一步 |
| G-5 | runtime Application 的 scanBasePackages 加 com.eaiselp.data + 激活 @MapperScan | EaiselpRuntimeApplication.java:9 TODO |
| G-6 | 加单测覆盖落库成功 / 失败 / 多 artifact 场景 | ES-002 §4 反幻觉回归保障 |

### 0.3 不在本 case 范围

- 产物正文 content 的外部存储（MinIO/OSS）—— 当前 content 落 `produced_artifacts` JSON 列，外部存储是 M2+ 的事
- 配额扣减（t_quota.derivation_used++）—— 是独立 case
- 检查点表 t_checkpoint 联动 —— 是独立 case
- data 模块的 Controller 层 —— M2 接 admin 时再做（ADR-001 P5 演进预留）

---

## 1. 改动清单（精确到文件:行）

> 改动顺序在 §2，每个文件的"为什么"在 §3 决策记录，回归步骤在 §4，风险点在 §5。
>
> **自检**：本节所有"当前内容摘要"的行号、字段、注解均由 SE 亲自 Read 源码核对（ES-002 §1.3 反幻觉），非抄 Dev 报告。

### 1.1 data 模块：补 Service 层（目标 G-2）

#### 1.1.1 新增 `eaiselp-data/src/main/java/com/eaiselp/data/service/DerivationService.java`

**当前状态**：文件不存在（data 模块 src 树仅有 config / entity / mapper 三个子包，无 service 子包）。

**改动类型**：新增文件。

**改动后内容摘要**：

```java
package com.eaiselp.data.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.eaiselp.data.entity.Derivation;

/** 派生记录服务接口（MyBatis-Plus IService 模式）。 */
public interface DerivationService extends IService<Derivation> {
}
```

#### 1.1.2 新增 `eaiselp-data/src/main/java/com/eaiselp/data/service/impl/DerivationServiceImpl.java`

**改动类型**：新增文件。

**改动后内容摘要**：

```java
package com.eaiselp.data.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eaiselp.data.entity.Derivation;
import com.eaiselp.data.mapper.DerivationMapper;
import com.eaiselp.data.service.DerivationService;
import org.springframework.stereotype.Service;

@Service
public class DerivationServiceImpl extends ServiceImpl<DerivationMapper, Derivation> implements DerivationService {
}
```

#### 1.1.3 新增 `eaiselp-data/src/main/java/com/eaiselp/data/service/ArtifactService.java`

**改动后内容摘要**：

```java
package com.eaiselp.data.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.eaiselp.data.entity.Artifact;

/** 产物服务接口。 */
public interface ArtifactService extends IService<Artifact> {
}
```

#### 1.1.4 新增 `eaiselp-data/src/main/java/com/eaiselp/data/service/impl/ArtifactServiceImpl.java`

**改动后内容摘要**：

```java
package com.eaiselp.data.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eaiselp.data.entity.Artifact;
import com.eaiselp.data.mapper.ArtifactMapper;
import com.eaiselp.data.service.ArtifactService;
import org.springframework.stereotype.Service;

@Service
public class ArtifactServiceImpl extends ServiceImpl<ArtifactMapper, Artifact> implements ArtifactService {
}
```

> Service 层设计决策见 §3.1。

---

### 1.2 data 模块：清理 application.yml（目标 G-3 / IMP-003）

#### 1.2.1 修改 `eaiselp-data/src/main/resources/application.yml`

**当前内容（Read 核对，共 26 行）**：

```yaml
server:                                    # 行 1-2
  port: 8083
spring:                                    # 行 3-14
  application:
    name: eaiselp-data
  datasource:                              # 行 6-10 ← library 不该有 datasource
    url: jdbc:mysql://${MYSQL_HOST:localhost}:${MYSQL_PORT:3306}/eaiselp?...
    username: ${MYSQL_USER:root}
    password: ${MYSQL_PASSWORD:root}
    driver-class-name: com.mysql.cj.jdbc.Driver
  cloud:                                   # 行 11-14 ← library 不该注册 nacos
    nacos:
      discovery:
        server-addr: ${NACOS_HOST:localhost}:${NACOS_PORT:8848}
mybatis-plus:                              # 行 15-22
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    db-config:
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0
eaiselp:                                   # 行 23-25
  tenant:
    mode: shared
```

**改动后内容**：**整文件删除**（不是清空，是删文件）。

**改动原因**（多线论证）：

1. **ADR-001 P1 / ES-001 §1.2**：library 模块不能有 `spring.application.name` / `server.port` / `spring.cloud.nacos.discovery` —— 这些是 service 的判定充分条件 S2 / 暴露端口特征。当前 data 模块**没有 @SpringBootApplication 类**（已删），但 application.yml 里残留这些配置等于"配置层留了一半 service 形态"，与定性矛盾（违反 ES-001 §1.3 表格里 data 的"Application 类：无 / Nacos 注册：无"）。
2. **classpath 冲突风险**：data jar 被 runtime 依赖后，其 `src/main/resources/application.yml` 会出现在 runtime classpath 根上。Spring Boot 启动时**会同时加载 classpath 根的所有 application.yml**，与 runtime 自己的 `application.yml` 形成"哪个生效不确定"的局面（实际由 jar 加载顺序决定，可能覆盖 runtime 的 `server.port: 8081` 为 `8083`，把 `spring.application.name` 从 `eaiselp-runtime` 改成 `eaiselp-data`，导致 Nacos 注册名错乱）。这是本次 IMP-003 的**真实危害面**。
3. **library 配置归属原则**：data 模块用到的 mybatis-plus / datasource 配置应该在**真正启动的 host（runtime）的 application.yml 里**声明，由 host 进程统一管。library 模块只贡献 Java 类（Service/Mapper/Entity），不贡献启动期配置。

> 注意：data 的 `application.yml` 里有 `eaiselp.tenant.mode: shared`，这个配置项目前**没有任何 Java 代码 @Value/@ConfigurationProperties 读它**（已 grep common 模块 `tenant.mode` 找不到引用），属于"预埋但未消费"。删文件不损失任何运行时配置。

---

### 1.3 runtime 模块：引入 data 依赖（目标 G-4）

#### 1.3.1 修改 `eaiselp-runtime/pom.xml`

**当前内容（Read 核对，共 36 行，关键段在行 10-20）**：

```xml
<dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>com.alibaba.cloud</groupId><artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId></dependency>
    <dependency><groupId>org.springframework.cloud</groupId><artifactId>spring-cloud-starter-openfeign</artifactId></dependency>
    <dependency><groupId>io.github.openfeign</groupId><artifactId>feign-okhttp</artifactId></dependency>
    <dependency><groupId>org.springframework.statemachine</groupId><artifactId>spring-statemachine-core</artifactId></dependency>
    <dependency><groupId>dev.langchain4j</groupId><artifactId>langchain4j</artifactId></dependency>
    <dependency><groupId>com.eaiselp</groupId><artifactId>eaiselp-common</artifactId></dependency>
    <dependency><groupId>com.eaiselp</groupId><artifactId>eaiselp-capability</artifactId><version>${project.version}</version></dependency>
    <dependency><groupId>com.eaiselp</groupId><artifactId>eaiselp-adapter</artifactId><version>${project.version}</version></dependency>
</dependencies>
```

**改动**：在 `eaiselp-adapter` 之后新增一行（行 20 后）：

```xml
    <dependency><groupId>com.eaiselp</groupId><artifactId>eaiselp-data</artifactId><version>${project.version}</version></dependency>
```

**改动原因**：M2 接入 data 的第一步。依赖方向符合 ADR-001 P3 / ES-001 §4.2 R3（runtime → data → common，单向）。

**传递依赖分析**（关键风险点，详见 §5.1）：
- data 的 pom 显式依赖 `mybatis-plus-spring-boot3-starter`（compile scope） → runtime 自动获得
- data 的 pom 显式依赖 `mysql-connector-j`（compile scope） → runtime 自动获得
- common 的 pom 把 `mybatis-plus-spring-boot3-starter` / `jsqlparser` 标为 `provided`（不传递），所以 runtime 不会从 common 链上重复拿到，**无版本冲突**
- data 已依赖 common（compile），runtime 之前已显式依赖 common —— Maven 自动去重，无冲突

---

### 1.4 runtime 入口：激活 @MapperScan + scanBasePackages（目标 G-5）

#### 1.4.1 修改 `eaiselp-runtime/src/main/java/com/eaiselp/runtime/EaiselpRuntimeApplication.java`

**当前内容（Read 核对，共 18 行）**：

```java
package com.eaiselp.runtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

// TODO(M2): 当 runtime 引入 eaiselp-data 依赖后，在此追加 @MapperScan("com.eaiselp.data.mapper")
@SpringBootApplication(scanBasePackages = {"com.eaiselp.runtime", "com.eaiselp.common", "com.eaiselp.capability", "com.eaiselp.adapter"})
@EnableDiscoveryClient
@EnableFeignClients
@EnableScheduling   // 迁移自 EaiselpCapabilityApplication（capability CapabilityLoader 有 @Scheduled 30s 热重载）
public class EaiselpRuntimeApplication {
    public static void main(String[] args) {
        SpringApplication.run(EaiselpRuntimeApplication.class, args);
    }
}
```

**改动 1：删除行 9 的 TODO 注释，新增 @MapperScan 注解**（行 9 替换）

```java
import org.mybatis.spring.annotation.MapperScan;

@MapperScan("com.eaiselp.data.mapper")   // M1.2 激活：runtime 引入 data 后扫 data.mapper（替代 M1.1 留的 TODO）
```

**改动 2：scanBasePackages 数组追加 "com.eaiselp.data"**（行 10 修改）

```java
@SpringBootApplication(scanBasePackages = {
    "com.eaiselp.runtime",
    "com.eaiselp.common",
    "com.eaiselp.capability",
    "com.eaiselp.adapter",
    "com.eaiselp.data"        // M1.2 新增：让 data 模块的 @Service / @Configuration 被 Spring 扫到
})
```

**改动原因**：
- `@MapperScan("com.eaiselp.data.mapper")` 让 MyBatis-Plus 给 data 的 8 个 Mapper 生成代理 Bean（DerivationMapper / ArtifactMapper / CaseMapper / CheckpointMapper / MilestoneMapper / QuotaMapper / TenantMapper / UserMapper）。Mapper 类上的 `@Mapper` 注解仅在 **Mapper 所在包被 @MapperScan 或自动扫描覆盖**时生效；最稳的做法是显式 @MapperScan。
- scanBasePackages 加 `com.eaiselp.data` 让 `MybatisPlusConfig`（@Configuration）、`DerivationServiceImpl`（@Service）、`ArtifactServiceImpl`（@Service）被 Spring 容器接管。

> 注意：data 模块的 `MybatisPlusConfig` 依赖 `EaiselpTenantHandler`（@Component，在 common 包，已被 scanBasePackages 的 `com.eaiselp.common` 覆盖）+ `EaiselpMetaObjectHandler`（同上）。所以加 `com.eaiselp.data` 后整条 MyBatis-Plus 链路完整。

---

### 1.5 runtime 配置：加 datasource + mybatis-plus（目标 G-1 前置）

#### 1.5.1 修改 `eaiselp-runtime/src/main/resources/application.yml`

**当前内容（Read 核对，共 13 行）**：

```yaml
server:
  port: 8081
spring:
  application:
    name: eaiselp-runtime
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_HOST:localhost}:${NACOS_PORT:8848}
eaiselp:
  system:
    path: ${SYSTEM_PATH:../agents-config}
```

**当前缺陷**：runtime 当前**没有 datasource 配置**，引入 data 后 MyBatis-Plus 启动会因无 DataSource 直接失败。

**改动后内容**：

```yaml
server:
  port: 8081
spring:
  application:
    name: eaiselp-runtime
  datasource:                                                   # M1.2 新增：data 模块需要
    url: jdbc:mysql://${MYSQL_HOST:localhost}:${MYSQL_PORT:3306}/eaiselp?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
    username: ${MYSQL_USER:root}
    password: ${MYSQL_PASSWORD:root}
    driver-class-name: com.mysql.cj.jdbc.Driver
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_HOST:localhost}:${NACOS_PORT:8848}
mybatis-plus:                                                    # M1.2 新增：从原 data/application.yml 迁移
  configuration:
    map-underscore-to-camel-case: true                          # 驼峰自动映射（case_id → caseId）
  global-config:
    db-config:
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0
eaiselp:
  system:
    path: ${SYSTEM_PATH:../agents-config}
```

**改动原因**：
- `spring.datasource.*`：data 模块 Mapper 真正生效需要 DataSource。docker-compose.yml 第 3-20 行的 MySQL 容器暴露 3306，库名 `eaiselp`，root/root —— 此处参数化（${MYSQL_HOST} 等）与 docker-compose 部署一致，本地默认值 fallback 到 localhost。
- `mybatis-plus.*`：从原 `eaiselp-data/application.yml` 第 15-22 行**逐行迁移**过来（删 data yml 必须把这些搬走，否则 MyBatis-Plus 行为变化）。`map-underscore-to-camel-case: true` 是关键：BaseEntity 的 `tenantId` ↔ t_derivation.tenant_id、Derivation.caseId ↔ t_derivation.case_id 全靠这条映射。
- 不迁 `eaiselp.tenant.mode: shared`（无消费方，见 §1.2.1 说明）。

> datasource URL 用 `allowPublicKeyRetrieval=true` + `useSSL=false`：MySQL 8 默认 caching_sha2_password，JDBC 驱动需要这两项才能在开发环境连上。docker-compose.yml 第 4 行 `mysql:8.0` 默认就是这套。

---

### 1.6 DerivationEngine 改造：落库逻辑（目标 G-1 / 核心）

#### 1.6.1 修改 `eaiselp-runtime/src/main/java/com/eaiselp/runtime/engine/DerivationEngine.java`

**当前内容关键段**（Read 核对）：

- 行 3-5：import adapter.spi / capability.model
- 行 22-30：类定义 + 构造注入 `AdapterFactory` / `ContextAssembler`
- 行 32-60：`derive()` 方法 7 步流程，行 49 是 TODO
- 行 50-55：构建 `DerivationResult` 内存对象
- 行 91-102：内部类 `DerivationResult` / `ProducedArtifact`

**改动 1：注入 data 的两个 Service**（修改类字段 + 构造函数，行 24-30）

```java
private final AdapterFactory adapterFactory;
private final ContextAssembler contextAssembler;
private final DerivationService derivationService;   // M1.2 新增
private final ArtifactService artifactService;        // M1.2 新增

public DerivationEngine(AdapterFactory af, ContextAssembler ca,
                        DerivationService ds, ArtifactService as) {
    this.adapterFactory = af;
    this.contextAssembler = ca;
    this.derivationService = ds;
    this.artifactService = as;
}
```

并在文件顶部 import 段追加：

```java
import com.eaiselp.data.entity.Derivation;
import com.eaiselp.data.entity.Artifact;
import com.eaiselp.data.service.DerivationService;
import com.eaiselp.data.service.ArtifactService;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.ArrayList;
```

**改动 2：在 derive() 行 49 TODO 处补落库逻辑**

把"构建 result"和"落库"分两步：

```java
// 6. 构建结果（内存对象）
DerivationResult result = DerivationResult.builder()
        .role(role).caseId(caseId).model(model)
        .output(content).experience(experience).artifacts(artifacts)
        .inputTokens(resp.getInputTokens()).outputTokens(resp.getOutputTokens())
        .durationMs(System.currentTimeMillis() - start)
        .finishedAt(LocalDateTime.now()).status("success").build();

// 6.1 落库（M1.2 新增：派生记录 + 产物原子化写入）
persistDerivation(result);

// 7. 埋点（保持不变）
log.info("[Derive] 完成: ...");
return result;
```

**改动 3：新增私有方法 `persistDerivation`**（行 72 `extractArtifacts` 之后插入）

```java
/**
 * 落库派生记录与产物。
 * 设计原则（M1.2）：
 *  - derivation + artifacts 用 @Transactional 包裹保证原子（要么全进，要么全不进）
 *  - 落库失败不影响派生结果返回（result 已构建好，业务可继续；只记错误日志）
 *  - 此处捕获 Throwable 是有意为之：data 不可用不能拖垮派生主流程（可靠性优先于一致性）
 */
@Transactional(rollbackFor = Exception.class)
public void persistDerivation(DerivationResult result) {
    try {
        // 6.1.1 构建并保存 Derivation
        Derivation d = new Derivation();
        d.setCaseId(result.getCaseId());
        d.setRole(result.getRole());
        d.setModel(result.getModel());
        // stage / modelTier / cost / retryCount / startedAt 当前 DerivationResult 无字段，
        // 留空（NULL）或给默认值；M2 增强时由调用方传入
        d.setInputTokens(result.getInputTokens());
        d.setOutputTokens(result.getOutputTokens());
        d.setStatus(result.getStatus());
        d.setExperience(result.getExperience());
        d.setDurationMs(result.getDurationMs());
        d.setFinishedAt(result.getFinishedAt());
        // produced_artifacts（JSON 列）落产物摘要（避免 content 重复落库；正文走 M2 外部存储）
        d.setProducedArtifacts(summarizeArtifacts(result.getArtifacts()));
        derivationService.save(d);   // 拿到 d.id（MyBatis-Plus ASSIGN_ID 回填）

        // 6.1.2 构建并批量保存 Artifacts（derivation_id 关联）
        if (result.getArtifacts() != null && !result.getArtifacts().isEmpty()) {
            List<Artifact> arts = new ArrayList<>(result.getArtifacts().size());
            for (ProducedArtifact pa : result.getArtifacts()) {
                Artifact a = new Artifact();
                a.setCaseId(pa.getCaseId());
                a.setRole(pa.getRole());
                a.setType(pa.getType());
                a.setDerivationId(d.getId());     // 关联刚插入的 derivation
                // title / docKey / frontmatter / contractKey 当前 ProducedArtifact 无字段，M2 增强
                arts.add(a);
            }
            artifactService.saveBatch(arts);
        }
    } catch (Throwable t) {
        // 关键设计决策（§3.4）：捕获 Throwable 不重抛
        // —— 派生结果已在内存构建好，落库失败不应让调用方拿不到结果
        // —— 错误已 log，运维可通过日志追，下次重派时幂等性由 case_id + role + finishedAt 保证（M2）
        log.error("[Derive] 落库失败（派生结果仍返回）: role={}, case={}, err={}",
                result.getRole(), result.getCaseId(), t.getMessage(), t);
    }
}

private String summarizeArtifacts(List<ProducedArtifact> artifacts) {
    if (artifacts == null || artifacts.isEmpty()) return null;
    // 简单 JSON 数组（M1.2 不引 Jackson，用字符串拼接；M2 改 ObjectMapper）
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < artifacts.size(); i++) {
        ProducedArtifact pa = artifacts.get(i);
        if (i > 0) sb.append(",");
        sb.append("{\"type\":\"").append(pa.getType())
          .append("\",\"role\":\"").append(pa.getRole()).append("\"}");
    }
    return sb.append("]").toString();
}
```

> 落库的关键决策（@Transactional / try-catch Throwable / 不重抛）见 §3.2-§3.4。
> 字段映射核对（反幻觉）见 §6。

---

### 1.7 单元测试（目标 G-6 / ES-002 §4 回归保障）

#### 1.7.1 新增 `eaiselp-runtime/pom.xml` test scope 依赖（H2）

当前 runtime pom **无 test scope 依赖**（父 pom 已全局加 `spring-boot-starter-test:test`，所以 JUnit5 / Mockito / AssertJ 都有）。需要新增 H2 内存库：

```xml
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>test</scope>
</dependency>
```

（版本由 spring-boot-dependencies BOM 管理，无需指定）

#### 1.7.2 新增 `eaiselp-runtime/src/test/resources/application-test.yml`

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:eaiselp;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE
    driver-class-name: org.h2.Driver
    username: sa
    password:
  sql:
    init:
      mode: always
      schema-locations: classpath:schema-h2.sql
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    db-config:
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0
eaiselp:
  tenant:
    mode: shared
```

#### 1.7.3 新增 `eaiselp-runtime/src/test/resources/schema-h2.sql`

**为什么不能直接复用 data 的 schema.sql**：data 的 `schema.sql` 用了 MySQL 特定语法（`ENGINE=InnoDB` / `DEFAULT CHARSET=utf8mb4` / `JSON` 类型 / `ON UPDATE CURRENT_TIMESTAMP`），H2 默认不认。需要单独写一份 H2 兼容版（只含本 case 测试用的 2 张表）：

```sql
-- H2 兼容版（MySQL 模式），仅测试用，只建本 case 涉及的 2 张表
CREATE TABLE IF NOT EXISTS t_derivation (
  id BIGINT NOT NULL PRIMARY KEY,
  tenant_id BIGINT DEFAULT 0,
  case_id VARCHAR(128),
  role VARCHAR(64),
  stage VARCHAR(32),
  model VARCHAR(64),
  model_tier VARCHAR(16),
  input_tokens INT DEFAULT 0,
  output_tokens INT DEFAULT 0,
  cost DECIMAL(10,4) DEFAULT 0,
  status VARCHAR(32),
  error_msg CLOB,
  produced_artifacts CLOB,   -- H2 无 JSON，用 CLOB 代替
  experience CLOB,
  retry_count INT DEFAULT 0,
  started_at TIMESTAMP,
  finished_at TIMESTAMP,
  duration_ms BIGINT,
  create_time TIMESTAMP,
  update_time TIMESTAMP,
  create_by VARCHAR(64),
  update_by VARCHAR(64),
  is_deleted INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS t_artifact (
  id BIGINT NOT NULL PRIMARY KEY,
  tenant_id BIGINT DEFAULT 0,
  case_id VARCHAR(128),
  role VARCHAR(64),
  stage VARCHAR(32),
  type VARCHAR(32),
  title VARCHAR(200),
  doc_key VARCHAR(200),
  frontmatter CLOB,
  derivation_id BIGINT,
  contract_key VARCHAR(200),
  create_time TIMESTAMP,
  update_time TIMESTAMP,
  create_by VARCHAR(64),
  update_by VARCHAR(64),
  is_deleted INT DEFAULT 0
);
```

#### 1.7.4 新增 `eaiselp-runtime/src/test/java/com/eaiselp/runtime/engine/DerivationEngineTest.java`

**测试用例 4 个**：

| TC | 场景 | 验证点 |
|---|---|---|
| TC-1 | 派生成功（LLM mock 返回正常 content） | t_derivation 1 条 + t_artifact 1 条；result.status=success |
| TC-2 | LLM 失败（mock 抛 RuntimeException） | 不入库（t_derivation count=0）+ 抛异常给上层 |
| TC-3 | 落库失败（mock DerivationService.save 抛异常） | result 仍返回（不被落库失败拖垮）+ 错误被 log |
| TC-4 | 多 artifact 场景（mock content 含多个产出块） | t_derivation 1 条 + t_artifact N 条；derivation_id 全部正确关联 |

**测试结构骨架**：

```java
@SpringBootTest
@ActiveProfiles("test")
class DerivationEngineTest {

    @Autowired DerivationEngine engine;
    @Autowired DerivationService derivationService;   // 用来 query 验证落库结果
    @Autowired ArtifactService artifactService;

    @MockBean AdapterFactory adapterFactory;          // mock LLM 调用
    @MockBean ContextAssembler contextAssembler;      // mock prompt 装配
    @MockBean LlmAdapter llmAdapter;

    @BeforeEach void setUp() {
        // 清表 + 默认 mock
        derivationService.remove(null);   // MyBatis-Plus IService 的全表删
        artifactService.remove(null);
        when(adapterFactory.getLlmAdapter()).thenReturn(llmAdapter);
        when(contextAssembler.assemble(any(), any())).thenReturn("fake prompt");
        // 关键：测试里手动 set tenant，绕过 TenantContextFilter（无 HTTP 请求）
        TenantContext.set(1L);
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    @Test void TC1_派生成功_落库验证() {
        when(llmAdapter.invoke(any(), any(), any()))
            .thenReturn(new LlmAdapter.LlmResponse("内容\n## 本次经验沉淀\nxxx", 100, 50));
        // ... 构造 AgentDefinition + DerivationContext
        DerivationResult r = engine.derive(agent, task, "case-test-1", ctx);
        assertEquals("success", r.getStatus());
        assertEquals(1, derivationService.count());
        assertEquals(1, artifactService.count());
        Derivation d = derivationService.list().get(0);
        assertEquals("case-test-1", d.getCaseId());
        assertNotNull(d.getProducedArtifacts());
    }

    @Test void TC2_LLM失败_不入库() {
        when(llmAdapter.invoke(any(), any(), any())).thenThrow(new RuntimeException("LLM down"));
        assertThrows(RuntimeException.class, () -> engine.derive(agent, task, "case-test-2", ctx));
        assertEquals(0, derivationService.count());
    }

    @Test void TC3_落库失败_派生结果仍返回() {
        when(llmAdapter.invoke(any(), any(), any()))
            .thenReturn(new LlmAdapter.LlmResponse("content", 100, 50));
        // 用 Spy 让 save 抛异常
        DerivationService spy = Mockito.spy(derivationService);
        doThrow(new RuntimeException("DB down")).when(spy).save(any());
        // 用反射替换 engine 里的 derivationService 字段（或重新构造 engine）
        // ...
        DerivationResult r = engine.derive(agent, task, "case-test-3", ctx);
        assertEquals("success", r.getStatus());  // 仍返回成功（落库失败不拖垮主流程）
    }

    @Test void TC4_多artifact场景() {
        // mock LlmAdapter 返回多产出块 → extractArtifacts 解析出多个
        // 验证 artifactService.count() == N，且 derivation_id 都 != null
    }
}
```

> **TC-3 落库失败注入的实现选项**：Spy 真实 ServiceImpl 是首选（不破坏 Spring 上下文）。也可以用 `@MockBean(DerivationService.class)` 整个替换，但要手动写 `when().thenReturn()` 模拟成功路径，复杂度更高。**Dev 实现时优先用 Spy**。

---

## 2. 改动顺序（关键：每步独立编译）

按依赖关系排序，**每一步完成后都能 mvn compile 通过**（除最后一步加业务代码外）。

| 步 | 改动 | 涉及文件 | 编译边界 | 依赖前置 |
|---|---|---|---|---|
| **S1** | data 模块补 Service 层（4 个新文件） | §1.1.1-§1.1.4 | data 模块独立编译；不依赖其他模块 | 无 |
| **S2** | data 模块删 application.yml | §1.2.1 | data 模块编译不受影响（yml 不参与编译） | S1 完成（同 case 内顺次）|
| **S3** | runtime pom 加 eaiselp-data 依赖 + H2 test 依赖 | §1.3.1 + §1.7.1 | runtime 编译能拿到 data 的类；不依赖运行时启动 | S1（否则 data 模块没 Service 类，runtime 引了也用不了）|
| **S4** | runtime Application 加 @MapperScan + scanBasePackages | §1.4.1 | runtime 编译通过（仅新增 import + 注解）；运行时启动需要 S5 配合 | S3 |
| **S5** | runtime application.yml 加 datasource + mybatis-plus | §1.5.1 | yml 不参与编译；runtime 才能真正启动（Spring 上下文加载需要 DataSource） | S4 |
| **S6** | 改 DerivationEngine 加落库逻辑 | §1.6.1 | runtime 编译通过；运行时落库生效 | S1 + S4 + S5（缺一不可：Service Bean、Mapper Bean、DataSource）|
| **S7** | 加单测 + schema-h2.sql + application-test.yml | §1.7.1-§1.7.4 | runtime test 编译通过；mvn test 能跑 | S6 |

**编译边界自检**：
- S1 后：`mvn -pl eaiselp-data compile` 应成功
- S2 后：`mvn -pl eaiselp-data compile` 仍成功
- S3 后：`mvn -pl eaiselp-runtime -am compile` 应成功（`-am` 同时编译依赖模块）
- S4-S6 后：`mvn -pl eaiselp-runtime compile` 应成功
- S7 后：`mvn -pl eaiselp-runtime test` 应成功（4 个 TC 全过）

---

## 3. 关键设计决策（裁决记录）

### 3.1 Service 层用 IService<T> + ServiceImpl<M,T>（不手写）

**选项 A**：MyBatis-Plus 的 IService + ServiceImpl 模式（§1.1 方案）
**选项 B**：手写 Service（自己定义 saveDerivation / findXxx 方法）

**裁决**：选 A。

**理由**：
1. **零业务方法**：本 case 只需 `save` / `saveBatch`，IService 已内置，手写只是重复造轮子。
2. **MyBatis-Plus 生态惯例**：项目已用 MP（BaseMapper + MybatisPlusInterceptor），Service 层用同生态的 IService 是顺承选择，Dev 学习成本零。
3. **M2 演进友好**：M2 需要复杂查询（按 caseId 列表、按 role 统计）时，在接口里加自定义方法即可，不需要重构。
4. **测试便利**：IService 提供 `count()` / `list()`，单测验证落库数量一行搞定（见 §1.7.4 TC-1）。

> **备选方案 B 未选**：当前阶段（M1.2）业务方法集合是空集，手写只会引入无意义的样板代码。等真正出现 MP 内置方法覆盖不了的查询（如多表 join、聚合）再考虑加自定义方法到接口。

### 3.2 落库用 @Transactional 包裹（原子性）

**裁决**：是。

**理由**：
- `t_derivation` + `t_artifact` 必须原子化（要么全进，要么全不进）。否则出现"有 derivation 没 artifact"的脏数据，下游查询崩溃。
- `@Transactional(rollbackFor = Exception.class)` 默认只对 RuntimeException 回滚，加 `rollbackFor = Exception.class` 让 checked exception 也回滚（虽然 MP 基本不抛 checked，但显式更稳）。
- 注意：`@Transactional` 标在 `persistDerivation` 方法上，**Spring AOP 通过代理调用才生效**。如果 DerivationEngine 内部 `this.persistDerivation()` 自调用，AOP 失效 → **必须由外部调用或拆成独立 Bean**。**当前 derive() 内调用 persistDerivation 是 this 自调用**，@Transactional 不生效！

**修正方案**（Dev 实现时二选一）：
- **方案 a（推荐）**：把 `persistDerivation` 抽到一个独立的 `@Service DerivationPersistenceService`，DerivationEngine 注入它并调用。@Transactional 标在新 Service 上，由 Spring 代理调用，事务生效。
- **方案 b**：在 `EaiselpRuntimeApplication` 加 `@EnableTransactionManagement`（Spring Boot 默认已开，通常不需要），并把 `persistDerivation` 改成 public，通过 `AopContext.currentProxy()` 自调用（丑且要 expose-proxy=true，不推荐）。

**SE 推荐方案 a**：新增 `eaiselp-runtime/src/main/java/com/eaiselp/runtime/engine/DerivationPersistenceService.java`，把 §1.6.1 改动 3 的逻辑搬过去，DerivationEngine 注入它。这样：
- @Transactional 真正生效
- 落库逻辑与派生主流程解耦（M2 加配额扣减、检查点联动时，都集中到这个 Service）
- 测试时 spy DerivationPersistenceService 比 spy DerivationService 干净

> **此调整对 §1.6.1 的影响**：DerivationEngine 不再直接注入 DerivationService / ArtifactService，改注入 `DerivationPersistenceService`。data 模块的 4 个 Service 文件不变（§1.1 仍要做，是 DerivationPersistenceService 的依赖）。

### 3.3 落库失败不重抛（可靠性优先于一致性）

**裁决**：落库失败 try-catch 不重抛，只 log.error。

**理由**：
1. **派生结果已经构建好**（行 50-55 的 DerivationResult），这是高价值产物（用户等了几十秒的 LLM 输出）。如果因为 MySQL 抖一下就把它丢了，用户体验灾难。
2. **M1 阶段定位**：DESIGN.md M1.0 范围是"最小运行时跑通派生"，落库是 M1.2 的增量，不应让增量功能（DB）成为主流程（LLM 派生）的单点故障。
3. **运维兜底**：log.error 已记录完整堆栈，运维可通过日志追；下次重派（case_id 一致）时由调用方做幂等检查（M2 增强）。

**代价 / 风险**：
- 可能出现"用户拿到结果，但 t_derivation 没记录"的状态。M1.2 阶段可接受（数据可重派，主流程优先）。
- M2 引入配额扣减时，需要重新评估：如果配额已扣但落库失败，会出现"扣了钱没记录"的不一致。届时可改为"落库成功后才扣配额"或"两阶段提交"。**本 case 不解决，留给 M2 case 评估**。

### 3.4 落库失败捕获 Throwable 而非 Exception

**裁决**：捕获 `Throwable`（含 Error）。

**理由**：
- `Throwable` 才能涵盖 `OutOfMemoryError` / `StackOverflowError` 等 JVM 级错误（理论上 DB 操作触发 OOM 不太可能，但保守捕获代价为零）。
- 关键是**不能让 Error 沿调用栈冒泡导致 runtime 进程崩**。LLM 派生结果一定要返回给用户。

**代价**：违反"不要 catch Throwable"的常识。但在"派生结果不丢失"这一业务硬约束下，是合理权衡。**Dev 实现时在 catch 块里加注释说明此决策**（见 §1.6.1 改动 3 代码注释）。

### 3.5 content 不进 t_artifact（走 produced_artifacts JSON 摘要）

**裁决**：本 case content 只写到 `t_derivation.produced_artifacts`（JSON 摘要，仅含 type/role，不含正文），不写 t_artifact 的任何正文列。

**理由**：
1. **schema.sql 设计**：t_artifact 表本身**没有 content 列**（schema.sql 第 142-162 行确认），只有 `title / doc_key / frontmatter / contract_key`。content 的归宿是**外部存储**（MinIO / 文件系统），doc_key 是外部存储的 key。这是 schema 设计的初衷，本 case 不改 schema。
2. **当前 ProducedArtifact 内部类**也只有 type/role/caseId/content，没有 docKey。落库时 content 字段**丢弃**（不入库）。
3. **M2 接 admin 时**会增强： ProducedArtifact 加 docKey/content，content 写 MinIO 拿到 docKey，再落 t_artifact.doc_key。**本 case 不做这步**，避免范围蔓延。

**实施细节**：
- `t_derivation.produced_artifacts` 列：落 `[{"type":"prd","role":"team-po"}]` 摘要 JSON（§1.6.1 改动 3 的 `summarizeArtifacts` 方法）
- t_artifact 行：title / docKey / frontmatter / contractKey 全部 NULL（M2 填充）
- content 完整文本：**只活在内存 DerivationResult.output + ProducedArtifact.content**，return 给调用方，不进 DB

### 3.6 stage / modelTier / cost 等字段留空

**裁决**：本 case 不为 Derivation entity 的 `stage / modelTier / cost / errorMsg / retryCount / startedAt` 字段填值，留 NULL。

**理由**：
- 这些字段的来源（DerivationContext / AgentDefinition / 调用方参数）当前 DerivationResult 内部类**没有对应字段**。要填值得改 DerivationResult 字段定义，扩大本 case 范围。
- M2 增强：derive() 方法签名扩展为接收 stage 等参数，DerivationResult 加字段。**本 case 不做**，保持 case 范围聚焦。
- DB 列允许 NULL（schema.sql 第 116-127 行除 input_tokens/output_tokens/retry_count 有 NOT NULL DEFAULT 0 外，其余都允许 NULL），留空不报错。

> **唯一例外**：`retry_count` 在 schema 是 `NOT NULL DEFAULT 0`，但 entity 字段 `Integer retryCount` 是包装类型（允许 null）。MyBatis-Plus save 时 null 字段不会进 SQL（默认 `FieldStrategy.NOT_NULL`），DB 用列默认值 0。**无需手动 set**。

---

## 4. 回归验证步骤（给 QA）

> 严格按此顺序跑，任一步失败即 PR 打回。

### 4.1 编译验证

```powershell
# 在项目根 D:\AI\mywork\platform
mvn clean package -DskipTests
```

**通过判据**：
- `BUILD SUCCESS`
- 10 个模块全部编译通过（eaiselp-common / gateway / auth / runtime / capability / data / adapter / observability / admin + parent）
- runtime 目标产物：`eaiselp-runtime/target/eaiselp-runtime.jar`（fat jar，repackage 后）
- data 目标产物：`eaiselp-data/target/eaiselp-data.jar`（普通 jar，**不能是 fat jar** —— 用 `jar tf eaiselp-data/target/eaiselp-data.jar | findstr BOOT-INF` 验证无 BOOT-INF 目录）

### 4.2 单测验证

```powershell
mvn -pl eaiselp-runtime test
```

**通过判据**：
- Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
- 4 个 TC（TC-1 派生成功 / TC-2 LLM 失败 / TC-3 落库失败 / TC-4 多 artifact）全过

### 4.3 制品验证（library vs service）

```powershell
# library 模块：必须普通 jar（无 BOOT-INF）
jar tf eaiselp-data/target/eaiselp-data.jar | findstr "BOOT-INF"
# 期望：无输出（empty）

# service 模块：必须 fat jar（有 BOOT-INF）
jar tf eaiselp-runtime/target/eaiselp-runtime.jar | findstr "BOOT-INF"
# 期望：BOOT-INF/classes/、BOOT-INF/lib/ 等
```

### 4.4 质量门禁

```powershell
# G1-G6 模块边界门禁
powershell -ExecutionPolicy Bypass -File .\docs\架构文档\质量门禁-模块边界.ps1
# 期望：PASS: 6/6

# G7-G10 产出物落盘门禁（本 case SE 已落盘技术方案）
powershell -ExecutionPolicy Bypass -File .\docs\架构文档\质量门禁-产出物落盘.ps1
# 期望：PASS
```

### 4.5 启动冒烟测试（手工）

```powershell
# 启动依赖（docker-compose）
docker compose -f docker-compose.yml up -d mysql nacos

# 等 MySQL healthy（约 20s）
docker compose ps   # mysql 应是 healthy

# 启动 runtime
java -jar eaiselp-runtime/target/eaiselp-runtime.jar
```

**通过判据**：
- 启动日志无异常
- 看到 `Successfully started EaiselpRuntimeApplication`（或类似 Spring Boot 启动完成日志）
- 看到 `@MapperScan` 扫到 8 个 mapper（DerivationMapper / ArtifactMapper / CaseMapper / CheckpointMapper / MilestoneMapper / QuotaMapper / TenantMapper / UserMapper）
- MySQL 日志可见 runtime 连接成功

> 启动冒烟测试是 QA 可选步骤（取决于环境是否方便起 docker）。M1 阶段不强求，编译 + 单测 + 门禁通过即可放行。

---

## 5. 风险点

### 5.1 依赖冲突（低风险，已分析）

| 风险 | 分析 | 结论 |
|---|---|---|
| mybatis-plus 版本冲突 | runtime 之前无 mybatis-plus 依赖，data 引入 3.5.5 是首次；父 POM dependencyManagement 锁定 3.5.5 | 无冲突 |
| jsqlparser 版本冲突 | common 的 jsqlparser 是 `provided`（不传递），data 通过 mybatis-plus-spring-boot3-starter 间接拉 4.6；父 POM 锁定 4.6 | 无冲突 |
| mysql-connector-j 版本冲突 | runtime 之前无此依赖，data 引入 8.0.33 是首次；父 POM 锁定 8.0.33 | 无冲突 |
| spring-boot-starter-web 重复 | runtime + data 都显式依赖；Maven 自动去重 | 无冲突 |

**验证命令**（QA 跑）：

```powershell
mvn -pl eaiselp-runtime dependency:tree | findstr "mybatis-plus jsqlparser mysql-connector"
# 应只各出现 1 次（compile scope）
```

### 5.2 @MapperScan 扫 data.mapper 后 runtime 自身 Mapper 冲突（无风险）

**分析**：runtime 模块**自身没有 Mapper**（src 树只有 controller / engine / context，无 mapper 子包）。`@MapperScan("com.eaiselp.data.mapper")` 只扫 data 的 8 个 Mapper，无冲突。

**未来风险**：如果 runtime 后续自己加 Mapper（如 RuntimeStatMapper），需要扩 @MapperScan 为 `{"com.eaiselp.data.mapper", "com.eaiselp.runtime.mapper"}`，或改用 `@MapperScan("com.eaiselp.**.mapper")`。**本 case 不预防，等真出现时改**。

### 5.3 多租户拦截器对测试的影响（已处理）

**分析**：data 的 `MybatisPlusConfig` 注册了 `TenantLineInnerInterceptor`，自动给所有非 IGNORE 表的 SQL 加 `tenant_id = ?` 条件。测试时：
- `TenantContext.get()` 返回 0（SYSTEM_TENANT，默认值）→ `EaiselpTenantHandler.ignoreTable` 返回 true（isSystem）→ 不加 tenant 条件
- 但 `EaiselpMetaObjectHandler.insertFill` 仍会 set tenantId=0 → 落库的行 tenant_id=0

**测试影响**：测试用例不依赖 tenant_id 值，count() / list() 都能正常工作（isSystem 时拦截器完全 ignore）。**无需特殊处理**。

**但**：测试时如果 `TenantContext.set(null)` 后调 `clear()`，ThreadLocal 是 null，get() 返回 SYSTEM_TENANT=0，行为一致。**setUp 里手动 set(1L) 是为了模拟"有租户"场景**，让测试更接近生产。

### 5.4 H2 SQL 方言与 MySQL 不同（已规避）

**关键差异**：
- `ENGINE=InnoDB` / `DEFAULT CHARSET=utf8mb4`：H2 不认，**已剔除**（§1.7.3 schema-h2.sql）
- `JSON` 类型：H2 MySQL 模式下有 JSON，但更稳的做法是用 CLOB（H2 MySQL 模式对 JSON 支持偶有坑）。**已改 CLOB**
- `ON UPDATE CURRENT_TIMESTAMP`：H2 不支持，**已剔除**（MetaObjectHandler 会管 updateTime）
- `AUTO_INCREMENT`：本项目用 `IdType.ASSIGN_ID`（雪花算法），不依赖 DB 自增，**无影响**
- 大小写：H2 默认大小写敏感，加 `DATABASE_TO_LOWER=TRUE` 让表名/列名转小写（§1.7.2 url 参数）

**测试 URL 关键参数**：
```
jdbc:h2:mem:eaiselp;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE
```
- `MODE=MySQL`：H2 启用 MySQL 兼容模式（部分 MySQL 函数 / 语法可用）
- `DB_CLOSE_DELAY=-1`：JVM 关闭前不关 DB（多个测试方法共享同一内存库）
- `DATABASE_TO_LOWER=TRUE`：表名小写（避免 H2 默认大写在 MP `map-underscore-to-camel-case` 转换时出错）

### 5.5 data application.yml 删除后的"配置真空"（已规避）

**风险**：删 data yml 后，data 模块依赖的 mybatis-plus 配置如果没在 runtime yml 补全，runtime 启动会失败（MyBatis-Plus 找不到 `logic-delete-field` 等配置）。

**规避**：§1.5.1 已把 data yml 的 mybatis-plus 段**逐行迁移**到 runtime yml。迁移点：
- `map-underscore-to-camel-case: true`
- `logic-delete-field: deleted`
- `logic-delete-value: 1`
- `logic-not-delete-value: 0`

**QA 验证**：runtime 启动后跑一次派生，查 `t_derivation` 表，确认 `is_deleted=0` 的行能被 select 出来（验证 logic-delete 配置生效）。

### 5.6 @Transactional 自调用失效（已在 §3.2 处理）

**风险**：如 §3.2 所述，`this.persistDerivation()` 自调用会让 @Transactional 失效。

**规避**：SE 裁决方案 a（新增 DerivationPersistenceService）。**Dev 实现时必须按 §3.2 方案 a 落地**，否则 TC-3 测试虽能过但生产事务不生效（原子性破坏）。

### 5.7 TC-3 测试用 Spy 改真 Bean 的复杂度（中等）

**风险**：TC-3 要 mock 落库失败，但 DerivationEngine 通过构造注入了真 Bean。要替换真 Bean 为 Spy 需要额外工程。

**两种实现选项**（Dev 选一）：

| 选项 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| a | 用 `@SpyBean`（Spring Boot Test 提供）替换 ServiceImpl 的某个方法 | 一行注解搞定 | @SpyBean 在某些 Spring Boot 版本与 @Transactional 配合有坑 |
| b | 在测试类里 `@Autowired DerivationEngine engine` + 反射改字段 | 灵活 | 反射代码丑 |
| c（推荐） | 按方案 a 引入 DerivationPersistenceService 后，整个 mock 它（@MockBean） | 测试最干净 | 需要方案 a 落地 |

**SE 推荐选项 c**：与 §3.2 方案 a 天然配合。DerivationPersistenceService 是 DerivationEngine 的依赖，@MockBean 替换它不影响其他 Bean。

---

## 6. 自检（反幻觉，ES-002 §4.3 同等约束）

### 6.1 引用的每个现状都自己 Read 过吗？

| 引用 | 文件:行 | Read 状态 |
|---|---|---|
| DerivationEngine.java:49 TODO | `eaiselp-runtime/src/main/java/com/eaiselp/runtime/engine/DerivationEngine.java:49` | ✅ 已 Read 全文 103 行 |
| EaiselpRuntimeApplication.java:9 TODO | `eaiselp-runtime/src/main/java/com/eaiselp/runtime/EaiselpRuntimeApplication.java:9` | ✅ 已 Read 全文 18 行 |
| Derivation entity 字段 | `eaiselp-data/src/main/java/com/eaiselp/data/entity/Derivation.java` | ✅ 已 Read 全文 30 行 |
| Artifact entity 字段 | `eaiselp-data/src/main/java/com/eaiselp/data/entity/Artifact.java` | ✅ 已 Read 全文 21 行 |
| DerivationMapper / ArtifactMapper | `eaiselp-data/src/main/java/com/eaiselp/data/mapper/` | ✅ 已 Read 全部 |
| MybatisPlusConfig | `eaiselp-data/src/main/java/com/eaiselp/data/config/MybatisPlusConfig.java` | ✅ 已 Read 全文 22 行 |
| schema.sql t_derivation / t_artifact | `eaiselp-data/src/main/resources/db/schema.sql:108-162` | ✅ 已 Read 全文 |
| data application.yml nacos 残留 | `eaiselp-data/src/main/resources/application.yml:1-14` | ✅ 已 Read 全文 |
| runtime application.yml（无 datasource） | `eaiselp-runtime/src/main/resources/application.yml:1-13` | ✅ 已 Read 全文 |
| runtime pom.xml | `eaiselp-runtime/pom.xml` | ✅ 已 Read 全文 36 行 |
| BaseEntity 字段 | `eaiselp-common/src/main/java/com/eaiselp/common/entity/BaseEntity.java` | ✅ 已 Read 全文 33 行 |
| 父 POM dependencyManagement 版本锁定 | `pom.xml:32-42, 70-83` | ✅ 已 Read 全文 167 行 |
| EaiselpTenantHandler（多租户拦截器） | `eaiselp-common/src/main/java/com/eaiselp/common/tenant/EaiselpTenantHandler.java` | ✅ 已 Read 全文 32 行 |
| EaiselpMetaObjectHandler（字段自动填充） | `eaiselp-common/src/main/java/com/eaiselp/common/mybatis/EaiselpMetaObjectHandler.java` | ✅ 已 Read 全文 25 行 |
| docker-compose.yml MySQL 配置 | `docker-compose.yml:3-20` | ✅ 已 Read 全文 |
| 质量门禁-模块边界.ps1（6 条规则） | `docs/架构文档/质量门禁-模块边界.ps1` | ✅ 已 Read 全文 215 行 |

### 6.2 entity 字段名 vs schema.sql 列名映射核对

**Derivation entity → t_derivation 表**（开启 `map-underscore-to-camel-case: true`）：

| entity 字段（驼峰） | 表列（下划线） | 来源 | 一致？ |
|---|---|---|---|
| id | id | BaseEntity | ✅ |
| tenantId | tenant_id | BaseEntity | ✅ |
| createTime | create_time | BaseEntity | ✅ |
| updateTime | update_time | BaseEntity | ✅ |
| createBy | create_by | BaseEntity | ✅ |
| updateBy | update_by | BaseEntity | ✅ |
| deleted | is_deleted | BaseEntity（@TableField(value="is_deleted") 显式映射）| ✅ |
| caseId | case_id | Derivation | ✅ |
| role | role | Derivation | ✅ |
| stage | stage | Derivation | ✅ |
| model | model | Derivation | ✅ |
| modelTier | model_tier | Derivation | ✅ |
| inputTokens | input_tokens | Derivation | ✅ |
| outputTokens | output_tokens | Derivation | ✅ |
| cost | cost | Derivation | ✅ |
| status | status | Derivation | ✅ |
| errorMsg | error_msg | Derivation | ✅ |
| producedArtifacts | produced_artifacts | Derivation | ✅ |
| experience | experience | Derivation | ✅ |
| retryCount | retry_count | Derivation | ✅ |
| startedAt | started_at | Derivation | ✅ |
| finishedAt | finished_at | Derivation | ✅ |
| durationMs | duration_ms | Derivation | ✅ |

**Artifact entity → t_artifact 表**：

| entity 字段 | 表列 | 一致？ |
|---|---|---|
| id / tenantId / createTime / updateTime / createBy / updateBy / deleted | 同上 | ✅ |
| caseId | case_id | ✅ |
| role | role | ✅ |
| stage | stage | ✅ |
| type | type | ✅ |
| title | title | ✅ |
| docKey | doc_key | ✅ |
| frontmatter | frontmatter | ✅ |
| derivationId | derivation_id | ✅ |
| contractKey | contract_key | ✅ |

**核对结论**：23 + 17 个字段映射全部对齐，无歧义。

### 6.3 DerivationResult 内部类 vs Derivation entity 字段映射

| DerivationResult 字段 | Derivation entity 字段 | 映射策略 |
|---|---|---|
| role | role | 直接 set |
| caseId | caseId | 直接 set |
| model | model | 直接 set |
| **output** | **（无对应字段）** | **不落库**（content 类，schema 无 output 列；§3.5 决策）|
| experience | experience | 直接 set |
| inputTokens | inputTokens | 直接 set |
| outputTokens | outputTokens | 直接 set |
| durationMs | durationMs | 直接 set |
| finishedAt | finishedAt | 直接 set |
| status | status | 直接 set |
| **artifacts**（List<ProducedArtifact>） | **producedArtifacts**（String/JSON） | **summarizeArtifacts 转 JSON 摘要**（§1.6.1 改动 3）+ 拆行入 t_artifact |
| **（无）** | stage / modelTier / cost / errorMsg / retryCount / startedAt | **本 case 留 NULL**（§3.6 决策）|

**ProducedArtifact 字段 → Artifact entity**：

| ProducedArtifact 字段 | Artifact entity 字段 | 映射策略 |
|---|---|---|
| type | type | 直接 set |
| role | role | 直接 set |
| caseId | caseId | 直接 set |
| **content** | **（无对应字段，schema 也无 content 列）** | **不落库**（§3.5 决策，走 M2 外部存储）|
| **（无）** | derivationId | 由 persistDerivation 关联（save 后取 d.id）|
| **（无）** | title / docKey / frontmatter / contractKey / stage | 本 case 留 NULL |

**核对结论**：映射关系清晰，无字段歧义。两个"不落库"（output / content）都有 schema 设计依据（content 走外部存储是 schema 的本意）。

### 6.4 落库主键策略核对

- BaseEntity.id：`@TableId(type = IdType.ASSIGN_ID)` → 雪花算法，由 MyBatis-Plus 在 save 前生成，save 后回填到 entity
- **关键**：`derivationService.save(d)` 后 `d.getId()` 即可拿到生成的主键，用于 Artifact.derivationId 关联（§1.6.1 改动 3 代码 `a.setDerivationId(d.getId())`）
- H2 测试也支持（ASSIGN_ID 不依赖 DB 自增）

---

## 7. 文件改动总览

| # | 文件 | 改动类型 | 行数估算 |
|---|---|---|---|
| 1 | `eaiselp-data/src/main/java/com/eaiselp/data/service/DerivationService.java` | 新增 | 8 |
| 2 | `eaiselp-data/src/main/java/com/eaiselp/data/service/impl/DerivationServiceImpl.java` | 新增 | 10 |
| 3 | `eaiselp-data/src/main/java/com/eaiselp/data/service/ArtifactService.java` | 新增 | 8 |
| 4 | `eaiselp-data/src/main/java/com/eaiselp/data/service/impl/ArtifactServiceImpl.java` | 新增 | 10 |
| 5 | `eaiselp-data/src/main/resources/application.yml` | **删除整文件** | -26 |
| 6 | `eaiselp-runtime/pom.xml` | 修改（加 eaiselp-data 依赖 + H2 test 依赖）| +6 |
| 7 | `eaiselp-runtime/src/main/java/com/eaiselp/runtime/EaiselpRuntimeApplication.java` | 修改（@MapperScan + scanBasePackages）| +5 |
| 8 | `eaiselp-runtime/src/main/resources/application.yml` | 修改（加 datasource + mybatis-plus）| +15 |
| 9 | `eaiselp-runtime/src/main/java/com/eaiselp/runtime/engine/DerivationEngine.java` | 修改（注入 + 落库调用）| +5（落库逻辑搬到文件 10）|
| 10 | `eaiselp-runtime/src/main/java/com/eaiselp/runtime/engine/DerivationPersistenceService.java` | **新增**（§3.2 方案 a 落库逻辑承载）| ~70 |
| 11 | `eaiselp-runtime/src/test/java/com/eaiselp/runtime/engine/DerivationEngineTest.java` | 新增 | ~120 |
| 12 | `eaiselp-runtime/src/test/resources/application-test.yml` | 新增 | ~20 |
| 13 | `eaiselp-runtime/src/test/resources/schema-h2.sql` | 新增 | ~50 |

**总计**：10 个新增/修改 + 1 个删除 = 11 个文件触及；新增代码约 320 行，删除 26 行。

---

## 8. 给 Dev 的执行提示

1. **严格按 §2 改动顺序执行**，每步独立 `mvn -pl <module> compile` 验证，发现问题立即停下排查，不要积压到末尾。
2. **§3.2 方案 a 必须落地**（新增 DerivationPersistenceService），不要用 `this.persistDerivation()` 自调用糊弄。这是 @Transactional 生效的硬要求。
3. **TC-3 测试用 §5.7 选项 c**（@MockBean DerivationPersistenceService），与方案 a 配合最干净。
4. **Dev 报告按 ES-002 §4.4 模板写**，每条改动以"对照 HEAD"为基准，产出前必跑 `git diff --stat` + `git diff <file>` 核对。
5. **不写 git commit**（按 Dev 边界，提交由 Ops 执行）。
6. **本 case 不动的范围**（§0.3 已列）：MinIO 接入、配额扣减、检查点联动、data Controller 层 —— Dev 不要顺手扩范围。

---

## 9. 给 Reviewer 的提示

按 ES-001 §6 checklist（C1-C14）逐条核对，本 case 重点：

| # | 检查项 | 本 case 关注点 |
|---|---|---|
| C1 | 父 POM pluginManagement 无 executions | 本 case 不动父 POM，应仍 PASS |
| C2 | library POM 无 spring-boot-maven-plugin | 本 case 不动 data/common/capability/adapter POM，应仍 PASS |
| C5 | library POM 无 nacos-discovery | 本 case 不动 data POM 依赖（只删 yml），应仍 PASS |
| C6 | service POM 显式 repackage executions | 本 case 给 runtime pom **新增了 H2 test 依赖**，但不能动 spring-boot-maven-plugin executions（runtime 已有，本 case 不改） |
| C8 | 依赖无环 | runtime → data → common，新增链路无环 |
| C10 | runtime scanBasePackages 包含所有 library 根包 | 本 case **扩展了 scanBasePackages 加 com.eaiselp.data**，C10 由原本 3 包变 4 包，仍 PASS |
| C13 | 删 library Application 类时横切注解迁移 | 本 case **不删 Application 类**（M1.1 已删完），不适用 |

**本 case 不引入新的 C 类违规**，所有改动在 ES-001 框架内。

---

## 10. 变更日志

| 日期 | 版本 | 变更 | 作者 |
|---|---|---|---|
| 2026-07-21 | 1.0 | 初版。覆盖 6 个目标（落库 + Service 层 + IMP-003 + runtime 引依赖 + MapperScan + 单测），含字段映射核对、改动顺序、风险点、回归步骤。 | team-se |

---

## 本次经验沉淀

1. **MyBatis-Plus IService + ServiceImpl 是项目惯例**：本项目 data 模块 Mapper 已用 BaseMapper，Service 层用同生态 IService 是零成本顺承，不要再引入"手写 Service"风格增加心智负担。
2. **library 模块绝对不能有 application.yml**：library jar 被依赖后，其 application.yml 会出现在 host classpath 根，与 host 自己的 application.yml 冲突（启动期"哪个生效"不确定，常导致端口/服务名错乱）。library 化时必须删 application.yml，配置统一由 host 管。这是 IMP-003 真正的危害面，比"nacos 注册残留"严重得多。
3. **@Transactional 自调用失效是隐性大坑**：Spring AOP 通过代理实现事务，类内 `this.method()` 自调用绕过代理，@Transactional 完全失效（不报错但事务不生效）。规避：把事务方法抽到独立 Bean，由外部调用。**SE 出方案时必须显式提示 Dev**，不能假设 Dev 知道。
4. **H2 测试库的 schema 必须单独写**：MySQL schema.sql 用 `ENGINE=InnoDB / JSON / ON UPDATE CURRENT_TIMESTAMP` 等 MySQL 特定语法，H2 即便开 `MODE=MySQL` 也不全认。最佳实践：测试资源目录单独写 schema-h2.sql，只建测试用到的表，类型用 CLOB 代替 JSON。不要试图复用生产 schema。
5. **落库失败 vs 主流程返回 的权衡**：高价值产物（如 LLM 派生结果）落库失败时，**捕获 Throwable 不重抛，只 log**，让主流程返回结果。这是"可靠性优先于一致性"在数据层落地的具体形态。代价：可能出现"用户拿到结果但 DB 无记录"的不一致，需运维/重派兜底。M2 引入配额扣减时需重新评估。
6. **多租户拦截器在 isSystem 时完全 ignore**：`EaiselpTenantHandler.ignoreTable` 在 `TenantContext.isSystem()` 时返回 true，跳过 tenant_id 条件注入。单测无需特殊 mock 多租户，但要注意 `EaiselpMetaObjectHandler.insertFill` 仍会 set tenantId=0（不依赖拦截器）。
