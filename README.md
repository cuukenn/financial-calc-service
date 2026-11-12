# financial-calc-service

融资租赁/贷款核心计算引擎（Plan 1：核心计算内核）。纯 Java、无框架依赖、JSR-310 时间、
`BigDecimal` 精确算术，支持六种内置还款算法与规则驱动的租金生成（Rule DSL + Excel 兼容函数库），
并输出设计文档 §9.5 定义的 21 项金融指标、R103 借款方视角的 4 项 `BORROWER_*` 指标与 §9.7 实际利率法摊销表。

## 环境要求

- JDK 11+（构建目标 `release=11`，无 text blocks 等 11+ 语法）
- Maven 3.8+

## 模块说明

| 模块 | JPMS 模块名 | 职责 |
|---|---|---|
| `calc-bom` | — | 依赖版本管理（BOM），无代码 |
| `calc-common` | `io.github.cuukenn.fincalc.common` | 共享原语：金额/日期时间、错误码、扩展点 SPI（`ExtensionRegistry`） |
| `calc-domain` | `io.github.cuukenn.fincalc.domain` | 领域模型（`LeaseElement`/`Schedule` 等）、端口（`ScheduleGenerator`、`DayCountConvention`…）、六种内置算法、现金流、指标 SPI 与内置指标 |
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
BigDecimal totalRent = schedule.header().metrics()      // 26 项金融指标（TOTAL_RENT、IRR…）
        .find(MetricCodes.TOTAL_RENT);
EffectiveInterestTable eir = r.effectiveInterest();     // §9.7 实际利率法摊销表（可能为 null）
```

`CalculationRequest(element, ruleSet, options)` 中 `ruleSet` 仅在 `method = RULE_DRIVEN` 时必填，
其余算法传 `null` 即按要素的名义利率生成；`options` 传 `null` 等价于
`CalculationOptions.defaults()`（不放宽规则、利率变动 RESOLVE、现金流含费用）。

### 指标口径：视角（R103）

`metrics` 中的 26 个 code 分三族，**同一包里共存、键不加前缀**：

- §9.5 的 21 项（`TOTAL_*`、`NPV`/`IRR*`、久期…）按出租方（lessor）视角读现金流，即 v1 口径；
- `LEASE_IMPLICIT_RATE`（CAS 21）：按 资产价值 + IDC 反解的年化内含利率；无未担保余值时与 `IRR_ANNUAL` 恒等；
- 借款方（承租方）视角的 4 项 `BORROWER_IRR_PERIOD`、`BORROWER_IRR_ANNUAL`、
  `BORROWER_IRR_ANNUAL_SIMPLE`、`BORROWER_TOTAL_REPAYMENT`：把同一份现金流的方向整体翻转
  （本金由流出变流入、租金由流入变流出）后读取。

**不是两套利率，而是同一个根。** 方向整体翻转只会让 NPV 变号，不移动它的零点，所以
`BORROWER_IRR_PERIOD / _ANNUAL / _ANNUAL_SIMPLE` 与 `IRR_PERIOD / IRR_ANNUAL /
IRR_ANNUAL_SIMPLE` **数值逐位相等**（测试里直接断言相等）。`BORROWER_*` 的 IRR 族是给
借款方视角做标注用的，不是另一套定价利率，不得据此判断融资成本不同。

`BORROWER_TOTAL_REPAYMENT`（scale 2）= **Σ rentDue + 单独结算的留购价**
（`ResidualHandling.SEPARATE` 时取 `residualValue`，`BALLOON_IN_RENT` 时为 0，因为留购价已在
租金内）。`rentDue` 本身已含本金、利息、费用与税，因此不再重复加 Σ fee / Σ tax；保证金是可
退还的 §9.4 现金流，不属于还款，也不计入。

视角由 `new CalculationRequest(element, ruleSet, options, CashFlowPerspective.LESSEE)` 选择，
缺省与三参数构造均为 `CashFlowPerspective.LESSOR`。**默认的 LESSOR 运行与 v1 完全一致**，
只是多出 4 个显式 `null` 的 `BORROWER_*` 条目（不可计算即为 `null`，字段不省略）；
`LESSEE` 运行下这 4 项才有值。视角只作用于指标层：计划表、现金流表、校验器一律不变（R103/R119）。

`BORROWER_*` 是**展示辅助指标**（[实]），不是监管指标，也不得与「租赁内含利率 / 增量借款利率」
等 §2.1 规范名混用。

**两族 IRR 年化共有的一个陷阱**：零额租金不生成现金流条目，所以现金流被挤稀的形态（典型是
`BULLET_LOAN`：放款与到期结清两条）里，周期 IRR 会把多期间隔当成一期，年化值随之失真——
出租方与借款方两族**同样**失真（它们本来就逐位相等）。这种形态下不得把 `IRR_ANNUAL` /
`BORROWER_IRR_ANNUAL*` 当成资金成本读数，年度成本请用 `XIRR` / `TRUE_ANNUAL_RATE`
（详见文末「已知缺陷 D-2」）。

**年化的前提是「一年几期」**（R148）：`IRREGULAR` 付租没有统一的年频率，引擎把它的
`periodsPerYear` 记为 0 而不是抬成 1。凡需要期/年换算的指标——`IRR_ANNUAL`、
`IRR_ANNUAL_SIMPLE`、`MIRR`、`PAYBACK_PERIOD`、`DISCOUNTED_PAYBACK`、`DURATION_MACAULAY`、
`DURATION_MODIFIED`、`CONVEXITY`、`LEASE_IMPLICIT_RATE`——一律显式 `null`；周期口径与按日口径的
`IRR_PERIOD`、`XIRR`、`XNPV`、`TRUE_ANNUAL_RATE` 与 §9.7 实际利率法表照常输出，26 个 code 一个不少。
每次这样的运行附一条 `FCS-ENG-1008` WARN：这不是「指标算不出来」，而是**年化单位在这笔测算里不存在**。
不规则付租的年度成本请用 `XIRR` / `TRUE_ANNUAL_RATE`（按日折现，不依赖期/年换算）。

## 还款算法 code 对照表

| code | 含义 |
|---|---|
| `EQUAL_INSTALLMENT` | 等额租金（年金），末期尾差归入最后一期 |
| `EQUAL_PRINCIPAL` | 等额本金 |
| `INTEREST_FIRST` | 先息后本（前 n−1 期付息，末期还本付息） |
| `PRINCIPAL_FIRST` | 先本后息（每期还 P/n，利息末期支付） |
| `BULLET_LOAN` | 到期一次还本付息：**每期照常计提利息**（`interestAccrued` 逐期产生，递延、不复利、不进余额，故余额线全程平于本金），期内每期本息实付为 0；到期日一次性结清本金与累计递延利息。引擎/API 对该方式接受到 1200 期上限（R102/R117）；金规〔2026〕5号「期限 > 1 年须按月还本付息 / ≤ 1 年可到期一次还本付息」的 ≤ 12 期限制是房贷前端闸门（R109），不是引擎校验 |
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

### CAS 21 计量链（选填）

在不发送 `assetValue` 的请求里，`principal` 就是融资额（既有口径）。同时发送下面五项时，引擎按
`docs/superpowers/specs/2026-11-11-lease-measurement-standard.md` 的计量链工作：

```
资产价值 + 初始直接费用 = PV(租赁收款额) + PV(未担保余值)
融资额   = 资产价值 + 初始直接费用 − 首付款        (= principal，容差 0.01 元，FCS-DOM-1016)
```

| 字段 | 现金流角色 |
|---|---|
| `assetValue` | t0 流出改为 `资产价值 + 初始直接费用`；也是 `LEASE_IMPLICIT_RATE` 的分母 |
| `downPayment` | t0 流入（属于租赁收款额）；单独发送会被拒：`FCS-DOM-1017` |
| `initialDirectCosts` | 计入 t0 流出；单独发送会被拒：`FCS-DOM-1017` |
| `guaranteedResidual` | 期末 `RESIDUAL` 流入，可独立于计量链发送 |
| `unguaranteedResidual` | **不进现金流**，只进 `LEASE_IMPLICIT_RATE` 方程；无 `assetValue` 时拒绝（`FCS-DOM-1017`） |

无未担保余值时 `LEASE_IMPLICIT_RATE` 与 `IRR_ANNUAL` 恒等（同一个根的两端写法）；有未担保余值
时两者分离。内含利率是**计量结果**而不是定价输入：`nominalRate` 只负责生成租金，宣告残值后内含
利率高于名义利率是 CAS 21 第三十九条 / IFRS 16.74–75 预期的结果，引擎**不**为使两者相等而重解
租金（见 `docs/adr/0001-lease-implicit-rate-is-a-measurement-output.md`）。`BORROWER_TOTAL_REPAYMENT`
把担保余值按**出租方全额**计入，CAS 21 第十八条（五）对承租方只计预计应付部分，故该读数是保守
口径（AGENTS.md §5 M-04）。

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
加上引擎实际扩充（如 `FCS-CALC-3006/3007` 与 `FCS-ENG-1005`–`1008`，见文末「实现偏差记录」勘误节）。
`FCS-ENG-1008` 是 WARN 级、不是失败：它说明这笔测算的**年化口径不存在**（`IRREGULAR` 付租没有
「一年几期」），相关的年化指标按 `null` 发出。

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
含测算表单（黄金要素默认值）、指标 Statistic 卡（悬浮显示指标目录的中文名/公式/说明，R86）、
16 列摊销计划表、构成堆叠柱图与余额曲线、现金流表（方向着色）与累计现金流回收曲线（R87）、
实际利率法摊销表；国际化 zh-CN/en-US 双语言，页头语言切换器（R85）。
启动与测试/构建命令见 `web/README.md`：

```bash
cd web && npm install && npm run dev    # 前置：calc-server 运行于 8080
```

### 场景说明：两个 Tab、同一个引擎（R100）

页内两个场景 Tab：**租赁测算** 与 **房贷计算器**（选中态记忆在 localStorage 的
`fcs.scenario`，回落默认 租赁测算；非活动面板只隐藏不销毁）。两个场景跑在**同一个引擎、
同一个 `POST /api/v1/calculations` 契约**上（R100）：房贷场景没有新端点、没有新 DTO、
没有新引擎路径，它只是一次参数映射；结果区（指标 / 摊销计划 / 现金流 / 实际利率法，
含借款方视角指标一节）两个场景完全共用同一个 `ResultPanel`。

房贷 Tab 的字段：贷款金额、贷款期限（月，≤ 480）、当前 LPR 与加点、放款日、首期还款日、
抵押物成交价、抵押物评估价、月可支配收入、其他月债务支出、还款方式（等额本息 / 等额本金 /
到期一次还本付息，最后一项仅在期限 ≤ 12 个月时可选，R109）。

**前端闸门不是引擎校验**（R101/R107/R144）：
- **首付比例 ≥ 15%** 是本场景唯一的**提交闸门** —— 低于 15%、或抵押物价值不可判定时，前端直接阻断提交，不发送请求。
- **DTI（月房贷支出/收入比 ≤ 50%、月所有债务支出/收入比 ≤ 60%）是仪表读数，不是提交闸门**：按 AGENTS.md §2.2 的阈值着色与标记（≤ 50% 合规、> 50% 接近上限、> 60% 超限；全部债务支出比值只有 ≤ 60% 合规 / > 60% 超限两档），提示风险但**不阻断提交**。
- 两者都在**未舍入原值**上比较（P-10），恰好达标不算超限（「以上」含本数）。
- 期限 480 个月是 UI 闸门，引擎上限仍是 1200 期（R104/R107）。引擎不校验这些监管比率，也没有任何房贷专属要素。

### 房贷字段 → 引擎参数映射（R101）

房贷 Tab 提交恰好 14 个 payload 键（`MortgageForm.buildRequest`），其余租赁专属键缺席：

| 房贷字段（前端） | 引擎参数 |
|---|---|
| 贷款金额 | `principal` |
| 贷款期限（月，≤ 480） | `periods`（1..480 是前端闸门；引擎上限 1200） |
| 当前 LPR + 加点 | `nominalRate`：前端把 LPR 与加点合成年化小数字符串，加点按 0.05 个百分点步长校验（P-04，不静默就近取整）；未建模重定价周期——合成出的那个利率就是全程名义利率 |
| 放款日 / 首期还款日 | `commenceDate` / `firstRentDate` |
| 还款方式 | `method`：`EQUAL_INSTALLMENT`（等额本息）/ `EQUAL_PRINCIPAL`（等额本金）/ `BULLET_LOAN`（到期一次还本付息，前端仅在 ≤ 12 期时开放） |
| —（固定） | `frequency = MONTHLY`、`timing = IN_ARREARS`、`dayCountCode = THIRTY_360`、`dateRollCode = NONE`、`currency = CNY`（P-03：30/360 使月利率 = 年利率 ÷ 12） |
| 抵押物成交价 / 评估价 | 不发送：抵押物价值 = 成交价与评估价孰低，只在前端算，驱动贷款成数（LTV）与首付比例 |
| 首付比例 ≥ 15% | 不发送：前端提交闸门（AGENTS.md §2.3） |
| 月可支配收入 / 其他月债务支出 | 不发送：前端 DTI 仪表的输入（50% / 60%） |
| 留购价（住房按揭没有这一项） | 显式发送 `residualValue = "0"` 与 `residualHandling = "SEPARATE"`：`SEPARATE` + 0 让 §3.4 校验一（Σ本金 = 合同本金）与校验二（末期余额 = 0）严格成立，不把任何留购价摊进租金 |
| 分段利率姿态 | 显式发送 `rateChangePolicy = "RESOLVE"`（重定价的既有姿态：自变更日重算）；仍未建模 3/6/12 个月的重定价周期 |
| 其余租赁专属要素 | `explicitRents` / `ruleRows` / `fees` / `deposit` / `vatRate` 一律不发送（缺席 = 该要素不存在；空串会被当成真实要素写进摊销表的费用/税列）。`gracePeriod` / `rounding` 缺席时由 `CalculationRequestAssembler` 补默认（`GracePeriod.none()` / `RoundingPolicy.defaults()`）；`residualHandling` 与 `rateChangePolicy` **不在**补默认之列 —— 它们是 `LeaseElement` 的必填要素（AGENTS.md §3.1：输入要素必须显式提供、缺失即报错、不得静默取默认值），缺席即 400 `FCS-COM-0001 … 必填`，所以必须由表单显式发送（R137，缺陷 D-1 的根因） |

## 已知缺陷（Task 7 全量验证实测发现，2026-11-09）

本节的编号 D-n 与报告 `.superpowers/sdd/2026-11-08-domain-expansion/task-7-report.md`（含
「Fix round 1」一节）对应。Task 7 首轮的范围是文档与验证，只登记不修；修复轮 1 按控制器裁决
修掉了 D-1 与 D-4，D-2 按 R135 留在引擎侧另案处理。

- **D-1 房贷 Tab 的 payload 被 REST 契约拒收 —— 已修复（Task 7 修复轮 1，R137）。**
  原现象：11 键提交 → HTTP 400 `FCS-COM-0001: 参数 residualHandling 非法: 必填`，补上后又缺
  `rateChangePolicy`。根因：`residualHandling` 与 `rateChangePolicy` 落在 `LeaseElement` 的
  `requireField` 必填清单里，而 `CalculationRequestAssembler` 只对 `rounding` / `gracePeriod` 补默认。
  修法在**前端**（R137：AGENTS.md §3.1 规定输入要素必须显式提供、缺失即报错、不得静默取默认值，
  所以不在 assembler 补默认）：`MortgageForm.buildRequest` 现在显式发送 `residualValue "0"` /
  `residualHandling "SEPARATE"` / `rateChangePolicy "RESOLVE"`，payload 11 → 14 键。契约由
  `calc-server` 的 `MortgagePayloadContractTest` 用**真实引擎**钉住（等额本息 360 期 200 且
  Σ本金守恒、末期余额 0.00；到期一次还本付息 12 期 200 且递延利息到期结清；少发必填要素仍 400）。
- **D-2 递延型现金流失稀 IRR 族的年化 —— 未修，owner 是引擎 §9.5 指标装配线（R135）。**
  `BULLET_LOAN` 的现金流表只有两条（放款 + 到期结清）：`StandardCashFlowBuilder.addFlow` 丢弃
  零额租金、`sorted()` 按列表序号重编号、`IrrCalculator.npv` 按 `flow.sequence()` 折现，于是
  12 期的间隔被当成 1 期。实测（2 380 000 @ 3.5%、12 期到期一次还本付息）
  `IRR_ANNUAL 0.511068951676`、`XIRR = TRUE_ANNUAL_RATE = 0.0350000168`（后者是正确值）。
  **房贷 Tab 的「IRR 年化」卡与 `BORROWER_IRR_*` 读数在这种稀疏现金流上没有参考价值**：
  一笔 3.5% 的一年期到期一次还本付息会被显示成 51.11%，核对年度成本请用 `XIRR` /
  `TRUE_ANNUAL_RATE`。稠密现金流（如等额本息、先本后息）不受影响。
- **D-4 `AlgorithmController` 的 `BULLET_LOAN` 说明与引擎口径矛盾 —— 已修复（R136，一行文案）。**
  服务端文案原写「期内不付本息」，与引擎实际的「每期计提利息 + 递延 + 到期结清」矛盾；
  现在服务端说明、README 对照表与 `MortgagePayloadContractTest` 三处同口径。code、错误码、
  `AlgorithmsApiTest` 的条目数契约均未动。

## 构建

```bash
mvn -q test          # 全模块测试
mvn -DskipTests package
```

## 设计文档

- 设计与规格：`docs/superpowers/specs/2026-11-02-financial-calc-service-design.md`
  （实现与设计的偏差记录见文末「实现偏差记录」一节）
- 任务分解与报告：`.superpowers/sdd/2026-11-02-core-calculation-engine/`
