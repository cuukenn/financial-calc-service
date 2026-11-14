# 03. 保证金口径：收取时点 / 是否计息 / 退还与抵扣

Status: resolved（2026-11-14 引擎+契约+前端落地；口径裁定见文末「裁定」）
Type: task
Priority: P2
数据角色: A 参与测算 —— 保证金进入期初现金流与 IRR
Blocked by: 01

## 背景

`AGENTS.md` §3.1 第 11 条要求保证金输入包含「金额、收取时点、退还/抵扣规则」，现在表单只有
一个金额框，引擎只有一种姿态（单独收取、可退，见 R123）。实务里保证金至少三种常见口径：

- 期初收取、到期退还（并可能计息）；
- 抵减本金（租赁本金 = 合同本金 − 保证金）；
- 冲抵末期租金。

## 要做什么

1. 表单新增：`保证金收取时点`（期初 / 随首期租金）、`是否计息`、`退还或抵扣方式`
   （退还 / 抵减本金 / 冲抵末期租金）。
2. 引擎把保证金作为一条真实现金流进入租赁投资净额与 IRR，而不是只做展示。
3. 结果区单列保证金现金流与退还金额，不与租金相加。

## 验收标准

- 三种抵扣方式各自有测试，期末余额与 §3.4 四项校验仍成立。
- 保证金为 0 时与现状逐位一致。
- 保证金计息时，利息单独成行并标注口径。

## 涉及文件

`web/src/components/CalculationForm.vue`、`web/src/i18n/locales/*.ts`、
`calc-domain`、`calc-engine`（现金流构建）、`calc-server`。

## 落地记录（2026-11-14）

### 裁定（口径，2026-11-14）

1. **收取时点**：`UPFRONT` = 起租日 t0（v1 姿态，默认）；`WITH_FIRST_RENT` = 首期租金支付日（事件表把租金记在首期 endDate，保证金行落同日、同 discount index）。
2. **退还抵扣**（`DepositSettlement`）：
   - `REFUND`（默认）：到期日保证金本金单独一行退还；计息时利息**再单独一行**（`CashFlowType.DEPOSIT_INTEREST`），绝不并入租金或保证金本金行（M-03/M-04）。
   - `OFFSET_PRINCIPAL`：保证金在收取时即抵减融资额——**摊销本金 = 合同本金 − 保证金**（`LeaseElement.measuredPrincipal()`，生成器/校验器/规则池统一从它读）；事件表仍把 t0 两腿显式列出（本金全额流出 + 保证金流入），净额即净本金。此方式下保证金不占用期间，**携带计息利率是口径矛盾，直接拒绝（FCS-DOM-1019）**。
   - `OFFSET_FINAL_RENT`：冲抵末期租金与「到期退还」在现金流上是同一对事件（同日收取、到期返还是冲抵的对价），故事件表与 REFUND 完全一致；摊销表租金列保持摊销租金，冲抵体现在保证金行，不净入租金。
3. **计息**（P-03/P-06）：保证金 × 年利率 × 实际占用天数 ÷ 360（ACT/360、算头不算尾、单利），算至分；从收取日到结算日。
4. **IRR 参与**：请求带保证金 > 0 时，assembler 自动以 `INCLUDE_DEPOSIT` 为现金流基准（此前恒为 INCLUDE_FEES，保证金只做展示——本票要消除的正是这一点）；无保证金请求基准不变，逐位一致。v1 不设逐请求 basis 覆盖，想排除保证金就报一个无保证金要素。

### 实现

- `calc-domain`：`DepositTiming`/`DepositSettlement` 枚举；`LeaseElement` 三字段（构造器归一化默认值，v1 姿态不变）+ `measuredPrincipal()`；校验 FCS-DOM-1018（利率必须与正保证金同时提供）/FCS-DOM-1019（抵减本金不得计息）；`CashFlowType.DEPOSIT_INTEREST`；`StandardCashFlowBuilder.addDepositLegs`（时点/结算/计息腿）；摊销本金切换点：`AbstractAmortizationGenerator`×3、`EqualInstallmentGenerator`×2、`EqualPrincipalGenerator`、`PrincipalFirstGenerator`、`PrincipalConservationValidator`、`RuleDrivenGenerator`、`RuleSetValidator`。
- `calc-server`：DTO 三字段；assembler 映射 + `cashFlowBasis(dto)`（保证金 > 0 → INCLUDE_DEPOSIT）。
- `web`：表单三字段（时点/方式/计息利率，百分数口径输入）+ payload + zh/en i18n + 现金流 pane `DEPOSIT_INTEREST` 标签（保证金行本就按类型单列，不与租金相加）。

### 测试

- `LeaseElementTest` 25 绿（默认姿态、1018/1019、越界利率、measuredPrincipal 仅 OFFSET_PRINCIPAL 净额）。
- `StandardCashFlowBuilderTest` 22 绿（随首期租金收日、利息 1525.00 = 50000×3%×366/360 钉桩、抵减本金双腿显式+无结算腿、冲抵末期租金 ≡ 退还流）。
- `CalculationRequestAssemblerTest` 11 绿（三字段映射、保证金>0 → INCLUDE_DEPOSIT、无保证金保持 INCLUDE_FEES）。
- `DepositCaliberTest`（testkit e2e）3 绿：**抵减本金单子的租金/余额/IRR_PERIOD 与「直接按净本金签约、无保证金」的单子完全一致**（经济学钉桩）；保证金=0 全链路与 v1 逐位一致；计息退还单子全绿且 TOTAL_RENT 不含保证金。
- web：lint + 278 vitest + vue-tsc build 全绿（新增 3 条表单 spec）。
