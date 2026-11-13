# 测算速度与引擎流程（calc-perf）

2026-11-12 对引擎做一次实测定量，结论：**引擎的摊销表生成、现金流表、校验器全部接近免费；一次 run 的墙钟时间几乎全花在 §9.5 指标装配线上**（`ACT_360` 分段形态下例外，见热点 6）。在此之前，`AGENTS.md` 硬约束清单与 `.scratch/form-field-gaps` 差距矩阵里**没有任何一条覆盖「测算速度」与「引擎流程」**，设计文档自己写的性能闸门也从来没有被可重复的测试钉住过。

依据（用户 2026-11-12 提出的三条核心要求之一）：**测算速度和测算引擎流程优化**。当天已据此在 `AGENTS.md` 立 **§6 测算性能与引擎流程（N-01…N-06）**，并落了 N-01 要求的闸门测试（见下）。

## 基线读数（闸门测试 `RunCostBaselineTest`，2026-11-12）

可重复基线已进仓库：`calc-testkit/src/test/java/io/github/cuukenn/fincalc/testkit/RunCostBaselineTest.java`（AGENTS.md §6 N-01 的落地）。形态：`EQUAL_INSTALLMENT`、月频、IN_ARREARS、本金 2 380 000、名义年利率 3.50%、`residualHandling=SEPARATE`；分段形态为 3 个月一段、每段换率（0.035 / 0.036 交替，所以每段都重解）。阶段与全链路都单独计时，读数取预热后的最优值。

三次独立运行的读数（毫秒，Java 11.0.28，10 核本机，预热后最优；三次均 `BUILD SUCCESS`，数字可从 surefire 输出核对）：

| 形态 | 规则行 | 摊销表生成 | 现金流表 | **指标装配线** | 校验器 | 阶段合计 | 全链路 |
|---|---|---|---|---|---|---|---|
| 120 期固定 `THIRTY_360` | 0 | 0.4 / 0.4 / 0.4 | 0.1 | **379.0 / 377.5 / 387.5** | 0.1 | 379.6 / 378.1 / 388.1 | **427.0 / 421.6 / 427.8** |
| 480 期固定 `THIRTY_360` | 0 | 0.7 / 0.6 / 0.8 | 0.2 | **1596.8 / 1586.4 / 1610.6** | 0.3 | 1598.1 / 1587.5 / 1611.9 | **1781.3 / 1782.9 / 1794.4** |
| 480 期 + 160 段 `THIRTY_360` | 160 | 2.4 / 2.5 / 2.1 | 0.2 | **1594.5 / 1587.5 / 1604.0** | 0.3 | 1597.4 / 1590.5 / 1606.6 | **1780.6 / 1765.2 / 1798.5** |
| 1200 期 + 400 段 `ACT_360` | 400 | **6041.6 / 5869.3 / 5847.3** | 0.4 | **4322.0 / 4231.5 / 4106.6** | 0.8 | **10364.7 / 10101.9 / 9955.0** | 只门阶段 |

波动（同一台机器，三次，按最大/最小极差）：120 期全链路 **1.5%**；480 期固定 **0.7%**；480 期分段 **1.9%**；1200 期阶段合计 **4.1%**；其中波动最大的一列是 1200 那一格的指标层 **5.2%**。**全部远小于 ticket 01 要求的 20% 上限** → 阈值可信，能当回归门用。

闸门余量（三次里的最差读数 vs 写入门槛）：120 期全链路 427.8 / 1200 = **36%**；480 期固定全链路 1794.4 / 5000 = 36%；480 期分段全链路 1798.5 / 5000 = 36%；1200 期摊销表阶段 6041.6 / 9000 = **67%**、阶段合计 10364.7 / 25000 = 41%。两条相对门：分段/固定 480 全链路比值三次是 0.99 / 1.00 / 1.00（闸门 1.35）；1200 阶段合计 ÷ 480 分段全链路 = 5.82 / 5.67 / 5.55（闸门 8.0）。**诚实记录**：480 分段那一格的摊销表门槛 700 ms vs 实测 ~2.3 ms 是**极大的余量** —— 它的用途不是微调 2 ms，而是「规则解析路径一旦被改坏（例如索引建错导致每段重解）就红灯」。

对照闸门：**120 期全链路 ~420 ms vs G6 的 50 ms（`docs/superpowers/specs/2026-11-02-financial-calc-service-design.md:55`），仍超约 8 倍。** N-02 据此生效：闸门是「不倒退」天花板，不代表达标。

日利率基准对成本的影响（同一测试的两次读数）：

| 形态 | dayCount | solver 头 | 全链路 / 阶段合计 |
|---|---|---|---|
| 480 期固定利率 | THIRTY_360 | CLOSED_FORM | 1782.9 ms |
| 480 期 + 160 段 | THIRTY_360 | SEGMENTED_RESOLVE | 1765.2 ms（**分段几乎免费**） |
| 1200 期 + 400 段 | ACT_360 | SEGMENTED_RESOLVE | 阶段合计 10101.9 ms |

要点不变：**分段本身不是成本来源**（30/360 保住闭式，160 段几乎不增加）；成本来源是**指标装配线里的重复求根**（固定 / 闭式形态下占九成），以及在 `ACT_360` 这种迭代分支里，**每段重解的摊销表阶段反超指标层**（1200 期那一格：schedule 5.9 s vs metrics 4.2 s）。

## 热点排名（已定位到 file:line；2026-11-14 状态更新见各条内嵌标注）

1. **一次 run 做 3–4 次求根**：`IRR_PERIOD`、`XIRR`、`LEASE_IMPLICIT_RATE`，加上 `calc-engine/.../CalculationFacade.java:361-387` 的 `netInvestmentPeriodIrr` —— 它为 §9.7 实际利率法表**重建一条 N+1 的现金流序列再解一遍**（`:386`）。**【2026-11-14 已消掉】** run 级求根台账 `PeriodicRootHandoff`：`IRR_PERIOD` 与 `LEASE_IMPLICIT_RATE` 各自把（根, 流集）记进台账，§9.7 链经 `rootFor(stream)` 只在流集确为同一根问题时复用未舍入根，否则自解——普通单子全链路 3 求根 → **2 求根**，带未担保余值的单子 4 → **3**（`MetricSolveCountTest` 钉死）。
2. **NPV 在求根循环里被整体重估**：`BisectionNewtonSolver.MAX_ITERATIONS = 100`（`calc-domain/.../support/BisectionNewtonSolver.java:43`），每轮还做 `newtonStep` 的额外求值（`:169-174`），即最多 ~400 次全序列求值；每次求值对每条现金流做一次 `pow`（`IrrCalculator.java:59-70`）→ 一次 run 约 10–20 万次 DECIMAL128 `pow`。**【2026-11-14 部分落地】** `xnpv`/`xirr` 的 `(1+rate)^t` 换成 `exp(t·ln(1+rate))` 并把权重与 ln 提出求根循环（`DecimalMath.powWithLn`，值逐位不变由金序列+Conservation 证明）→ 480 期指标线 1586–1611 ms → **1105–1174 ms（≈ −30%）**；剩余成本是求解轮数本身（N-03 禁止下调），不再有测得出的算术浪费。
3. **指标之间重复走查与重复 pow**：`DurationMetrics.java:100-110` 的 Macaulay / Modified / Convexity 各自重走现金流并各自重 pow；`TotalMetrics.java:88-92` 五个列合计各走一遍行，而 `cumulativePrincipal` / `cumulativeInterest` 本来已经挂在每期行上（`AbstractAmortizationGenerator.java:178-179,185`）；`AverageBalanceMetric.java:40` 再走一遍。**【2026-11-14 按 §6 规则 1 裁定不实施】** 逐码计时（`MetricSolveCountTest#the_metric_line_costs_are_attributable_to_each_code`）：26 码中除 XIRR 970 ms / IRR_PERIOD 193 ms 外**全部 ≤ 8 ms**——列合计 0.1 ms、Durations 1.5 ms、NPV 对 1.3 ms——单遍化省的全在测量噪声之下，省不出可测收益的优化不做。
4. **LESSEE 视角的重复分配**：`MetricSupport.flowsFor`（`MetricSupport.java:50-60`）对 LESSEE 每次指标调用翻一份镜像列表，流驱动指标约 17 个 → 一次 run 十几次全列表复制；`LeaseImplicitRateMetric.java:47` 无条件复制 `flows`。**【同上，裁定不实施】** borrower 族逐码 0.0 ms。
5. **规则解析是线性扫描**：`CompiledRuleSet.resolve`（`calc-rule/.../model/CompiledRuleSet.java:148-182`）逐行 `matches(periodNo)`，每期新建一个 `RuleOutcome`；行按 `(priority, ruleId)` 排序（`:102-106`）而不是按期区间，所以现在连二分都做不了。R150 的形态把行数推到 160（480 期 / 3 个月）乃至 400（1200 期）→ 48 万次匹配。**闸门测试的读数：480 期 + 160 段那一格的摊销表阶段 2.5 ms，同格无规则形态 0.6 ms** —— 160 行线性扫描加上 160 次每段重解总共只值 1.9 ms，即这一条**现在测不出代价**（尚未从噪声里分辨出来），但它是唯一随重定价行数线性增长的部分。**【2026-11-14 裁定：与热点 6 的走查不可分离、1.9 ms/1.18 万次匹配 = 0.16 μs 一次，索引化省不出闸门可读的差值——不实施，理由与数字记入 ticket 04】**
6. **迭代分支的每段重解**：`PmtSolver.isConstantPeriodRate` 只认 `THIRTY_360` / `THIRTY_E_360`（`calc-domain/.../support/PmtSolver.java:44,56`），所以 `ACT_360` / `ACT_365F` / `ACT_365_25` / `ACT_ACT` 一律走 `iterative`（`:112-141`，≤64 次 bracket 加倍 + ≤100 轮二分），而每次 `error()` / `closingBalance` 是一个 **O(剩余期数)** 的 BigDecimal 走查；`EqualInstallmentGenerator.solveSegment` 每段先跑 `uniformWindows` 也是一次 O(剩余期数) 走查。**闸门测试已证明这一格在 `ACT_360` 分段形态下是第一热点**：1200 期 / 400 段那一格的摊销表阶段 5.9 s，盖过同格的指标层 4.2 s。**【2026-11-14 走查内的重复派生已消掉】** `PmtSolver.periodRates` 每 solve 只派生一次、`solveSegment` 的 uniform 检查与迭代共用同一份（~1130 万次 `yearFraction` 商 + 精确乘积/run → **0**；墙钟不动，因为格子由 ~1150 万步 DECIMAL128(34位) 走查主导——那是 RESOLVE 口径的地板）；`RateDerivationCountTest` 把 1200p 格钉在恰好 2×期数。**走查本身再往下只能靠降精度或改口径，§6 禁止，不再追。**

> 排名的形态依赖：固定利率 / 闭式形态（房贷月频的现形态）下热点 1–3 占九成；`ACT_360` 分段形态（重定价普及后）下热点 6 先到第一。**所以热点 1–3 与热点 6 的先后不是口味问题，而是「房贷月频允许哪种计息基准」这个标准裁定的函数** —— 见 `AGENTS.md` §6 流程约束 3 与 ticket 04。

## 标准裁定：房贷月频计息基准（ticket 04 的裁定点，2026-11-14）

**房贷月频保持 `THIRTY_360`，允许 `ACT_*` 的迭代路径只属于租赁侧分段形态。** 依据：AGENTS.md P-03 银行业口径「月利率 = 年利率 ÷ 12」与 30/360 月频的每期费率（年利率 × 30/360 = 年利率 ÷ 12）**严格等价**，走闭式；129 号的「日利率 = 年利率 ÷ 360」约束的是**计息天数拆分**（积数/逐笔法），不是让月频等额还款改走逐日费率——两者对月频等额本金/本息的每期费率给出同一个数，选 `ACT_360` 只会切分计提天数、不会改变月供，却让同一笔测算落进迭代分支（480 期固定 1782.9 → 1876 ms，+160 段 1765.2 → 2768 ms）。房贷表单继续硬编码 `THIRTY_360`（`MortgageForm.vue:311`），这不是性能妥协而是口径选择；若未来有产品明确约定 `ACT/365` 等基准，按 P-03「按约定执行并在结果中标注」走，成本由本 spec 的闸门看着。

## 2026-11-14 落地记录（tickets 02 / 03 / 04(b)）

- **ticket 03 前半（已落）**：`DecimalMath.powWithLn`（`exp(t·ln(1+rate))`）+ `xnpv`/`xirr` 权重与 `ln(1+rate)` 提出求根循环。480 期指标线 1586–1611 ms → **1105–1174 ms（−30%）**；120/480/1200 三格闸门全绿，Golden + Conservation 逐位一致。ticket 03 后半（单遍化 / pow 阶梯 memo / 镜像复用）按 §6 规则 1 裁定**不实施**（逐码计时证据见热点 3、4）。
- **ticket 02（已落）**：run 级求根台账 `PeriodicRootHandoff`（`record` / `rootFor`，同根问题按内容判定含 R103 镜像）；`IrrMetrics` 记录未舍入根，`LeaseImplicitRateMetric` 先查台账再解，`CalculationFacade.netInvestmentPeriodIrr` 经 `rootFor` 复用、不同流集才自解（口径差已在 Javadoc 写明：CAS 21 第三十八条净投资 vs §9.4 现金-only 事件表）。普通单子 3 → 2 求根、未担保余值单子 4 → 3、资产价值无未担保余值的单子隐含利率 0 求根；`MetricSolveCountTest` 七条钉桩全绿。**按码集装配（该 ticket 第 2 项）不实施**：N-06 要求 facade 保持产出 §9.5 全部码，前端分区裁剪属展示层，省不出引擎侧时间。
- **ticket 04(b)（已落）**：`PmtSolver.periodRates` 公开派生、`iterative`/`closingBalance` 改吃派生好的率表；`EqualInstallmentGenerator.solveSegment` 派生一次、uniform 检查与求根共用（删 `uniformWindows`）。每 run 消掉 ~1130 万次重复 `yearFraction` 商与精确乘积；墙钟不动（1200p 格 5845.9 ms，由 ~1150 万步 DECIMAL128 走查主导 = RESOLVE 口径地板）。`RateDerivationCountTest`：1200p ACT_360 格恰好 2400 次派生（= 2×期数）、1 次求根。**ticket 04(a) 裁定不实施**：480p+160 段摊销表阶段 2.5 vs 0.6 ms，索引化可省的总共 ~1.9 ms、且与热点 6 的每段重解耦合（索引要动 `solveSegment` 的调用契约），省不出闸门可读差值；重定价行数涨到 1200×400 时重跑 01 基线再议。

## 非目标

- **不为速度牺牲 §1 的精度硬约束**：P-01（一律 BigDecimal）、P-07（内部 ≥ 10 位、中间结果不截断）、P-08（先明细求和再舍入）、P-09（尾差单点带符号吸收）一律不变。
- **不改变任何输出数值**：优化前后同一笔测算的输出必须**逐位一致**（金额容差 0.01 元只用于校验，不是用来给优化放行）。
- **不改变 §9.5 指标口径、不改 REST 契约、不改前端要素**（`form-field-gaps` spec 的「Plan 5 已冻结」同样适用本目录）。
- 不做缓存层 / 不做并行化 / 不做「只算前端要看的码」之外的功能扩展 —— 这些先等基线钉住再说。

## Issue 索引

| 编号 | 状态 | 内容 |
|---|---|---|
| 01 | **resolved（2026-11-12）** | 可重复耗时基线与闸门：`calc-testkit/.../RunCostBaselineTest.java`，四格形态 + 分阶段计时 + 相对闸门 |
| 02 | **resolved（2026-11-14）** | 求根台账消重复求根（普通单子 3→2、未担保余值 4→3）；按码集装配裁定不实施（N-06） |
| 03 | **resolved（2026-11-14）** | `powWithLn` + xnpv 权重/ln 外提（指标线 −30%）已落；单遍化/pow memo/镜像复用按 §6 规则 1 裁定不实施（逐码 ≤8 ms） |
| 04 | **resolved（2026-11-14）** | (a) 规则索引裁定不实施（2.5 vs 0.6 ms，省不出闸门差值）；(b) 期利率派生复用已落（~1130 万次重复商/run → 0，`RateDerivationCountTest` 钉 2×期数）；走查本身 = RESOLVE 口径地板；房贷月频裁定保持 `THIRTY_360`（P-03 等价） |

01 是其它一切的前提：**没有可重复的基准，任何优化都无法证明它没有改变数值**（AGENTS.md §6 N-04）。
