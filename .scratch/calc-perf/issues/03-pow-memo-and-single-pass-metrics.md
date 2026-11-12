# 03. 求根里的 `pow` 复用与指标走查合并

Status: ready-for-agent
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
