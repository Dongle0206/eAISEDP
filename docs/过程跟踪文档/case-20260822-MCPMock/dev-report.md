# Dev 交付报告 — case-20260822-MCPMock

> Dev：team-dev（单 Dev 全栈，fast 档）| 2026-08-22 | 本报告由编排者依据 Dev 回报代落盘（ES-002 §4.4 履行）

## 变更清单

- [T1] MockMCPProvider：`eaiselp-adapter/src/main/java/com/eaiselp/adapter/defaultimpl/MockMCPProvider.java`（新，20KB）——9 工具命名空间式注册（schema 附录写入 Javadoc，Q3 定稿）、`ConcurrentHashMap<Long,TenantStore>` 租户分桶+进程级唯一 id、`__mock:true` 顶层标识（Q2）、结构化错误 `{code,message}` 对齐 JSON-RPC（-32601/-32602/-32603/-32004）
- [T2] 装配：HttpMCPAdapter.java（改）——enabled=true 且 provider=http；MockMCPProvider enabled=true 且 provider=mock（matchIfMissing，默认 mock）。Boot 3.2 `@ConditionalOnProperty` 不可重复，用 `AllNestedConditions` 组合（AND），6 用例装配测试覆盖互斥。MCPController/工厂零改动（SPI 多态）
- [T3] 配置：application.yml——新增 `provider: ${MCP_PROVIDER:mock}`+注释（enabled=false 默认不变）
- [T4] 前端：pages/mcp.html（改 +124/-14）——source 分组目录卡+schema 动态表单+`.text()` 转义结果+`<details>` 折叠 JSON-RPC 原始报文（Q5 默认收起），全 esc
- [T5] 测试：MockMCPProviderTest(11) + HttpMcpMockLoopbackTest(10) + McpProviderWiringTest(6)

## 测试统计

`mvn -pl eaiselp-auth,eaiselp-runtime -am test` BUILD SUCCESS，**656/656 绿 = 629 基线 + 27 新增，零回退**（adapter 31→58）。前端内联脚本 node --check 全过。

## AC 对照

AC-01/02/03/04/06/07 单测直证；AC-05 含"跨租户 id 直取 NOT_FOUND 且无他租户字段"断言（实现中发现并修复 per-租户 id 重号缺陷，改为进程级唯一 id）；AC-08/09 复用既有权限/30min 限流+前端转义；AC-10 原始报文层断言 jsonrpc/id/result-error 二选一；AC-11 第二回环 endpoint 仅改 serverUrl 断言通过。

## 遗留

① AC-04 括注"gitlab issue create 后可 get/list"——F1 固定 9 工具无 gitlab.get/list 工具，按 create 语义+分桶存储覆盖，如需 get/list 工具须 PRD 变更；② dev-report 落盘（本文件已补）。

## 经验沉淀

1. Spring Boot 3.2 `@ConditionalOnProperty` 不可重复（编译期证实），多属性 AND 条件装配用 `AllNestedConditions` 嵌套条件类是标准写法。
2. 多租户 mock 的 id 序列必须进程级唯一——per-租户序列会产生跨租户重号 id，使"他租户 id 必然 NOT_FOUND"隔离语义依赖数据巧合（本 case 测试抓出的真实缺陷）。
3. PowerShell `-replace` 批量改 .java 后用 `Set-Content -Encoding UTF8` 会写入 BOM，javac 拒绝；需 `[IO.File]::WriteAllText` + `UTF8Encoding($false)` 回写。
