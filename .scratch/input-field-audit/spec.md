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

## Comments

### 2026-11-14 补扫（第二轮）：前端「抵押物与杠杆」整块移除

用户指出第一轮漏扫（只按字段名扫了 vatRate/currency，没按业务语义扫前端）。补扫发现 MortgageForm
有一个完整的「抵押物与杠杆（前端计算，不进引擎）」业务块（R103 裁定建的前端派生读数），按本 spec
的硬标准整体违背。用户裁定：**全部移除**。

已移除：
- 输入（不进 payload）：抵押物成交价、评估价、月可支配收入、其他月债务支出
- 前端派生读数与仪表：抵押物价值（孰低）、贷款成数（LTV）、首付比例、月房贷支出/收入比（DTI ≤50%）、
  月所有债务支出/收入比（≤60%）、合规/接近上限/超限/无法判断四态徽章与阈值配色
- 前端闸门：首付 ≥15% 阻断提交、抵押物价值 ≤0 阻断
- 文件：MortgageIncomePanel.vue、ratioStatus.ts（+spec）整体删除；utils/ratio.ts 裁掉
  collateralValue/loanToValue/downPaymentRatio/debtToIncomeRatio/allDebtToIncomeRatio/
  exceedsLimit/belowFloor/downPaymentFloorBlocked 与三个阈值常量（保留十进制算术与
  pickMonthlyPayment——月供读数仍由摊销表派生）
- i18n：mortgage.collateral / income(收入部分) / status / gates.downPaymentFloor* /
  errors.transactionPrice+appraisalValue 全删；income 块收窄为 payment 块（月供读数文案保留）
- workbench.css/.spec：阈值配色 token 与 .ratio-* 读数区删除，保留 block-card/compact-form/借款方分界
- 注释残留：CalculationForm（币种头注、税率）、MortgageForm（CNY、税率、重置注释）、
  RuleRowsEditor（增值税率）、mortgageRules 死校验规则及其 spec、format/ratio 注释

保留（属测算结果，过 M-02）：月供读数（月供/到期一次性偿还额标签 + 口径说明）由本次摊销表派生；
印花税（R149）；引擎侧 MortgageThresholdValidator（A2，输入字段本身参与测算）。

披露的后果：首付 ≥15% 与 DTI 50%/60% 在系统内不再有任何执行点（前端闸门随读数移除，房贷
payload 不携带 assetValue/downPayment，引擎无从判定）——合规防线消失，已在本文件显式记录。

验证：vue-tsc+vite build 通过；vitest 217 全绿；eslint 干净；语义扫描（抵押/评估价/成交价/LTV/
杠杆/DTI/收入比/相关标识符）仅剩披露性注释命中。
