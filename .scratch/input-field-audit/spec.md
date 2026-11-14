# input-field-audit —— 死输入字段移除（2026-11-14 裁定：全部移除）

Status: resolved（已实施并验证）

## 背景

用户硬标准（2026-11-14）：**不直接参与测算或不属于测算结果的不得出现**。按此标准对 LeaseElement 全部输入字段、指标码、输出结构盘点，发现三个死字段——改变它们，测算结果逐位不变：

- `LeaseElement.vatRate`：无任何计算消费（价税分离 FCS-CALC-3005 拒绝，税列结构性恒空，TOTAL_TAX 恒 null）
- `FeeItem.vatRate`：`addUpfrontFees` 只读 amount/chargeMode/owner/includeInIrr
- `LeaseElement.currency`：只回显；P-13 小数位来自 RoundingPolicy，不读 currency

## 裁定

全部移除（字段、getter、builder、DTO、web 表单与 i18n 同步删）。税要素悬空以披露承载（v1 不支持价税分离）；将来支持增值税/外币时按 §4（2026《增值税法》13%/9%/6%、差额征税 10 号公告）与 P-13（ISO 4217 小数位）口径重新进场，届时必过 M-01。

## 实施

- 后端：LeaseElement/FeeItem/CalculationRequestDto/CalculationRequestAssembler/testkit Fixtures + 22 个测试文件的 builder 调用与位置参数
- web：CalculationForm.vue（币种下拉、税率输入+档次快选、payload）、FeesEditor.vue（费用税率列）、api/types.ts、editors/types.ts、MortgageForm.vue、i18n 两个 locale、两个 spec（R92-2 币种用例、R92-5/R93-4 税率用例整体删除，计数钉 15→14）
- 兼容性：Jackson（Spring Boot 缺省）忽略未知 JSON 字段，旧客户端携带 vatRate/currency 不报错、静默忽略

## 验证

- `mvn -o test`（JDK 17）：6 模块 510 测试全绿
- web：`vue-tsc --noEmit && vite build` 通过；vitest 292 全绿；eslint 干净

## 合规结论（盘点全录）

其余全部输入字段均有 M-01 答案（直接进现金流/摊销表，或参与校验/内含利率判定）；指标 26 码全部 M-02 可复算；BORROWER_* 有 M-04 背书；杠杆/DTI/LTV 未出现在结果区。三个死字段清除后，输入面与「参与测算」一一对应。
