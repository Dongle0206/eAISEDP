# 安全评审报告 — case-20260821-L3收口（governance 三域 + web 三页）

> 结论：**通过（无阻断，附 2 中危建议修复 + 4 低危观察）**
> 评审人：team-security（模型隔离独立验证，磁盘实证，不采信 Dev 自述；前次代理网络中断，本报告为重试完整轮）
> 注：报告由 Security 全文回复、编排者代落盘。文末"非安全侧观察"的 description 未映射为其评审时点（D1 修复前）快照，D1 已修复并由 Reviewer 二轮复审确认中。

## 磁盘事实核对
- 两仓 `git status` 实测与任务清单一致：platform 37 新增（3 Controller + governance 27 主代码 + V7__l3_close.sql + 6 测试）+ schema-h2.sql 修改；web 3 页面新增 + governance-dict.js/menu.js/README 修改。
- 测试复现：`mvn -pl eaiselp-auth,eaiselp-runtime -am test` 退出码 0，surefire 汇总 **623 tests / 0 failures / 0 errors / 0 skipped**（adapter 31 + auth 12 + capability 12 + common 6 + data 66 + runtime 496）。

## 威胁面逐项核查（磁盘实证）
1. **鉴权/越权 — 通过**。20 端点 @RequirePermission 逐个与 V7 seed 1071~1080 对照一致（R1/R3/R7=view、R2=create、R4/R5/R6=edit；C1/C3=view、C2=create、C4/C5=edit；B1/B3/B8=view、B2=create、B4/B5/B6=edit、**B7=approve**）。PermissionInterceptor 服务端查角色权限链，未持有→HTTP 403 + 40301，前端隐藏按钮仅为体验层。PM（role_id=3）seed 恰 7 原子、无 1075/1076/1080 → 防自我批准/防自查自登在权限链生效。IDOR：t_risk/t_compliance_check/t_business_case 确不在 EaiselpTenantHandler.IGNORE_TABLES（磁盘读源码 + L3RbacSwitchContractTest.TC_L3a 反射断言双证），loadOr404→getById 经租户拦截器→跨租户 null→404；关联对象存在性校验走 selectById 同样租户过滤。聚合端点 dashboard/portfolio 挂 view 原子，手写 @Select 被 TenantLineInnerInterceptor 改写注入 tenant_id，is_deleted=0 显式过滤。
2. **防伪造 — 通过**。riskValue/riskLevel、netBenefit/paybackYears/roiPercent/riceScore 均不在入参 DTO（toEntity 不映射），Service 写库前经 Calculator 重算覆盖；测试佐证伪造 riceScore=999 被覆盖（BusinessCaseServiceImplTest:440）。数值入参 BigDecimal 承载，validateIntegral/validateFactor10/validateConfidence 用 stripTrailingZeros().scale() 判整——**1.5 到达 Service 被 400 指名，不落 50000**（GlobalExceptionHandler 确无 HttpMessageNotReadable 专项，前提成立；坏 JSON 落 50000 为平台既有行为，见 S6）。汇总口径 `status IN('approved','executing','done')` SQL 字面量钉死，portfolio 端点无 status 入参——不可参数绕过。
3. **注入/XSS — 通过**。三页所有动态渲染逐处 escHtml（含热力图 count/riskValue、rel-chip、title 属性 escAttr）+ 详情体再过 sanitize.js DOM 清洗（两层防御）；DICT.badge/text/options 内部 esc。热力图格 data-p/data-i 为循环字面量，点击下钻 data-level 转义后作 R1 level 筛选参数（服务端 eq 参数化）。bizcase 关联 JSON 为 Long 数组拼接无注入面；risk 关联 JSON 手工拼接转义 `"`与`\`（见 S4）。Mapper 全参数化，无 SQL 拼接。
4. **信息泄露 — 通过**。未捕获异常统一"服务暂时不可用"，无堆栈/SQL/路径外泄；聚合数据范围=拦截器过滤后本租户；decision_note/rejected_reason 按角色 view 权限可见（设计口径）。
5. **审计防抵赖 — 基本通过，edit 路径有缺口（S2）**。三域 create/delete/transit 均审计且 operator 取自 LoginUser(JWT) 服务端；compliance_update 含 oldResult→newResult+oldEvidenceNote，B6 含旧值→新值，bizcase transit 含流转前快照+from/to；risk_update/bizcase_update 仅记新值。
6. **业务逻辑 — 通过（含 S1/S3/S5）**。编辑端点 toEntity 不映射 status、UpdateWrapper 不 set status——**无法 PATCH status 绕过流转**；状态机枚举内聚（open→mitigating→closed + mitigating→open 回退；draft→approved/rejected→executing→done，终态无出边），跳级/终态出边/closed 编辑均 400；bizcase 非 draft 删/编 400；amount ≥0 校验但无上限（S1）。
7. **基线对齐 — 通过**。sanitize.js 引用、40301 码、UI 隐藏+服务端 403 两层、INSERT IGNORE 幂等、V7 零 ALTER、uk 含 tenant_id——均与 L2 收口先例一致。

## 缺陷清单
| 编号 | 严重度 | 文件:行 | 问题 | 修复建议 |
|---|---|---|---|---|
| S1 | 🟡建议 | BizCaseCalculator.java:117 | validateAmount 仅查空+负值，无上限/精度校验；>999999999999.99 严格模式落 50000，非严格 MySQL 静默截断且计算列按原值算→行内自相矛盾 | 补 `scale()<=2` 且 `compareTo(new BigDecimal("999999999999.99"))<=0` 否则 400 |
| S2 | 🟡建议 | RiskServiceImpl.java:114；BusinessCaseServiceImpl.java:120 | risk/bizcase 的 update 审计 detail 仅新值快照，无前值——P/I/owner 变更直接改变风险姿态，无 before 无法追溯篡改轨迹 | 仿 compliance edit 补 old→new（至少 P/I/category/owner 与金额/RICE 因子） |
| S3 | 🟢可选 | RiskServiceImpl.java:212；BusinessCaseServiceImpl.java:242 | transit/edit 的 UPDATE 仅 eq(id) 无 eq(status,from)，并发双流转可 TOCTOU 互覆 | UpdateWrapper 追加 `.eq(status, from.dbValue())` + 更新行数=0 时重读 400 |
| S4 | 🟢可选 | RiskController.java:191 | toJsonRelated 手工拼 JSON 仅转义 `"`与`\`，未转义控制字符——存在性校验前置使其实际不可达，但违背"JSON 构造走 ObjectMapper"基线 | 改 ObjectMapper.writeValueAsString |
| S5 | 🟢可选 | Risk/ComplianceCheck ServiceImpl | owner(64)/resolutionNote(500)/clauseRef(128)/frameworkName(128)/evidenceNote(1000)/rejectedReason(500) 未校验长度，超长落 500 而非 400 | 对齐 *Name 补长度前置校验 |
| S6 | 🟢可选 | GlobalExceptionHandler.java:32 | 无 HttpMessageNotReadableException 专项，坏请求体落 50000（平台既有，非本 case 引入） | 平台级补 handler→400（记入技术债） |

非安全侧观察（转 Reviewer，D1 已修复）：Risk.description（V7 r2 补列）在评审时点贯通了实体与 VO 但 RiskSaveRequest/toEntity/toVo 未映射——编排者已修复（见 review-l3收口.md D1 修复记录），Reviewer 二轮复审确认中。

## 经验沉淀
1. "计算列不在入参 DTO + Service 重算覆盖 + 测试伪造值断言"三层防伪造链可作为所有派生字段的标准模式。
2. DECIMAL(14,2) 类金额校验"非负"不够——上限=列宽最大值、精度=列 scale 必须在应用层镜像校验（S1 模式）。
3. 手写 @Select 聚合 SQL 会被 TenantLineInnerInterceptor 改写注入 tenant_id，但 @TableLogic 不生效——is_deleted=0 必须手写显式过滤（本 case 两处均正确处理，可复用）。

GATE:PASS
