# financial-calc-service

融资租赁/贷款核心计算引擎（Plan 1：核心计算内核）。纯 Java、无框架依赖、JSR-310 时间、
`BigDecimal` 精确算术，支持五种内置还款算法与规则驱动的租金生成（Rule DSL + Excel 兼容函数库），
并输出设计文档 §9.5 定义的全部 21 项金融指标与 §9.7 实际利率法摊销表。

## 环境要求

- JDK 11+（构建目标 `release=11`，无 text blocks 等 11+ 语法）
- Maven 3.8+

## 模块说明

| 模块 | JPMS 模块名 | 职责 |
|---|---|---|
| `calc-bom` | — | 依赖版本管理（BOM），无代码 |
| `calc-common` | `io.github.cuukenn.fincalc.common` | 共享原语：金额/日期时间、错误码、扩展点 SPI（`ExtensionRegistry`） |
| `calc-domain` | `io.github.cuukenn.fincalc.domain` | 领域模型（`LeaseElement`/`Schedule` 等）、端口（`ScheduleGenerator`、`DayCountConvention`…）、五种内置算法、现金流、指标 SPI 与内置指标 |
| `calc-rule` | `io.github.cuukenn.fincalc.rule` | 规则引擎：表达式解析求值、规则编译、`.fcs` 函数脚本、80 个 Excel 兼容函数 |
| `calc-engine` | `io.github.cuukenn.fincalc.engine` | 编排门面 `CalculationFacade`：校验 → 生成 → 现金流 → 指标 → 实际利率表 |
| `calc-testkit` | — | 测试工具：黄金参考表 `ReferenceTables` 与测试夹具 `Fixtures` |

作为 JPMS 消费方，在你的 `module-info.java` 中声明：

```java
module your.app {
    requires io.github.cuukenn.fincalc.engine;
    requires io.github.cuukenn.fincalc.domain;   // API 类型（LeaseElement、Schedule…）在 domain 模块
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
| `EXPLICIT_RENT` | 显式租金（§8.1 `04_现金流` 映射点）：租金给定反推本息——`LeaseElement.explicitRents` 直传逐期租金，利息 = 期初余额 × 期利率，本金 = 租金 − 利息，不钳制末期（不收敛由校验器 3001/3002 报错，R69）；Plan 2 的 `04_现金流` sheet 由此接入 |
| `RULE_DRIVEN` | 规则驱动：按编译后的规则集逐期解析租金/利率/本金计划（必须携带 ruleSet） |

### 分段利率（rateChangePolicy）

内置算法消费规则集费率：请求携带 `ruleSet` 时，各内置生成器逐期经 `RuleSetView` 解析期利率
（R70；R17 的均匀费率守卫仅在无规则集时生效）。等额本息受 `rateChangePolicy` 三态控制：
`RESOLVE`（默认，段初按「段初余额 + 剩余期数 + 段利率」重解 PMT，solver
`SEGMENTED_RESOLVE`）、`FIXED_RENT`（全程租金不变，费率只改本息拆分）、`DRIFT`（逐期重解）；
其余算法本金计划与费率解耦，天然支持分段。

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
`io.github.cuukenn.fincalc.testkit.ReferenceTables`（如标准租约：本金 1 000 000、6% 名义年利率、
THIRTY_360、12 期月付期末付，`TOTAL_RENT = 1 032 797.15`）。改动算法或舍入逻辑前先跑
`calc-testkit` 的黄金测试，防止基线无意识漂移。

## REST API（calc-server）

`calc-server` 是 Spring Boot 2.7.18 薄壳（Plan 2），把 `CalculationFacade` 暴露为 JSON API；
依赖版本集中于 `calc-bom`（R78）。机器可读契约见 OpenAPI 文档 `GET /v3/api-docs`
（springdoc-openapi 1.7.x，DTO 层 `@Schema` 注解；核心模块零注解，R76）。

### POST /api/v1/calculations

最小正路请求（12 期标准租约黄金基线）：

```bash
curl -s -X POST http://localhost:8080/api/v1/calculations \
  -H 'Content-Type: application/json' \
  -d '{
    "principal": 1000000,
    "nominalRate": 0.06,
    "commenceDate": "2024-01-01",
    "firstRentDate": "2024-02-01",
    "periods": 12,
    "frequency": "MONTHLY",
    "timing": "IN_ARREARS",
    "dayCountCode": "THIRTY_360",
    "dateRollCode": "NONE",
    "residualValue": 0,
    "residualHandling": "SEPARATE",
    "deposit": 0,
    "vatRate": 0.06,
    "method": "EQUAL_INSTALLMENT",
    "currency": "CNY",
    "rateChangePolicy": "RESOLVE"
  }'
```

正路返回 HTTP 200；`$.schedule.header.metrics.TOTAL_RENT` 应为 **`"1032797.15"`**
（11 × 86 066.43 + 末期调整 86 066.42，Task-21 修正后的黄金基线）。

响应约定（money-as-string）：所有金额与利率一律为 JSON **字符串**（`BigDecimal.toPlainString()`），
避免客户端按二进制浮点解析破坏精确小数；期数/天数/序号等整数为 JSON 数值；日期为 ISO-8601
字符串；不可计算的指标序列化为显式 `null`（字段不省略）。请求字段与枚举取值的完整说明见
`/v3/api-docs` 的 `CalculationRequestDto` schema。

### 错误体契约

所有失败返回统一错误体 `{code, message}`（R77），无其他形态：

| 场景 | HTTP | code |
|---|---|---|
| 业务失败（领域校验 `FCS-DOM-1xxx`、规则编译 `FCS-RULE-2xxx`/`FCS-COM-0001`、编排 `FCS-ENG-1002` 未知的还款方式 / `FCS-ENG-1003` 规则驱动缺规则集、指标 `FCS-CALC-3xxx`） | 400 | 引擎/领域/规则 FCS 码 |
| 请求体 JSON 非法或字段绑定失败 | 400 | `FCS-SRV-1001` |
| 已注册路径上无此 HTTP 方法（如对 `GET /api/v1/calculations`） | 405 | `FCS-SRV-1003`（消息含请求方法） |
| 请求 Content-Type 无匹配的处理方（如 POST 带 `text/plain`） | 415 | `FCS-SRV-1004`（消息含媒体类型） |
| 请求路径不存在 | 404 | `FCS-SRV-1005`（消息含请求路径） |
| `SystemException` 与一切未捕获异常 | 500 | `FCS-SRV-1002`（消息不含内部细节） |

错误码清单以设计文档 **§B 错误码草案** 为基础（`docs/superpowers/specs/2026-11-02-financial-calc-service-design.md`），
加上引擎实际扩充（如 `FCS-CALC-3006/3007`，见文末「实现偏差记录」勘误节）。

### GET /api/v1/algorithms

```bash
curl -s http://localhost:8080/api/v1/algorithms
```

返回全部还款方式 code 及中文说明：`[{"code": "EQUAL_INSTALLMENT", "description": "…"}, …]`
（含 `EXPLICIT_RENT`）。code → 说明映射手写于 `AlgorithmController`（领域枚举不携带注解，R76），
与上文「还款算法 code 对照表」一致。

### 范围说明（v1）

Excel 适配与持久化/版本链已延后（设计文档文末「实现偏差记录」勘误，2026-11-07）：出现真实
Excel 用户或持久化消费方时再按最小适配器立项；`04_现金流` → `LeaseElement.explicitRents`
映射点不变。v1 现金流口径固定为 INCLUDE_FEES（含费用、不含保证金收付）；
INCLUDE_DEPOSIT/CONTRACT 待 Plan 3 暴露为请求字段。v1 仅含基础资源限制（R79），无鉴权体系。

## 前端（web/）

仓库内可视化测算前端：Vue 3 + TypeScript + Ant Design Vue 4 + ECharts 单页应用（R80），
经 Vite dev server 代理 `/api` → `http://localhost:8080` 消费 calc-server（R81，后端不加 CORS）。
含测算表单（黄金要素默认值）、指标 Statistic 卡、16 列摊销计划表、构成堆叠柱图与余额曲线。
启动与测试/构建命令见 `web/README.md`：

```bash
cd web && npm install && npm run dev    # 前置：calc-server 运行于 8080
```

## 构建

```bash
mvn -q test          # 全模块测试
mvn -DskipTests package
```

## 设计文档

- 设计与规格：`docs/superpowers/specs/2026-11-02-financial-calc-service-design.md`
  （实现与设计的偏差记录见文末「实现偏差记录」一节）
- 任务分解与报告：`.superpowers/sdd/2026-11-02-core-calculation-engine/`
