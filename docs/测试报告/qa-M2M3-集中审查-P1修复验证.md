# 测试报告 — M2M3 集中审查 P1 修复验证（CircuitBreaker D1 阻断）

> 结论：**通过**（6/6 验证项全 PASS）

- 受测对象：`eaiselp-adapter/src/main/java/com/eaiselp/adapter/resilience/CircuitBreaker.java`
- 受测缺陷：Reviewer D1 阻断——冷却时间戳 CAS 失效（`openSince.compareAndSet(0L, now)` 在 HALF_OPEN→OPEN 时因 `openSince!=0` 而不刷新，旧时间戳导致 30s 冷却被绕过）
- 修复方案：`tripOpen()` 改为无条件 `openSince.set(System.currentTimeMillis())`
- 验证日期：2026-08-03
- 验证人：team-qa（L1）
- 编译环境：`JAVA_HOME=D:\工具\jdk-26.0.1`，Maven 3.9.16，JDK 26

---

## 0. 磁盘事实核对（前置门禁，先于一切测试动作）

**Dev 报告不可信，以磁盘 git diff 为唯一事实来源。**

### 0.1 git status / diff 真实落地情况

```
modified:   eaiselp-adapter/src/main/java/com/eaiselp/adapter/resilience/CircuitBreaker.java
Untracked:  eaiselp-adapter/src/test/java/com/eaiselp/adapter/resilience/   (新增测试目录)
```

`git diff CircuitBreaker.java` 真实输出（核心片段）：

```diff
@@ -96,12 +96,10 @@ public class CircuitBreaker {
     private void tripOpen() {
-        // 进入 OPEN：记录时间戳（若已是 OPEN 不刷新，保留最早一次进入时间用于冷却计时），
-        // 再切状态。并发下多个线程同时 trip 也只是重复 set 同一 OPEN，幂等无害。
-        if (state.get() != State.OPEN) {
-            openSince.compareAndSet(0L, System.currentTimeMillis());
-            state.set(State.OPEN);
-        }
+        // 进入 OPEN：无条件刷新时间戳（HALF_OPEN→OPEN 也必须重置冷却计时，
+        // 否则旧 openSince 导致 30s 冷却被绕过——Reviewer D1 阻断修复）
+        openSince.set(System.currentTimeMillis());
+        state.set(State.OPEN);
     }
```

- diff 落地与 Dev 报告一致：`compareAndSet(0L, ...)` → `set(...)`，且移除了 `if (state.get() != State.OPEN)` 外层判断。
- 新增测试文件：`eaiselp-adapter/src/test/java/com/eaiselp/adapter/resilience/CircuitBreakerTest.java`（untracked，磁盘可读）。
- **核对结论：改动真实落地，与报告一致，无虚报。**

### 0.2 反幻觉自检

| 自检问 | 答 |
|---|---|
| git diff 是否真包含 Dev 声称的 tripOpen 改动？ | 是，`openSince.set(...)` 在 diff 第 101 行真实存在。 |
| 每个用例"预期"依据的代码是自己 Read 出来还是抄报告？ | 全部由本人 `Read` CircuitBreaker.java / CircuitBreakerTest.java 取得，并附行号。 |
| 若 Dev 报告是假的，我的用例还能发现吗？ | 能——核心反向验证 TC-06 直接走查 disk 第 90-92 行 → tripOpen → 第 101 行 `set`，并经动态 `mvn test` PASS 兑现；不依赖报告文字。 |

---

## 1. 验证项执行结果

| # | 验证项 | 结果 | 证据 |
|---|---|---|---|
| 1 | `tripOpen` 改为 `openSince.set`（非 compareAndSet） | PASS | 磁盘 CircuitBreaker.java 第 101 行：`openSince.set(System.currentTimeMillis());`；旧 `compareAndSet(0L, ...)` 已删除（见 0.1 diff）。 |
| 2 | `CircuitBreakerTest` 含 `halfOpenFailureReTripsOpenWithRefreshedTimestamp` | PASS | 测试文件第 59-75 行存在该方法；该方法断言第 74 行 `assertFalse(cb.allowRequest(), "冷却时间未到不应放行探针...")`。 |
| 3 | `mvn test -pl eaiselp-adapter -am` 全过 | PASS | surefire：`Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 90.23 s`；BUILD SUCCESS。（注：初跑因 `-Dtest=CircuitBreakerTest` 命中上游 `eaiselp-common` 无匹配测试而误报，加 `-Dsurefire.failIfNoSpecifiedTests=false` 后通过——非测试失败） |
| 4 | 全量 `mvn clean package -DskipTests` BUILD SUCCESS | PASS | 10/10 模块 SUCCESS（parent/common/gateway/data/auth/capability/adapter/runtime/observability/admin），Total 22.969 s；adapter testCompile 编译 2 个测试源（含新 CircuitBreakerTest）。 |
| 5 | 门禁 6/6 PASS | PASS | 见下 §2 执行结果表，6 条用例全 PASS。 |
| 6 | 反向验证：CLOSED→OPEN→HALF_OPEN→OPEN→冷却 不再被绕过 | PASS | 见 §3 静态走查 + TC-06 动态兑现。 |

---

## 2. 执行结果（用例级）

| 用例ID | 方法（CircuitBreakerTest.java） | 结果 | 备注 | 关联验证项 |
|---|---|---|---|---|
| TC-01 | `closedToOpenAfterThreshold`（L13-27） | PASS | 4 次失败不跳 OPEN，第 5 次跳 OPEN 并拒绝请求——验证 FAILURE_THRESHOLD=5。 | V1 |
| TC-02 | `openBlocksUntilTimeoutThenHalfOpen`（L30-42） | PASS | sleep(OPEN_RESET_MS+50) 后 allowRequest 返回 true 且状态切 HALF_OPEN——验证冷却超时放探针。 | V6 |
| TC-03 | `halfOpenSuccessRestoresClosed`（L45-56） | PASS | HALF_OPEN 探针成功 → CLOSED，failureCount 归零——验证恢复正常路径。 | V6 |
| TC-04 | `halfOpenFailureReTripsOpenWithRefreshedTimestamp`（L59-75） | PASS | **核心反向验证**：HALF_OPEN 探针失败立即切 OPEN，且 `assertFalse(allowRequest())` 证明冷却窗口已刷新（否则会因旧 openSince 立即放行）。 | V2/V6 |
| TC-05 | `recordSuccessResetsOpenSince`（L78-91） | PASS | recordSuccess 后 failureCount 从 0 重计，1 次失败不跳 OPEN——验证恢复后状态干净。 | V1 |
| TC-06 | `concurrentFailuresTripOpenSafely`（L94-106） | PASS | 10 线程并发 recordFailure 仍正确进入 OPEN——验证无锁并发安全。 | V1 |

surefire 汇总：`Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`。

---

## 3. 反向验证（V6）——状态流转闭环走查

基于磁盘 CircuitBreaker.java（行号引用本人 Read 结果），完整推演状态机：

### 3.1 正向流转

| 迁移 | 触发点（代码行） | openSince 处理 | 正确性 |
|---|---|---|---|
| CLOSED→OPEN | `recordFailure` L93-94（`count>=5 && current!=OPEN`）→ `tripOpen()` | L101 `set(now)` 无条件刷新 | 正确 |
| OPEN→HALF_OPEN | `allowRequest` L56-58（超 30s）CAS 切 HALF_OPEN | 不动 openSince | 正确 |
| HALF_OPEN→OPEN | `recordFailure` L90-92（探针失败）→ `tripOpen()` | L101 `set(now)` **无条件刷新** | **修复关键点，正确** |
| HALF_OPEN→CLOSED | `recordSuccess` L76-80 | L79 `set(0L)` 清零 | 正确 |

### 3.2 缺陷复现推演（修复前为何绕过）

修复前 `tripOpen` 用 `openSince.compareAndSet(0L, now)`：
1. 首次 CLOSED→OPEN：`openSince==0`，CAS 成功写入 t0。
2. 30s 后切 HALF_OPEN，探针失败 → HALF_OPEN→OPEN → 再次 `tripOpen`。
3. 此时 `openSince==t0`（非 0），`compareAndSet(0L, now)` **CAS 失败，不刷新**。
4. 紧接着 `allowRequest` 读 `now - t0`（已远超 30s）→ 立即再切 HALF_OPEN → **冷却被绕过，熔断器形同虚设**。

### 3.3 修复后为何有效

`tripOpen` 改为 `openSince.set(now)`（L101，无条件 set）：
- HALF_OPEN→OPEN 时 `set(now)` 写入当前时间，新 OPEN 的冷却窗口从"此刻"起重新计 30s。
- 紧接的 `allowRequest` 读 `now - now ≈ 0 < 30000` → 拒绝放探针，冷却强制生效。
- 动态兑现：TC-04（`halfOpenFailureReTripsOpenWithRefreshedTimestamp` L74 `assertFalse(allowRequest())`）已 PASS。

### 3.4 并发安全性

- `tripOpen` 的 `set` + `state.set(OPEN)` 非原子，但两者都是 volatile/atomic 写；最坏情况是并发下重复写相同值，幂等无害（与原注释语义一致）。
- TC-06（10 线程并发）已 PASS，未观察到状态错乱。

---

## 4. 覆盖情况

- 状态机三态全覆盖：CLOSED / OPEN / HALF_OPEN。
- 全部 6 条迁移边覆盖：CLOSED→OPEN、OPEN→HALF_OPEN、HALF_OPEN→OPEN、HALF_OPEN→CLOSED、OPEN（冷却中拒绝）、并发 trip。
- 缺陷专项：HALF_OPEN→OPEN 时间戳刷新（TC-04）。
- 未覆盖（非本次修复范围，不追加）：OPEN 状态下 recordSuccess 的边界（理论不应发生，代码 L76-80 已兜底）；真实 wall-clock 与 System.currentTimeMillis 漂移。

## 5. 缺陷

无。本次修复范围内未发现新缺陷。

## 6. 结论

**通过。** Reviewer D1 阻断（冷却时间戳 CAS 失效）已有效修复：
- 磁盘代码确认 `tripOpen` 无条件 `openSince.set`（L101）。
- 6 条单测全 PASS（含核心反向验证 TC-04）。
- 模块测试 BUILD SUCCESS，全量 10 模块 package BUILD SUCCESS。
- 状态流转闭环 CLOSED→OPEN→HALF_OPEN→OPEN→冷却 不再被绕过。

可放行进入下一阶段。

---

## 本次经验沉淀

1. **熔断器/限流类时间戳 bug 的典型反推法**：任何"冷却/窗口/退避"逻辑里出现 `compareAndSet(0L, x)` 或"仅在首次设置"的语义，必须反向追问"非首次进入时旧值还在不在"——HALF_OPEN→OPEN 这类二次进入恰好命中盲区。反推用例应直接断言"二次进入后立即 allowRequest 必须被拒"。
2. **`-Dtest=XxxTest` 配合 `-am` 的误报陷阱**：Maven 反应堆在上游无匹配测试的模块会因 `failIfNoSpecifiedTests=true`（默认）直接 BUILD FAILURE，易被误读为"测试失败"。验证时务必加 `-Dsurefire.failIfNoSpecifiedTests=false`，并看真实目标模块的 `Tests run: N, Failures:` 行而非仅看最终 BUILD 结果。
3. **"幂等无害"注释的反向警觉**：原代码注释写"并发下重复 set 同一 OPEN，幂等无害"，但实际 CAS 逻辑把"幂等"做成了"不刷新"。代码注释里的自我安慰性断言（"幂等/无害/可接受"）应作为 Reviewer/QA 重点怀疑对象，须用具体用例证伪或证实。
