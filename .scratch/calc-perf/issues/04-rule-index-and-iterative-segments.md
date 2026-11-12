# 04. 规则解析改区间索引；迭代分支的每段重解（ACT_360 × 分段）

Status: needs-triage
Type: task
Priority: P3（当前实测不显著；是重定价形态与 `ACT_360` 组合下唯一会增长的部分）
口径影响: 不改要素、不改口径、不改任何输出数值
Blocked by: 01

## 背景

**(a) 规则解析是线性扫描。** `CompiledRuleSet.resolve`（`calc-rule/.../model/CompiledRuleSet.java:148-182`）逐行 `rule.matches(periodNo)`（`CompiledRule.java:61-63`，就是 `fromPeriod/toPeriod` 比较），每期新建一个 `RuleOutcome`（`:184-190`）。行按 `(priority, ruleId)` 排序（`CompiledRuleSet.java:102-106`）而不是按期区间，所以现在**连二分都做不到**。每期从 `AbstractAmortizationGenerator.generate` 调一次（`AbstractAmortizationGenerator.java:153`）→ **O(期数 × 行数)**。

R150（房贷重定价，见 `form-field-gaps` 的 02）把行数推到 160（480 期 / 3 个月周期）乃至 400（1200 期）：**480 × 160 ≈ 7.7 万次、1200 × 400 ≈ 48 万次**匹配调用。实测目前测不出代价（分段计时里 schedule 那一格是 0 ms），所以这一条**不是现在的问题，是重定价形态普及后会成形的问题** —— 因此排 P3 并要求先有 01 的基线，避免无证据优化。

**(b) 迭代分支每段重解。** `PmtSolver.isConstantPeriodRate` 只认 `THIRTY_360` / `THIRTY_E_360`（`calc-domain/.../support/PmtSolver.java:44,56`），所以 `ACT_360` / `ACT_365F` / `ACT_365_25` / `ACT_ACT` 与带 stub 窗口的计划一律走 `iterative`（`:112-141`：≤64 次 bracket 加倍 + ≤100 轮二分），每次 `error()` / `closingBalance` 是一个 **O(剩余期数)** 的 BigDecimal 走查（`:149-163`）；`EqualInstallmentGenerator.solveSegment`（`:126-133`）每段先跑 `uniformWindows`（`:136-149`）也是一次 O(剩余期数) 走查；`rent()` 只在解析出的年利率变化时才重解（`:212-234`，边界在 `:228`）。

实测对照（480 期，同本金同利率）：`THIRTY_360` 固定 1743 ms → 160 段 1755 ms（**分段几乎免费**）；`ACT_360` 固定 1876 ms → 160 段 **2768 ms**；1200 期 + 400 段 **10564 ms**。

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
