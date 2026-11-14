# financial-calc-service

融资租赁 / 个人住房贷款核心计算引擎：纯 Java、`BigDecimal` 精确算术、无框架依赖的内核，
配 Spring Boot JSON API 与 Vue 3 可视化前端。支持六种内置还款算法与规则驱动的租金生成
（Rule DSL + 80 个 Excel 兼容函数），输出 26 项金融指标（含借款方视角 4 项）与 §9.7
实际利率法摊销表。

## 特性一览

- **双场景同一引擎**：租赁测算与房贷计算器跑在同一个 `POST /api/v1/calculations` 契约上，房贷只是一次参数映射
- **六种还款算法 + 规则驱动**：等额本息 / 等额本金 / 先息后本 / 先本后息 / 到期一次还本付息 / 显式租金 / RULE_DRIVEN（分段利率、本金计划、条件表达式）
- **26 项指标**：`TOTAL_*`、`IRR`/`XIRR`/`MIRR`、久期/凸性、回收期、租赁内含利率（CAS 21）、借款方视角 `BORROWER_*`
- **一体化部署**：前端 dist 直接打进 Spring Boot jar 的静态资源，一个容器一个端口跑全部
- **精确口径**：金额一律字符串传输（`toPlainString`），先明细求和再舍入，不可计算的指标显式 `null` 不给 0

## 快速开始

### 一键部署（推荐）

```bash
docker compose up -d --build      # 构建 + 启动单容器（含前端界面）
# 工作台：http://localhost:8080/    API：http://localhost:8080/api/v1/...
docker compose down
```

### 本地运行

```bash
mvn -DskipTests package                                   # 后端 jar（不含前端界面）
java -jar calc-server/target/calc-server-*.jar            # API 于 8080
cd web && npm install && npm run dev                      # 前端 dev server（代理 /api → 8080）
```

### 作为库使用

```java
CalculationResult r = CalculationFacade.defaults().calculate(
        new CalculationRequest(leaseElement, null, CalculationOptions.defaults()));
Schedule schedule = r.schedule();                       // 租金表（含校验问题列表）
BigDecimal totalRent = schedule.header().metrics()      // 指标（TOTAL_RENT、IRR…）
        .find(MetricCodes.TOTAL_RENT);
EffectiveInterestTable eir = r.effectiveInterest();     // §9.7 实际利率法摊销表（可能为 null）
```

`ruleSet` 仅在 `method = RULE_DRIVEN` 时必填；`options` 传 `null` 等价于默认
（不放宽规则、利率变动 RESOLVE、现金流含费用）。作为 JPMS 消费方声明
`requires io.github.cuukenn.fincalc.engine;` 与 `requires io.github.cuukenn.fincalc.domain;`。

## 模块说明

| 模块 | 职责 |
|---|---|
| `calc-bom` | 依赖版本管理（BOM），无代码 |
| `calc-common` | 共享原语：金额/日期时间、错误码、扩展点 SPI |
| `calc-domain` | 领域模型（`LeaseElement`/`Schedule`）、端口、六种内置算法、现金流、指标 SPI 与内置指标（框架无关，零日志） |
| `calc-rule` | 规则引擎：表达式解析求值、规则编译、`.fcs` 函数脚本、80 个 Excel 兼容函数 |
| `calc-engine` | 编排门面 `CalculationFacade`：校验 → 生成 → 现金流 → 指标 → 实际利率表（核心日志在此） |
| `calc-server` | Spring Boot 薄壳：JSON API + 前端静态资源托管 |
| `calc-testkit` | 黄金参考表与测试夹具 |
| `web/` | Vue 3 + TypeScript + Ant Design Vue 4 + ECharts 前端 |

## 还款算法 code

| code | 含义 |
|---|---|
| `EQUAL_INSTALLMENT` | 等额本息（年金），末期尾差归入最后一期 |
| `EQUAL_PRINCIPAL` | 等额本金 |
| `INTEREST_FIRST` | 先息后本 |
| `PRINCIPAL_FIRST` | 先本后息 |
| `BULLET_LOAN` | 到期一次还本付息：每期照常计提利息（递延、不复利、不进余额），到期一次性结清本金与累计递延利息。引擎接受到 1200 期上限；「≤ 12 期才可到期一次还本付息」是房贷前端闸门，不是引擎校验 |
| `EXPLICIT_RENT` | 显式租金：租金给定反推本息，不收敛由校验器报错 |
| `RULE_DRIVEN` | 规则驱动：按编译后的规则集逐期解析租金/利率/本金计划（必须携带 ruleSet） |

内置算法消费规则集费率即可分段计息；等额本息另受 `rateChangePolicy` 三态控制：
`RESOLVE`（默认，段初重解 PMT）/ `FIXED_RENT`（租金不变只改本息拆分）/ `DRIFT`（逐期重解）。

## REST API

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
    "rateChangePolicy": "RESOLVE"
  }'
```

正路返回 200，`$.schedule.header.metrics.TOTAL_RENT` 应为 **`"1032797.15"`**（黄金基线）。
所有金额与利率一律为 JSON **字符串**；不可计算的指标序列化为显式 `null`；完整字段说明见
`GET /v3/api-docs`（OpenAPI）。还款方式清单见 `GET /api/v1/algorithms`。

错误体统一为 `{code, message}`：

| 场景 | HTTP | code |
|---|---|---|
| 业务失败（领域 `FCS-DOM-1xxx`、规则 `FCS-RULE-*`、编排 `FCS-ENG-*`、指标 `FCS-CALC-3xxx`） | 400 | 对应 FCS 码 |
| 请求体非法 / 无处理方法 / 媒体类型不匹配 / 路径不存在 | 400/405/415/404 | `FCS-SRV-1001/1003/1004/1005` |
| 系统异常与未捕获异常 | 500 | `FCS-SRV-1002`（不含内部细节） |

### CAS 21 计量链（选填）

同时发送 `assetValue`、`downPayment`、`initialDirectCosts`、`guaranteedResidual`、
`unguaranteedResidual` 五项时，引擎按计量链工作：

```
资产价值 + 初始直接费用 = PV(租赁收款额) + PV(未担保余值)
融资额 = 资产价值 + 初始直接费用 − 首付款（= principal，容差 0.01 元）
```

`guaranteedResidual` 进现金流；`unguaranteedResidual` 不进现金流、只进内含利率方程。
无未担保余值时 `LEASE_IMPLICIT_RATE` 与 `IRR_ANNUAL` 恒等；内含利率是**计量结果**而非
定价输入，宣告残值后它高于名义利率是 CAS 21 预期结果，引擎不为凑等而重解租金。

## 指标口径要点

- **借款方视角**：`BORROWER_*` 4 项把现金流方向整体翻转后读取；方向翻转不移动 IRR 零点，
  所以 `BORROWER_IRR_*` 与出租方 `IRR_*` **数值逐位相等**——它们是视角标注，不是另一套定价利率。
- **零额租金挤稀现金流的年化陷阱**：`BULLET_LOAN` 现金流只有放款+到期两条，`IRR_ANNUAL`
  会把多期间隔当成一期而严重失真（如 3.5% 的一年期到期还本付息被显示成 ~51%）。年度成本
  请用 `XIRR` / `TRUE_ANNUAL_RATE`（按日折现）。
- **`IRREGULAR` 付租没有年化口径**：需要期/年换算的指标（`IRR_ANNUAL`、`MIRR`、久期族、
  回收期族等）显式 `null`，并附一条 `FCS-ENG-1008` WARN——这不是算不出来，而是年化单位
  在这笔测算里不存在。周期/按日口径的 `IRR_PERIOD`、`XIRR`、`XNPV`、`TRUE_ANNUAL_RATE` 照常输出。
- **印花税（租赁场景）**：结果区独立小节，计税依据 = `TOTAL_RENT − TOTAL_TAX`，税率
  0.005%（《印花税法》融资租赁合同独立税目）。不进摊销表、不进 IRR、不与 Σ租金相加。
  黄金基线：依据 1 032 797.15 → 印花税 **51.64 元**。房贷场景不显示（税目不同）。

## 前端两个场景

页内两个 Tab（租赁测算 / 房贷计算器）共用同一个引擎与 `ResultPanel`。房贷 Tab 提交
恰好 14 个键（13 个要素键 + `product = "MORTGAGE"`）：

| 房贷字段 | 引擎参数 |
|---|---|
| 贷款金额 | `principal` |
| 期限（月，≤ 480 前端闸门；引擎上限 1200） | `periods` |
| 当前 LPR + 加点（加点按 5bp 步长校验） | `nominalRate`（前端合成年化小数） |
| 放款日 / 首期还款日 | `commenceDate` / `firstRentDate` |
| 还款方式（等额本息 / 等额本金 / 到期一次还本付息 ≤12 期） | `method` |
| —（固定口径） | `frequency=MONTHLY`、`timing=IN_ARREARS`、`dayCountCode=THIRTY_360`、`dateRollCode=NONE` |
| —（引擎必填要素，显式发送） | `residualValue="0"`、`residualHandling="SEPARATE"`、`rateChangePolicy="RESOLVE"` |
| 重定价周期 + 重定价日 + 后续各段 LPR | 编译为分段利率规则行 `ruleRows` |
| 产品身份 | `product="MORTGAGE"`（仅选择校验链，内核产品盲） |

前端闸门（期限 ≤ 480、还款方式规则）不是引擎校验；监管阈值披露由引擎以 WARN 读数给出。

## 日志

四级按语义分工（框架：SLF4J → logback，`application.yml` 默认 `INFO`）：

- **INFO**：测算开始/结束（runId、耗时、期数、issue 数）、HTTP 请求完成、启动装配摘要
- **DEBUG**：引擎步骤装配明细（口径解析、生成器、现金流事件数、失败指标数）、客户端请求格式误用
- **WARN**：业务 400 拒绝、指标不可算、IRR 不唯一、§9.7 拒绝路径（均带 FCS 码；口径以
  响应体的 ValidationIssue 为准，日志只做旁路观测）
- **ERROR**：系统异常与未捕获异常（带堆栈）

领域模块（calc-domain）保持零日志。排查引擎细节时把
`logging.level.io.github.cuukenn.fincalc` 调到 `DEBUG`。

## 构建与发布

```bash
mvn -o test                     # 全模块测试（JDK ≥ 17；6 模块 510 个测试）
mvn -DskipTests package         # 打包（纯后端 jar）
docker compose up -d --build    # 一体化镜像（jar 自带前端界面，分层构建）
```

打 tag 自动发版：`git tag vX.Y.Z && git push origin vX.Y.Z` → GitHub Release 自动附上
**一体化 jar**（含前端界面）与部署文件（compose + Dockerfile）。

## 已知缺陷

- **D-2 递延型现金流的 IRR 年化失真**（未修）：`BULLET_LOAN` 等稀疏现金流上 `IRR_ANNUAL`
  无参考价值，用 `XIRR` / `TRUE_ANNUAL_RATE` 替代（见上文「指标口径要点」）。

## 设计文档

- 设计与规格：`docs/superpowers/specs/2026-11-02-financial-calc-service-design.md`
- 租赁计量标准：`docs/superpowers/specs/2026-11-11-lease-measurement-standard.md`
- ADR：`docs/adr/`（如 0001 内含利率是计量输出）
- 任务分解与报告：`.superpowers/sdd/`
