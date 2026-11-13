# 04. 规则解析改区间索引；迭代分支的每段重解（ACT_360 × 分段）

Status: resolved（2026-11-14，(b) 期利率派生复用落地；(a) 与走查本体裁定不实施；基准裁定保持 THIRTY_360）
Type: task
Priority: P3（当前实测不显著；是重定价形态与 `ACT_360` 组合下唯一会增长的部分）
口径影响: 不改要素、不改口径、不改任何输出数值
Blocked by: 01

## 背景

**(a) 规则解析是线性扫描。** `CompiledRuleSet.resolve`（`calc-rule/.../model/CompiledRuleSet.java:148-182`）逐行 `rule.matches(periodNo)`（`CompiledRule.java:61-63`，就是 `fromPeriod/toPeriod` 比较），每期新建一个 `RuleOutcome`（`:184-190`）。行按 `(priority, ruleId)` 排序（`CompiledRuleSet.java:102-106`）而不是按期区间，所以现在**连二分都做不到**。每期从 `AbstractAmortizationGenerator.generate` 调一次（`AbstractAmortizationGenerator.java:153`）→ **O(期数 × 行数)**。

R150（房贷重定价，见 `form-field-gaps` 的 02）把行数推到 160（480 期 / 3 个月周期）乃至 400（1200 期）：**480 × 160 ≈ 7.7 万次、1200 × 400 ≈ 48 万次**匹配调用。所以这一条**不是现在的问题，是重定价形态普及后会成形的问题** —— 因此排 P3 并要求先有 01 的基线，避免无证据优化。

**2026-11-12 基线就位（01 resolved）后，(a) 的确切读数是「仍然 separable 不出来」**：480 期 + 160 段 `THIRTY_360` 那一格的摊销表阶段只有 **2.5 ms**，而同格固定利率形态是 0.6 ms → 160 行的线性扫描加上 160 次每段重解，总共只值 1.9 ms。**(a) 现在的代价确实测不出来**，1200 那一格 5.9 s 的主体是 (b)。这不代表 (a) 不用改 —— 它只是还没贵到能从噪声里分辨出来；改它的理由是先跑出可对照的差值，不是现在救火。

**(b) 迭代分支每段重解。** `PmtSolver.isConstantPeriodRate` 只认 `THIRTY_360` / `THIRTY_E_360`（`calc-domain/.../support/PmtSolver.java:44,56`），所以 `ACT_360` / `ACT_365F` / `ACT_365_25` / `ACT_ACT` 与带 stub 窗口的计划一律走 `iterative`（`:112-141`：≤64 次 bracket 加倍 + ≤100 轮二分），每次 `error()` / `closingBalance` 是一个 **O(剩余期数)** 的 BigDecimal 走查（`:149-163`）；`EqualInstallmentGenerator.solveSegment`（`:126-133`）每段先跑 `uniformWindows`（`:136-149`）也是一次 O(剩余期数) 走查；`rent()` 只在解析出的年利率变化时才重解（`:212-234`，边界在 `:228`）。

实测对照（480 期，同本金同利率）。**出处要分清**：下表的 `THIRTY_360` 固定 / 160 段两行已被 01 的闸门测试取代（该测试读作 1782.9 ms → 1765.2 ms，同结论）；`ACT_360` 的两行与 1200 期那一行的最后数字来自 2026-11-12 的一次性探针，**闸门测试不钉 480 期 `ACT_360` 这一格**，所以它们只能当趋势看，不能当闸门：

| 形态 | dayCount | 读数 | 出处 |
|---|---|---|---|
| 480 期固定利率 | THIRTY_360 | 1743 ms（闸门测试：1782.9 ms） | 探针 → 已被基线取代 |
| 480 期 + 160 段 | THIRTY_360 | 1755 ms（闸门测试：1765.2 ms） | 探针 → 已被基线取代 |
| 480 期固定利率 | ACT_360 | 1876 ms | **仅探针**，闸门未钉 |
| 480 期 + 160 段 | ACT_360 | **2768 ms** | **仅探针**，闸门未钉 |
| 1200 期 + 400 段 | ACT_360 | 10564 ms（闸门测试阶段合计 10101.9 ms） | 探针 / 基线（量法不同：全链路 vs 阶段合计） |

**2026-11-12 补：这一格的分阶段读数把热点顺序翻过来了。** `calc-testkit` 的 `RunCostBaselineTest`（AGENTS.md §6 N-01 的闸门测试）实测 1200 期 + 400 段 `ACT_360`（预热后最优，三次独立运行）：

| 阶段 | 读数（三次） |
|---|---|
| 摊销表生成 | **5869.3** / 6041.6 / 6044.7 ms |
| 现金流表 | 0.4 ms |
| 指标装配线 | **4231.5** / 4322.0 / 4111.0 ms |
| 校验器 | 0.8 ms |
| 阶段合计 | 10101.9 / 10311.9 / 10156.8 ms |

也就是说：**在 `ACT_360` × 400 段这一格，摊销表生成阶段（~5.9 s）已经盖过指标装配线（~4.2 s）**，而同一测试里 `THIRTY_360` 的 480 期两格摊销表阶段只有 0.6 ms 与 2.5 ms。热点排名 1 与 2 的相对位置取决于形态：固定利率 / 闭式形态下指标层占九成（排名照旧），`ACT_360` 分段形态下**每段重解（本条 (b)）才是第一热点**。闸门把这一格的 schedule 阶段标成 9000 ms 的「有余量的天花板」，不是承认 5.9 s 合理 —— 本 ticket 的存在理由就是把它降下来。

**裁定点（需要 owner 先定）**：AGENTS.md P-03 的银行业口径是「日利率 = 年利率 ÷ 360、月利率 = 年利率 ÷ 12」。房贷表单现在硬编码 `THIRTY_360`（`web/src/components/MortgageForm.vue:311`），对月频等额还款它与「月利率 = 年利率 ÷ 12」等价，所以走闭式；但如果按 129 号的另一种读法改用 `ACT_360`（逐日计提），同一笔测算就落进迭代分支，成本按上面的数字成立。**「房贷月频到底允许选哪种计息基准」是标准问题，不是性能问题** —— 先裁定标准，性能这一条才有靶子。

## 要做什么

1. 先由 01 的基线确认 (a) 与 (b) 各自在耗时测试里占多少格；占不出来的部分不动。
2. (a) 改法：`CompiledRuleSet` 内另建按 `(fromPeriod, toPeriod)` 的区间索引（有序区间 + 二分，或 `period → rule` 的一次性展开表），并停止每期新建 `RuleOutcome`（可缓存同一行覆盖期间的复用 —— `RuleOutcome` 是不可变的，安全）。**priority 语义（R53：低者胜）与 conditioned 行跳过语义（Task 26 honest-no-match）不得改变**：同一期同一 priority 序列解析出的必须还是同一行、同一个 `annualRate`。
3. (b) 视裁定结果：若月频保留 30/360 → (b) 只在租赁侧 `ACT_360` 场景成立，改法是**让每段重解复用上一段的走查结果**（段起点余额已知，不必每次从 O(剩余期) 重新走一遍）；若房贷允许 `ACT_360` → 先补闭式（月频 `ACT_360` 的每期费率其实是常数 `年利率/12`，日基准只影响计提天数切分），别一上来就调迭代轮数。
4. **不得**通过降低 `MAX_ITERATIONS`（`PmtSolver.java:37`、`BisectionNewtonSolver.java:43`）或放宽求根容差来「提速」—— 求根容差的语义刚被 R148 定为解域语义，动它等于动口径。

## 验收标准

- 改 (a) 后：同一组规则行 + 同一笔测算，每期 `appliedRate()` 与 `memo`、以及 `CompiledRuleSet` 的 `hash()` 指纹逐位一致；`RuleCompilerTest`、`BuiltinSegmentedRateTest`、`RuleDrivenGeneratorTest`、`RuleSetValidator` 覆盖/重叠/优先级相关测试全绿。
- 改 (b) 后：`PmtSolver` 的 bracket/收敛路径测试全绿，且末期余额与四项校验（AGENTS.md §3.4）仍成立；分段合计与原口径的差异可解释（`form-field-gaps` 02 的验收同源）。
- 耗时测试里 1200 期 + 400 段那一格相对 10564 ms 有 measurable 下降，且 01 的闸门收紧到 G6 的目标值写进 spec（120 期 ≤ 50 ms）时能达标。

## 涉及文件

`calc-rule/.../model/CompiledRuleSet.java`、`calc-rule/.../model/CompiledRule.java`、`calc-domain/.../support/AbstractAmortizationGenerator.java`、`calc-domain/.../support/algo/EqualInstallmentGenerator.java`、`calc-domain/.../support/PmtSolver.java`、`calc-domain/.../support/cashflow/`（若涉及 stub 窗口）。

## 落地记录（2026-11-14）

**基准裁定（裁定点）**

**房贷月频保持 `THIRTY_360`，`ACT_*` 迭代路径只属于租赁侧分段形态。** 依据：P-03「月利率 = 年利率 ÷ 12」与 30/360 月频每期费率（年利率 × 30/360）严格等价，走闭式；129 号的日利率口径约束计息天数拆分，不是让月频等额还款改走逐日费率。`MortgageForm.vue:311` 的硬编码是口径选择而非性能妥协；产品明确约定其它基准时按 P-03「按约定执行并标注」走。全文见 spec.md「标准裁定」节。

**(b) 落地：走查内的重复派生消掉**

- `PmtSolver.periodRates(annualRate, windows, dayCount)` 公开：一次 solve 只派生一份期利率表（每窗一次 `yearFraction` 商 + 精确乘积），`iterative` 的全部求值、`uniform` 检查共用；`closingBalance` 同源。`EqualInstallmentGenerator.solveSegment` 删掉独立的 `uniformWindows` 走查，改为派生一次、`uniform(periodRates)` 用率表判均匀（RATE_EPSILON 比较，不触碰舍入结果——uniform 判定只选路径，两种路径的算术都与改前逐位一致）。
- **省了多少**：RESOLVE 口径下每段从段初余额重解，~400 段 × ~48 次求值 × ~600 剩余窗 ≈ **1130 万次**重复 `yearFraction` 商与精确乘积 / run → **0**。
- **墙钟为什么不动**：1200p+400 段摊销表阶段 5847–6041 → **5845.9 ms**——格子由 ~1150 万步 DECIMAL128(34 位) 余额走查主导，那是 RESOLVE 口径（每段从段初余额、按剩余期数重解）的地板；再往下只能降精度或改口径，§6 禁止。
- **工作计数钉桩**：`RateDerivationCountTest`（calc-testkit）——装饰 `DayCountConvention` 计数 `yearFraction`，1200p ACT_360 均匀计划：**2400 次派生（恰好 2×期数：1200 行 + 1 次求解的率表）、1 次求根**；改前每次求值自派生 ~150×1200 ≈ 18 万次。墙钟测不出的省法，计数钉得住。
- 数值不变：全量 `mvn -o test` 绿（含 GoldenSchedule 20 例、`BisectionNewtonSolver`/`PmtSolver` 收敛路径、分段 `RuleDrivenGeneratorTest`）。

**(a) 裁定不实施——§6 规则 1**

480p+160 段摊销表阶段 2.5 vs 0.6 ms：160 行线性扫描 + 160 次每段重解总共 **1.9 ms**（0.16 μs/次匹配）。索引化省不出闸门可读的差值，且区间索引必须动 `solveSegment` 的调用契约（与 (b) 的每段重解耦合）——属「口径相邻区」的结构改动，性能不是裁定理由。重定价行数涨到 1200×400 时重跑 01 基线再议。

**(b) 剩余部分裁定不实施**

「让每段复用上一段走查结果」不做了：上一段的走查终点 ≠ 本段的段初余额问题（本段按剩余期数重解是 RESOLVE 口径本身），复用等于改口径；走查步数已由闸门与上表钉住，属口径地板。若未来 owner 改 RESOLVE 口径（如改为「段初租金按段内期数闭式」），本 ticket 的闸门读数是对照基线。

**验收对照**

- `PmtSolver` bracket/收敛路径测试全绿；末期余额与四项校验成立。
- 「1200 期 + 400 段 measurable 下降」**未达**：重复派生消掉了、墙钟不动（口径地板），已如实记录——N-02 的诚实条款同样适用于性能收益声明。
