# 02. 房贷补「重定价日 / 重定价周期」

Status: resolved（2026-11-12 前端实现，引擎契约零改动；见文末 Comments）
Type: task
Priority: P1
数据角色: A 参与测算 —— 分段利率改变整条摊销表
Blocked by: —

## 背景

`AGENTS.md` §2.2 把 **重定价日** 与 **重定价周期** 列为规范指标，§3.5 的房贷最小要素集也
要求「重定价周期（按合同选择 3/6/12 个月）」。现状是 LPR 与加点一次性合成一个固定年利率，
浮动利率的核心要素完全没有 —— 合同里最常变的那个参数无法表达。

依据：央行公告〔2019〕第 16 号（重定价周期最短 1 年）、央行公告〔2024〕第 11 号（可选择
3/6/12 个月，存量房贷通常仅可调整一次）。

## 要做什么

1. 表单新增：`重定价周期`（3 / 6 / 12 个月）、`重定价日`（每年对应日，默认放款日对日）。
2. 前端展示「当期执行利率」与「下次重定价日」两个派生读数。
3. 引擎支持按重定价周期分段计息：每段用该段起点的 LPR + 加点，段内利率不变。
   LPR 历史序列作为输入（或按约定给定每段 LPR），**不得**静默沿用首期 LPR。
4. 利率变更策略与重定价的关系写清楚（现有 `RESOLVE` 是「自变更日重算」的姿态）。

## 验收标准

- 固定利率路径（不填重定价）与现状逐位一致。
- 选 3/6/12 个月时，摊销表按段切分，每段 `appliedRate` 正确，且分段合计与原口径的差异
  可解释。
- 3/6/12 之外的周期被拒绝。**裁定（2026-11-12，已定）：只有前端拦，并诚实标注它是前端门槛。**
  引擎不认识「重定价周期」这个词 —— 它只看见分段利率规则行（`RuleRow`），非 3/6/12 的周期在
  引擎侧没有对应的非法形态可拦（规则行只受 `RuleSetValidator` 的期区间/覆盖/年利率 ∈ [0,1]
  检查）。所以这一条从「UI 与引擎都要拦」改定为：取值闸门住在前端（`Select` 只给 0 / 3 / 6 / 12
  四个选项 + `isRepricingCycle` 提交闸门 + `repricingSegments` 对非 3/6/12 抛错），与 480 月
  上限、≤ 12 期 BULLET 同属**前端门槛**；文案已写明「该取值闸门在前端，引擎只看见分段利率」
  与「该取值由前端校验，引擎未校验」，不把它写成引擎保证。不在引擎侧引入重定价要素（那是
  契约级改动，与「Plan 5 已冻结」冲突）。
- **运行耗时不得失控**（2026-11-12 追加，依据「测算速度」这条核心要求）：480 期 + 3 个月
  周期（160 条规则行）这一格的耗时不得比同笔固定利率路径恶化出量级。实测已经证明它免费
  （见下方 Comments），这一条的作用是把结论钉住，防止后续把房贷计息基准换成 `ACT_360` 时
  悄悄退化成迭代分支。

## 裁定点：房贷计息基准（与耗时直接相关）

房贷表单现在硬编码 `dayCountCode: 'THIRTY_360'`（`web/src/components/MortgageForm.vue:311`），
对月频等额还款它与 `AGENTS.md` P-03 的「月利率 = 年利率 ÷ 12」等价，因此 `PmtSolver` 走闭式
（`calc-domain/.../support/PmtSolver.java:44,56` 只认 `THIRTY_360` / `THIRTY_E_360`）。如果按
129 号的另一种读法改走 `ACT_360`（逐日计提），同一笔测算就落进 `PmtSolver.iterative` 分支，
每段重解各带一个 O(剩余期数) 的走查 → 耗时按下方 Comments 的第三/四行成立。
**「房贷月频允许选哪种计息基准」是标准问题，不是性能问题**；裁定归 `calc-perf` 的 04 与本页
共同处理。

## 涉及文件

`web/src/components/MortgageForm.vue`、`web/src/i18n/locales/*.ts`、
`calc-domain`（`LeaseElement` / 利率策略）、`calc-engine`（分段计息）、`calc-server`（DTO）。

## Comments

**2026-11-12 —— 实现路线与实测。** 工作区里有一版**未提交**的实现（R150）：新增
`web/src/components/repricing.ts`（+ `repricing.spec.ts`）把「重定价周期 + 每段 LPR」编译成
引擎**既有**的分段利率规则行（`buildRateRuleRows`，形态与租赁 Tab 的 `mapRuleRows` 同源），
所以**不新增契约、不新增引擎要素** —— 重定价成为既有分段能力的参数化（`AGENTS.md` §3.2
「分段计息（利率调整）」）。周期闸门 0 / 3 / 6 / 12 在前端；缺段 LPR 未勾选「沿用当前 LPR」
即阻断提交，勾选则作为**显式假设**披露（不得静默沿用首期 LPR，符合 §3.1）。

一次性探针实测（同本金 2 380 000、名义 3.50%、月频、JIT 预热后 best-of-5，详见
`.scratch/calc-perf/spec.md`）：

| 形态 | dayCount | solver 头 | 全链路 |
|---|---|---|---|
| 480 期固定利率 | THIRTY_360 | CLOSED_FORM | 1743 ms |
| 480 期 + 160 段 | THIRTY_360 | SEGMENTED_RESOLVE | 1755 ms |
| 480 期固定利率 | ACT_360 | ITERATIVE | 1876 ms |
| 480 期 + 160 段 | ACT_360 | SEGMENTED_RESOLVE | 2768 ms |
| 1200 期 + 400 段 | ACT_360 | SEGMENTED_RESOLVE | 10564 ms |

结论：**分段本身不是成本来源**（30/360 闭式下 160 段只多 12 ms）；真正的耗时主体在 §9.5
指标装配线（480 期 1612 ms / 全程 1797 ms），归 `calc-perf` 目录另案处理，本 ticket 不背。

**红灯收尾（2026-11-12）**

① 样式违规（组件侧，不是测试侧）：`.repricing-readout` 与 `.repricing-assumed` 原写
`var(--fcs-border, #e5e7eb)` 与 `var(--fcs-warning, #ad6800)` —— 这两个 token 名字**根本不存在**
（是随手发明的），且带 hex 兜底，同时撞 `web/src/styles/workbench.spec.ts` 的两条守卫
（「组件样式块不得落 hex / rgba」与「不得自带阈值色值」）。改法：assumed 文案用 R143 已有的
`--fcs-caveat`（「读数存疑标注」，`result/MetricsPane.vue` 同源，语义正是「这段利率是假设」）；
面板描边在共用样式表新增 `--fcs-panel-border`，**不**复用 `--fcs-ratio-border` —— 那条 token 的
语义是「阈值读数框」，借它会让上面那条守卫失去意义。

② 测试侧：reset 测试用 `findAllComponents(DatePicker)` 按下标取日期控件，而新增的「重定价日」
排在放款日 / 首期还款日之前 → 取错控件、首期还款日仍早于放款日、提交被拦、`lastRequest()` 为空。
改法：按 `data-test` 定位（`mortgage-commence-date` / `mortgage-first-repayment-date`），
不再依赖 DOM 次序 —— 表单里控件的先后是布局细节，不该成为测试的假设。

**收尾后读数**：`web` vitest 276/276 通过（20 个文件）；`vue-tsc --noEmit` 与 `eslint .` 无输出；
后端本轮零改动（`mvn -o test` 之前全绿：460 tests, 0 failures / 0 errors / 0 skipped）。


