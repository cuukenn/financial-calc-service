# 14. 房贷 payload 被迫携带两个「租赁专属」必填要素

Status: needs-triage
Type: task
Priority: P2
数据角色: 契约形状问题 —— 现携带的这两个值对房贷不改变任何现金流
Blocked by: —

## 背景

2026-11-12 按「测算要素只保留各类型业务必要的，可加可不加的另算」这条核对两个表单，找到一处已经存在的违背：

房贷表单必须硬编码发送两个**租赁专属**要素，否则 REST 契约 400（R137）：

- `web/src/components/MortgageForm.vue:316` → `residualHandling: 'SEPARATE'`
- `web/src/components/MortgageForm.vue:317` → `rateChangePolicy: 'RESOLVE'`

表单文件头自己就写着这件事（`MortgageForm.vue:10-11`）：「payload 共 14 键（R101 + R137）：11 个房贷要素 + 三个 `LeaseElement` 必填要素（`residualValue` / `residualHandling` / `rateChangePolicy`）」。根因在领域层：`LeaseElement.validate()` 的 `requireField(residualHandling, "residualHandling")`（`calc-domain/.../model/LeaseElement.java:127`），而 `AGENTS.md` §3.1 又规定「输入要素必须显式提供、缺失即报错、**不得静默取默认值**」—— 所以 `CalculationRequestAssembler` 只补 `gracePeriod` / `rounding` 的默认，明确**不**补这两个（README「房贷字段 → 引擎参数映射」一节写明了这一条，也是缺陷 D-1 的根因）。

问题在于：**房贷这一笔业务没有残值。** `residualValue` 发的是 `null`，`SEPARATE` 因此是一个填补位的枚举值，它不改变任何一期现金流、不改变余额、不改变任何指标 —— 它就是「不为这一笔业务所需、却被迫录入的要素」。这违背「要素保留各类型业务必要的」，也让房贷 payload 里出现了一个读者会误以为有残值语义的字段。

（`rateChangePolicy: 'RESOLVE'` 在 R150 之后才真的开始参与：重定价分段规则行走的就是「自变更日重算」的姿态。所以它已经从填补位变成有意义的口径，**不再是本 ticket 的对象**；本 ticket 的对象是 `residualHandling` 与那个 `null` 的 `residualValue`。）

## 裁定点（需要 owner 先定，故 needs-triage）

| 方案 | 做法 | 代价 |
|---|---|---|
| **A** | `LeaseElement` 把「残值 / 残值处理」改成条件必填：只有当 `residualValue != null` 或业务类型声明有残值时才必填；房贷侧不再被迫发值 | 动领域校验 + 契约 schema 描述；要防止「漏发残值」从 400 变成静默无残值 —— 这与 §3.1「缺失即报错」的精神要用别的方式守（例如：显式的 `noResidual` 声明，而不是靠缺席） |
| **B（最便宜，且不动契约）** | 保留现状，但把「房贷没有残值，`SEPARATE` 是契约形状」写进契约描述与 UI 说明：`residualHandling` 在房贷场景的 schema 描述里标明「房贷无残值语义，本字段为满足 `LeaseElement` 必填的形状需要」，前端结果区不得因此显示任何残值读数 | 一行文案 + 一处 schema 描述；不改变任何数值；**诚实但不消除**这个多余要素 |
| **C** | 拆领域模型：共享测算内核 + 业务专属要素（租赁侧才有残值族） | 与「Plan 5 已冻结」正面冲突，是契约级重构，本表单轮次不该做 |

先选 A / B / C，再写实现。选 B 时本 ticket 降级为「文案与 schema 描述」小改；选 A 时要连 `PrincipalConservationValidator` 的 `expectedRepayablePrincipal`（`calc-domain/.../support/validate/PrincipalConservationValidator.java:62`）与 `StandardCashFlowBuilder.addClosingResidual`（`calc-domain/.../support/cashflow/StandardCashFlowBuilder.java:159`）一起确认：无残值时 §3.4 的第 1、2 项校验（Σ本金 = 合同本金、末期余额 = 0）仍然成立。

## 验收标准（按裁定后的方案再具体化）

- 房贷这一笔的输出数值（每期金额、余额、指标）与改动前**逐位一致**。
- 结果区 / 现金流表 / 校验器里不出现任何「房贷残值」读数。
- 前端门槛与文案不得让读者以为房贷有残值或 `SEPARATE` 有业务含义。
- 租赁侧残值 / 留购价 / 担保与无担保余值的现有测试（含钉住 §9.7 目标与事件表口径分叉的那两组端到端测试）全绿。

## 涉及文件

`calc-domain/.../model/LeaseElement.java`（校验）、`calc-server/.../dto/CalculationRequestDto.java` 与 `CalculationRequestAssembler.java`（契约与必填形状）、`web/src/components/MortgageForm.vue`、`web/src/i18n/locales/*.ts`。
