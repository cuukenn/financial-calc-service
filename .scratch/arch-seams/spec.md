# arch-seams —— facade 两处轻微渗漏的收口决策（待裁定）

Status: proposed（2026-11-14 架构检查裁定为「观察，不构成缺陷」；用户 2026-11-14 同意立项记录，是否收口待口径裁定）

## 背景

2026-11-14 全库设计模式与高内聚低耦合检查结论：遵循度高，模块边界（JPMS + ArchUnit）、依赖方向、SPI 一致性均无问题。仅发现两处轻微渗漏，均为观察项；按 AGENTS.md §6 规则 3 属**口径相邻区**——先裁定标准，再动代码。

## 渗漏点 1：memo 字符串成为跨模块隐式接口

- 位置：`calc-engine/src/main/java/io/github/cuukenn/fincalc/engine/CalculationFacade.java:302`
- 现状：engine 调 `RuleDrivenGenerator.isFallbackMemo(installment.memo())`（calc-rule 实现类静态方法），读 `Installment.memo()` 字符串编码判断 fallback 期。
- 风险：memo 格式若变化，engine 静默失配——WARN 期次漏报，无编译期或测试期信号。
- 候选收口：fallback 状态走显式 `ValidationIssue`（rule 层直接报），或 `Installment` 增显式标记字段。后者触及 §3.3 输出要素，需裁定。

## 渗漏点 2：CAS 21 净投资口径判断落在 facade

- 位置：`CalculationFacade.unrecoveredResiduals`（:371）/ `netInvestmentPeriodIrr`（:389）
- 现状：「留购价/担保余值/未担保余值如何进入终端净投资」的 CAS 21 第三十八/三十九条口径判断写在 engine；`EffectiveInterestCalculator`（domain/support）只算不裁。
- 现状背书：R52 裁定 + 类文档详尽，风险可控。
- 候选收口：口径判断下沉 domain/support（如 `EffectiveInterestCalculator` 的输入装配），facade 只传 element 与 schedule。属引擎流程结构性改动，须先裁定再动。

## 裁定请求

逐点裁定：收口 or 维持现状（维持现状需写明理由入本文件 Comments）。

## Comments

### 2026-11-14 裁定与实施

- **裁定**（用户当天确认）：两处都收口；点 1 走「规则层直接报 issue」路线。
- **实施**：
  - 点 1：fallback WARN 由 `RuleDrivenGenerator` 直接产出（新码 **FCS-RULE-2009**，`RuleErrorCode`），engine 删除 memo 扫描与 `isFallbackMemo`；`EngineErrorCode.RULE_FALLBACK_PERIOD`（FCS-ENG-1006）删除。memo 仍按 R15 写入（§3.3 备注列不变）。fallback 期次在 `allocate` 记录（未匹配 outcome，先于模板的 memo 后写），`generate` 覆写追加 WARN 后重建 Schedule。生成器实例按 `withRuleDriven` 每 run 新建，javadoc 写明单 run 契约。
  - 点 2：`unrecoveredResiduals(LeaseElement)`（去死参数 schedule）与 `netInvestmentPeriodIrr(Schedule, extra, PeriodicRootHandoff)` 逐字迁入 `EffectiveInterestCalculator`（R52/N-05 javadoc 随迁）；facade 只留编排与 WARN 报告。
- **披露的行为变化**（均非金额类）：
  1. issue code `FCS-ENG-1006` → `FCS-RULE-2009`（前缀随产出方；web 前端不按 code 匹配，已核实）。
  2. `schedule.issues()` 顺序：fallback WARN 移到 generator 产出位（validator 发现之前），REST JSON 数组顺序变化，无测试钉顺序。
  3. ~~stub 拼接漏报修复~~ **更正**：原判「period-1 fallback + 首期 stub 会因 memo 拼接漏发 WARN」经查不可达——`LeaseElement` 校验本就拒绝 `RULE_DRIVEN + firstPeriodStub`（规则驱动租金解算不消费 stub），故无此行为变化；收口收益是 fallback 判定从字符串比较改为类型化 outcome 记录。
- **验证**：`mvn -o test`（JDK 17）全绿，6 模块 510 测试；N-04（43 位 eir 钉、行分钉）、N-05（2/3 次求解钉）、N-01（RunCostBaselineTest）、ArchUnit 均通过。
- **约束遵守**：N-03 未动任何求解参数；N-05 无新增求根（搬运保持 rootFor 先于 solve）；N-06 码集未裁剪。

## Constraints

- 任何改动遵守 AGENTS.md §6：N-03 精度不下调、N-04 数值逐位一致、N-05 不新增重复求根、N-01 闸门测试通过。
- 渗漏点 2 的下沉不得改变 §9.7 产出口径与数值（N-06）。
