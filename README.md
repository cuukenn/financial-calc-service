# financial-calc-service

> 融资租赁 / 个人住房贷款测算引擎 —— 精确到分、一套契约、开箱即部署。

融资租赁 / 个人住房贷款的测算引擎：给定合同要素（本金、利率、期限、频率、残值、保证金、费用……），
产出逐期摊销表、现金流事件表、26 项金融指标与实际利率法摊销表。纯 Java 内核（`BigDecimal`
精确算术、无框架依赖）+ Spring Boot JSON API + Vue 3 可视化前端，前端与 API 打在同一个 jar 里。

## 特性

- **一套契约，两类业务**：租赁测算与房贷计算器是同一个引擎、同一个 API 的两种参数映射，监管阈值以 WARN 读数披露
- **七种还款方式**：等额本息 / 等额本金 / 先息后本 / 先本后息 / 到期一次还本付息 / 显式租金 / 规则驱动（Rule DSL + 80 个 Excel 兼容函数）
- **26 项指标**：`TOTAL_*`、`IRR`/`XIRR`/`MIRR`、久期/凸性、回收期、租赁内含利率（CAS 21）、借款方视角 `BORROWER_*`
- **精确到分，算不了就说**：全程 `BigDecimal`、先明细求和再舍入、尾差带符号披露；指标不可算时输出显式 `null` 并附 WARN，绝不静默给 0
- **全链路可扩展**：还款算法、指标、校验器、计息基准、日期调整、尾差策略、公式函数全部经统一注册表按 code 装配
- **质量闸门**：525 个测试含性能闸门（120/480/1200 期 + 400 段），逐位一致回归先红再修

## 快速开始

```bash
docker compose up -d --build      # 单容器：前端 + API 同端口
# 工作台：http://localhost:8080/
docker compose down
```

前端无需单独部署：构建期 dist 直接打进 Spring Boot jar 的静态资源。

## 使用

### 测算 API

```bash
curl -s -X POST http://localhost:8080/api/v1/calculations \
  -H 'Content-Type: application/json' \
  -d '{
    "principal": 1000000, "nominalRate": 0.06,
    "commenceDate": "2024-01-01", "firstRentDate": "2024-02-01",
    "periods": 12, "frequency": "MONTHLY", "timing": "IN_ARREARS",
    "dayCountCode": "THIRTY_360", "dateRollCode": "NONE",
    "residualValue": 0, "residualHandling": "SEPARATE",
    "rateChangePolicy": "RESOLVE"
  }'
```

- `$.schedule.header.metrics.TOTAL_RENT` 应为 **`"1032797.15"`**（黄金基线）
- 金额与利率一律为 JSON 字符串；不可计算的指标为显式 `null`
- 完整字段说明见 `GET /v3/api-docs`；还款方式清单见 `GET /api/v1/algorithms`
- 错误体统一为 `{code, message}`：业务失败 400（FCS 码），系统异常 500

### 作为 Java 库

```java
CalculationResult r = CalculationFacade.defaults().calculate(
        new CalculationRequest(leaseElement, null, CalculationOptions.defaults()));
BigDecimal totalRent = r.schedule().header().metrics().find(MetricCodes.TOTAL_RENT);
```

### 读结果的三个注意

1. **借款方视角指标**（`BORROWER_*`）与出租方 `IRR_*` 数值逐位相等——是视角标注，不是另一套利率。
2. **到期一次还本付息**的现金流只有两条，`IRR_ANNUAL` 会把多期当一期而严重失真——年度成本用 `XIRR` / `TRUE_ANNUAL_RATE`。
3. **印花税**（租赁场景）是合同税费读数：依据 = Σ租金 − 单列增值税，税率 0.005%；不进摊销表、不进 IRR。

### 求解参数调优（可选）

逼近次数、容差、求根区间等不写死：`NumericConfig` 提供全部默认值，特殊指标的收敛性调参经
`CalculationFacade.calculate(request, config)` 传入；默认路径行为不变。

## 构建

```bash
mvn -o test                                # 全模块测试（JDK ≥ 17）
mvn -DskipTests package                    # 纯 API jar（不含前端）
docker compose up -d --build               # 一体化镜像（jar 自带前端界面，分层构建）
```

发布：`git tag vX.Y.Z && git push origin vX.Y.Z`，GitHub Release 自动附一体化 jar 与部署文件。

## 文档

- 术语与规范名：[GLOSSARY.md](GLOSSARY.md)
- 架构技能（agent 用）：[.agents/fincalc-engine/](.agents/fincalc-engine/)
- 设计文档与决策记录：`docs/superpowers/specs/`、[docs/adr/](docs/adr/)
- 前端细节：[web/README.md](web/README.md)
