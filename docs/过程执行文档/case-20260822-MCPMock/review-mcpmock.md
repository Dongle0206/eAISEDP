# 代码评审报告 — MCP Mock 演示层（MockMCPProvider）

> Case: case-20260822-MCPMock（fast 档）| 评审人：team-reviewer（独立复现）| 2026-09-07
> 结论：**通过**（GATE:PASS，阻断缺陷 0，建议 2，可选 3）
> 依据：docs/需求文档/case-20260822-MCPMock/（PRD 11 AC + 编排者裁决 Q1~Q5 与技术要点 6 条）+ CLAUDE.md（ES-002/ES-003 摘要）

## 0. 磁盘事实核对（强制第一步，先于规范审查）

以 `git status` / `git diff --numstat` / 逐文件 diff 为唯一事实来源（platform = D:\AI\mywork\platform，web = D:\AI\mywork\eaiselp-web-separate）：

| 报告对象（编排者清单） | 磁盘实际 | 判定 |
|---|---|---|
| MockMCPProvider.java（新 ~20KB） | 未跟踪新文件，20113 字节 / 405 行 | 落地 ✓ |
| HttpMCPAdapter.java（+32/-7） | 已修改，实际 +27/-5 | 落地 ✓（合计口径 31+/7-，属计数粒度差异，非虚报） |
| application.yml（provider 配置） | 已修改，+4/-2，`provider: ${MCP_PROVIDER:mock}` | 落地 ✓ |
| 3 测试类（27 用例） | 3 个未跟踪新文件；用例数 11+6+10=27 | 落地 ✓ |
| web pages/mcp.html（+124/-14） | 已修改，numstat 恰 124/14 | 落地 ✓ |
| diff 中有而清单未提及的改动 | 无（无夹带）；MCPController/工厂/pom 零改动 | ✓ |

**反幻觉自检**：全部引用均来自本次自跑 diff 与 Read；测试结论来自本次自跑构建。Dev 报告未在评审中采信。

**测试独立复现**：`mvn -pl eaiselp-auth,eaiselp-runtime -am test`（JDK 17.0.20 / Maven 3.9.16，exit 0），surefire 汇总 **656 通过 / 0 失败 / 0 错误 / 0 跳过**（629 基线 + 27 新增，AC-10"不回退"达成）。新增三类全绿：MockMCPProviderTest 11、McpProviderWiringTest 6、HttpMcpMockLoopbackTest 10；既有 MCPControllerTest 9 项回归绿。

## 1. 必查项逐条结论

1. **git 盘点 + 656 绿**：见 §0，复现成立。
2. **装配正确性（重点）**：`AllNestedConditions`（`ConfigurationPhase.REGISTER_BEAN`）组合 enabled=true AND provider=http/mock 是标准 Boot 写法；mock 侧 `matchIfMissing=true` 与 yml `${MCP_PROVIDER:mock}` 双重兜底缺省 mock。互斥成立：provider 单值属性，http 条件无 matchIfMissing；非法值时仅 Stub 装配（isAvailable=false）→ 工厂返回 null → Controller 走既有降级，无 500。McpProviderWiringTest 6 用例固化全部组合。**升级风险判定**：既有 `MCP_ENABLED=true` 未配 `MCP_PROVIDER` 的部署升级后会从 Http 静默切到 Mock——与裁决 Q1"mock 默认"语义一致，属**已裁决行为而非缺陷**；且 enabled 默认 false、PRD 明示当前无真实 server 部署、`__mock:true` 使切换自标识可见、yml 注释已写明切换方式。判**中低风险/非阻断**，建议随发布补升级注记（D1）。
3. **租户隔离**：`ConcurrentHashMap<Long,TenantStore>` + `TenantContext.get()`（永非 null，缺省落 SYSTEM_TENANT=0 系统桶，无 NPE）；列表/搜索/直取均只读本租户桶；id 为进程级 AtomicLong 序列（跨租户不重号），他租户 id 在本桶必然 NOT_FOUND（-32004，响应仅 `{__mock, error}`，无他租户字段）；读路径全部拷贝快照，内部可变状态不外泄；synchronized 列表与 findById 锁对象一致。无清理机制致内存只增——AC-06 已定内存态、Javadoc 已声明边界，可接受（D4）。
4. **9 工具/schema/错误码**：恰好 9 工具且命名与 AC-01 逐字一致；schema 为 JSON-Schema 形态（properties/required，字段名+type+必填标记）；错误码 -32601/-32602/-32603 为 JSON-RPC 2.0 保留码、-32004 落 server error 区间（-32000~-32099），结构 `{code,message}`，缺参/未知工具/兜底异常均结构化不抛 500。AC-04"gitlab.create_issue 同语义"在固定 9 工具集（AC-01 权威）下无 get/list 工具，测试内注释了消解理由（分桶结构保证同租户语义），合理。
5. **前端**：卡片/表单/chips/placeholder 全走 `esc()`；结果与原始报文用 `.text()` 纯文本渲染（`<script>` 注入不执行，AC-09）；banner 消息拼入用户可控内容处均先 esc 再 .html()；schema 动态表单 + 客户端必填预检（服务端 AC-07 为权威）；原始报文 `<details>` 默认收起（裁决 Q5）；限流 429 走 `.fail()` 显示"HTTP 429"，可判读但后端 msg 被丢弃（D3）；原始报文为前端重构报文而非线上字节流（D2）。
6. **ES-002**：本报告落盘即履行 G7（编排者指定路径优先于 §8.1 标准路径）。**dev-report 补落盘提示**：`docs/过程跟踪文档/case-20260822-MCPMock/dev-report-*.md` 当前**不在磁盘**，Dev 须按 ES-002 §4.4/§8.4 补落盘（以 git diff 为基准描述，含 HEAD 对照），否则 G7/G10 口径不闭环。

## 2. 通用规范维度（CLAUDE.md / ES-003）

无新增依赖（mockwebserver 既有，pom 零改动）；无敏感信息/生产地址硬编码（server-url 默认值既有）；9 工具为 PRD F1 明文规定的固定预置逻辑（非场景硬编码违例），前端 GROUP_ORDER 仅展示层配置且有注释；中文单语符合裁决 Q4；无 DDL/迁移脚本（AC-06）；包结构符合 P3/P5（defaultimpl 既有位置，library 模块未加 Application）；REST 零新增端点（MCPController 零改动，G14 n/a）；G11（Javadoc 中"编排者"字样属注释豁免）、G13（无 @InterceptorIgnore，隔离经 TenantContext）无命中。

## 缺陷清单

| 编号 | 严重度 | 文件:位置 | 问题 | 修复建议 |
|---|---|---|---|---|
| D1 | 🟡建议 | HttpMCPAdapter.java:85-93 / application.yml:104 | provider 缺省 mock（yml 默认 + matchIfMissing 双重兜底）：既有 `MCP_ENABLED=true` 未配 `MCP_PROVIDER` 的部署升级后静默从 Http 切到 Mock。与裁决 Q1 一致、非违规，但属隐性行为变更 | 发布/升级注记写明"保留真实 server 行为须显式 `MCP_PROVIDER=http`"；可在启动日志打印生效 provider 便于运维核对 |
| D2 | 🟡建议 | pages/mcp.html（doInvoke/rawEnvelope） | "JSON-RPC 原始报文"折叠区为前端**重构**报文（浏览器→runtime 实为 REST；provider=mock 时进程内无 JSON-RPC 线上报文），演示时易被误读为真实流量 | 摘要文案标注"契约形态（重构报文）"，或注明"仅 provider=http 时反映适配层→server 契约" |
| D3 | 🟢可选 | pages/mcp.html（doInvoke .fail） | 429 限流时后端 msg（"MCP 工具调用请求过于频繁…"）与 Retry-After 被丢弃，仅显示 HTTP 429；AC-09"可判读"已满足但体验降级 | fail 处理器读 `xhr.responseJSON.msg` 展示 |
| D4 | 🟢可选 | MockMCPProvider.java:111 | 租户桶与记录无清理/上限，长驻进程内存单调增长（AC 已定内存态，Javadoc 已声明） | 如日后延长演示时间，可加简单上限（如每桶 N 条环形淘汰）；当前可接受 |
| D5 | 🟢可选 | HttpMCPAdapter.java:96 | `getProvider()="http-mcp"` 与配置枚举值 `http` 不同名（前端 provider 徽标显示 http-mcp） | 纯命名观察；如统一改为 "http" 须同步前端判断，可不动 |

## 本次经验沉淀

1. `@ConditionalOnProperty` 不可重复注解时，`AllNestedConditions` + 嵌套条件类 + `ConfigurationPhase.REGISTER_BEAN` 是组合开关（enabled×provider 互斥）的标准解；配套 `ApplicationContextRunner` wiring 测试能以极低成本固化全部组合（含缺省与非法值），值得作为多 provider 装配的固定模式。
2. "缺省值切换 provider"类改动，评审要点不是实现正确性而是**既有部署的静默迁移**：matchIfMissing + yml 默认值会形成双重兜底，任何一层都足以触发切换；判责依据是裁决语义是否覆盖该场景，未覆盖则必须阻断并回提编排者。
3. mock/演示层的租户隔离最容易在"id 直取"处穿透——本实现用"进程级全局 id 序列 + 租户分桶"双保险（他租户 id 在本桶必然不存在），配合"错误响应仅含 `{__mock,error}` 不含数据字段"的断言写法，可作为隔离类 AC 的测试范式。

GATE:PASS
