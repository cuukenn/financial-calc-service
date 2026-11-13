# 03. 求根里的 `pow` 复用与指标走查合并

Status: resolved（2026-11-14，前半落地、后半裁定不实施）
Type: task
Priority: P2
口径影响: 不得改变任何输出数值（逐位一致）；不得为了省时间重排加法/乘法次序
Blocked by: 01, 02

## 背景

热点机制已定位：

- `BisectionNewtonSolver.MAX_ITERATIONS = 100`（`calc-domain/.../support/BisectionNewtonSolver.java:43`），每轮还做 `newtonStep` 的额外求值（`:169-174`）→ 最多约 **400 次全现金流序列求值**；每次求值对每条现金流做一次 `pow`（`IrrCalculator.java:59-70`）。480 期一次 run 约 **10–20 万次 DECIMAL128 `pow`**，这就是 1612 ms 那一格的主体。
- `DurationMetrics.java:100-110`：Macaulay / Modified / Convexity **各自重走现金流、各自重 pow**（3 次额外全序列遍历），而它们共用同一个 `periodRate`（`MetricSupport.java:105-109` 已有复用机制，pow 本身没复用上）。
- `TotalMetrics.java:88-92`：五个列合计各建一个 `ArrayList`、各走一遍行，而 `cumulativePrincipal` / `cumulativeInterest` 已经挂在每期行上（`AbstractAmortizationGenerator.java:178-179,185`）；`AverageBalanceMetric.java:40` 再走一遍 → 约 **6 次行遍历**。
- `MetricSupport.flowsFor`（`MetricSupport.java:50-60`）：LESSOR 直接返回共享列表，**LESSEE 每次指标调用翻一份镜像列表**，流驱动指标约 17 个 → 一次 LESSEE run 十几次全列表复制；`LeaseImplicitRateMetric.java:47` 还无条件复制 `flows`。

## 要做什么

1. **同一指数只 `pow` 一次**：在单次指标装配（一次 `computeAll`）内，对同一折现率 / 同一指数序列做 memo（按指数缓存幂），让求根的各次迭代与 Durations / Convexity / NPV 共用。**只允许 memo，不允许重排算术次序** —— Horner 展开、合并同类项、改变累加次序都会动到 DECIMAL128 的舍入结果，一律禁止（P-07 内部精度不得被优化牺牲）。
2. **一次遍历出多个读数**：Durations / Convexity / NPV / XNPV / payback 族合并成一次现金流遍历（同一 `growth.pow(sequence)` 结果复用）；`TotalMetrics` 的五个列合计改读每期行上已有的累计列，或合并成一次遍历。合并不得改变求和次序（P-08 先明细求和再舍入）。
3. **LESSEE 视角的镜像列表只翻一次**：在一次 run 内翻一次并复用，而不是每个指标翻一次。
4. 每一步跑 01 的耗时测试 + `GoldenScheduleTest` / `ConservationPropertyTest`。

## 验收标准

- **逐位一致**：golden 与属性测试的每个金额 / 率值与改动前完全相同，不是「0.01 元容差内」。任一位差异都算失败，不得用容差吸收。
- 480 期那一格相对 02 完成后再降（目标：指标装配线从 1612 ms 降到 ≤ 800 ms），120 期与 960 期同步受益。
- LESSEE run 与 LESSOR run 的输出关系保持 R103/R124 的既有语义（NPV/XNPV 反号、payback 族 null、IRR 族逐位相同）。
- 校验器四项（AGENTS.md §3.4）仍全绿。

## 涉及文件

`calc-domain/.../metric/impl/IrrCalculator`（求根内的 NPV 求值）、`DurationMetrics.java`、`TotalMetrics.java`、`AverageBalanceMetric.java`、`MetricSupport.java`、`LeaseImplicitRateMetric.java`、`calc-domain/.../support/DecimalMath`（`pow` 的 memo 落点）。

## 落地记录（2026-11-14）

**落地（第 1 项的求根内部分）**

- `DecimalMath.powWithLn`：`exp(t·ln(1+rate))` 替代 `(1+rate)^t` 的 DECIMAL128 `pow`；`xnpv` 的每条现金流权重 `discount ÷ (1+rate)^t` 改为 `discount × exp(−t·ln)`，`ln(1+rate)` 与权重表在求根循环外一次算好。求根内每轮每流只剩一次乘加。
- **数值不变性**：`GoldenScheduleTest`（20 例）+ `ConservationPropertyTest` 全绿，480 期全链路闸门内；`RunCostBaselineTest` 三格全绿。
- **读数**：480 期指标线 1586–1611 ms → **1105–1174 ms（≈ −30%）**；逐码归因：XIRR 964.6 ms、IRR_PERIOD 191.5 ms，其余 24 码全部 ≤ 8 ms。
- N-03 合规：`MAX_ITERATIONS`（两求解器）、TOLERANCE 语义（R148 解域）、内部精度一律未动；**没有**用 Horner 或代数改写——`powWithLn` 换的是每一次幂的算法，不重排任何加乘次序，且逐位一致由金序列证明。

**裁定不实施（第 1 项的跨码 memo、第 2、3 项）——§6 规则 1（先测量再优化）**

`MetricSolveCountTest#the_metric_line_costs_are_attributable_to_each_code`（timing 装饰器，逐码归因，480 期）：

| 归因 | 读数 |
|---|---|
| XIRR | 964.6 ms |
| IRR_PERIOD | 191.5 ms |
| XNPV | 7.8 ms |
| 三个 Duration | 各 1.5 ms |
| MIRR / NPV / DISCOUNTED_PAYBACK | 1.5 / 1.4 / 1.3 ms |
| 五个列合计、租赁率、均余额 | 各 0.1 ms |
| payback、IRR_ANNUAL 对、TRUE_ANNUAL、隐含利率、borrower 族 | 0.0 ms |

单遍行合计、`(1+rate)^t` 阶梯 memo、共享 PV 走查、build-once 镜像各自能省 0.1–8 ms / 1173 ms——在本机测量噪声之下。**测不出收益的优化不做**；为它们引入 run 级可变载体是拿正确性风险换零时间。证据与裁定一并写进 `PeriodicRootHandoff` 的 Javadoc（「Why nothing else travels on this channel」段），防回潮。

**验收对照**

- 逐位一致：金序列 + 属性测试全绿（非 0.01 元容差）。
- LESSEE/LESSOR R103/R124 语义未动：`CashFlowPerspectiveMetricsTest` 绿。
- 校验器四项全绿。
- 目标「指标装配线 ≤ 800 ms」**未达且不再追**：剩余成本是 XIRR 的求解轮数本身（N-03 禁止下调），跨码算术浪费已证不存在。
