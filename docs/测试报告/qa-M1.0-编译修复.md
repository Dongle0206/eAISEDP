# 测试报告 — M1.0 编译修复（library 模块 repackage 根因消除）

| 字段 | 值 |
|---|---|
| 编号 | QA-M1.0-001 |
| 标题 | M1.0 编译修复：13 文件改动磁盘核对 + 编译/制品/门禁回归验证 |
| 日期 | 2026-07-21 |
| 执行人 | team-qa（L1 测试工程师）|
| 上游 | TS-M1.0-001（L1-SE 技术方案 §4 回归步骤）、QG-MODULE（模块边界门禁）|
| 被测对象 | L1-Dev 报告的 13 文件改动（1 父 POM + 8 子 POM + 4 源码）|
| 验收门禁 | `质量门禁-模块边界.ps1` 6/6 PASS + `mvn clean package -DskipTests` BUILD SUCCESS |
| 结论 | **通过** |

---

## 1. 磁盘事实核对（首条门禁，强制第一步）

> QA 角色规范：不信 Dev 报告，以 `git diff` 真实改动为唯一事实来源。

### 1.1 git status 真实改动清单

```
 M eaiselp-adapter/pom.xml
 D eaiselp-adapter/src/main/java/com/eaiselp/adapter/EaiselpAdapterApplication.java
 M eaiselp-admin/pom.xml
 M eaiselp-auth/pom.xml
 M eaiselp-capability/pom.xml
 D eaiselp-capability/src/main/java/com/eaiselp/capability/EaiselpCapabilityApplication.java
 M eaiselp-data/pom.xml
 D eaiselp-data/src/main/java/com/eaiselp/data/EaiselpDataApplication.java
 M eaiselp-gateway/pom.xml
 M eaiselp-observability/pom.xml
 M eaiselp-runtime/pom.xml
 M eaiselp-runtime/src/main/java/com/eaiselp/runtime/EaiselpRuntimeApplication.java
 M pom.xml
```

**共 13 个文件**（9 M + 3 D + 1 D 在 adapter），与 Dev 报告 13 文件清单**逐条匹配**。

### 1.2 与 Dev 报告逐条比对

| # | Dev 报告声称 | git diff 真实情况 | 一致？ |
|---|---|---|---|
| 1 | 父 POM 删 pluginManagement executions，保留 configuration | diff 显示**新增** 4 行 `<configuration>` + 1 行注释，**无删除动作**。HEAD 版本经 `git show HEAD:pom.xml` 核对，本就**无 executions 段**（只有 groupId/artifactId/version）。最终状态（无 executions、有 configuration）符合方案目标 | 磁盘最终态 ✅；Dev 措辞 ❌（HEAD 本就没 executions，谈不上"删"）|
| 2 | eaiselp-runtime/pom.xml 补 executions/repackage | diff 第 14-26 行新增 plugin + executions + repackage goal | ✅ |
| 3 | eaiselp-gateway/pom.xml 补 executions/repackage | diff 第 14-26 行同上 | ✅ |
| 4 | eaiselp-auth/pom.xml 补 executions/repackage | diff 第 14-26 行同上 | ✅ |
| 5 | eaiselp-observability/pom.xml 补 executions/repackage | diff 第 14-26 行同上 | ✅ |
| 6 | eaiselp-admin/pom.xml 补 executions/repackage | diff 第 14-26 行同上 | ✅ |
| 7 | eaiselp-capability/pom.xml 删 plugin + nacos-discovery 依赖 | diff 第 12 行删 nacos 依赖 + 第 18-20 行删 build/plugin 段，保留 finalName | ✅ |
| 8 | eaiselp-adapter/pom.xml 删 plugin + nacos-discovery 依赖 | 同 capability 模式 | ✅ |
| 9 | eaiselp-data/pom.xml 删 plugin + nacos-discovery 依赖 | 同 capability 模式 | ✅ |
| 10 | runtime Application 加 @EnableScheduling + TODO | diff 第 7 行新增 import、第 9 行 TODO 注释、第 13 行 @EnableScheduling | ✅ |
| 11 | D EaiselpCapabilityApplication.java | diff 显示删除 15 行 | ✅ |
| 12 | D EaiselpAdapterApplication.java | diff 显示删除 13 行 | ✅ |
| 13 | D EaiselpDataApplication.java | diff 显示删除 15 行 | ✅ |

### 1.3 磁盘核对结论

- **改动全部真实落地**（13/13 文件 diff 可见）。
- **唯一偏差**：Dev 报告第 1 条"删 pluginManagement executions"措辞不准——HEAD 版本本就无 executions 段，diff 实际是"新增 configuration"。**磁盘最终状态完全符合 SE 方案 §2.1 改动后目标**（pluginManagement 有 configuration 无 executions），不构成代码缺陷，仅是 Dev 报告措辞误导。
- **门禁判定**：磁盘事实核对**通过**（改动真实落地 + 最终态符合方案）。继续执行测试用例。

---

## 2. 测试用例表

| 用例ID | 分类 | 前置条件 | 步骤 | 预期 | 关联AC | 结果 | 证据 |
|---|---|---|---|---|---|---|---|
| TC-01 | 正常 | JDK 26 + Maven 3.9.16 可用 | `mvn clean package -DskipTests -B`（连跑 2 次） | BUILD SUCCESS；reactor 全 SUCCESS，无 SKIPPED/FAIL | D1, D2 | ✅ PASS | 两次 reactor 均打印 `BUILD SUCCESS`；10 行 reactor（1 parent + 9 子模块）全部 SUCCESS；4 library 仅 `jar:jar` 无 repackage，5 service 有 `spring-boot:repackage` 步骤 |
| TC-02 | 正常 | TC-01 通过 | `jar tf <lib>.jar` 数 BOOT-INF 条目 | 4 library 全 thin（<1MB，BOOT-INF=0） | D3 | ✅ PASS | common 13.4KB / capability 17.9KB / adapter 24.1KB / data 28.5KB；4 个全部 BOOT-INF entries=0, lib=0 |
| TC-03 | 正常 | TC-01 通过 | `jar tf <svc>.jar` 数 BOOT-INF/lib 条目 | 5 service 全 fat（>10MB，BOOT-INF/lib 非 0） | D4 | ✅ PASS | runtime 67847KB（lib=107）/ gateway 48792KB（lib=84）/ auth 36671KB（lib=59）/ observability 38127KB（lib=64）/ admin 47872KB（lib=71）；全部远超阈值 |
| TC-04 | 正常 | TC-01 通过 | `powershell -File docs\架构文档\质量门禁-模块边界.ps1` | 6/6 PASS，退出码 0 | D5 | ✅ PASS | 控制台打印 `PASS: 6/6    FAIL: 0`；G1-G6 全 `[PASS]`；脚本退出码 0 |
| TC-05 | 边界 | — | Read `pom.xml` 第 121-134 行 pluginManagement 段 | 无 `<executions>`，保留 `<configuration>` | G1 | ✅ PASS | pom.xml:122-134 pluginManagement 段：第 128-130 行 configuration 保留，第 131 行注释明确 executions 已下沉，无 executions 元素 |
| TC-06 | 边界 | — | Select-String 5 个 service POM 找 `<id>repackage</id>` | 5 个 service 各自命中 | G6, D8 | ✅ PASS | runtime pom.xml:29 / gateway pom.xml:29 / auth pom.xml:23 / observability pom.xml:25 / admin pom.xml:24；5 个全部命中 `<id>repackage</id>` + `<goal>repackage</goal>` |
| TC-07 | 边界 | — | Select-String 4 library POM 找 `spring-boot-maven-plugin` | 实际配置无命中（注释行可忽略） | G2 | ✅ PASS | common 无任何命中；capability/adapter/data 命中行均为 `<!--` 开头的注释行（pom.xml:17/17/16），无实际 plugin 配置 |
| TC-08 | 边界 | — | `Get-ChildItem -Filter *Application.java` 在 4 library 下 | 0 命中 | G3, G4, D6 | ✅ PASS | library 检索结果为空；对照组 5 service 各有 1 个 Application.java（runtime/gateway/auth/observability/admin） |
| TC-09 | 边界 | — | `Get-ChildItem -Filter *Controller.java` 在 capability/adapter | CapabilityController + AdapterController 保留 | C9 | ✅ PASS | capability/controller/CapabilityController.java（1998B）+ adapter/controller/AdapterController.java（978B）均存在且非空 |
| TC-10 | 边界 | — | Read runtime/EaiselpRuntimeApplication.java | 含 @EnableScheduling；scanBasePackages 未误删 capability/adapter | D7, D9, C13 | ✅ PASS | 第 7 行 import；第 13 行 `@EnableScheduling` 注解 + 迁移注释；第 9 行 TODO（@MapperScan M2 标记）；第 10 行 scanBasePackages 含 `com.eaiselp.runtime/common/capability/adapter` 4 包 |
| TC-11 | 边界 | — | `git diff --stat` 对 src 下业务代码目录 | DerivationEngine/CapabilityLoader/ContextAssembler 零改动 | D10 | ✅ PASS | src 下 diff 仅 4 文件（3 删 Application + 1 改 runtime Application 加 3 行）；DerivationEngine（4367B）/ CapabilityLoader（6241B）/ ContextAssembler（1842B）`git diff` 输出为空，零改动 |
| TC-12 | 异常 | WSL2 + Nacos 就绪 | `java -jar eaiselp-runtime.jar` 启动 + curl 端点 | Started 日志 + 30s 后 @Scheduled 触发 + 端点 200 | 4.4 | ⏭ N/A | 编排者声明 WSL2/Docker 未就绪，启动验证暂缓（M1.0 范围外）。运行时正确性靠 M1.1 后续补跑 |

**用例统计**：11 PASS / 0 FAIL / 1 N/A。

---

## 3. 覆盖情况

| 维度 | 覆盖 | 说明 |
|---|---|---|
| 编译验证（D1/D2）| ✅ 全覆盖 | TC-01 连跑 2 次稳定 |
| 制品验证（D3/D4）| ✅ 全覆盖 | TC-02/TC-03 用 `jar tf` 数 BOOT-INF 条目，不只看体积 |
| 门禁验证（D5）| ✅ 全覆盖 | TC-04 跑脚本，6/6 PASS 退出码 0 |
| 父 POM executions（G1/D-改1）| ✅ 全覆盖 | TC-05 Read 第 121-134 行 |
| service repackage（G6/D8）| ✅ 全覆盖 | TC-06 独立 grep 5 个 POM |
| library 无 plugin（G2/D-改2~4）| ✅ 全覆盖 | TC-07 独立 grep + 注释行排除 |
| library 无 Application（G3/G4/D6）| ✅ 全覆盖 | TC-08 Get-ChildItem |
| Controller 保留（C9）| ✅ 全覆盖 | TC-09 文件存在 + 非空 |
| 横切注解迁移（C13/D7）| ✅ 全覆盖 | TC-10 Read runtime Application 第 7/9/13 行 |
| 业务代码零回归（D10）| ✅ 全覆盖 | TC-11 git diff --stat 业务目录空 |
| 启动 + 端点（4.4）| ❌ 未覆盖 | TC-12 标 N/A：WSL2/Docker 未就绪，编排者声明 M1.0 范围外，M1.1 后续补 |
| @Scheduled 30s 热重载运行时验证（R1）| ❌ 未覆盖 | 同上，依赖启动 |
| @MapperScan 暂不迁移决策（R2/R7）| ✅ 间接覆盖 | TC-10 第 9 行 TODO 注释存在，决策已落盘；运行时验证待 M2 引入 data 后 |

**结论**：编译期 + 制品期 + 门禁期 100% 覆盖；运行时验证按编排者声明豁免，符合 M1.0 范围。

---

## 4. 缺陷清单

| 缺陷ID | 级别 | 描述 | 影响 | 修复建议 |
|---|---|---|---|---|
| - | - | 无阻断缺陷 | - | - |

**非缺陷观察**（不计入 FAIL，仅记录）：

- **观察-1**：Dev 报告第 1 条措辞"删 pluginManagement executions"与磁盘 diff 不符（HEAD 本就无 executions，diff 实际是新增 configuration）。磁盘最终态符合方案，不影响验收。建议 Dev 后续报告用"对照目标态描述"而非"对照 HEAD 描述"，避免歧义。
- **观察-2**：质量门禁脚本实际输出是英文（`[PASS] G1`、`Gate Summary`），与 `质量门禁-模块边界.md` §4.2 中文示例不一致。可能是脚本被改为英文版但 md 未同步。功能正确，不影响判定。建议 L1-standards 同步 md 与脚本。
- **观察-3**：mvn 警告 `LF will be replaced by CRLF`（9 个 POM + 1 个 Java 文件）。Windows 换行符警告，不影响编译/制品/门禁。如需消除，可在 `.gitattributes` 显式声明 `*.xml text eol=lf`。

---

## 5. 结论

**通过**。

- **磁盘事实核对**：13 文件改动全部真实落地，与 Dev 报告匹配（仅父 POM 一条措辞偏差，非代码缺陷）。
- **编译验证**：`mvn clean package -DskipTests` 连跑 2 次 BUILD SUCCESS，9 子模块 + 1 parent 全 SUCCESS 无 SKIPPED。
- **制品验证**：4 library 全 thin（13-29KB，BOOT-INF=0）；5 service 全 fat（35-68MB，BOOT-INF/lib 59-107 条）。
- **门禁验证**：`质量门禁-模块边界.ps1` 6/6 PASS，退出码 0。
- **边界验证**：父 POM 无 executions / 5 service 显式 repackage / 4 library 无 plugin / library 无 Application / 2 Controller 保留 / runtime 含 @EnableScheduling / 业务代码零改动——全部通过。
- **未覆盖**：启动 + 端点验证（TC-12）按编排者声明豁免（WSL2 未就绪），M1.0 范围外。

**建议下一步**：可进入 Ops git commit 阶段。

---

## 6. 反幻觉自检

| 自检项 | 自检结果 |
|---|---|
| 我跑的 git diff 是否真的包含 Dev 声称的所有 13 处改动？ | ✅ 是。`git status --short` 13 行 + `git diff --stat` 13 行，逐条匹配 Dev 报告。每条 diff 内容都亲自看过（父 POM、5 service POM、3 library POM、runtime Application、3 删除 Application）。 |
| 我每个用例"预期"依据的代码，是自己 Read/grep 出来的，还是抄 Dev 报告？ | ✅ 全部自验。TC-05 Read 父 pom.xml:121-134；TC-06 Select-String 5 service POM 拿到 repackage 行号；TC-07 Select-String 4 library POM 拿到注释行号；TC-08 Get-ChildItem 拿到 Application 列表；TC-09 Get-ChildItem 拿到 Controller 字节数；TC-10 Read runtime Application 全文 18 行；TC-11 `git diff --stat` 拿到 src 下业务目录零改动证据。引用行号全部是本次实测得到，非抄 Dev/Reviewer。 |
| 如果 Dev 报告是假的，我的用例还能不能发现？ | ✅ 能。三个独立兜底层：(1) TC-01 跑两次 mvn clean，若 Dev 没真改父 POM 则编译必失败（library 仍被 repackage，runtime 找不到 capability 符号）；(2) TC-02/TC-03 用 `jar tf` 直接看制品 BOOT-INF，与 Dev 声称的 thin/fat 无关，若 library 仍是 fat jar 立刻露馅；(3) TC-04 门禁脚本独立跑 6 条规则，每条直接 grep 磁盘文件，绕开 Dev 报告。三层兜底任一层都能抓到虚假改动。 |
| 我是否依赖了 Reviewer 报告？ | ❌ 不依赖。`docs\测试报告\review-M1.0-编译修复.md` 文件不存在（Reviewer 未提交报告）。我的所有验证独立完成。 |

---

## 本次经验沉淀

1. **"删 X"措辞陷阱：磁盘事实核对时必须看 HEAD 版本对照**。本次 Dev 报告说"删 pluginManagement executions"，但 `git show HEAD:pom.xml` 显示 HEAD 本就无 executions——diff 实际是"新增 configuration"。若仅看 Dev 报告 + 当前磁盘，会把"目标态符合"误判为"删除动作真实发生"。**QA 核对 diff 时必须三态对照**：HEAD（改动前）→ 工作区（改动后）→ 报告声称的动作，三者的"动作语义"要对齐，不能只看最终态。最终态对 ≠ 报告诚实。

2. **thin/fat jar 区分不能只看体积，必须 `jar tf` 数 BOOT-INF**。本次 4 library jar 都 <30KB，单看体积就能猜是 thin；但 size 不是可靠判据（一个依赖极少的 service 也可能 <10MB）。可靠判据是 `jar tf x.jar | findstr BOOT-INF`：thin=0 条目，fat=几十到几百条目（含 BOOT-INF/lib/*.jar）。这条已固化为 TC-02/TC-03 的硬步骤，未来验 Maven 制品一律用条目数不用体积。

3. **门禁脚本本身的英文/中文差异需对账**。本次发现 `质量门禁-模块边界.ps1` 实际输出英文，但 md §4.2 示例是中文——脚本与文档不同步是常见漂移。QA 跑脚本时如果完全按 md 描述的"打印 PASS: 6/6"去找，可能因大小写/中英文差异误判 FAIL。**正确做法**：把脚本实际输出原样贴进报告（见 TC-04 证据），不按 md 文案臆造。这条经验沉淀给后续 QA 与 standards 角色协同：脚本改了要同步 md，否则下游 QA 报告口径会乱。
