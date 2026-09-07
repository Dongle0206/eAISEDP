# 测试报告 — case-20260822-MCPMock（fast 档）

> 结论：**PASS**（11 AC 全覆盖：自动化 9 + 混合 2；656 全绿三方复现）
> 执行：team-qa 职责由编排者代验（fast 档——三方证据充分：Dev 27 新用例直证 + Reviewer 独立复现 656 + Security 独立复现 656 与隔离/XSS 面核查，QA 增量价值边际小，代验留痕）

## 证据链
- mvn -pl eaiselp-auth,eaiselp-runtime -am test：Dev/Reviewer/Security **三方独立执行均 656/0/0**（629 基线+27：MockMCPProviderTest 11 + McpProviderWiringTest 6 + HttpMcpMockLoopbackTest 10；adapter 31→58 零回退）
- Reviewer GATE:PASS（0 阻断/2 建议/3 可选）+ Security GATE:PASS（0 阻断/3 建议/2 可选），报告均落盘

## 11 AC 覆盖矩阵
| AC | 覆盖 | 结果 |
|---|---|---|
| 01 工具注册元数据 | AUTO MockMCPProviderTest（9 工具 name/description/schema 断言） | PASS |
| 02 Jira 链路 | AUTO create_issue/list_issues/get_issue 往返 | PASS |
| 03 Confluence 链路 | AUTO create_page/get_page/search_pages | PASS |
| 04 GitLab 链路 | AUTO create_issue/create_mr/list_mrs（get/list 括注→PRD 变更留痕，create 语义+分桶覆盖） | PASS* |
| 05 租户隔离 | AUTO 跨租户 id 直取 -32004 无他租户字段（进程级唯一 id 修复后固化）+ Security 链路核查 | PASS |
| 06 mock 标识/无 DDL | AUTO __mock:true 顶层断言；git 确认零迁移文件 | PASS |
| 07 参数校验错误结构 | AUTO -32601/-32602/-32603/-32004 对齐 JSON-RPC | PASS |
| 08 目录/试调用/转义 | Security 全 sink esc() 核查 + 前端 node --check | PASS+M |
| 09 限流复用 | AUTO+Security（30/min USER 维度注解在位，试调用同端点） | PASS+M |
| 10 契约回环 | AUTO HttpMcpMockLoopbackTest（jsonrpc/id/result-error 二选一） | PASS |
| 11 零改动替换 | AUTO 第二回环仅改 serverUrl 通过 + WiringTest 4 组合 | PASS |

## 人工联测清单（部署机，MCP 演示）
1. mcp.html 目录三源分组渲染（9 工具）→ 选中工具 schema 动态表单 → 试调用 → 结果转义展示 + 展开原始报文折叠区
2. 两个不同租户账号各 create → 互查对方 id 应 NOT_FOUND
3. 连续 invoke >30 次/分钟 → 429 提示
4. MCP_PROVIDER=http 且无 server → 降级"未配置"提示（fail-closed）

## 缺陷
产品缺陷 0。过程缺陷 1（已修）：per-租户 id 序列跨租户重号——Dev 自抓改进程级唯一（测试断言固化）。

## 结论
PASS。部署机完成 4 项联测后关闭 case。
