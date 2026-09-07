# 安全评审报告 — case-20260822-MCPMock（MCP Mock 演示层，fast 档）

> 结论：**GATE:PASS（通过）**。三级计数：阻断 0 / 建议 3 / 可选 2
> 评审人：team-security（独立验证，与 Dev/Reviewer 不同模型）| 本报告由 Security 全文回复、编排者代落盘
> 审查基线：CLAUDE.md §9.3（多租户 P11）+ OWASP 通用基线

## 磁盘事实核对
- 真实 diff 与 Dev 报告一致：platform 仓 `M` HttpMCPAdapter.java（+32/-7 条件装配 AllNestedConditions）、`M` application.yml（provider 配置）、`??` MockMCPProvider.java + 3 测试类；web 仓 `M` mcp.html（+124/-14）。"零新增 Maven 坐标"实测成立（pom 无 diff，mockwebserver 为既有 test 依赖）。
- mvn 复现：exit 0，**60 suites / 656 tests / 0 failures / 0 errors / 0 skipped** = 629 基线 + 27 新增。

## 威胁面核查（逐项）

### 1. 租户隔离 — PASS
- 取数链：MCPController（InvokeRequest 仅 name/params 无 tenant 字段）→ adapterFactory → MockMCPProvider.store() → `tenants.computeIfAbsent(TenantContext.get(),...)`。tenantId 唯一来源 ThreadLocal TenantContext，由 JwtAuthInterceptor→LoginUser.set 从 JWT claims 注入（CLAUDE.md §9.3 规则 4"不从请求参数取"）。
- 跨租户 id 枚举：进程级序列（跨租户不重号）+ 分桶双重保证；跨租户 get 返回 -32004 且不含他租户任何字段（MockMCPProviderTest:226-239 固化断言），无存在性 oracle。list/search 全部经 store() 分桶，无全表扫描。

### 2. 注入/XSS — PASS
- SQL 注入 N/A（无 DB 无 DDL，纯内存）。XSS：mcp.html 全量 sink 核查——目录卡/徽章/chip、动态表单 label/input/placeholder、banner 各分支均 esc() 或静态；结果与 JSON-RPC 报文折叠区 `.text()` 纯文本；esc() 覆盖五字符，属性上下文无引号逃逸。
- schema 伪造面：mock 模式 schema 来自服务端构造器固定注册表，registerTool 无 REST 暴露（全仓 grep 仅构造器自调）；http 模式 schema 来自运维配置信任边界内，即使伪造已被 esc() 拦截——双层防御。
- SSRF/命令注入：serverUrl 仅配置项，无客户端可控 URL，无 shell。

### 3. 配置面 — PASS
- provider 切换仅 yml/env 级，无 REST 切换接口=运维面，可接受。enabled 默认 false 复核（WiringTest 4 组合固化）。非法 provider 值 fail-closed（仅 Stub 装配→降级"未配置"，不 500）。

### 4. 限流/权限复用 — PASS
- invoke 30/min（USER 维度）/tools 60/min 注解保留；试调用走同一端点无绕过。拦截器链 order 限流0→JWT1→权限2 覆盖 /api/**；adapter:view 两端点在位。USER 维度 key 取 JWT userId，不受 XFF 伪造影响。

### 5. 信息泄露 — PASS
- 无硬编码密钥；mock 数据无真实凭据。异常不回传堆栈；错误消息仅反射调用者自身输入。

### 6. 内存 DoS — 可接受（fast 档）
- 30/min/user 下 ≈4.3 万条/天（数十 MB/天/用户），重启清空，演示机量级可接受。放大向量见 S1。

## 缺陷清单
| 编号 | 严重度 | 文件:行 | 问题 | 修复建议 |
|---|---|---|---|---|
| S1 | 🟡建议 | MCPController:93 / Mock:225-307 | invoke 参数值无长度上限+mock 常驻内存；JSON body 无默认上限，已认证用户可单请求大 body 放大（限流仅限频次） | 参数级上限（单值 10KB→-32602）+全局 body 限制；PRG-005 前完成 |
| S2 | 🟡建议（既有留痕） | TenantContextFilter:23-26 + LoginUser:12-16 | X-Tenant-Id 客户端可控且先于 JWT 写 ThreadLocal；靠"claims.tenantId 恒非空"隐式前提覆盖 | claims.tenantId null 时显式 clear/fail-closed；SP-2 网关后仅信任网关 header |
| S3 | 🟡建议（既有留痕） | MCPController:65/89 | invoke 与 tools 复用 adapter:view——http 真实工具接入后粒度不足 | PRG-005 增设 mcp:invoke 独立权限点过门禁 |
| S4 | 🟢可选 | Mock:203 | CODE_INTERNAL 反射 e.getMessage() | 固定文案，明细留日志 |
| S5 | 🟢可选 | Mock:399-404 | 每桶实体数无上限 | 每桶上限（10k）+结构化错误 |

## STRIDE 速览
欺骗/提权：JWT 强制前置—挡住；篡改：自写自读无跨主体面—挡住；信息泄露：跨租户 NOT_FOUND 无字段—挡住；DoS：S1/S5 判定可接受；抵赖：审计链未接 MCP invoke（随 S3 补）。

## 经验沉淀
1. 内存 mock 层租户隔离必须"id 生成策略"双保险——分桶+全局唯一 id 是可复用模式。
2. "零新增依赖"与"schema 驱动动态表单"是 mock 类 case 两个高幻觉宣称点——须实测 pom 无 diff + grep registerTool 无暴露 + esc() 全 sink 覆盖才放行。
3. 限流只限频次不限体积：凡新增"存用户输入到内存"的端点应同时给参数长度上限。

GATE:PASS
