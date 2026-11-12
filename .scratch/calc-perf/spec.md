# 测算速度与引擎流程（calc-perf）

2026-11-12 对引擎做一次实测定量，结论：**引擎的摊销表生成、现金流表、校验器全部接近免费；一次 run 的墙钟时间几乎全花在 §9.5 指标装配线上。** 而现有的 `AGENTS.md` 硬约束清单与 `.scratch/form-field-gaps` 差距矩阵里，**没有任何一条覆盖「测算速度」与「引擎流程」**：设计文档自己写的性能闸门从来没有被可重复的测试钉住过。

依据（用户 2026-11-12 提出的三条核心要求之一）：**测算速度和测算引擎流程优化**。

## 实测基线（2026-11-12，本机，一次性探针，未进仓库）

探针：`EQUAL_INSTALLMENT`、月频、IN_ARREARS、本金 2 380 000、名义年利率 3.50%、`residualHandling=SEPARATE`、无规则集；JIT 预热后每组 best-of-5；Java 11.0.28。

| 期数 | schedule 生成 | 现金流表 | **指标装配线** | 校验器 | 全链路 |
|---|---|---|---|---|---|
| 120 | 0 ms | 0 ms | **389 ms** | 0 ms | **424 ms** |
| 240 | 0 ms | 0 ms | **798 ms** | 0 ms | **882 ms** |
| 480 | 0 ms | 0 ms | **1612 ms** | 0 ms | **1797 ms** |
| 960 | 1 ms | 0 ms | **3174 ms** | 0 ms | **3623 ms** |

对照闸门：`docs/superpowers/specs/2026-11-02-financial-calc-service-design.md:55` 的 **G6：120 期 P99 < 50 ms**。实测 120 期全链路 **424 ms**，**超闸门约 8 倍**，且仓库里没有 JMH、没有任何计时测试（全 pom 无 JMH；`calc-testkit` 只有 `GoldenScheduleTest` 与 `ConservationPropertyTest`）。

日利率基准对成本的影响（同一 480 期）：

| 形态 | dayCount | solver 头 | 全链路 |
|---|---|---|---|
| 固定利率，无规则集 | THIRTY_360 | CLOSED_FORM | 1743 ms |
| 160 段分段利率（房贷 3 个月重定价形态，R150） | THIRTY_360 | SEGMENTED_RESOLVE | 1755 ms |
| 固定利率，无规则集 | ACT_360 | ITERATIVE | 1876 ms |
| 160 段分段利率 | ACT_360 | SEGMENTED_RESOLVE | 2768 ms |
| 1200 期 + 400 段（引擎上限 `LeaseElement.MAX_PERIODS = 1200`） | ACT_360 | SEGMENTED_RESOLVE | **10564 ms** |

要点：**分段本身不是成本来源**（30/360 保住闭式，160 段只多 12 ms）；成本来源是**指标装配线里的重复求根**，以及**ACT_360 落入迭代分支**时每段重解带来的 O(段数 × 剩余期数) 走查。

## 热点排名（已定位到 file:line）

1. **一次 run 做 3–4 次求根**：`IRR_PERIOD`、`XIRR`、`LEASE_IMPLICIT_RATE`，加上 `calc-engine/.../CalculationFacade.java:361-387` 的 `netInvestmentPeriodIrr` —— 它为 §9.7 实际利率法表**重建一条 N+1 的现金流序列再解一遍**（`:386`）。
2. **NPV 在求根循环里被整体重估**：`BisectionNewtonSolver.MAX_ITERATIONS = 100`（`calc-domain/.../support/BisectionNewtonSolver.java:43`），每轮还做 `newtonStep` 的额外求值（`:169-174`），即最多 ~400 次全序列求值；每次求值对每条现金流做一次 `pow`（`IrrCalculator.java:59-70`）→ 一次 run 约 10–20 万次 DECIMAL128 `pow`。
3. **指标之间重复走查与重复 pow**：`DurationMetrics.java:100-110` 的 Macaulay / Modified / Convexity 各自重走现金流并各自重 pow；`TotalMetrics.java:88-92` 五个列合计各走一遍行，而 `cumulativePrincipal` / `cumulativeInterest` 本来已经挂在每期行上（`AbstractAmortizationGenerator.java:178-179,185`）；`AverageBalanceMetric.java:40` 再走一遍。
4. **LESSEE 视角的重复分配**：`MetricSupport.flowsFor`（`MetricSupport.java:50-60`）对 LESSEE 每次指标调用翻一份镜像列表，流驱动指标约 17 个 → 一次 run 十几次全列表复制；`LeaseImplicitRateMetric.java:47` 无条件复制 `flows`。
5. **规则解析是线性扫描**：`CompiledRuleSet.resolve`（`calc-rule/.../model/CompiledRuleSet.java:148-182`）逐行 `matches(periodNo)`，每期新建一个 `RuleOutcome`；行按 `(priority, ruleId)` 排序（`:102-106`）而不是按期区间，所以现在连二分都做不了。R150 的形态把行数推到 160（480 期 / 3 个月）乃至 400（1200 期）→ 48 万次匹配。**目前测不出代价**，但它是唯一随重定价形态增长的部分。
6. **迭代分支的每段重解**：`PmtSolver.isConstantPeriodRate` 只认 `THIRTY_360` / `THIRTY_E_360`（`calc-domain/.../support/PmtSolver.java:44,56`），所以 `ACT_360` / `ACT_365F` / `ACT_365_25` / `ACT_ACT` 一律走 `iterative`（`:112-141`，≤64 次 bracket 加倍 + ≤100 轮二分），而每次 `error()` / `closingBalance` 是一个 **O(剩余期数)** 的 BigDecimal 走查（`:149-163`）；`EqualInstallmentGenerator.solveSegment` 每段先跑 `uniformWindows`（`:126-149`）也是一次 O(剩余期数) 走查。

## 非目标

- **不为速度牺牲 §1 的精度硬约束**：P-01（一律 BigDecimal）、P-07（内部 ≥ 10 位、中间结果不截断）、P-08（先明细求和再舍入）、P-09（尾差单点带符号吸收）一律不变。
- **不改变任何输出数值**：优化前后同一笔测算的输出必须**逐位一致**（金额容差 0.01 元只用于校验，不是用来给优化放行）。
- **不改变 §9.5 指标口径、不改 REST 契约、不改前端要素**（`form-field-gaps` spec 的「Plan 5 已冻结」同样适用本目录）。
- 不做缓存层 / 不做并行化 / 不做「只算前端要看的码」之外的功能扩展 —— 这些先等基线钉住再说。

## Issue 索引

见 `issues/`。01 是其它一切的前提：**没有可重复的基准，任何优化都无法证明它没有改变数值**。
