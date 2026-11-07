# 分段利率与自定义租金机制实施计划（Plan 1.5）

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development（R33 三角色：后台实现者 + 独立评审者 + 控制器记账）。

**Goal:** 把设计文档 §7.5 已确认的分段利率语义（D3：默认 R 方案，`rateChangePolicy` 可切换 F/D）接进四种内置算法，并新增 §8.1 `04_现金流` 的引擎级自定义租金机制（显式逐期租金 → 本息拆分）。两者都是 Plan 1 明确顺延、本次经需求方确认拉前的特性。

**Spec:** `docs/superpowers/specs/2026-11-02-financial-calc-service-design.md`（§7.5 / §8.1 / §9.2），其「实现偏差记录」节随本计划追加。

**Spec:** 本计划 argue 自上述设计文档 v1.0（已确认）。Global Constraints 全部沿用（JDK 11、BigDecimal、不可变、FCS 错误码、`mvn -o clean test` 绿后提交、feat:/fix:/test:/chore: 前缀）。

## 背景与现状

- Plan 1 终态：`main` = `bf7a124`，332 测试绿，命名空间 `io.github.cuukenn.fincalc.*`，JPMS 模块名同步。
- **分段利率现状**：只有 `RULE_DRIVEN` 生成器消费规则费率；四种内置算法遇到非均匀费率按 R17 快速失败（`FCS-DOM-2002`），且 `rateChangePolicy`（RESOLVE/FIXED_RENT/DRIFT）字段存在但无行为。等额本息的 PMT 由 `PmtSolver` 按 `element.nominalRate()` 求解（R27），期次计息走各生成器的 `periodRate` 钩子。
- **自定义租金现状**：设计 §8.1 `04_现金流` 显式租金模式被 Q9 顺延；§8.2 列 I 的 `principalPlan=CUSTOM` 只覆盖"自定义本金计划"，不覆盖"给定租金总额反推本息"。
- **需求方确认（2026-11-07）**：①自定义租金经 `LeaseElement.explicitRents` 直传；②分段利率做全三态（R/F/D）。

## 设计裁决（本计划基线，控制器裁定）

- **R67** 包命名空间已迁移 `io.github.cuukenn.fincalc.*`（用户指令，已落地于 `bf7a124`）——本计划所有新代码在该命名空间下。
- **R68 自定义租金载体**：`LeaseElement.explicitRents`（`List<BigDecimal>`，长度必须 = 期数，每期 > 0；null/空 = 不使用）。新增 `RepaymentMethodId.EXPLICIT_RENT` 常量（自有常量，不破坏"封闭枚举、不开放用户自定义 id"立场——勘误行追加）。设计文档勘误节追加对应行。
- **R69 拆分语义（标准口径）**：`EXPLICIT_RENT` 生成器逐期计算：利息 = 期初余额 × 期利率（期利率按 `DayCountConvention` 折算），本金 = 当期租金 − 利息，期末余额 = 期初 − 本金；**不做末期钳制**（租金是给定值不是推导值）。若租金序列不恰好把余额摊到 `outstandingResidual()`，由既有 `ValidatorChain`（PrincipalConservationValidator 3001 / TailBalanceValidator 3002）报 ERROR——计划表保留但 `hasErrors()` 为真，绝不禁默。`EXPLICIT_RENT` 下 `fee/tax` 仍按 LeaseElement 的费用机制叠加进 `rentDue`？——否：给定租金即 `rentDue`，费用按 `CashFlowBuilder` 既有规则走现金流，不改租金（与 `04_现金流` 口径一致，报告披露）。
- **R70 分段费率解析基础设施**：`CalculationContext` 携带 `RuleSetView`（facade 组装时放入；`null` = 无规则集 = 现状）。内置生成器的期费率解析顺序：规则集命中（含 R66 的 `EvalContext` 条件求值，复用 `RuleDrivenGenerator` 的变量工厂——提取为共享助手）→ 元素名义利率。**R17 的均匀费率守卫仅在"无规则集"时生效**；有规则集时允许逐期变化。
- **R71 等额本息三态**（`rateChangePolicy` 只影响等额本息；其余三种算法本金计划与费率解耦，天然支持分段）：
  - `RESOLVE`（默认，D3 确认）：按规则费率把期次轴切分成**费率段**；每段以「段初剩余本金 + 段内剩余期数 + 段利率」重解 PMT（复用 `PmtSolver`），段内等额。
  - `FIXED_RENT`：以初始费率求解一次 PMT，全程租金不变；费率变化只改变本息拆分。
  - `DRIFT`：逐期以「当前余额 + 剩余期数 + 当期费率」重解，等价于严格按日计息口径的浮动。
  - 求解器命名进 `ScheduleHeader.solver`（如 `SEGMENTED_RESOLVE` / `FIXED_RENT` / `DRIFT`）。
- **R72 黄金基线扩展**：`ReferenceTables` 新增分段与显式租金参考表；等额本息 × 分段（THIRTY_360，R 方案）逐期到分，且与"分段边界重解"的独立手算一致。

## Tasks

### Task 1: 显式租金模型（LeaseElement.explicitRents + EXPLICIT_RENT）

**Files:** `calc-domain/.../model/LeaseElement.java`（+explicitRents 字段/builder/validate）、`RepaymentMethodId.java`（+EXPLICIT_RENT）、`DomainErrorCode.java`（+FCS-DOM-1008 租金序列为空或长度不匹配、+FCS-DOM-1009 租金必须为正）、`LeaseElementTest.java`。
**语义:** `explicitRents` 仅当 `method == EXPLICIT_RENT` 时必填；长度 == periods；每期 > 0（`compareTo` 校验）；`IRREGULAR` 时长度 == explicitPeriods.size()。`toBuilder` 自然携带。
**TDD:** 先测（3 个失败用例：缺租金、长度错、非正租金；1 个正路构造）→ 实现 → 绿。

### Task 2: 分段费率解析基础设施

**Files:** `calc-domain/.../domain/calc/CalculationContext.java`（+ruleSet）、`AbstractAmortizationGenerator.java`（+规则解析期费率钩子，R17 守卫改为"仅无规则集时生效"）、`calc-rule` 提取共享的期次变量 `EvalContext` 工厂（从 `RuleDrivenGenerator.PlannedRuleSet` 抽出 `PeriodVariables` 助手，两侧复用）、`GeneratorRegistry`/`CalculationFacade`（ruleSet 进 context；`EXPLICIT_RENT` 路由占位）。
**语义:** 见 R70。`RuleDrivenGenerator` 重构后行为不变（现有测试全绿为准绳）。
**TDD:** 新增"内置算法 + 规则集分段费率"的集成测试（先红后绿）；`RuleDrivenGeneratorTest` 全绿回归。

### Task 3: 等额本息三态（R/F/D）

**Files:** `calc-domain/.../support/algo/EqualInstallmentGenerator.java` + `PmtSolver`（暴露段级求解）、新测试。
**语义:** 见 R71。分段边界 = 规则解析年利率发生变化的首期。`FIXED_RENT`/`DRIFT` 的 `ScheduleHeader.solver` 如实标注。零利率/单期/残值分支在三态下全部保持（Plan 1 的退化分支测试回归）。
**TDD:** 手算金标准：1 000 000 @ 前 6 期 3% / 后 6 期 6%，THIRTY_360，三态各一组逐期断言（报告含手算链）。

### Task 4: 其余三种内置算法的分段接受 + EXPLICIT_RENT 生成器

**Files:** `EqualPrincipalGenerator`/`InterestFirstGenerator`/`PrincipalFirstGenerator`（费率钩子接规则集，断言守卫移除）、新 `ExplicitRentGenerator`（calc-domain support/algo，`code() = "EXPLICIT_RENT"`，order 50）、注册与 facade 路由、端到端测试。
**语义:** 三种内置算法只改"允许规则费率"，本金计划不变；`EXPLICIT_RENT` 按 R69 逐期拆分，现金流与指标沿用既有编排（facade 不需要新分支，除路由）。
**TDD:** 三算法 × 分段费率各 1 组；EXPLICIT_RENT 正路 + 不收敛（校验 3002、`hasErrors()` 真）+ IRR 非空。

### Task 5: 黄金基线扩展 + 勘误 + 文档

**Files:** `calc-testkit/.../ReferenceTables.java`（新增 2 张表：等额本息分段 R 方案、EXPLICIT_RENT 正路）、`GoldenScheduleTest`（MethodSource 扩展）、README（新算法 code、新字段）、设计文档勘误节（R68 的 EXPLICIT_RENT 常量行、R71 三态行为行）。
**TDD:** 新表逐期到分 + 独立手算核对（沿用 D2 流程：报告含手算链，核对人/日期入类注释）。

## 验收

- 全量 `mvn -o clean test` 绿（332 + 新增）。
- 分段三态各有一组逐期手算金标准；EXPLICIT_RENT 正路 + 失败路径均有测试。
- 设计文档勘误节、README 同步；Plan 2 的 `04_现金流` 映射点在 README 标注（`LeaseElement.explicitRents`）。
