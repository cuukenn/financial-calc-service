# 01. 融资租赁补「租赁物价值」与首付款

Status: resolved
Type: task
Priority: P1
数据角色: A 参与测算 —— 改变租赁内含利率的分母与首付款
Blocked by: —

## 背景

`AGENTS.md` §3.5 的融资租赁最小要素集第一项就是**租赁物价值**，但表单里只有「租赁本金」。
两者是不同概念，混用会让两件事都算不对：

- 首付款 = 租赁物价值 − 租赁本金；
- 租赁内含利率的定义（§2.1、CAS 21）是「租赁收款额现值 + 未担保余值现值 = 租赁资产
  公允价值 + 出租人初始直接费用」——分母不是本金。

## 要做什么

1. 表单「租赁要素」新增 `资产价值`（租赁物价值）字段；本金仍单独录入。
2. 前端派生并展示 `首付款 = 价值 − 本金`，本金大于价值时给出校验错误（不得静默为负）。
3. 引擎侧新增可选输入 `assetValue`（缺省时退回当前「本金 = 价值」的既有口径，保证旧请求
   行为不变），用于租赁内含利率与首付款计算。
4. 结果区标注本次测算用的是哪一种口径。

## 落地记录（2026-11-11）

按 AGENTS.md §5 的 M-01，`assetValue` 必须有一个真正的消费者，否则就是装饰字段 —— 消费者
是新增的 **`LEASE_IMPLICIT_RATE`**（CAS 21 内含利率，第 26 个 code）：

- `LeaseElement.assetValue`（可选）+ `FCS-DOM-1013`（> 0 且 ≥ principal，差额即首付款）；
- `LeaseImplicitRateMetric`：把期初流出替换为资产价值后求解周期性 IRR 并年化，scale 12；
  无 assetValue 时为 present-but-null（与 `BORROWER_*` 同姿态）；
- DTO / assembler / web 表单 / payload（留空则键缺席，向后兼容）；
- 指标目录 25 → 26 项，README/设计文档口径同步。

## 验收标准

- 只填本金时，全部既有测试与黄金基线不变（向后兼容）。
- 同时填价值与本金时，首付款在 UI 可见，且租赁内含利率的分母使用「价值 + 初始直接费用」。
- 本金 > 价值时提交被拦，提示文案说明原因。

## 涉及文件

`web/src/components/CalculationForm.vue`、`web/src/i18n/locales/*.ts`、
`calc-domain`（`LeaseElement`）、`calc-engine`（内含利率计算）、`calc-server`（DTO）。
