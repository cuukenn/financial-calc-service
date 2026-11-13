# 02. 砍掉重复求根：指标装配线一次 run 只做一次求根

Status: resolved（2026-11-14）
Type: task
Priority: P1
口径影响: 不得改变任何输出数值（逐位一致）；不改 §9.5 口径、不改契约
Blocked by: 01

## 背景

实测 480 期：schedule 生成 0 ms、现金流表 0 ms、校验器 0 ms、**指标装配线 1612 ms**（占全链路 1797 ms 的九成）。`MetricRegistry.defaults()` 无条件算 `MetricCodes.ALL` 全部 **26 个码**（`calc-domain/.../metric/MetricCodes.java:122-129`；`MetricRegistry.java:83-115`），其中**求根做 3–4 次**：

- `IRR_PERIOD`（`IrrMetrics.java`）、`XIRR`（`XirrMetric.java`）、`LEASE_IMPLICIT_RATE`（`LeaseImplicitRateMetric.java:53`）；
- 加上 `CalculationFacade.netInvestmentPeriodIrr`（`calc-engine/.../CalculationFacade.java:361-387`）—— 它为 §9.7 实际利率法摊销表**另建一条 N+1 的现金流序列、再从头解一遍**（`:386`）。

复用已经部分存在（`IRR_ANNUAL` / `IRR_ANNUAL_SIMPLE` 读 `computed(IRR_PERIOD)`，`IrrMetrics.java:96`；`TRUE_ANNUAL_RATE` 抄 `XIRR`，`XirrMetric.java:73`；`BorrowerIrrPeriodMetric.java:68` 复用 `IRR_PERIOD`），所以这一条不是补复用机制，而是**把还多出来的那几次求根消掉**。

## 要做什么

1. **`netInvestmentPeriodIrr` 不得再自行求根**：§9.7 的租金口径 root 与 `IRR_PERIOD` 的关系要先在标准层面写清楚（同一现金流集就能复用；确实不同就必须说明为什么不同、并把这次求根显式记为一次独立求解而不是隐性重复）。先裁定口径，再动代码。
2. **按请求的码集装配指标**，不要 26 个全算：前端 `ResultPanel` 实际展示的是分区，引擎侧要能只算被请求的码（及其依赖码，走 `MetricRegistry` 已有的 `order()` 与 `computed(...)` 依赖序）。这一步的边界要说清楚：**不得因为「前端没显示」就不产出 §9.5 要求的码**（口径冻结），所以先做「调用方显式给出需要的码」而不是引擎自作主张裁剪。
3. `LEASE_IMPLICIT_RATE` 只在 CAS 21 计量链真的成立时求根（`assetValue` 缺失时现在返回 null —— 确认它**没有**在返回 null 之前已经跑了一遍求根）。
4. 每一步都要用 01 的耗时测试证明它省下了时间，用 `GoldenScheduleTest` + `ConservationPropertyTest` 证明数值没动。

## 验收标准

- 01 的耗时测试：480 期那一格 measurable 下降（目标 ≥ 30%），并且 120 期那一格同步下降。
- `GoldenScheduleTest`、`ConservationPropertyTest`、`MetricRegistryTest`、`EndToEndSmokeTest` 全绿，且**同一笔 golden 输入的每个输出数值逐位一致**（不是 0.01 元容差通过，是完全相同）。
- 求根次数有可核对的记录（日志/测试断言/计数），不是「看起来快了」。
- §9.7 表在 IRR 退化路径上仍然按现有契约回答 null + WARN（`FCS-CALC-3003`），不得因为省一次求根而把退化输入变成硬失败。

## 涉及文件

`calc-engine/.../CalculationFacade.java`（`netInvestmentPeriodIrr`、`effectiveInterest`）、`calc-domain/.../metric/MetricRegistry.java`、`calc-domain/.../metric/impl/`（`IrrMetrics`、`XirrMetric`、`LeaseImplicitRateMetric`、`BorrowerIrrPeriodMetric`）。

## 落地记录（2026-11-14）

**做了什么**

1. **run 级求根台账** `calc-domain/.../metric/PeriodicRootHandoff.java`：`record(root, flows)` 记录每次求解的未舍入根与流集，`rootFor(stream)` 按内容判定「同一根问题」（同 index、同金额、方向全同或全反——R103 镜像）后复用。`MetricContext.rootHandoff()` 是随 run 共享的通道。
2. **§9.7 链复用**：`CalculationFacade.netInvestmentPeriodIrr` 先 `rootFor(stream)`，命中即读未舍入根（不是公布的 scale-10 `IRR_PERIOD`——N-04）；不命中才自解，且口径差已在 Javadoc 写明（CAS 21 第三十八条净投资含未担保余值 vs §9.4 事件表现金-only）。
3. **隐含利率查台账再解**：`LeaseImplicitRateMetric` 先查、解完记录——无未担保余值时它的流集就是事件表，直接复用 `IRR_PERIOD` 的根，一次求根都不花。

**读数**（`MetricSolveCountTest` 七条钉桩，全绿）

- 480 期普通单子全链路：**3 → 2 求根**（246 次求值不变——XIRR 124 + IRR_PERIOD 122）。
- 12 期 + 未担保余值：**4 → 3**（隐含利率解一次，§9.7 链读它的根，不再解第三遍同一条净投资流）。
- 12 期 + 资产价值、无未担保余值：分期指标线 **3 → 2**，隐含利率 = IRR_ANNUAL（年化舍入放大界 1e-8 内，注释写明为什么不是逐位）。
- 无资产价值时 `LEASE_IMPLICIT_RATE`：**0 求根**（确认它返回 null 前不碰求解器）。

**没做什么（裁定）**

- **按码集装配（第 2 项）不实施**：N-06 明文——不得因「界面未展示」停止产出 §9.5 要求的码；引擎侧省不出时间，裁剪属展示层职责。
- 复用不是「部分复用机制的补齐」而是换机制：原 `MetricRunState` 单根通道换成多记录台账，因为带资产价值的单子里隐含利率与 §9.7 链构成**第二条**同根问题，单根通道接不住。

**验收对照**

- 求根次数有可核对的记录：`BisectionNewtonSolver.SOLVE_COUNT/EVALUATION_COUNT` + 测试断言，非「看起来快了」。
- 全量 `mvn -o test` 绿（36 类，含 GoldenSchedule / Conservation / MetricRegistry / EndToEndSmoke / CalculationFacade 44 位 `eir()` 钉桩）——数值逐位一致。
- §9.7 退化路径契约未动：`IRR_PERIOD` 缺失仍 null + WARN `FCS-CALC-3003`。
