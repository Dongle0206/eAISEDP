# ops-GLM接入-新机配置记录 — case-20260723-GLM接入

> 本文档由 team-ops 产出。本机（dev 机）已完成 git commit；以下为**新机（部署机 172.16.180.166）**的用户操作清单。
> Ops 无 SSH 权限，全部命令由**用户在新机上手动执行**。

## 0. 背景与前提

| 项 | 值 |
|---|---|
| Case | case-20260723-GLM接入 |
| 变更内容 | GlmLlmAdapter 占位实现 → 智谱 GLM-4 真实 API（方案 C 手写 RestClient） |
| 本机 commit | `4644f9d`（feat(M1.0): GLM 真实 API 接入——手写 RestClient 替换占位实现） |
| 本机 push 状态 | **失败**——本机无法连接 github.com:443（网络层）。新机 `git pull` 前需确认能访问 github，或改用其他途径同步代码（见 §0.1） |
| 新机 IP | 172.16.180.166 |
| 新机代码路径 | D:\eaiselp\platform（已 clone） |
| runtime 端口 | 8081（/api/runtime/derive） |
| 部署方式 | java -jar 直跑（无 docker） |

### 0.1 本机 push 失败的处置（重要）

本机执行 `git push origin main` 两次均失败：
```
fatal: unable to access 'https://github.com/Dongle0206/eAISEDP.git/':
Failed to connect to github.com:443 after 21144 ms: Could not connect to server
```
commit `4644f9d` 已在本机落盘，但**尚未推到远程**。新机 `git pull` 拉不到这次提交。请编排者/用户选择：

- **选项 A**：修复本机到 github 的网络（代理/防火墙/VPN），重试 `git push origin main`，再让新机 pull。
- **选项 B**：本机能直连新机时，用 scp/共享盘把代码或打好的 jar 直接传新机，绕过 github。
- **选项 C**：新机若能访问 github，可由用户在新机直接 cherry-pick / patch 应用（需本机先 `git format-patch -1 4644f9d` 导出补丁再传过去）。

下方 §1~§4 的命令**默认假设 push 已成功、新机能正常 pull**；若采用选项 B/C，跳过 §1.1 的 `git pull`，改用对应同步方式。

---

## 1. 新机更新代码 + 打包

### 1.1 拉取最新代码

```cmd
REM 在新机（172.16.180.166）执行
cd /d D:\eaiselp\platform
git pull origin main
```

**预期**：拉到 commit `4644f9d`。验证：
```cmd
git log --oneline -3
REM 应看到最顶部是 4644f9d feat(M1.0): GLM 真实 API 接入——手写 RestClient 替换占位实现
```

若 `git pull` 报连接失败，回到 §0.1 处置。

### 1.2 打包

```cmd
cd /d D:\eaiselp\platform
mvn clean package -DskipTests
```

**预期**：`BUILD SUCCESS`，产出 `eaiselp-runtime\target\eaiselp-runtime.jar`（及 adapter 等模块 jar）。

**前置依赖**：新机需有 Maven + JDK（与既有 runtime 一致版本，本工程为 Spring Boot 3.x / JDK 17+）。

---

## 2. 配置 GLM_API_KEY 环境变量

> API Key 是敏感信息，**不要**写进任何 commit 文件。application.yml 里是 `${GLM_API_KEY:}` 占位符，运行时从环境变量注入。

### 2.1 方式 A（推荐，持久化）——管理员 PowerShell

```powershell
[System.Environment]::SetEnvironmentVariable("GLM_API_KEY", "<your-glm-api-key>", "User")
```

设完后**关闭所有 cmd / PowerShell 窗口重开**（环境变量对新进程才生效）。

验证：
```powershell
echo $env:GLM_API_KEY
```

### 2.2 方式 B（临时，仅当前 cmd 进程）

```cmd
set GLM_API_KEY=<your-glm-api-key>
```
仅对当前 cmd 窗口有效，关窗即失效。适合一次性验证；生产建议用方式 A。

---

## 3. 重启 runtime

### 3.1 停止旧 runtime

旧 runtime 跑的是占位 GLM 的旧 jar，必须先停。

```cmd
REM 方式 1：若旧 runtime 的 cmd 窗口还在，切到该窗口按 Ctrl+C
REM 方式 2：按窗口标题 kill
taskkill /fi "windowtitle eq eaiselp-runtime*" /f

REM 方式 3（兜底）：按端口找进程再 kill（8081）
netstat -ano | findstr :8081
REM 拿到 PID 后：
taskkill /pid <PID> /f
```

验证旧 runtime 已停：
```cmd
curl http://172.16.180.166:8081/api/capability/overview
REM 预期：连接失败 / 无响应
```

### 3.2 启动新 runtime

**注意：必须带环境变量启动**（方式 B 临时法，确保新进程能读到 Key）。

```cmd
cd /d D:\eaiselp\platform
set GLM_API_KEY=<your-glm-api-key>
java -jar eaiselp-runtime\target\eaiselp-runtime.jar
```

若已用 §2.1 方式 A 持久化，且**新开了 cmd 窗口**，则可省略 `set` 行直接 `java -jar`。但为保险，建议显式 `set` 一次。

**启动成功标志**：日志出现 Spring Boot 启动完成、Tomcat 监听 8081、无 GLM/Bean 装配异常。

---

## 4. 验证 GLM 已接入

### 4.1 启动日志确认

runtime 启动日志中应**无**以下报错：
- `GlmLlmAdapter` Bean 装配失败
- `GLM_API_KEY` 为空导致 `isAvailable()=false`（若日志打印可用性）

### 4.2 接口冒烟（curl）

在**本机或新机**执行（已验证本机浏览器可访问 172.16.180.166:8081）：

```cmd
curl -X POST http://172.16.180.166:8081/api/runtime/derive ^
  -H "Content-Type: application/json" ^
  -d "{\"role\":\"team-po\",\"task\":\"用一句话描述登录功能\",\"caseId\":\"case-glm-smoke-001\"}"
```

**预期**：返回的 `output` **不再是** `[M1.0 占位]` 字样，而是真实 GLM 生成的中文内容（如"登录功能允许用户通过账号密码验证身份后访问系统..."）。

若仍是占位 → 说明新 runtime 没生效（jar 没更新 / 旧进程没杀干净 / 环境变量没注入），回 §3 重做。

---

## 5. 给 QA 的验证 SQL

确认 GLM 调用结果已落库（DerivationEngine 派生持久化，M1.0 已接入）。

```sql
-- 验证派生记录落库（input_tokens/output_tokens 不再为占位 null，应有真实值）
SELECT id, role, case_id, model, input_tokens, output_tokens, status, create_time 
FROM t_derivation ORDER BY id DESC LIMIT 5;

-- 验证制品落库
SELECT id, derivation_id, type, role, case_id 
FROM t_artifact ORDER BY id DESC LIMIT 5;
```

**预期**：
- `t_derivation` 新增 `case-glm-smoke-001` 记录，`status` 成功，`model` 为 glm-4 系列，`input_tokens`/`output_tokens` 有真实数值（非 null）。
- `t_artifact` 有对应 type 的产出记录。

---

## 6. 回滚

若新机接入后异常需回退：

1. 停新 runtime（§3.1）。
2. 回退代码：`git reset --hard <4644f9d 的父 commit>`（即 `7a3a248`）。
3. 重新打包启动旧 jar：`mvn clean package -DskipTests && java -jar eaiselp-runtime\target\eaiselp-runtime.jar`。
4. （已 push 的 commit 若要撤销，用 `git revert 4644f9d`，勿 reset 改写远程历史。）

---

## 7. 操作执行记录（用户填写）

| 步骤 | 执行人 | 时间 | 结果 | 备注 |
|---|---|---|---|---|
| §1.1 git pull | | | | |
| §1.2 mvn package | | | | |
| §2 配 Key | | | | 方式 A / B |
| §3 重启 runtime | | | | |
| §4.2 curl 冒烟 | | | | output 是否为真实 GLM 内容 |
| §5 SQL 验证 | QA | | | |

---

## 本次经验沉淀

1. **本机 push 失败的预案**：commit 已落盘但 push 因网络失败时，新机部署会卡在 `git pull` 拉不到提交。Ops 应在操作清单里显式给出"push 失败处置"三选项（修网络/绕 github 直传/patch 传输），不能假设 push 总会成功。
2. **环境变量注入的进程可见性陷阱**：方式 A（SetEnvironmentVariable User 级）只对**新开的**进程生效，旧 cmd 窗口读不到；方式 B（set）只对当前窗口有效。部署文档里必须强调"设完重开窗口"或"启动命令前显式 set 一次"，否则 runtime 读到空 Key 静默走兜底。
3. **敏感信息双闸**：commit 前 Ops 须做两道检查——(a) 看配置文件 diff 确认是 `${ENV:default}` 占位符；(b) 全仓库 findstr 扫描真实 Key 字符串确认零命中。本次两项均通过，未泄漏 `<redacted-prefix>...` 到版本库。
