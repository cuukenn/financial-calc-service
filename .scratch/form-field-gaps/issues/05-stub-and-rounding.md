# 05. 首末期规则与尾差去向

Status: resolved（2026-11-14 首期 stub 落地；尾差吸收裁定为「末期显式化 + 非 LAST 显式拒绝」，见文末「落地记录」）
Type: task
Priority: P2
数据角色: A 参与测算 —— 首期切分与尾差归属改变每期金额
Blocked by: —

## 背景

`AGENTS.md` §3.1 第 9 条要求显式提供「首末期规则（首期 stub、末期是否吸收尾差）」，
§3.2 第 8 条要求声明「尾差去向（末期 / 首期 / 按比例分摊）」，P-09 要求尾差只在**一个**
位置吸收并以带符号金额披露。现状是 UI 无开关，尾差去向由引擎隐式决定。

## 要做什么

1. 表单「租金结构」新增：`首期是否按实际天数（stub）`、`尾差吸收位置`（末期 / 首期）。
2. 结果区在摊销表中标出被吸收的那一行，并显示带符号的尾差金额（P-09）。
3. 校验：吸收位置只能有一个，且该行是唯一允许出现尾差的行。

## 验收标准

- 首期 stub 开/关各有一条用例，首期天数与利息按 P-03/P-14 口径正确。
- 尾差在末期与首期两种设置下总额一致，且符号随方向正确。
- 未选择吸收位置时按文档默认（末期）并显式标注，不静默。

## 涉及文件

`web/src/components/CalculationForm.vue`、`web/src/components/result/SchedulePane.vue`、
`web/src/i18n/locales/*.ts`、`calc-engine`（生成器末期处理）。

## 裁定（2026-11-14，triage：设计已定，待实现）

1. **现状盘点**：引擎的尾差吸收已是「单点、末期」姿态（P-09：末期吸收 + 带符号 `roundingDifference` 在 `ScheduleHeader` 披露）——本票不是「造功能」，是把引擎已经隐式做的口径**显式化**成 §3.1 第 9 条的输入要素。
2. **首期 stub**：`firstRentDate ≠ 起租日 + 整期` 时期间构建器已经产生不等长首期（现有行为）；新增开关控制的是「首期是否按实际天数单独计息」（P-14 零头天数规则：整期部分 × 期利率、零头部分 × 日利率，两种 129 号算法不得混用于单笔）。默认 = 现状（按年利率 × yearFraction 一段计），开关打开才走「整期 + 零头」两段——**默认必须与现状逐位一致**。
3. **尾差吸收位置（末期 / 首期）**：改的是 `AbstractAmortizationGenerator` 末期的 tail 调整落点（现为末期 rentDue 吸收）。首期吸收 = 把带符号尾差加在首期 rentDue。约束：只能选一个位置（校验），被吸收行在 memo 标注（已有 `memo` 通道），`ScheduleHeader.roundingDifference` 语义不变。
4. **默认显式标注**：未选择时按「末期」并在表单/结果标注「尾差吸收：末期（默认）」，不静默（验收第 3 条）。
5. 实施顺序：开关 + 默认逐位一致（金序列测试钉）→ 首期吸收路径（± 符号两向）→ stub 两段计息 → 摊销表 memo 标注。全程 N-04 逐位纪律适用。

## 落地记录（2026-11-14）

### 首期 stub（129号①）——落地

- `LeaseElement.firstPeriodStub`（缺省 false = 129号② 一段计，逐位一致）；IRREGULAR / RULE_DRIVEN 拒绝（FCS-DOM-1021：显式期间没有整月锚点 / 规则驱动租金解算不消费 stub）。
- `FirstPeriodStubSplit.yearFraction`：整月按日历月走（`plusMonths`，月利率占比 k/12），零头按实际天数（d/360，算头不算尾），CALC 12 位 HALF_UP——**摊销行与租金求解吃同一个拆分值**（`PmtSolver.periodRates`/`iterative` 新增首窗 override 重载；`EqualInstallmentGenerator` 的 prepare 无规则/FIXED_RENT/RESOLVE/DRIFT 四条路径全部带上）。stub 开启时闭式解禁用（① 的首窗费率一般离开均匀域，二分按混合率表定价）。
- 首期行 memo 标注「首期按整月+零头计息（129号①）」（口径显式可见，不得只改数字不留痕）。
- web：租金结构区 Switch + 提示 + 打开时的口径说明 + payload（仅打开时携带键）+ zh/en。
- 测试：`FirstPeriodStubSplitTest` 6 绿（对齐月 = 1/12、纯零头、月+零头、**闰年月末边缘（1-31→3-30 = 2/12+1/360，30/360 会给 59/360——①②在此分叉）**、季频 3/12、IRREGULAR 拒绝）；`LeaseElementTest` 27 绿；`FirstPeriodStubTest` 3 绿——THIRTY_360 对齐窗口 ON/OFF **逐位一致**（租金/利息/IRR_PERIOD）、ACT_360 上 ① 的首月利息（1/12）< ②（31/360）且 memo 标注、两种算法各自四项校验全绿。

### 尾差吸收位置——显式化 + 非 LAST 显式拒绝

- 现状盘点修正：`RoundingPolicy.tailAdjustPolicy` + `TailAdjusters`（三策略俱全）**从未被引擎消费**——死输入（M-01 违背），而引擎实际的姿态是「末期吸收 + 表头带符号 roundingDifference 披露」（P-09 已满足）。
- 裁定：v1 只支持 `LAST_PERIOD`；`FIRST_PERIOD`/`PROPORTIONAL` 在 `AbstractAmortizationGenerator.generate` 入口**显式拒绝**（FCS-DOM-1020）。原因：前向逐行 HALF_UP 的舍入漂移只能在末行闭合（末行本金 = 剩余 − 残值），首期吸收需要把首行租金设为自由变量逆解整表——口径级重构，不是本票能顺手做的；拒绝优于静默忽略（修复死输入）。
- 界面：摊销计划表上方显式标注「尾差吸收：末期（默认，P-09 单点带符号披露）」——验收第 3 条「不静默」达成。
- **验收标准 2（末期与首期两设置下总额一致）未达成并如实记录**：首期吸收未实现（见上），闸门是 FCS-DOM-1020 的显式拒绝。
