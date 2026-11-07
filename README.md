# financial-calc-service

融资租赁/贷款核心计算引擎（Plan 1：核心计算内核）。纯 Java、无框架依赖、JSR-310 时间、
`BigDecimal` 精确算术，支持四种内置还款算法与规则驱动的租金生成（Rule DSL + Excel 兼容函数库），
并输出设计文档 §9.5 定义的全部 21 项金融指标与 §9.7 实际利率法摊销表。

## 环境要求

- JDK 11+（构建目标 `release=11`，无 text blocks 等 11+ 语法）
- Maven 3.8+

## 模块说明

| 模块 | JPMS 模块名 | 职责 |
|---|---|---|
| `calc-bom` | — | 依赖版本管理（BOM），无代码 |
| `calc-common` | `com.fincalc.common` | 共享原语：金额/日期时间、错误码、扩展点 SPI（`ExtensionRegistry`） |
| `calc-domain` | `com.fincalc.domain` | 领域模型（`LeaseElement`/`Schedule` 等）、端口（`ScheduleGenerator`、`DayCountConvention`…）、四种内置算法、现金流、指标 SPI 与内置指标 |
| `calc-rule` | `com.fincalc.rule` | 规则引擎：表达式解析求值、规则编译、`.fcs` 函数脚本、80 个 Excel 兼容函数 |
| `calc-engine` | `com.fincalc.engine` | 编排门面 `CalculationFacade`：校验 → 生成 → 现金流 → 指标 → 实际利率表 |
| `calc-testkit` | — | 测试工具：黄金参考表 `ReferenceTables` 与测试夹具 `Fixtures` |

作为 JPMS 消费方，在你的 `module-info.java` 中声明：

```java
module your.app {
    requires com.fincalc.engine;
    requires com.fincalc.domain;   // API 类型（LeaseElement、Schedule…）在 domain 模块
}
```

## 快速开始

```java
CalculationResult r = CalculationFacade.defaults().calculate(
        new CalculationRequest(leaseElement, null, CalculationOptions.defaults()));
Schedule schedule = r.schedule();                       // 12 期租金表（含校验问题列表）
BigDecimal totalRent = schedule.header().metrics()      // 21 项金融指标（TOTAL_RENT、IRR…）
        .find(MetricCodes.TOTAL_RENT);
EffectiveInterestTable eir = r.effectiveInterest();     // §9.7 实际利率法摊销表（可能为 null）
```

`CalculationRequest(element, ruleSet, options)` 中 `ruleSet` 仅在 `method = RULE_DRIVEN` 时必填，
其余算法传 `null` 即按要素的名义利率生成；`options` 传 `null` 等价于
`CalculationOptions.defaults()`（不放宽规则、利率变动 RESOLVE、现金流含费用）。

## 还款算法 code 对照表

| code | 含义 |
|---|---|
| `EQUAL_INSTALLMENT` | 等额租金（年金），末期尾差归入最后一期 |
| `EQUAL_PRINCIPAL` | 等额本金 |
| `INTEREST_FIRST` | 先息后本（前 n−1 期付息，末期还本付息） |
| `PRINCIPAL_FIRST` | 先本后息（每期还 P/n，利息末期支付） |
| `RULE_DRIVEN` | 规则驱动：按编译后的规则集逐期解析租金/利率/本金计划（必须携带 ruleSet） |

## 扩展点

全部端口与默认实现见设计文档 **§12 扩展点清单（端口）**
（`docs/superpowers/specs/2026-11-02-financial-calc-service-design.md`）：
`ScheduleGenerator`、`DayCountConvention`、`DateRollConvention`、`RoundingPolicy`/`TailAdjuster`、
`FinancialMetric`、`FormulaFunction`、`CashFlowBuilder`、`Validator`、`ClockProvider` 等 20 个端口。

## 如何新增一种算法

1. 在 `calc-domain`（或你自己的模块）实现 `ScheduleGenerator`，通常继承
   `AbstractAmortizationGenerator`，只覆写 `code()`、`order()`、`supports(...)`、
   `validate(...)`、`prepare(...)` 等钩子（参考 `EqualInstallmentGenerator`）；
2. 注册到引擎使用的注册表：`GeneratorRegistry.defaults().register(new MyGenerator())`
   （或用 `ExtensionRegistry.empty()` 自组一套）。同 code 后注册者覆盖先注册者；
3. 把 `LeaseElement.method` 指到你的 code，`CalculationFacade` 按 `code()` 路由。

注意：v1 的方法标识 `RepaymentMethodId` 是封闭枚举，自定义生成器只能注册到既有 code
（覆盖内置实现），或经 `GeneratorRegistry` 显式注册用于测试/内嵌场景；开放自定义 method id
是 Plan 2 的设计决策。

注册基础设施也支持 `ServiceLoader`（`ExtensionRegistry.load(Class)`，JPMS 下自动
`Module.addUses` 声明服务依赖），但内置算法一律显式注册；如需走 ServiceLoader，请在
`META-INF/services/` 提供提供方文件，并在 JPMS 模块里声明对应的 `uses`/`provides`。

## 黄金基线（对账参考）

Task-30 固化的租金表与指标黄金基线位于 `calc-testkit` 的
`com.fincalc.testkit.ReferenceTables`（如标准租约：本金 1 000 000、6% 名义年利率、
THIRTY_360、12 期月付期末付，`TOTAL_RENT = 1 032 797.15`）。改动算法或舍入逻辑前先跑
`calc-testkit` 的黄金测试，防止基线无意识漂移。

## 构建

```bash
mvn -q test          # 全模块测试
mvn -DskipTests package
```

## 设计文档

- 设计与规格：`docs/superpowers/specs/2026-11-02-financial-calc-service-design.md`
  （实现与设计的偏差记录见文末「实现偏差记录」一节）
- 任务分解与报告：`.superpowers/sdd/2026-11-02-core-calculation-engine/`
