# 核心测算引擎实施计划（Plan 1 / 3）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付一个零外部依赖、可直接嵌入的 Java 测算库：输入租赁要素（或规则集），输出逐期摊销计划、现金流与收益率。

**Architecture:** 六边形分层。`calc-common` 提供金额/日期/异常/SPI 基础设施；`calc-domain` 定义领域模型、端口与四种内置算法；`calc-rule` 把规则行编译成不可变 `CompiledRuleSet` 并实现规则驱动生成器；`calc-engine` 只做用例编排。本期不引入 Excel、数据库、Spring——它们各自是后续 Plan 2/3 的适配器。

**Tech Stack:** Java 11、Maven 多模块、JUnit 5、AssertJ、jqwik、ArchUnit、JMH（仅基准模块占位）。

**Spec:** `docs/superpowers/specs/2026-11-02-financial-calc-service-design.md`

## Global Constraints

- JDK 11；根 POM 设 `maven.compiler.release=11`，禁止使用 JDK 12+ API（无 record / sealed / switch 表达式）。
- 金额一律 `BigDecimal`；**禁止** `double` / `float` 参与任何金额或利率运算。
- 日期一律 `java.time.LocalDate`；禁止 `Date` / `Calendar` / `SimpleDateFormat`。
- 根包名 `com.fincalc`；Maven `groupId` 为 `com.fincalc`，版本 `0.1.0-SNAPSHOT`。
- `calc-common` 与 `calc-domain` **不得**依赖 Spring、POI、MyBatis、Jackson、Lombok 或任何第三方运行时库（JUnit/AssertJ 仅 `test` scope）。
- 错误码规范：`FCS-<模块>-<4位数字>`；业务异常抛 `BusinessException`，系统异常抛 `SystemException`。
- 领域对象不可变：`final class`、`final` 字段、私有构造 + `Builder`；集合字段用 `Collections.unmodifiableList` 包装。
- 金额输出 scale = 2、`RoundingMode.HALF_UP`；中间计算 scale = 10；利率/天数折算 scale = 12。
- 每个任务结束必须 `mvn -q test` 全绿后再提交；提交信息用 `feat:` / `test:` / `chore:` 前缀。
- 表达式引擎与函数库**禁止反射**；只允许白名单函数与白名单变量；脚本加载失败必须启动即失败（fail-fast），不允许静默降级。
- 外部脚本 `.fcs` 一律 UTF-8；脚本 hash 必须进入 `ruleSetHash`。
- 本期**不做**：提前还款、展期、多币种、Excel 解析、持久化、REST。

## Review Focus

按"最可能被真实输入击穿"排序，每条都必须在对应任务里有测试：

1. **零利率 / 单期 / 本金为零**——等额本息 PMT 分母为 0、`(1+i)^n − 1 = 0`；必须走退化分支而不是抛 `ArithmeticException`。
2. **闰年 2/29、月末起租（1/31）跨月**——期次边界与天数口径必须可预测，不能出现 2/30 或期次漂移。
3. **首期非整月 / 付款日早于起租日**——`PeriodBuilder` 必须保证 `start < end` 且日期严格单调，否则计息天数为负。
4. **极端舍入下的尾差**——12 期每期 `0.005` 元级别残差累加后，末期余额仍必须归零、Σ本金仍必须等于本金。
5. **规则表缺口、区间重叠、百分比合计 ≠ 100%**——编译器必须**阻断**，不能静默算出错误计划。
6. **现金流重复或漏计**——税额、气球残值、保证金最容易各算两遍或少算一遍；`Σ流入 − Σ流出` 必须能对上租金合计 + 残值，否则 IRR 全错。
7. **Excel 函数语义偏差与脚本写错**——`ROUND(-2.5)`、`MOD(-3,2)`、`EDATE(2024-01-31,1)` 这类边界与 Excel 不一致，业务一对账就会暴露；脚本递归、覆盖内置、语法错误必须在**加载期**失败，不能等到测算中途。

---

### Task 1: 多模块骨架与依赖方向守护

**Files:**
- Create: `pom.xml`
- Create: `calc-bom/pom.xml`
- Create: `calc-common/pom.xml`
- Create: `calc-domain/pom.xml`
- Create: `calc-rule/pom.xml`
- Create: `calc-engine/pom.xml`
- Create: `calc-testkit/pom.xml`
- Create: `calc-domain/src/test/java/com/fincalc/arch/DependencyDirectionTest.java`
- Create: `.gitignore`

**Interfaces:**
- Consumes: 无
- Produces: 六个可编译模块；`com.fincalc` 根包；根 POM 统一管理 `junit-jupiter 5.10.x`、`assertj-core 3.24.x`、`jqwik 1.8.x`、`archunit-junit5 1.2.x`。

- [ ] **Step 1: 初始化仓库与根 POM**

```bash
git init
```

根 `pom.xml`：`packaging=pom`，`modules` 依次为 `calc-bom, calc-common, calc-domain, calc-rule, calc-engine, calc-testkit`，`properties` 含 `maven.compiler.release=11`、`project.build.sourceEncoding=UTF-8`、`project.reporting.outputEncoding=UTF-8`。`dependencyManagement` 只 import `calc-bom`。

- [ ] **Step 2: 写各模块 POM 与依赖方向**

依赖只允许：`calc-common` 无依赖；`calc-domain → calc-common`；`calc-rule → calc-domain`；`calc-engine → calc-rule, calc-domain`；`calc-testkit → calc-domain, calc-rule`（scope `test` 由使用方引入）。测试依赖在各模块内以 `test` scope 引入 JUnit 5 + AssertJ。

- [ ] **Step 3: 写依赖方向守护测试**

```java
package com.fincalc.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class DependencyDirectionTest {
    private final JavaClasses classes =
            new ClassFileImporter().importPackages("com.fincalc");

    @Test
    void domain_must_not_depend_on_frameworks() {
        ArchRule rule = noClasses().that().resideInAPackage("com.fincalc.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "org.apache.poi..",
                        "org.apache.ibatis..", "com.fasterxml.jackson..");
        rule.check(classes);
    }
}
```

- [ ] **Step 4: 验证构建**

Run: `mvn -q -DskipTests package`
Expected: BUILD SUCCESS，六个模块全部产出 jar。

Run: `mvn -q -pl calc-domain test`
Expected: `DependencyDirectionTest` PASS。

- [ ] **Step 5: 提交**

```bash
git add pom.xml calc-*/pom.xml calc-domain/src/test .gitignore
git commit -m "chore: bootstrap multi-module maven skeleton"
```

---

### Task 2: 金额工具与精度常量

**Files:**
- Create: `calc-common/src/main/java/com/fincalc/common/money/MoneyScale.java`
- Create: `calc-common/src/main/java/com/fincalc/common/money/MoneyUtils.java`
- Test: `calc-common/src/test/java/com/fincalc/common/money/MoneyUtilsTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `MoneyScale.CALC = 12`、`INTERMEDIATE = 10`、`OUTPUT = 2`、`ROUNDING = RoundingMode.HALF_UP`
  - `MoneyUtils.scale(BigDecimal, int) -> BigDecimal`
  - `MoneyUtils.out(BigDecimal) -> BigDecimal`（scale 2）
  - `MoneyUtils.mid(BigDecimal) -> BigDecimal`（scale 10）
  - `MoneyUtils.zeroIfNull(BigDecimal) -> BigDecimal`
  - `MoneyUtils.sum(Collection<BigDecimal>) -> BigDecimal`
  - `MoneyUtils.eq(BigDecimal, BigDecimal, BigDecimal tolerance) -> boolean`
  - `MoneyUtils.ZERO`、`MoneyUtils.ONE_HUNDRED`

- [ ] **Step 1: 写失败测试**

```java
@Test
void out_rounds_half_up_to_two_decimals() {
    assertThat(MoneyUtils.out(new BigDecimal("1.005"))).isEqualByComparingTo("1.01");
    assertThat(MoneyUtils.out(new BigDecimal("1.004"))).isEqualByComparingTo("1.00");
}

@Test
void sum_ignores_null_and_keeps_scale() {
    assertThat(MoneyUtils.sum(Arrays.asList(new BigDecimal("1.10"), null, new BigDecimal("2.20"))))
            .isEqualByComparingTo("3.30");
}

@Test
void eq_uses_absolute_tolerance() {
    assertThat(MoneyUtils.eq(new BigDecimal("100.00"), new BigDecimal("100.01"), new BigDecimal("0.01")))
            .isTrue();
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-common test -Dtest=MoneyUtilsTest`
Expected: 编译失败，`MoneyUtils` 不存在。

- [ ] **Step 3: 实现**

`scale` 用 `value.setScale(scale, MoneyScale.ROUNDING)`，`null` 返回 `null`；`sum` 以 `BigDecimal.ZERO` 起累加，逐项 `zeroIfNull`；`eq` 比较 `a.subtract(b).abs().compareTo(tolerance) <= 0`。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-common test -Dtest=MoneyUtilsTest`
Expected: 3 tests PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-common/src
git commit -m "feat: add money scale and rounding utilities"
```

---

### Task 3: 日期工具与时钟端口

**Files:**
- Create: `calc-common/src/main/java/com/fincalc/common/date/DateUtils.java`
- Create: `calc-common/src/main/java/com/fincalc/common/time/ClockProvider.java`
- Create: `calc-common/src/main/java/com/fincalc/common/time/SystemClockProvider.java`
- Test: `calc-common/src/test/java/com/fincalc/common/date/DateUtilsTest.java`
- Test: `calc-common/src/test/java/com/fincalc/common/time/ClockProviderTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `DateUtils.endOfMonth(LocalDate) -> LocalDate`
  - `DateUtils.isEndOfMonth(LocalDate) -> boolean`
  - `DateUtils.daysBetween(LocalDate startInclusive, LocalDate endExclusive) -> int`
  - `DateUtils.plusMonthsKeepEom(LocalDate, int months) -> LocalDate`
  - `ClockProvider { LocalDate today(); Instant now(); }`，`SystemClockProvider.INSTANCE`

- [ ] **Step 1: 写失败测试**

```java
@Test
void plusMonthsKeepEom_clamps_31st_to_target_month_end() {
    assertThat(DateUtils.plusMonthsKeepEom(LocalDate.of(2024, 1, 31), 1))
            .isEqualTo(LocalDate.of(2024, 2, 29));
    assertThat(DateUtils.plusMonthsKeepEom(LocalDate.of(2023, 1, 31), 1))
            .isEqualTo(LocalDate.of(2023, 2, 28));
    assertThat(DateUtils.plusMonthsKeepEom(LocalDate.of(2024, 2, 29), 12))
            .isEqualTo(LocalDate.of(2025, 2, 28));
}

@Test
void daysBetween_is_half_open() {
    assertThat(DateUtils.daysBetween(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 3, 1))).isEqualTo(60);
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-common test -Dtest=DateUtilsTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

`plusMonthsKeepEom`：若原日期是月末，则先 `plusMonths` 再 `endOfMonth`，否则直接 `plusMonths`（`LocalDate` 自动 clamp）。`daysBetween` 用 `Math.toIntExact(ChronoUnit.DAYS.between(...))`。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-common test -Dtest='DateUtilsTest,ClockProviderTest'`
Expected: 全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-common/src
git commit -m "feat: add date utilities and clock port"
```

---

### Task 4: 错误码与异常体系

**Files:**
- Create: `calc-common/src/main/java/com/fincalc/common/error/ErrorCode.java`
- Create: `calc-common/src/main/java/com/fincalc/common/error/CommonErrorCode.java`
- Create: `calc-common/src/main/java/com/fincalc/common/error/BusinessException.java`
- Create: `calc-common/src/main/java/com/fincalc/common/error/SystemException.java`
- Test: `calc-common/src/test/java/com/fincalc/common/error/BusinessExceptionTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `interface ErrorCode { String code(); String message(); }`
  - `enum CommonErrorCode implements ErrorCode`（`INVALID_ARGUMENT("FCS-COM-0001", ...)`、`NOT_FOUND("FCS-COM-0002", ...)`、`STATE_CONFLICT("FCS-COM-0003", ...)`）
  - `BusinessException(ErrorCode, Object... args)`、`SystemException(String, Throwable)`
  - `BusinessException.errorCode() -> ErrorCode`

- [ ] **Step 1: 写失败测试**

```java
@Test
void business_exception_exposes_code_and_formatted_message() {
    BusinessException ex = new BusinessException(CommonErrorCode.INVALID_ARGUMENT, "periods", 0);
    assertThat(ex.errorCode().code()).isEqualTo("FCS-COM-0001");
    assertThat(ex.getMessage()).contains("periods").contains("0");
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-common test -Dtest=BusinessExceptionTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

`message()` 用 `String.format` 占位符（如 `"参数 %s 非法: %s"`）；`BusinessException` 在构造时格式化并保存 `args`。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-common test -Dtest=BusinessExceptionTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-common/src
git commit -m "feat: add error code and exception hierarchy"
```

---

### Task 5: SPI 扩展注册表

**Files:**
- Create: `calc-common/src/main/java/com/fincalc/common/spi/OrderedExtension.java`
- Create: `calc-common/src/main/java/com/fincalc/common/spi/ExtensionRegistry.java`
- Test: `calc-common/src/test/java/com/fincalc/common/spi/ExtensionRegistryTest.java`

**Interfaces:**
- Consumes: `BusinessException`、`CommonErrorCode`
- Produces:
  - `interface OrderedExtension { String code(); default int order() { return 100; } }`
  - `ExtensionRegistry.load(Class<T>) -> ExtensionRegistry<T>`（ServiceLoader）
  - `registry.register(T) -> ExtensionRegistry<T>`（显式注册，同 code 覆盖）
  - `registry.require(String code) -> T`（缺失抛 `BusinessException(NOT_FOUND)`）
  - `registry.find(String code) -> Optional<T>`
  - `registry.all() -> List<T>`（按 `order()` 升序）

- [ ] **Step 1: 写失败测试**

```java
@Test
void explicit_registration_overrides_and_sorts_by_order() {
    ExtensionRegistry<FakeExt> reg = ExtensionRegistry.load(FakeExt.class);
    reg.register(new FakeExt("b", 20));
    reg.register(new FakeExt("a", 10));
    reg.register(new FakeExt("a", 30));

    assertThat(reg.all()).extracting(FakeExt::code).containsExactly("a", "b");
    assertThat(reg.require("a").order()).isEqualTo(30);
    assertThatThrownBy(() -> reg.require("missing")).isInstanceOf(BusinessException.class);
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-common test -Dtest=ExtensionRegistryTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

内部用 `LinkedHashMap<String, T>`；`all()` 返回按 `order()` 稳定排序的不可变列表。`ServiceLoader.load(type)` 的结果逐个 `register`，捕获 `ServiceConfigurationError` 转 `SystemException`。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-common test -Dtest=ExtensionRegistryTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-common/src
git commit -m "feat: add ordered SPI extension registry"
```

---

### Task 6: 输入模型（LeaseElement）

**Files:**
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/PaymentFrequency.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/PaymentTiming.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/ResidualHandling.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/RateChangePolicy.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/FeeChargeMode.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/TailAdjustPolicy.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/RepaymentMethodId.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/FeeItem.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/GracePeriod.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/RoundingPolicy.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/PeriodDate.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/LeaseElement.java`
- Test: `calc-domain/src/test/java/com/fincalc/domain/model/LeaseElementTest.java`

**Interfaces:**
- Consumes: `MoneyUtils`、`BusinessException`、`CommonErrorCode`
- Produces:
  - `PaymentFrequency { MONTHLY(1,true), QUARTERLY(3,true), SEMI_ANNUAL(6,true), ANNUAL(12,true), IRREGULAR(0,false) }`，方法 `monthsPerPeriod() -> int`、`periodsPerYear() -> int`（12/4/2/1/0）、`isRegular() -> boolean`
  - `PaymentTiming { IN_ADVANCE, IN_ARREARS }`
  - `ResidualHandling { SEPARATE, BALLOON_IN_RENT }`
  - `RateChangePolicy { RESOLVE, FIXED_RENT, DRIFT }`
  - `FeeChargeMode { UPFRONT, DEDUCT_FROM_PRINCIPAL, AMORTIZED }`
  - `TailAdjustPolicy { LAST_PERIOD, FIRST_PERIOD, PROPORTIONAL }`
  - `RepaymentMethodId { EQUAL_INSTALLMENT, EQUAL_PRINCIPAL, INTEREST_FIRST, PRINCIPAL_FIRST, RULE_DRIVEN }`
  - `RoundingPolicy.defaults()`；字段 `scale`、`mode`、`tailAdjustPolicy`
  - `FeeItem`：`type`、`amount`、`chargeMode`、`vatRate`、`includeInIrr`
  - `GracePeriod`：`interestOnlyPeriods`、`fullGracePeriods`；`GracePeriod.none()`
  - `PeriodDate`：`startDate`、`endDate`、`paymentDate`
  - `LeaseElement.builder()...build()`；`validate()` 由 `build()` 调用；`toBuilder() -> Builder`（从现有实例派生，供测试与规则覆盖使用）

- [ ] **Step 1: 写失败测试**

```java
@Test
void builder_rejects_non_positive_principal() {
    assertThatThrownBy(() -> baseBuilder().principal(BigDecimal.ZERO).build())
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("FCS-DOM");
}

@Test
void builder_rejects_rate_above_one() {
    assertThatThrownBy(() -> baseBuilder().nominalRate(new BigDecimal("1.5")).build())
            .isInstanceOf(BusinessException.class);
}

@Test
void irregular_requires_explicit_periods() {
    assertThatThrownBy(() -> baseBuilder()
            .frequency(PaymentFrequency.IRREGULAR)
            .explicitPeriods(Collections.emptyList())
            .build())
            .isInstanceOf(BusinessException.class);
}

@Test
void regular_derives_periods_per_year() {
    assertThat(PaymentFrequency.QUARTERLY.periodsPerYear()).isEqualTo(4);
    assertThat(PaymentFrequency.MONTHLY.monthsPerPeriod()).isEqualTo(1);
}
```

`baseBuilder()` 返回 `LeaseElement.builder().principal(new BigDecimal("1000000")).nominalRate(new BigDecimal("0.06")).commenceDate(LocalDate.of(2024,1,1)).firstRentDate(LocalDate.of(2024,2,1)).periods(12).frequency(MONTHLY).timing(ARREARS).dayCountCode("THIRTY_360").dateRollCode("NONE").rounding(RoundingPolicy.defaults()).residualValue(ZERO).residualHandling(SEPARATE).deposit(ZERO).fees(emptyList()).vatRate(null).method(EQUAL_INSTALLMENT).gracePeriod(GracePeriod.none()).currency("CNY").rateChangePolicy(RESOLVE)`。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-domain test -Dtest=LeaseElementTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

`build()` 依次校验：`principal > 0`、`0 <= nominalRate <= 1`、`periods >= 1`、`commenceDate != null`、`firstRentDate >= commenceDate`、`IRREGULAR` 时 `explicitPeriods` 非空且每项 `start < end`、`BALLOON_IN_RENT` 时 `0 <= residualValue < principal`。错误码 `FCS-DOM-1001`~`1007`。集合字段用 `Collections.unmodifiableList(new ArrayList<>(...))`。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-domain test -Dtest=LeaseElementTest`
Expected: 4 tests PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-domain/src
git commit -m "feat: add immutable lease element input model"
```

---

### Task 7: 输出模型（Installment / Schedule）

**Files:**
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/IssueLevel.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/ValidationIssue.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/CashFlowType.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/CashFlow.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/Installment.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/ScheduleHeader.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/Schedule.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/metric/FinancialMetrics.java`
- Test: `calc-domain/src/test/java/com/fincalc/domain/model/ScheduleTest.java`
- Test: `calc-domain/src/test/java/com/fincalc/domain/metric/FinancialMetricsTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `IssueLevel { INFO, WARN, ERROR }`
  - `ValidationIssue(level, code, message, Integer periodNo)`
  - `CashFlowType { PRINCIPAL, RENT, FEE, RESIDUAL, DEPOSIT }`
  - `CashFlow(sequence, date, amount, type, direction)`——`direction` 来自 Task 19 的 `CashFlowDirection`
  - `Installment` 16 个字段：`periodNo, startDate, endDate, days, openingBalance, principalDue, interestAccrued, interestPaid, fee, tax, rentDue, closingBalance, cumulativePrincipal, cumulativeInterest, appliedRate, memo`
  - `FinancialMetrics`：不可变、`Map<String, BigDecimal>` 支撑的值对象；`of(Map)`、`find(code) -> BigDecimal`（无值返回 `null`）、`codes()`、`asMap()`；`empty()`
  - `ScheduleHeader(metrics, periods, firstDate, lastDate, solver, roundingDifference)`——**所有金额/收益率指标都放进 `metrics`，不再散落字段**
  - `Schedule(header, installments, cashFlows, issues)`；`hasErrors() -> boolean`；`with(FinancialMetrics metrics, List<CashFlow> cashFlows) -> Schedule`（生成器产出未富化的 Schedule，引擎用此方法补指标与现金流）

- [ ] **Step 1: 写失败测试**

```java
@Test
void schedule_is_immutable() {
    Schedule s = new Schedule(header(), new ArrayList<>(List.of(inst(1))),
            new ArrayList<>(List.of(new CashFlow(1, LocalDate.of(2024,2,1), new BigDecimal("100"),
                    CashFlowType.RENT, CashFlowDirection.INFLOW))),
            new ArrayList<>());
    assertThatThrownBy(() -> s.installments().add(inst(2)))
            .isInstanceOf(UnsupportedOperationException.class);
}

@Test
void has_errors_detects_error_level_issue() {
    Schedule s = scheduleWithIssue(new ValidationIssue(IssueLevel.ERROR, "FCS-CALC-3001", "本金不守恒", null));
    assertThat(s.hasErrors()).isTrue();
}

@Test
void financial_metrics_returns_null_for_absent_code() {
    FinancialMetrics m = FinancialMetrics.of(Map.of("NPV", new BigDecimal("12.34")));
    assertThat(m.find("NPV")).isEqualByComparingTo("12.34");
    assertThat(m.find("XIRR")).isNull();
    assertThat(m.codes()).containsExactly("NPV");
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-domain test -Dtest='ScheduleTest,FinancialMetricsTest'`
Expected: 编译失败。

- [ ] **Step 3: 实现**

全部 `final class` + 全参构造，构造内对 `List` 做 `Collections.unmodifiableList(new ArrayList<>(src))`。`FinancialMetrics` 内部用 `Collections.unmodifiableMap(new LinkedHashMap<>(src))`，允许 `value == null`（表示该指标不适用/不可计算），`find` 直接返回 `null`。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-domain test -Dtest=ScheduleTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-domain/src
git commit -m "feat: add immutable schedule output model"
```

---

### Task 8: 计息口径

**Files:**
- Create: `calc-domain/src/main/java/com/fincalc/domain/port/DayCountConvention.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/daycount/Act360.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/daycount/Act365F.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/daycount/ActAct.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/daycount/Thirty360.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/daycount/ThirtyE360.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/daycount/Act36525.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/DayCountConventions.java`
- Test: `calc-domain/src/test/java/com/fincalc/domain/support/DayCountConventionsTest.java`

**Interfaces:**
- Consumes: `OrderedExtension`、`DateUtils`
- Produces:
  - `interface DayCountConvention extends OrderedExtension { int days(LocalDate, LocalDate); BigDecimal yearFraction(LocalDate, LocalDate); }`
  - `DayCountConventions.all() -> ExtensionRegistry<DayCountConvention>`，code 分别为 `ACT_360`、`ACT_365F`、`ACT_ACT`、`THIRTY_360`、`THIRTY_E_360`、`ACT_365_25`

- [ ] **Step 1: 写失败测试（含闰年与月末）**

```java
@Test
void act360_uses_actual_days_over_360() {
    DayCountConvention c = DayCountConventions.all().require("ACT_360");
    assertThat(c.days(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 3, 1))).isEqualTo(29);
    assertThat(c.yearFraction(LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1)))
            .isEqualByComparingTo(new BigDecimal("1.016666666667"));
}

@Test
void thirty360_clamps_day31_to_30() {
    DayCountConvention c = DayCountConventions.all().require("THIRTY_360");
    assertThat(c.days(LocalDate.of(2024, 1, 31), LocalDate.of(2024, 2, 29))).isEqualTo(29);
    assertThat(c.days(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 2, 1))).isEqualTo(30);
}

@Test
void act_act_splits_by_calendar_year() {
    DayCountConvention c = DayCountConventions.all().require("ACT_ACT");
    // 完整落在闰年内：366/366 = 1
    assertThat(c.yearFraction(LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1)))
            .isEqualByComparingTo(new BigDecimal("1.000000000000"));
    // 跨闰年边界：184/366 + 181/365
    assertThat(c.yearFraction(LocalDate.of(2024, 7, 1), LocalDate.of(2025, 7, 1)))
            .isEqualByComparingTo(new BigDecimal("0.998622651396"));
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-domain test -Dtest=DayCountConventionsTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

`THIRTY_360`：`d1 = min(day(start),30)`；若 `day(start)==31` 则 `d1=30`；若 `day(end)==31 && d1==30` 则 `d2=30`。`ACT_ACT`：按自然年切分，`Σ segmentDays / daysInYear(segment)`，结果 scale 12。其余按名称直译。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-domain test -Dtest=DayCountConventionsTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-domain/src
git commit -m "feat: add pluggable day count conventions"
```

---

### Task 9: 日期滚动口径

**Files:**
- Create: `calc-domain/src/main/java/com/fincalc/domain/port/DateRollConvention.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/dateroll/NoRoll.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/dateroll/Following.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/dateroll/ModifiedFollowing.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/dateroll/Preceding.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/dateroll/ModifiedPreceding.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/dateroll/EndOfMonthRoll.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/DateRollConventions.java`
- Test: `calc-domain/src/test/java/com/fincalc/domain/support/DateRollConventionsTest.java`

**Interfaces:**
- Consumes: `OrderedExtension`、`DateUtils`、`DayOfWeek`
- Produces: `interface DateRollConvention extends OrderedExtension { LocalDate roll(LocalDate); }`；`DateRollConventions.all()` 提供 `NONE`、`FOLLOWING`、`MODIFIED_FOLLOWING`、`PRECEDING`、`MODIFIED_PRECEDING`、`END_OF_MONTH`

- [ ] **Step 1: 写失败测试**

```java
@Test
void following_moves_weekend_to_monday() {
    // 2024-06-01 是周六
    assertThat(DateRollConventions.all().require("FOLLOWING")
            .roll(LocalDate.of(2024, 6, 1))).isEqualTo(LocalDate.of(2024, 6, 3));
}

@Test
void modified_following_stays_in_month() {
    // 2024-08-31 周六，向后会跨月 → 改向前
    assertThat(DateRollConventions.all().require("MODIFIED_FOLLOWING")
            .roll(LocalDate.of(2024, 8, 31))).isEqualTo(LocalDate.of(2024, 8, 30));
}

@Test
void end_of_month_snaps_to_month_end() {
    assertThat(DateRollConventions.all().require("END_OF_MONTH")
            .roll(LocalDate.of(2024, 2, 10))).isEqualTo(LocalDate.of(2024, 2, 29));
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-domain test -Dtest=DateRollConventionsTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

仅处理周六/周日（工作日历为 v2 扩展点，`DateRollConvention` 接口已允许后续实现注入节假日集合）。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-domain test -Dtest=DateRollConventionsTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-domain/src
git commit -m "feat: add date roll conventions"
```

---

### Task 10: 期次生成

**Files:**
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/PeriodBuilder.java`
- Test: `calc-domain/src/test/java/com/fincalc/domain/support/PeriodBuilderTest.java`

**Interfaces:**
- Consumes: `LeaseElement`、`PeriodDate`、`DateRollConvention`、`DateUtils`
- Produces: `PeriodBuilder.build(LeaseElement element, DateRollConvention roll) -> List<PeriodDate>`

**生成规则（按此实现，不要另选）：**
1. `IRREGULAR`：直接返回 `element.explicitPeriods()`，并校验严格单调、`start < end`。
2. 常规：`anchor = element.firstRentDate()`；`m = frequency.monthsPerPeriod()`。
   - `end[k] = roll.roll(DateUtils.plusMonthsKeepEom(anchor, (k-1)*m))`，`k = 1..n`；
   - `start[1] = element.commenceDate()`，`start[k] = end[k-1]`；
   - `paymentDate = timing == IN_ARREARS ? end[k] : start[k]`。

- [ ] **Step 1: 写失败测试**

```java
@Test
void builds_twelve_monthly_periods_arrears() {
    List<PeriodDate> periods = PeriodBuilder.build(baseElement(), DateRollConventions.all().require("NONE"));
    assertThat(periods).hasSize(12);
    assertThat(periods.get(0).startDate()).isEqualTo(LocalDate.of(2024, 1, 1));
    assertThat(periods.get(0).endDate()).isEqualTo(LocalDate.of(2024, 2, 1));
    assertThat(periods.get(0).paymentDate()).isEqualTo(LocalDate.of(2024, 2, 1));
    assertThat(periods.get(11).endDate()).isEqualTo(LocalDate.of(2025, 1, 1));
}

@Test
void advance_timing_pays_at_period_start() {
    List<PeriodDate> periods = PeriodBuilder.build(baseElementWithAdvance(), DateRollConventions.all().require("NONE"));
    assertThat(periods.get(0).paymentDate()).isEqualTo(LocalDate.of(2024, 1, 1));
}

@Test
void monthly_on_31st_does_not_drift() {
    // 起租 2024-01-31，首期 2024-02-29
    List<PeriodDate> periods = PeriodBuilder.build(eomElement(), DateRollConventions.all().require("END_OF_MONTH"));
    assertThat(periods.get(0).endDate()).isEqualTo(LocalDate.of(2024, 2, 29));
    assertThat(periods.get(1).endDate()).isEqualTo(LocalDate.of(2024, 3, 31));
    assertThat(periods.get(11).endDate()).isEqualTo(LocalDate.of(2025, 1, 31));
}

@Test
void rejects_non_monotonic_explicit_periods() {
    assertThatThrownBy(() -> PeriodBuilder.build(irregularWithOverlap(), DateRollConventions.all().require("NONE")))
            .isInstanceOf(BusinessException.class);
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-domain test -Dtest=PeriodBuilderTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

返回 `Collections.unmodifiableList`；任何 `start >= end` 或 `start[k] < end[k-1]` 抛 `FCS-DOM-2001`。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-domain test -Dtest=PeriodBuilderTest`
Expected: 4 tests PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-domain/src
git commit -m "feat: add period builder for regular and irregular schedules"
```

---

### Task 11: 尾差处理

**Files:**
- Create: `calc-domain/src/main/java/com/fincalc/domain/port/TailAdjuster.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/tail/LastPeriodTailAdjuster.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/tail/FirstPeriodTailAdjuster.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/tail/ProportionalTailAdjuster.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/TailAdjusters.java`
- Test: `calc-domain/src/test/java/com/fincalc/domain/support/TailAdjustersTest.java`

**Interfaces:**
- Consumes: `Installment`、`RoundingPolicy`、`MoneyUtils`
- Produces:
  - `interface TailAdjuster extends OrderedExtension { Installment adjust(Installment target, BigDecimal difference, RoundingPolicy policy); }`
  - `TailAdjusters.forPolicy(TailAdjustPolicy) -> TailAdjuster`
  - `TailAdjusters.applyProportional(List<Installment> rows, BigDecimal expectedTotal) -> List<Installment>`

> 说明：`difference = 期望合计 − 当前合计`，由调用方算好传入。`LAST_PERIOD` / `FIRST_PERIOD` 作用于单期；`PROPORTIONAL` 走 `applyProportional` 整体重算。

- [ ] **Step 1: 写失败测试**

```java
@Test
void last_period_absorbs_all_difference() {
    Installment last = inst(12, "86066.43");
    Installment adjusted = TailAdjusters.forPolicy(TailAdjustPolicy.LAST_PERIOD)
            .adjust(last, new BigDecimal("-0.03"), RoundingPolicy.defaults());
    assertThat(adjusted.rentDue()).isEqualByComparingTo("86066.40");
    assertThat(adjusted.principalDue()).isEqualByComparingTo("81066.40");
}

@Test
void proportional_preserves_sum_exactly() {
    List<Installment> rows = List.of(inst(1, "100.01"), inst(2, "100.01"), inst(3, "100.01"));
    List<Installment> adjusted = TailAdjusters.applyProportional(rows, new BigDecimal("300.00"));
    assertThat(adjusted).extracting(Installment::rentDue)
            .allSatisfy(v -> assertThat(v.scale()).isEqualTo(2));
    assertThat(MoneyUtils.sum(adjusted.stream().map(Installment::rentDue).collect(toList())))
            .isEqualByComparingTo("300.00");
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-domain test -Dtest=TailAdjustersTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

`Proportional` 用**最大余额法**：先按 `difference × 权重` 取整到分，再按余数从大到小逐分补足，保证合计精确。权重用各期 `rentDue` 绝对值。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-domain test -Dtest=TailAdjustersTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-domain/src
git commit -m "feat: add tail difference adjusters"
```

---

### Task 12: 算法 SPI 与模板骨架

**Files:**
- Create: `calc-domain/src/main/java/com/fincalc/domain/port/ScheduleGenerator.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/port/RuleSetView.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/RuleOutcome.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/PrincipalPlanKind.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/InterestTreatmentKind.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/SpecialActionKind.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/CashFlowBasis.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/CalculationOptions.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/calc/CalculationContext.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/AbstractAmortizationGenerator.java`
- Test: `calc-domain/src/test/java/com/fincalc/domain/support/AbstractAmortizationGeneratorTest.java`

**Interfaces:**
- Consumes: 全部模型与端口
- Produces:
  - `interface ScheduleGenerator extends OrderedExtension { boolean supports(CalculationContext); Schedule generate(CalculationContext); }`
  - `interface RuleSetView { RuleOutcome resolve(int periodNo, BigDecimal openingBalance, int days, BigDecimal defaultAnnualRate); }`
  - `RuleOutcome`：`matched, ruleId, annualRate, rateBase, principalPlan, principalValue, principalSequence, interestTreatment, specialAction, actionParam`
  - `PrincipalPlanKind { EQUAL, FIXED, PERCENT, CUSTOM, NONE, INHERIT }`
  - `InterestTreatmentKind { PAY_AS_INCURRED, CAPITALIZE, DEFER, SETTLE_AT_MATURITY, EXEMPT }`
  - `SpecialActionKind { NONE, FEE_AMORTIZE, DEPOSIT_OFFSET, PREPAY, EXTEND }`
  - `CashFlowBasis { CONTRACT, INCLUDE_FEES, INCLUDE_DEPOSIT }`
  - `CalculationOptions(allowPartialRuleSet, rateChangePolicy, cashFlowBasis)`；`CalculationOptions.defaults()` → `(false, RESOLVE, INCLUDE_FEES)`
  - `CalculationContext(element, ruleSet, dayCount, dateRoll, clock, options)`
  - `abstract class AbstractAmortizationGenerator implements ScheduleGenerator`
    - `protected abstract Allocation allocate(AllocationInput input, AmortizationState state)`
    - `protected void prepare(LeaseElement element, AmortizationState state)` ← 默认空实现，子类在此求 PMT 等一次性参数
    - `protected void validate(LeaseElement)`
    - `protected List<PeriodDate> periods(CalculationContext)`
    - `protected BigDecimal periodRate(LeaseElement, int days)`  ← 默认 `annualRate × yearFraction`
    - 内部类 `Allocation(principalDue, interestPaid)`、`AllocationInput(periodNo, totalPeriods, openingBalance, periodRate, accruedInterest, remainingPrincipal, lastPeriod, paymentTiming)`、`AmortizationState(deferredInterest, cumulativePrincipal, cumulativeInterest, pmt)`（`pmt` 默认 `null`，由 `prepare` 赋值）

- [ ] **Step 1: 写失败测试（用一个假生成器验证骨架行为）**

```java
@Test
void skeleton_computes_interest_closes_balance_and_fills_cumulative() {
    Schedule s = new FixedPrincipalStub().generate(context(baseElement()));

    assertThat(s.installments()).hasSize(12);
    assertThat(s.installments().get(0).interestAccrued()).isEqualByComparingTo("5000.00");
    assertThat(s.installments().get(0).closingBalance()).isEqualByComparingTo("916666.67");
    assertThat(s.installments().get(11).closingBalance()).isEqualByComparingTo("0.00");
    assertThat(s.installments().get(11).cumulativePrincipal()).isEqualByComparingTo("1000000.00");
}

@Test
void zero_rate_is_handled_without_division_by_zero() {
    Schedule s = new FixedPrincipalStub().generate(context(baseElement().toBuilder()
            .nominalRate(BigDecimal.ZERO).build()));
    assertThat(s.installments()).allSatisfy(i ->
            assertThat(i.interestAccrued()).isEqualByComparingTo("0.00"));
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-domain test -Dtest=AbstractAmortizationGeneratorTest`
Expected: 编译失败。

- [ ] **Step 3: 实现骨架**

`generate()` 顺序固定：`validate → periods → 逐期 { accruedInterest = round(opening × periodRate,2)；allocate；fee；rent = principal + interestPaid + fee；closing = opening − principal } → 汇总 → 校验守恒（不通过则追加 ERROR issue，不抛异常） → 返回 Schedule`。`ScheduleHeader.solver` 默认 `"CLOSED_FORM"`。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-domain test -Dtest=AbstractAmortizationGeneratorTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-domain/src
git commit -m "feat: add schedule generator spi and amortization skeleton"
```

---

### Task 13: 等额本息

**Files:**
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/algo/EqualInstallmentGenerator.java`
- Test: `calc-domain/src/test/java/com/fincalc/domain/support/algo/EqualInstallmentGeneratorTest.java`

**Interfaces:**
- Consumes: `AbstractAmortizationGenerator`
- Produces: `EqualInstallmentGenerator`，`code() = "EQUAL_INSTALLMENT"`，`order() = 10`

- [ ] **Step 1: 写失败测试**

```java
@Test
void golden_first_three_periods() {
    Schedule s = generate(principal("1000000"), rate("0.06"), 12, THIRTY_360, ARREARS);
    assertThat(s.installments().get(0).rentDue()).isEqualByComparingTo("86066.43");
    assertThat(s.installments().get(0).interestAccrued()).isEqualByComparingTo("5000.00");
    assertThat(s.installments().get(0).principalDue()).isEqualByComparingTo("81066.43");
    assertThat(s.installments().get(0).closingBalance()).isEqualByComparingTo("918933.57");
    assertThat(s.installments().get(1).interestAccrued()).isEqualByComparingTo("4594.67");
    assertThat(s.installments().get(1).principalDue()).isEqualByComparingTo("81471.76");
    assertThat(s.installments().get(1).closingBalance()).isEqualByComparingTo("837461.81");
}

@Test
void last_period_closes_to_zero_and_principal_is_conserved() {
    Schedule s = generate(principal("1000000"), rate("0.06"), 12, THIRTY_360, ARREARS);
    assertThat(s.installments().get(11).closingBalance()).isEqualByComparingTo("0.00");
    assertThat(MoneyUtils.sum(s.installments().stream().map(Installment::principalDue).collect(toList())))
            .isEqualByComparingTo("1000000.00");
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-domain test -Dtest=EqualInstallmentGeneratorTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

`prepare` 中求 `state.pmt`（本任务先用内联公式 `P·i·(1+i)^n / ((1+i)^n − 1)`；Task 17 替换为 `PmtSolver` 调用）。`allocate`：`if (lastPeriod) principal = remainingPrincipal else principal = state.pmt.subtract(accruedInterest)`。利率为 0 时 `pmt = principal / periods`；单期时 `pmt = principal × (1 + i) − residualValue`。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-domain test -Dtest=EqualInstallmentGeneratorTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-domain/src
git commit -m "feat: add equal installment generator"
```

---

### Task 14: 等额本金

**Files:**
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/algo/EqualPrincipalGenerator.java`
- Test: `calc-domain/src/test/java/com/fincalc/domain/support/algo/EqualPrincipalGeneratorTest.java`

**Interfaces:**
- Consumes: `AbstractAmortizationGenerator`
- Produces: `EqualPrincipalGenerator`，`code() = "EQUAL_PRINCIPAL"`，`order() = 20`

- [ ] **Step 1: 写失败测试**

```java
@Test
void golden_schedule() {
    Schedule s = generate(principal("1000000"), rate("0.06"), 12, THIRTY_360, ARREARS);
    assertThat(s.installments().get(0).principalDue()).isEqualByComparingTo("83333.33");
    assertThat(s.installments().get(0).interestAccrued()).isEqualByComparingTo("5000.00");
    assertThat(s.installments().get(0).rentDue()).isEqualByComparingTo("88333.33");
    assertThat(s.installments().get(1).openingBalance()).isEqualByComparingTo("916666.67");
    assertThat(s.installments().get(1).interestAccrued()).isEqualByComparingTo("4583.33");
    assertThat(s.installments().get(11).principalDue()).isEqualByComparingTo("83333.37");
}

@Test
void interest_decreases_monotonically() {
    Schedule s = generate(principal("1000000"), rate("0.06"), 12, THIRTY_360, ARREARS);
    for (int i = 1; i < 12; i++) {
        assertThat(s.installments().get(i).interestAccrued())
                .isLessThan(s.installments().get(i - 1).interestAccrued());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-domain test -Dtest=EqualPrincipalGeneratorTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

`perPeriod = principal.divide(periods, 2, HALF_UP)`；非末期 `principal = perPeriod`，末期 `principal = remainingPrincipal`。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-domain test -Dtest=EqualPrincipalGeneratorTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-domain/src
git commit -m "feat: add equal principal generator"
```

---

### Task 15: 先息后本

**Files:**
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/algo/InterestFirstGenerator.java`
- Test: `calc-domain/src/test/java/com/fincalc/domain/support/algo/InterestFirstGeneratorTest.java`

**Interfaces:**
- Consumes: `AbstractAmortizationGenerator`
- Produces: `InterestFirstGenerator`，`code() = "INTEREST_FIRST"`，`order() = 30`

- [ ] **Step 1: 写失败测试**

```java
@Test
void pays_only_interest_until_maturity() {
    Schedule s = generate(principal("1000000"), rate("0.06"), 12, THIRTY_360, ARREARS);
    for (int i = 0; i < 11; i++) {
        assertThat(s.installments().get(i).principalDue()).isEqualByComparingTo("0.00");
        assertThat(s.installments().get(i).interestAccrued()).isEqualByComparingTo("5000.00");
        assertThat(s.installments().get(i).closingBalance()).isEqualByComparingTo("1000000.00");
    }
    assertThat(s.installments().get(11).principalDue()).isEqualByComparingTo("1000000.00");
    assertThat(s.installments().get(11).rentDue()).isEqualByComparingTo("1005000.00");
    assertThat(s.installments().get(11).closingBalance()).isEqualByComparingTo("0.00");
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-domain test -Dtest=InterestFirstGeneratorTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

`interestPaid = accruedInterest`；`principal = lastPeriod ? remainingPrincipal : ZERO`。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-domain test -Dtest=InterestFirstGeneratorTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-domain/src
git commit -m "feat: add interest first generator"
```

---

### Task 16: 先本后息

**Files:**
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/algo/PrincipalFirstGenerator.java`
- Test: `calc-domain/src/test/java/com/fincalc/domain/support/algo/PrincipalFirstGeneratorTest.java`

**Interfaces:**
- Consumes: `AbstractAmortizationGenerator`、`AmortizationState.deferredInterest`
- Produces: `PrincipalFirstGenerator`，`code() = "PRINCIPAL_FIRST"`，`order() = 40`

**口径（已确认）：** 每期偿还等额本金；利息按当期期初余额逐期计提；第 1..n−1 期利息支付为 0 并累计到 `state.deferredInterest`；末期一次支付 `deferredInterest + 当期计提`。

- [ ] **Step 1: 写失败测试**

```java
@Test
void defers_all_interest_to_maturity() {
    Schedule s = generate(principal("1000000"), rate("0.06"), 12, THIRTY_360, ARREARS);
    for (int i = 0; i < 11; i++) {
        assertThat(s.installments().get(i).principalDue()).isEqualByComparingTo("83333.33");
        assertThat(s.installments().get(i).interestPaid()).isEqualByComparingTo("0.00");
        assertThat(s.installments().get(i).rentDue()).isEqualByComparingTo("83333.33");
    }
    Installment last = s.installments().get(11);
    assertThat(last.interestPaid()).isGreaterThan(new BigDecimal("30000"));
    assertThat(last.rentDue()).isEqualByComparingTo(
            last.principalDue().add(last.interestPaid()));
    assertThat(last.closingBalance()).isEqualByComparingTo("0.00");
}

@Test
void total_interest_equals_sum_of_accrued() {
    Schedule s = generate(principal("1000000"), rate("0.06"), 12, THIRTY_360, ARREARS);
    BigDecimal accrued = MoneyUtils.sum(s.installments().stream()
            .map(Installment::interestAccrued).collect(toList()));
    BigDecimal paid = MoneyUtils.sum(s.installments().stream()
            .map(Installment::interestPaid).collect(toList()));
    assertThat(paid).isEqualByComparingTo(accrued);
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-domain test -Dtest=PrincipalFirstGeneratorTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

`state.deferredInterest = state.deferredInterest.add(accruedInterest)`；非末期 `interestPaid = ZERO`；末期 `interestPaid = state.deferredInterest`（含当期计提）。本金规则与 `EqualPrincipalGenerator` 相同。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-domain test -Dtest=PrincipalFirstGeneratorTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-domain/src
git commit -m "feat: add principal first generator with deferred interest"
```

---

### Task 17: PMT 求解器（封闭解 + 迭代解）

**Files:**
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/PmtSolver.java`
- Modify: `calc-domain/src/main/java/com/fincalc/domain/support/algo/EqualInstallmentGenerator.java`（改用 `PmtSolver`）
- Test: `calc-domain/src/test/java/com/fincalc/domain/support/PmtSolverTest.java`

**Interfaces:**
- Consumes: `MoneyUtils`、`PaymentTiming`
- Produces:
  - `PmtSolver.closedForm(BigDecimal principal, BigDecimal periodicRate, int periods, BigDecimal residualValue) -> BigDecimal`
  - `PmtSolver.iterative(BigDecimal principal, int periods, DayCountConvention dc, LocalDate firstDate, LocalDate firstPaymentDate, BigDecimal annualRate, BigDecimal residualValue) -> BigDecimal`（二分法，容差 `1e-10`，上限 100 次）
  - `PmtSolver.isConstantPeriodRate(DayCountConvention) -> boolean`（`THIRTY_360` / `THIRTY_E_360` 为 `true`，`ACT_*` 为 `false`）

- [ ] **Step 1: 写失败测试**

```java
@Test
void closed_form_matches_reference() {
    assertThat(PmtSolver.closedForm(new BigDecimal("1000000"), new BigDecimal("0.005"), 12, BigDecimal.ZERO))
            .isEqualByComparingTo("86066.43");
}

@Test
void closed_form_handles_zero_rate() {
    assertThat(PmtSolver.closedForm(new BigDecimal("1200000"), BigDecimal.ZERO, 12, BigDecimal.ZERO))
            .isEqualByComparingTo("100000.00");
}

@Test
void closed_form_handles_single_period() {
    assertThat(PmtSolver.closedForm(new BigDecimal("1000000"), new BigDecimal("0.005"), 1, BigDecimal.ZERO))
            .isEqualByComparingTo("1005000.00");
}

@Test
void closed_form_with_residual_solves_balloon() {
    BigDecimal pmt = PmtSolver.closedForm(new BigDecimal("1000000"), new BigDecimal("0.005"), 12,
            new BigDecimal("100000"));
    // 末期余额应为 100000
    assertThat(pmt).isLessThan(new BigDecimal("86066.43"));
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-domain test -Dtest=PmtSolverTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

封闭解：`i == 0 → P/n`；`n == 1 → P*(1+i) − FV`；否则 `PMT = (P − FV/(1+i)^n) × i × (1+i)^n / ((1+i)^n − 1)`，结果 scale 2。迭代解：对候选 PMT 构造计划、计算末期现值误差，二分收缩。`EqualInstallmentGenerator` 改为：`PmtSolver.isConstantPeriodRate(dc) ? closedForm(...) : iterative(...)`，并把 `ScheduleHeader.solver` 设为 `"CLOSED_FORM"` 或 `"ITERATIVE"`。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-domain test -Dtest='PmtSolverTest,EqualInstallmentGeneratorTest'`
Expected: 全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-domain/src
git commit -m "feat: add pmt solver with closed-form and iterative modes"
```

---

### Task 18: 校验器链

**Files:**
- Create: `calc-domain/src/main/java/com/fincalc/domain/port/Validator.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/validate/PrincipalConservationValidator.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/validate/TailBalanceValidator.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/validate/DateMonotonicValidator.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/ValidatorChain.java`
- Test: `calc-domain/src/test/java/com/fincalc/domain/support/ValidatorChainTest.java`

**Interfaces:**
- Consumes: `Schedule`、`LeaseElement`、`ValidationIssue`
- Produces:
  - `interface Validator extends OrderedExtension { List<ValidationIssue> validate(Schedule, LeaseElement); }`
  - `ValidatorChain.defaults() -> ValidatorChain`
  - `chain.validate(Schedule, LeaseElement) -> Schedule`（把 issues 合并进 Schedule，返回新实例）

- [ ] **Step 1: 写失败测试**

```java
@Test
void detects_principal_conservation_violation() {
    Schedule broken = scheduleWithPrincipalSum("999999.99");
    List<ValidationIssue> issues = ValidatorChain.defaults().validateAll(broken, baseElement());
    assertThat(issues).anySatisfy(i -> {
        assertThat(i.code()).isEqualTo("FCS-CALC-3001");
        assertThat(i.level()).isEqualTo(IssueLevel.ERROR);
    });
}

@Test
void detects_non_zero_tail_balance() {
    Schedule broken = scheduleWithLastClosing("0.01");
    assertThat(ValidatorChain.defaults().validateAll(broken, baseElement()))
            .anySatisfy(i -> assertThat(i.code()).isEqualTo("FCS-CALC-3002"));
}

@Test
void passes_on_valid_schedule() {
    Schedule ok = new EqualInstallmentGenerator().generate(context(baseElement()));
    assertThat(ValidatorChain.defaults().validateAll(ok, baseElement()))
            .noneMatch(i -> i.level() == IssueLevel.ERROR);
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-domain test -Dtest=ValidatorChainTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

容差统一 `0.01`；`TailBalanceValidator` 允许末期余额等于 `residualValue`（当 `ResidualHandling.BALLOON_IN_RENT`）。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-domain test -Dtest=ValidatorChainTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-domain/src
git commit -m "feat: add schedule validator chain"
```

---

### Task 19: 现金流构建

**Files:**
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/CashFlowDirection.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/CashFlowPerspective.java`
- Modify: `calc-domain/src/main/java/com/fincalc/domain/model/CashFlow.java`（增加 `direction`）
- Create: `calc-domain/src/main/java/com/fincalc/domain/cashflow/CashFlowBuilder.java`
- Test: `calc-domain/src/test/java/com/fincalc/domain/cashflow/CashFlowBuilderTest.java`

**Interfaces:**
- Consumes: `Schedule`、`LeaseElement`、`CalculationOptions`、`CashFlowBasis`
- Produces:
  - `enum CashFlowDirection { INFLOW, OUTFLOW }`
  - `enum CashFlowPerspective { LESSOR, LESSEE }`
  - `CashFlow(sequence, date, amount, type, direction)`——`amount` 恒为正数，方向由 `direction` 表达
  - `CashFlowBuilder.build(Schedule schedule, LeaseElement element, CalculationOptions options) -> List<CashFlow>`

**构建规则（严格按设计 §9.4，不要自行发挥）：**
1. `LESSOR` 视角：t0 本金为 `OUTFLOW`，各期租金为 `INFLOW`；
2. `INCLUDE_DEPOSIT` 且 `deposit > 0`：t0 收保证金 `INFLOW`，末期退保证金 `OUTFLOW`；
3. `UPFRONT` 且 `includeInIrr` 的费用：t0 `INFLOW`；
4. `ResidualHandling.SEPARATE`：末期追加 `RESIDUAL` `INFLOW`；`BALLOON_IN_RENT` 不追加；
5. `tax` 已含在 `rentDue` 内，**不单列**；
6. `DEDUCT_FROM_PRINCIPAL` 的保证金不单列；
7. 按日期升序，同日按 `PRINCIPAL → FEE → DEPOSIT → RENT → RESIDUAL` 排序，`sequence` 从 0 连续编号。

- [ ] **Step 1: 写失败测试**

```java
@Test
void lessor_flows_start_with_principal_outflow_and_end_with_rent_inflow() {
    List<CashFlow> flows = CashFlowBuilder.build(schedule(), element(), CalculationOptions.defaults());

    assertThat(flows.get(0).type()).isEqualTo(CashFlowType.PRINCIPAL);
    assertThat(flows.get(0).direction()).isEqualTo(CashFlowDirection.OUTFLOW);
    assertThat(flows.get(0).amount()).isEqualByComparingTo("1000000.00");
    assertThat(flows).filteredOn(f -> f.type() == CashFlowType.RENT).hasSize(12);
    assertThat(flows).allSatisfy(f -> assertThat(f.amount()).isGreaterThan(BigDecimal.ZERO));
}

@Test
void tax_is_not_double_counted() {
    List<CashFlow> flows = CashFlowBuilder.build(scheduleWithTax(), elementWithTax(), CalculationOptions.defaults());
    BigDecimal inflow = MoneyUtils.sum(flows.stream()
            .filter(f -> f.direction() == CashFlowDirection.INFLOW)
            .map(CashFlow::amount).collect(toList()));
    assertThat(inflow).isEqualByComparingTo("1032797.16"); // 仅租金，不含税
}

@Test
void balloon_residual_is_not_added_twice() {
    LeaseElement balloon = element().toBuilder().residualHandling(ResidualHandling.BALLOON_IN_RENT).build();
    List<CashFlow> flows = CashFlowBuilder.build(scheduleWithBalloon(), balloon, CalculationOptions.defaults());
    assertThat(flows).noneMatch(f -> f.type() == CashFlowType.RESIDUAL);
}

@Test
void deposit_only_appears_when_basis_includes_it() {
    LeaseElement withDeposit = element().toBuilder().deposit(new BigDecimal("50000")).build();
    List<CashFlow> none = CashFlowBuilder.build(schedule(), withDeposit,
            new CalculationOptions(false, RateChangePolicy.RESOLVE, CashFlowBasis.INCLUDE_FEES));
    List<CashFlow> with = CashFlowBuilder.build(schedule(), withDeposit,
            new CalculationOptions(false, RateChangePolicy.RESOLVE, CashFlowBasis.INCLUDE_DEPOSIT));
    assertThat(none).noneMatch(f -> f.type() == CashFlowType.DEPOSIT);
    assertThat(with).filteredOn(f -> f.type() == CashFlowType.DEPOSIT).hasSize(2);
}

@Test
void sequence_is_contiguous_and_ordering_is_stable() {
    List<CashFlow> flows = CashFlowBuilder.build(schedule(), element(), CalculationOptions.defaults());
    for (int i = 0; i < flows.size(); i++) {
        assertThat(flows.get(i).sequence()).isEqualTo(i);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-domain test -Dtest=CashFlowBuilderTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

先收集事件到 `List<CashFlow>`（用 `EnumMap<CashFlowType, Integer>` 定义同日排序权重），再 `sort(comparing(date).thenComparing(typeOrder).thenComparing(sequence))`，最后重排 `sequence`。返回不可变列表。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-domain test -Dtest=CashFlowBuilderTest`
Expected: 5 tests PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-domain/src
git commit -m "feat: add cash flow builder with perspective and basis rules"
```

---

### Task 20: 求根器与收益率指标（NPV / IRR / XIRR / MIRR）

**Files:**
- Create: `calc-domain/src/main/java/com/fincalc/domain/port/RootSolver.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/BisectionNewtonSolver.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/IrrCalculator.java`
- Test: `calc-domain/src/test/java/com/fincalc/domain/support/RootSolverTest.java`
- Test: `calc-domain/src/test/java/com/fincalc/domain/support/IrrCalculatorTest.java`

**Interfaces:**
- Consumes: `CashFlow`、`CashFlowDirection`、`MoneyUtils`
- Produces:
  - `interface RootSolver extends OrderedExtension { BigDecimal solve(Function<BigDecimal, BigDecimal> f, BigDecimal lo, BigDecimal hi, BigDecimal tolerance); }`
  - `BisectionNewtonSolver`（`code() = "BISECTION_NEWTON"`）——二分保证收敛，Newton 加速；100 次上限，默认容差 `1e-10`
  - `IrrCalculator.npv(List<CashFlow>, BigDecimal periodRate) -> BigDecimal`（按 `sequence` 折现）
  - `IrrCalculator.xnpv(List<CashFlow>, BigDecimal annualRate) -> BigDecimal`（按实际天数 / 365）
  - `IrrCalculator.periodIrr(List<CashFlow>) -> BigDecimal`
  - `IrrCalculator.xirr(List<CashFlow>) -> BigDecimal`
  - `IrrCalculator.annualize(BigDecimal periodIrr, int periodsPerYear) -> BigDecimal`（复利）
  - `IrrCalculator.simpleAnnualize(BigDecimal periodIrr, int periodsPerYear) -> BigDecimal`（单利）
  - `IrrCalculator.mirr(List<CashFlow>, BigDecimal reinvestRate, BigDecimal financeRate, int periodsPerYear) -> BigDecimal`
  - `IrrCalculator.hasSingleSignChange(List<CashFlow>) -> boolean`

- [ ] **Step 1: 写失败测试**

```java
@Test
void solver_finds_root_of_simple_function() {
    // f(x) = x^2 - 4，根为 2
    Function<BigDecimal, BigDecimal> f = x -> x.multiply(x).subtract(new BigDecimal("4"));
    assertThat(new BisectionNewtonSolver().solve(f, BigDecimal.ONE, new BigDecimal("3"), new BigDecimal("1e-10")))
            .isBetween(new BigDecimal("1.9999999999"), new BigDecimal("2.0000000001"));
}

@Test
void npv_at_irr_is_zero() {
    List<CashFlow> flows = flatLoanFlows();
    BigDecimal irr = IrrCalculator.periodIrr(flows);
    assertThat(IrrCalculator.npv(flows, irr).abs()).isLessThan(new BigDecimal("0.01"));
}

@Test
void period_irr_of_flat_loan_matches_periodic_rate() {
    assertThat(IrrCalculator.periodIrr(flatLoanFlows()))
            .isBetween(new BigDecimal("0.0049"), new BigDecimal("0.0051"));
}

@Test
void annualization_supports_both_conventions() {
    assertThat(IrrCalculator.annualize(new BigDecimal("0.005"), 12))
            .isEqualByComparingTo("0.061677811865");
    assertThat(IrrCalculator.simpleAnnualize(new BigDecimal("0.005"), 12))
            .isEqualByComparingTo("0.060000000000");
}

@Test
void xirr_matches_excel_reference_across_leap_year() {
    List<CashFlow> flows = List.of(
            flow(0, LocalDate.of(2024, 1, 1), "1000000", CashFlowType.PRINCIPAL, OUTFLOW),
            flow(1, LocalDate.of(2025, 1, 1), "1100000", CashFlowType.RENT, INFLOW));
    // 2024 是闰年，间隔 366 天：r = 1.1^(365/366) − 1 ≈ 0.099710
    assertThat(IrrCalculator.xirr(flows)).isBetween(new BigDecimal("0.0990"), new BigDecimal("0.1000"));
}

@Test
void returns_null_when_no_sign_change() {
    List<CashFlow> flows = List.of(
            flow(0, LocalDate.now(), "100", CashFlowType.RENT, INFLOW),
            flow(1, LocalDate.now().plusMonths(1), "100", CashFlowType.RENT, INFLOW));
    assertThat(IrrCalculator.periodIrr(flows)).isNull();
    assertThat(IrrCalculator.hasSingleSignChange(flows)).isFalse();
}

@Test
void mirr_uses_reinvestment_and_finance_rates() {
    BigDecimal mirr = IrrCalculator.mirr(flatLoanFlows(),
            new BigDecimal("0.04"), new BigDecimal("0.08"), 12);
    assertThat(mirr).isBetween(new BigDecimal("0.03"), new BigDecimal("0.09"));
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-domain test -Dtest='RootSolverTest,IrrCalculatorTest'`
Expected: 编译失败。

- [ ] **Step 3: 实现**

按设计 §9.5 的求根约定：符号变化检查 → 二分 `[−0.9999, 10]` → Newton 加速 → 不收敛返回 `null`。`npv` 与 `xnpv` 的符号约定：`INFLOW` 为正、`OUTFLOW` 为负。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-domain test -Dtest='RootSolverTest,IrrCalculatorTest'`
Expected: 全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-domain/src
git commit -m "feat: add root solver and npv/irr/xirr/mirr calculators"
```

---

### Task 21: 金融指标 SPI 与指标集

**Files:**
- Create: `calc-domain/src/main/java/com/fincalc/domain/metric/MetricCodes.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/metric/MetricContext.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/metric/FinancialMetric.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/metric/MetricRegistry.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/metric/impl/TotalMetrics.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/metric/impl/NpvMetric.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/metric/impl/IrrMetrics.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/metric/impl/XirrMetric.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/metric/impl/MirrMetric.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/metric/impl/PaybackMetric.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/metric/impl/DurationMetrics.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/metric/impl/RentalRateMetric.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/metric/impl/AverageBalanceMetric.java`
- Test: `calc-domain/src/test/java/com/fincalc/domain/metric/MetricRegistryTest.java`

**Interfaces:**
- Consumes: `CashFlow`、`Schedule`、`LeaseElement`、`IrrCalculator`
- Produces:
  - `MetricCodes`：`TOTAL_PRINCIPAL`、`TOTAL_INTEREST`、`TOTAL_FEE`、`TOTAL_TAX`、`TOTAL_RENT`、`RESIDUAL_VALUE`、`RENTAL_RATE`、`NPV`、`XNPV`、`IRR_PERIOD`、`IRR_ANNUAL`、`IRR_ANNUAL_SIMPLE`、`XIRR`、`MIRR`、`PAYBACK_PERIOD`、`DISCOUNTED_PAYBACK`、`DURATION_MACAULAY`、`DURATION_MODIFIED`、`CONVEXITY`、`AVG_OUTSTANDING_BALANCE`、`TRUE_ANNUAL_RATE`；以及 `MetricCodes.ALL -> Set<String>`（全部 code 的不可变集合，供测试与文档生成使用）
  - `MetricContext(List<CashFlow> cashFlows, Schedule schedule, LeaseElement element, int periodsPerYear, BigDecimal discountRate, BigDecimal reinvestRate, BigDecimal financeRate)`
  - `interface FinancialMetric extends OrderedExtension { BigDecimal compute(MetricContext ctx); }`
  - 复用 Task 7 已定义的 `FinancialMetrics`（本任务只填充实现，不重新定义）
  - `MetricRegistry.defaults() -> MetricRegistry`；`register(FinancialMetric)`；`computeAll(MetricContext) -> FinancialMetrics`

- [ ] **Step 1: 写失败测试**

```java
@Test
void computes_all_builtin_metrics() {
    MetricContext ctx = context(flatLoanSchedule());
    FinancialMetrics m = MetricRegistry.defaults().computeAll(ctx);

    assertThat(m.find(MetricCodes.TOTAL_PRINCIPAL)).isEqualByComparingTo("1000000.00");
    assertThat(m.find(MetricCodes.TOTAL_RENT)).isEqualByComparingTo("1032797.16");
    assertThat(m.find(MetricCodes.RENTAL_RATE)).isEqualByComparingTo("1.032797");
    assertThat(m.find(MetricCodes.IRR_PERIOD)).isBetween(new BigDecimal("0.0049"), new BigDecimal("0.0051"));
    assertThat(m.find(MetricCodes.IRR_ANNUAL)).isNotNull();
    assertThat(m.find(MetricCodes.XIRR)).isNotNull();
    assertThat(m.find(MetricCodes.PAYBACK_PERIOD)).isBetween(new BigDecimal("0.9"), new BigDecimal("1.0"));
    assertThat(m.find(MetricCodes.DURATION_MACAULAY)).isGreaterThan(BigDecimal.ZERO);
    assertThat(m.find(MetricCodes.AVG_OUTSTANDING_BALANCE)).isBetween(
            new BigDecimal("400000"), new BigDecimal("600000"));
}

@Test
void npv_at_irr_is_zero() {
    FinancialMetrics m = MetricRegistry.defaults().computeAll(context(flatLoanSchedule()));
    assertThat(m.find(MetricCodes.NPV).abs()).isLessThan(new BigDecimal("0.01"));
}

@Test
void uncomputable_metric_is_absent_not_zero() {
    MetricContext ctx = context(scheduleWithSingleInflowOnly());
    FinancialMetrics m = MetricRegistry.defaults().computeAll(ctx);
    assertThat(m.find(MetricCodes.IRR_PERIOD)).isNull();
    assertThat(m.find(MetricCodes.PAYBACK_PERIOD)).isNull();
}

@Test
void custom_metric_can_be_registered_without_touching_engine() {
    MetricRegistry reg = MetricRegistry.defaults()
            .register(new FinancialMetric() {
                public String code() { return "CUSTOM_X"; }
                public BigDecimal compute(MetricContext c) { return new BigDecimal("42"); }
            });
    assertThat(reg.computeAll(context(flatLoanSchedule())).find("CUSTOM_X"))
            .isEqualByComparingTo("42");
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-domain test -Dtest=MetricRegistryTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

- `TotalMetrics` 一个类注册 6 个 code（`TOTAL_*`、`RESIDUAL_VALUE`），`order()` 都是 10；
- `NpvMetric`：显式 `discountRate` 为空时用 `IRR_PERIOD` 折现，二者都为空返回 `null`；
- `PaybackMetric`：按设计 §9.5 的插值公式；期末仍未回收返回 `null`；
- `DurationMetrics`：`PV_t = CF_t / (1+y/m)^t`，`t = periodNo/m`，分母为 `ΣPV_t`；零分母返回 `null`；
- `MetricRegistry.computeAll` 用 `LinkedHashMap` 保持 `order()` 稳定，单个指标异常只记 WARN 并跳过，不影响其他指标。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-domain test -Dtest=MetricRegistryTest`
Expected: 4 tests PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-domain/src
git commit -m "feat: add financial metric spi and builtin metric set"
```

---

### Task 22: 实际利率法摊销表

**Files:**
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/EffectiveInterestRow.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/model/EffectiveInterestTable.java`
- Create: `calc-domain/src/main/java/com/fincalc/domain/support/EffectiveInterestCalculator.java`
- Test: `calc-domain/src/test/java/com/fincalc/domain/support/EffectiveInterestCalculatorTest.java`

**Interfaces:**
- Consumes: `Schedule`、`Installment`、`IrrCalculator`
- Produces:
  - `EffectiveInterestRow(periodNo, openingNetInvestment, interestIncome, cashReceived, principalRecovery, closingNetInvestment)`
  - `EffectiveInterestTable(List<EffectiveInterestRow> rows, BigDecimal eir, BigDecimal totalInterestDifference)`
  - `EffectiveInterestCalculator.calculate(Schedule schedule, BigDecimal periodIrr) -> EffectiveInterestTable`

**口径：**
1. `EIR_period = periodIrr`（由 `IrrCalculator.periodIrr` 传入）；
2. `interestIncome = openingNetInvestment × EIR_period`，scale 2；
3. `cashReceived = installment.rentDue()`；
4. `principalRecovery = cashReceived − interestIncome`；
5. `closingNetInvestment = openingNetInvestment − principalRecovery`；
6. 末期强制 `closingNetInvestment = 0`，差额并入末期 `interestIncome`；
7. `totalInterestDifference = Σ interestIncome − Σ installment.interestPaid()`。

- [ ] **Step 1: 写失败测试**

```java
@Test
void effective_interest_table_closes_to_zero() {
    Schedule s = schedule();
    EffectiveInterestTable t = EffectiveInterestCalculator.calculate(s, IrrCalculator.periodIrr(flowsOf(s)));

    assertThat(t.rows()).hasSize(12);
    assertThat(t.rows().get(0).openingNetInvestment()).isEqualByComparingTo("1000000.00");
    assertThat(t.rows().get(11).closingNetInvestment()).isEqualByComparingTo("0.00");
    assertThat(t.rows().get(0).interestIncome()).isEqualByComparingTo("5000.00");
    assertThat(t.rows()).allSatisfy(r ->
            assertThat(r.principalRecovery()).isEqualByComparingTo(
                    r.cashReceived().subtract(r.interestIncome())));
}

@Test
void reports_interest_difference_against_nominal_method() {
    EffectiveInterestTable t = EffectiveInterestCalculator.calculate(schedule(), IrrCalculator.periodIrr(flowsOf(schedule())));
    assertThat(t.totalInterestDifference().abs()).isLessThan(new BigDecimal("0.05"));
}

@Test
void rejects_non_positive_rate() {
    assertThatThrownBy(() -> EffectiveInterestCalculator.calculate(schedule(), BigDecimal.ZERO))
            .isInstanceOf(BusinessException.class);
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-domain test -Dtest=EffectiveInterestCalculatorTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

逐期推进用 `opening = 上一期 closing`；末期差额吸收保证归零。`eir <= 0` 抛 `FCS-CALC-3006`。返回不可变表。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-domain test -Dtest=EffectiveInterestCalculatorTest`
Expected: 3 tests PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-domain/src
git commit -m "feat: add effective interest amortization table"
```

---

### Task 23: 规则元模型

**Files:**
- Create: `calc-rule/src/main/java/com/fincalc/rule/model/RateBase.java`
- Create: `calc-rule/src/main/java/com/fincalc/rule/model/RuleRow.java`
- Create: `calc-rule/src/main/java/com/fincalc/rule/model/CompiledRule.java`
- Create: `calc-rule/src/main/java/com/fincalc/rule/model/CompiledRuleSet.java`
- Create: `calc-rule/src/main/java/com/fincalc/rule/error/RuleErrorCode.java`
- Test: `calc-rule/src/test/java/com/fincalc/rule/model/CompiledRuleSetTest.java`

**Interfaces:**
- Consumes: `domain.model.*`、`domain.port.RuleSetView`
- Produces:
  - `enum RateBase { OPENING_BALANCE, ORIGINAL_PRINCIPAL, REMAINING_PRINCIPAL, FIXED_AMOUNT }`
  - `RuleRow`：字段同设计文档 §8.2（`ruleId, priority, fromPeriod, toPeriod, annualRate, rateBase, principalPlan, principalValue, principalSequence, interestTreatment, paymentTiming, specialAction, actionParam, memo, rowNumber, cellRef`）
  - `CompiledRule(RuleRow row, Predicate<EvalContext> condition)` ← `EvalContext` 由 Task 24 提供
  - `CompiledRuleSet implements RuleSetView`：`resolve(...)` 按 `priority` 升序返回首个命中规则；`ruleSetHash() -> String`（SHA-256 over canonical rows）；`rules() -> List<CompiledRule>`

- [ ] **Step 1: 写失败测试**

```java
@Test
void resolve_returns_lowest_priority_matching_rule() {
    CompiledRuleSet set = ruleSet(
            row("R1", 20, 1, 6, "0.06"),
            row("R2", 10, 1, 3, "0.03"));
    RuleOutcome out = set.resolve(2, new BigDecimal("1000000"), 30, new BigDecimal("0.06"));
    assertThat(out.ruleId()).isEqualTo("R2");
    assertThat(out.annualRate()).isEqualByComparingTo("0.03");
}

@Test
void unmatched_period_returns_not_matched() {
    CompiledRuleSet set = ruleSet(row("R1", 10, 1, 3, "0.06"));
    assertThat(set.resolve(5, BigDecimal.ONE, 30, new BigDecimal("0.06")).matched()).isFalse();
}

@Test
void hash_is_stable_for_same_rows() {
    CompiledRuleSet a = ruleSet(row("R1", 10, 1, 12, "0.06"));
    CompiledRuleSet b = ruleSet(row("R1", 10, 1, 12, "0.06"));
    assertThat(a.ruleSetHash()).isEqualTo(b.ruleSetHash()).hasSize(64);
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-rule test -Dtest=CompiledRuleSetTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

`ruleSetHash` 用 `MessageDigest SHA-256`，输入为按 `(priority, ruleId)` 排序的规范化字符串（字段用 `|` 分隔，日期 `yyyy-MM-dd`，金额 `toPlainString`）。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-rule test -Dtest=CompiledRuleSetTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-rule/src
git commit -m "feat: add compiled rule model and rule set view"
```

---

### Task 24: 表达式引擎核心（词法 / 语法 / AST / 求值）

**Files:**
- Create: `calc-rule/src/main/java/com/fincalc/rule/expression/ValueType.java`
- Create: `calc-rule/src/main/java/com/fincalc/rule/expression/Value.java`
- Create: `calc-rule/src/main/java/com/fincalc/rule/expression/FormulaError.java`
- Create: `calc-rule/src/main/java/com/fincalc/rule/expression/EvalContext.java`
- Create: `calc-rule/src/main/java/com/fincalc/rule/expression/Expression.java`
- Create: `calc-rule/src/main/java/com/fincalc/rule/expression/Token.java`
- Create: `calc-rule/src/main/java/com/fincalc/rule/expression/Lexer.java`
- Create: `calc-rule/src/main/java/com/fincalc/rule/expression/AstNode.java`
- Create: `calc-rule/src/main/java/com/fincalc/rule/expression/Parser.java`
- Create: `calc-rule/src/main/java/com/fincalc/rule/expression/ExpressionParser.java`
- Create: `calc-rule/src/main/java/com/fincalc/rule/function/FormulaFunction.java`
- Create: `calc-rule/src/main/java/com/fincalc/rule/function/FunctionRegistry.java`（仅骨架：`empty/register/find/codes`）
- Test: `calc-rule/src/test/java/com/fincalc/rule/expression/ExpressionParserTest.java`

**Interfaces:**
- Consumes: `BusinessException`、`RuleErrorCode`
- Produces:
  - `enum ValueType { NUMBER, TEXT, BOOLEAN, BLANK, ERROR }`
  - `FormulaError(String code, String message)`——`code` 用 `FCS-RULE-3005..3009`
  - `Value`：不可变；`number(BigDecimal)`、`text(String)`、`bool(boolean)`、`blank()`、`error(FormulaError)`；`asNumber()`（`BLANK`→0，`BOOLEAN`→1/0）、`asBoolean()`（`NUMBER` 非零为真）、`asText()`
  - `EvalContext(Map<String, Value> variables, FunctionRegistry functions)`；`EvalContext.empty()`；`variable(String) -> Value`
  - `Expression { Value evaluate(EvalContext); default boolean test(EvalContext); }`——`test` 遇到 `ERROR` 抛 `BusinessException`
  - `ExpressionParser.parse(String expr, FunctionRegistry functions) -> Expression`（空串返回恒真常量表达式）
  - 语法：`= <> < > <= >=`、`+ - * / ^`、`&& || !`、括号、数字/字符串/`TRUE`/`FALSE`/`BLANK`、函数调用

**变量白名单（硬编码，大小写不敏感）**：`PERIOD BALANCE DAYS RATE INDEX IS_LAST PRINCIPAL TOTAL_PERIODS RESIDUAL`。未知标识符 → `FCS-RULE-3001`。

- [ ] **Step 1: 写失败测试**

```java
@Test
void evaluates_arithmetic_with_precedence() {
    assertThat(eval("1 + 2 * 3").asNumber()).isEqualByComparingTo("7");
    assertThat(eval("2 ^ 3 + 1").asNumber()).isEqualByComparingTo("9");
}

@Test
void evaluates_comparison_and_logic() {
    Expression e = parse("PERIOD >= 1 && PERIOD <= 12 && RATE > 0.05");
    assertThat(e.test(ctx(3))).isTrue();
    assertThat(e.test(ctx(13))).isFalse();
}

@Test
void function_call_resolves_from_registry() {
    FunctionRegistry reg = FunctionRegistry.empty().register(new FormulaFunction() {
        public String code() { return "DOUBLE"; }
        public int minArity() { return 1; }
        public int maxArity() { return 1; }
        public Value invoke(List<Value> args, EvalContext c) {
            return Value.number(args.get(0).asNumber().multiply(new BigDecimal("2")));
        }
    });
    assertThat(eval("DOUBLE(21)", reg).asNumber()).isEqualByComparingTo("42");
}

@Test
void division_by_zero_becomes_excel_error_value() {
    Value v = eval("1 / 0");
    assertThat(v.type()).isEqualTo(ValueType.ERROR);
    assertThat(((FormulaError) v.raw()).code()).isEqualTo("FCS-RULE-3005");
}

@Test
void rejects_unknown_variable() {
    assertThatThrownBy(() -> parse("FOO > 1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("FCS-RULE-3001");
}

@Test
void rejects_unknown_function() {
    assertThatThrownBy(() -> parse("NOPE(1)"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("FCS-RULE-3009");
}

@Test
void rejects_method_call_and_property_access() {
    assertThatThrownBy(() -> parse("PERIOD.getClass()"))
            .isInstanceOf(BusinessException.class);
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-rule test -Dtest=ExpressionParserTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

递归下降：`expr → comparison → additive → term → power → unary → primary`。`Lexer` 产出带行列号的 token，便于错误定位。`Parser` 把函数调用解析为 `CallNode(code, args)`，**编译期**向 `FunctionRegistry` 校验函数存在与参数个数。禁止 `.`、`new`、赋值、方法调用。数值运算统一 `MathContext.DECIMAL128`；除零返回 `ERROR` 值而不是抛异常（由 `IFERROR` 捕获）。`AstNode` 用 `eval(EvalContext)` 递归求值，不做反射。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-rule test -Dtest=ExpressionParserTest`
Expected: 7 tests PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-rule/src
git commit -m "feat: add expression lexer, parser and ast evaluator"
```

---

### Task 25: Excel 兼容函数库

**Files:**
- Modify: `calc-rule/src/main/java/com/fincalc/rule/function/FunctionRegistry.java`（新增 `defaults()`、`hash()`）
- Create: `calc-rule/src/main/java/com/fincalc/rule/function/math/MathFunctions.java`
- Create: `calc-rule/src/main/java/com/fincalc/rule/function/financial/FinancialFunctions.java`
- Create: `calc-rule/src/main/java/com/fincalc/rule/function/datex/DateFunctions.java`
- Create: `calc-rule/src/main/java/com/fincalc/rule/function/datex/DateSerialize.java`
- Create: `calc-rule/src/main/java/com/fincalc/rule/function/logical/LogicalFunctions.java`
- Create: `calc-rule/src/main/java/com/fincalc/rule/function/lookup/LookupFunctions.java`
- Create: `calc-rule/src/main/java/com/fincalc/rule/function/textx/TextFunctions.java`
- Create: `calc-rule/src/main/java/com/fincalc/rule/function/info/InfoFunctions.java`
- Test: `calc-rule/src/test/java/com/fincalc/rule/function/FunctionRegistryTest.java`
- Test: `calc-rule/src/test/java/com/fincalc/rule/function/ExcelSemanticsTest.java`

**Interfaces:**
- Consumes: `Value`、`FormulaError`、`EvalContext`、`MathContext`
- Produces:
  - `FunctionRegistry.defaults() -> FunctionRegistry`（注册约 70 个内置函数并冻结）
  - `FunctionRegistry.hash() -> String`（按 `code` 排序后 SHA-256）
  - 各 `*Functions.register(FunctionRegistry)` 静态方法
  - 日期一律用 **Excel 1900 序列号** `NUMBER` 表示；`DateSerialize.toSerial(LocalDate)` / `toLocalDate(BigDecimal)` 工具类同包提供

**v1 清单**：数学 `ABS ROUND ROUNDUP ROUNDDOWN INT TRUNC MOD POWER SQRT EXP LN LOG LOG10 SIGN MAX MIN SUM PRODUCT AVERAGE CEILING FLOOR`；财务 `PMT IPMT PPMT PV FV RATE NPER NPV IRR XIRR MIRR EFFECT NOMINAL SLN DB DDB SYD`；日期 `DATE YEAR MONTH DAY EDATE EOMONTH DAYS DAYS360 YEARFRAC DATEVALUE`；逻辑 `IF IFS AND OR NOT XOR IFERROR IFNA SWITCH TRUE FALSE`；查找 `CHOOSE INDEX MATCH LOOKUP VLOOKUP`；文本 `CONCAT CONCATENATE LEFT RIGHT MID LEN TRIM UPPER LOWER TEXT VALUE`；信息 `ISBLANK ISNUMBER ISTEXT ISERROR NA`。

- [ ] **Step 1: 写失败测试（Excel 语义对齐）**

```java
@Test
void round_is_half_away_from_zero_like_excel() {
    assertThat(eval("ROUND(2.5, 0)").asNumber()).isEqualByComparingTo("3");
    assertThat(eval("ROUND(-2.5, 0)").asNumber()).isEqualByComparingTo("-3");
    assertThat(eval("ROUND(2.345, 2)").asNumber()).isEqualByComparingTo("2.35");
}

@Test
void mod_sign_follows_divisor_like_excel() {
    assertThat(eval("MOD(-3, 2)").asNumber()).isEqualByComparingTo("1");
    assertThat(eval("MOD(3, -2)").asNumber()).isEqualByComparingTo("-1");
}

@Test
void int_floor_and_trunc_toward_zero() {
    assertThat(eval("INT(-1.5)").asNumber()).isEqualByComparingTo("-2");
    assertThat(eval("TRUNC(-1.5)").asNumber()).isEqualByComparingTo("-1");
}

@Test
void pmt_matches_excel_reference() {
    // Excel: PMT(0.005, 12, -1000000) = 86066.43
    assertThat(eval("ROUND(PMT(0.005, 12, -1000000), 2)").asNumber())
            .isEqualByComparingTo("86066.43");
}

@Test
void edate_and_eomonth_handle_month_end_and_leap_year() {
    assertThat(eval("EDATE(DATE(2024,1,31), 1)").asNumber())
            .isEqualByComparingTo(String.valueOf(DateSerialize.toSerial(LocalDate.of(2024, 2, 29))));
    assertThat(eval("EOMONTH(DATE(2024,2,1), 0)").asNumber())
            .isEqualByComparingTo(String.valueOf(DateSerialize.toSerial(LocalDate.of(2024, 2, 29))));
}

@Test
void iferror_catches_div0() {
    assertThat(eval("IFERROR(1/0, -1)").asNumber()).isEqualByComparingTo("-1");
}

@Test
void registry_exposes_all_codes_and_stable_hash() {
    FunctionRegistry r1 = FunctionRegistry.defaults();
    FunctionRegistry r2 = FunctionRegistry.defaults();
    assertThat(r1.codes()).contains("PMT", "ROUND", "EDATE", "IF", "SUM");
    assertThat(r1.hash()).isEqualTo(r2.hash());
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-rule test -Dtest='FunctionRegistryTest,ExcelSemanticsTest'`
Expected: 编译失败。

- [ ] **Step 3: 实现**

每个函数一个 `FormulaFunction` 内部类（或 `enum` 实现），按 `code` 分派；**不使用反射**。`ROUND` 用 `HALF_UP`；`MOD` 用 `n − d×floor(n/d)`；`INT` 用 `RoundingMode.FLOOR`；`TRUNC` 用 `DOWN`。`IF` 必须**惰性求值**（只求命中分支），因此 `IF/IFERROR/IFS/SWITCH` 的实参在 AST 层以未求值节点传入——在 `FormulaFunction` 之外增加 `LazyFunction` 子接口：

```java
public interface LazyFunction extends FormulaFunction {
    Value invokeLazy(List<Expression> args, EvalContext ctx);
}
```

`PMT/PV/FV/RATE/NPER` 复用 `calc-domain` 的 `PmtSolver` 与 `IrrCalculator`（`calc-rule` 已依赖 `calc-domain`）。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-rule test -Dtest='FunctionRegistryTest,ExcelSemanticsTest'`
Expected: 全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-rule/src
git commit -m "feat: add excel-compatible function library"
```

---

### Task 26: 规则编译器与校验

**Files:**
- Create: `calc-rule/src/main/java/com/fincalc/rule/compiler/RuleCompiler.java`
- Create: `calc-rule/src/main/java/com/fincalc/rule/compiler/RuleValidationException.java`
- Create: `calc-rule/src/main/java/com/fincalc/rule/compiler/RuleSetValidator.java`
- Test: `calc-rule/src/test/java/com/fincalc/rule/compiler/RuleCompilerTest.java`

**Interfaces:**
- Consumes: `RuleRow`、`CompiledRuleSet`、`ExpressionParser`、`LeaseElement`、`FunctionRegistry`
- Produces:
  - `RuleCompiler(FunctionRegistry functions)`；`compile(List<RuleRow> rows, LeaseElement element, boolean allowPartial) -> CompiledRuleSet`
  - `CompiledRuleSet.functionRegistryHash() -> String`（进入 `ruleSetHash`，保证脚本变更可追溯）
  - `RuleValidationException extends BusinessException`，`issues() -> List<ValidationIssue>`

**校验规则（全部实现，缺一不可）：**
| 编号 | 规则 | 错误码 |
|---|---|---|
| V1 | `fromPeriod >= 1` 且 `toPeriod <= element.periods()` 且 `from <= to` | `FCS-RULE-2001` |
| V2 | 同 `priority` 区间不重叠 | `FCS-RULE-2002` |
| V3 | 非 `allowPartial` 时 1..n 必须被完整覆盖 | `FCS-RULE-2001` |
| V4 | 所有 `PERCENT` 行 `principalValue` 合计 = 100 | `FCS-RULE-2003` |
| V5 | `FIXED` 行 `value × 期数` + 其他计划合计 = 本金 | `FCS-RULE-2003` |
| V6 | `CUSTOM` 序列长度 = 覆盖期数 | `FCS-RULE-2003` |
| V7 | `annualRate` 在 `[0,1]` | `FCS-RULE-2006` |
| V8 | `specialAction ∈ {NONE, FEE_AMORTIZE, DEPOSIT_OFFSET}` | `FCS-RULE-2005` |

- [ ] **Step 1: 写失败测试**

```java
@Test
void rejects_gap_in_coverage() {
    RuleValidationException ex = assertThrows(RuleValidationException.class,
            () -> compiler().compile(List.of(row("R1", 1, 6)), element(12), false));
    assertThat(ex.issues()).anySatisfy(i -> assertThat(i.code()).isEqualTo("FCS-RULE-2001"));
}

@Test
void allows_gap_when_partial_enabled() {
    CompiledRuleSet set = compiler().compile(List.of(row("R1", 1, 6)), element(12), true);
    assertThat(set.resolve(8, BigDecimal.ONE, 30, new BigDecimal("0.06")).matched()).isFalse();
}

@Test
void rejects_percent_sum_not_100() {
    RuleValidationException ex = assertThrows(RuleValidationException.class,
            () -> compiler().compile(List.of(percentRow("R1", 1, 12, "60")), element(12), false));
    assertThat(ex.issues()).anySatisfy(i -> assertThat(i.code()).isEqualTo("FCS-RULE-2003"));
}

@Test
void rejects_unsupported_special_action() {
    RuleValidationException ex = assertThrows(RuleValidationException.class,
            () -> compiler().compile(List.of(rowWithAction("PREPAY")), element(12), false));
    assertThat(ex.issues()).anySatisfy(i -> assertThat(i.code()).isEqualTo("FCS-RULE-2005"));
}

@Test
void rejects_overlapping_same_priority() {
    RuleValidationException ex = assertThrows(RuleValidationException.class,
            () -> compiler().compile(List.of(row("R1", 1, 6), row("R2", 3, 9)), element(12), false));
    assertThat(ex.issues()).anySatisfy(i -> assertThat(i.code()).isEqualTo("FCS-RULE-2002"));
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-rule test -Dtest=RuleCompilerTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

先按 V1–V8 全量收集 issues，全部收集完后若有 `ERROR` 再一次性抛 `RuleValidationException`（不要遇到第一个就抛，便于前端一次展示全部问题）。通过后按 `priority` 升序构造 `CompiledRule`。所有表达式列用构造注入的 `FunctionRegistry` 解析——未知函数在编译期就报 `FCS-RULE-3009`，不拖到运行期。测试辅助方法 `compiler()` 固定用 `new RuleCompiler(FunctionRegistry.defaults())`，后续 Task 28/29 复用同一个 helper。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-rule test -Dtest=RuleCompilerTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-rule/src
git commit -m "feat: add rule compiler with full validation"
```

---

### Task 27: 外部函数脚本与组合

**Files:**
- Create: `calc-rule/src/main/java/com/fincalc/rule/script/FunctionDefinition.java`
- Create: `calc-rule/src/main/java/com/fincalc/rule/script/FunctionScriptParser.java`
- Create: `calc-rule/src/main/java/com/fincalc/rule/script/FunctionScriptLoader.java`
- Create: `calc-rule/src/main/java/com/fincalc/rule/script/ScriptInliner.java`
- Test: `calc-rule/src/test/java/com/fincalc/rule/script/FunctionScriptLoaderTest.java`

**Interfaces:**
- Consumes: `ExpressionParser`、`FunctionRegistry`、`Value`
- Produces:
  - `FunctionDefinition(String name, List<String> parameters, Expression body, String sourceName, int line)`
  - `FunctionScriptParser.parse(String text, String sourceName) -> List<FunctionDefinition>`
  - `FunctionScriptLoader.load(List<Path> files, FunctionRegistry base) -> FunctionRegistry`
  - `FunctionScriptLoader.loadClasspath(String prefix, FunctionRegistry base) -> FunctionRegistry`
  - `FunctionRegistry.withDefinitions(List<FunctionDefinition>) -> FunctionRegistry`（拓扑排序 + 内联 + 冻结）

**脚本语法**：

```
VERSION 1
DEFINE 含税金额(AMOUNT, VAT_RATE) = ROUND(AMOUNT * (1 + VAT_RATE), 2)
DEFINE 租金净额(RENT, VAT_RATE) = ROUND(含税金额(RENT, -VAT_RATE) / (1 + VAT_RATE), 2)
```

**规则**：名称大小写不敏感；允许前向引用；禁止直接/间接递归（`FCS-RULE-3011`）；不得覆盖内置函数，除非 `OVERRIDE`（`FCS-RULE-3010`）；单文件 ≤ 500 定义、表达式深度 ≤ 64；语法错误必须带 `文件:行号`。

- [ ] **Step 1: 写失败测试**

```java
@Test
void defines_and_calls_simple_function() {
    FunctionRegistry reg = load("""
            VERSION 1
            DEFINE 含税金额(AMOUNT, VAT_RATE) = ROUND(AMOUNT * (1 + VAT_RATE), 2)
            """);
    assertThat(eval("含税金额(100, 0.06)", reg).asNumber()).isEqualByComparingTo("106.00");
}

@Test
void supports_forward_reference_and_composition() {
    FunctionRegistry reg = load("""
            VERSION 1
            DEFINE 租金净额(RENT, VAT_RATE) = 含税金额(RENT, -VAT_RATE)
            DEFINE 含税金额(AMOUNT, VAT_RATE) = ROUND(AMOUNT * (1 + VAT_RATE), 2)
            """);
    // 100 / 1.06 = 94.34（等价于 含税金额(100, -0.06)）
    assertThat(eval("租金净额(100, 0.06)", reg).asNumber()).isEqualByComparingTo("94.34");
}

@Test
void rejects_recursive_definition() {
    assertThatThrownBy(() -> load("""
            VERSION 1
            DEFINE A(X) = B(X)
            DEFINE B(X) = A(X)
            """))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("FCS-RULE-3011");
}

@Test
void rejects_override_of_builtin_without_keyword() {
    assertThatThrownBy(() -> load("""
            VERSION 1
            DEFINE ROUND(X, N) = X
            """))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("FCS-RULE-3010");
}

@Test
void syntax_error_reports_file_and_line() {
    assertThatThrownBy(() -> load("""
            VERSION 1
            DEFINE A(X) = X +
            """))
            .hasMessageContaining("test.fcs").hasMessageContaining("2");
}

@Test
void script_can_be_used_inside_rule_expression() {
    FunctionRegistry reg = load("""
            VERSION 1
            DEFINE 高利率(R) = R >= 0.08
            """);
    RuleCompiler compiler = new RuleCompiler(reg);
    CompiledRuleSet set = compiler.compile(
            List.of(rateRowWithCondition("R1", 1, 12, "0.06", "高利率(RATE)")), element(12), false);
    assertThat(set.resolve(1, BigDecimal.ONE, 30, new BigDecimal("0.09")).matched()).isFalse();
}

@Test
void hash_changes_when_script_changes() {
    FunctionRegistry a = load("VERSION 1\nDEFINE F(X) = X + 1\n");
    FunctionRegistry b = load("VERSION 1\nDEFINE F(X) = X + 2\n");
    assertThat(a.hash()).isNotEqualTo(b.hash());
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-rule test -Dtest=FunctionScriptLoaderTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

1. `FunctionScriptParser`：逐行解析，跳过空行与 `#` 注释；保留行号；
2. `FunctionRegistry.withDefinitions`：先按名称建图，DFS 检测环；再按拓扑序把每个定义编译为 `Expression`；
3. `ScriptInliner`：把对脚本函数的调用节点替换为其 AST（形参名替换为实参节点），展开后校验深度 ≤ 64；展开后脚本函数不再有运行时调用开销；
4. `FunctionScriptLoader`：文件按路径排序加载，后加载者覆盖同名定义；返回冻结 registry。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-rule test -Dtest=FunctionScriptLoaderTest`
Expected: 7 tests PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-rule/src
git commit -m "feat: add external function script loader and composition"
```

---

### Task 28: 规则驱动生成器

**Files:**
- Create: `calc-rule/src/main/java/com/fincalc/rule/generator/RuleDrivenGenerator.java`
- Test: `calc-rule/src/test/java/com/fincalc/rule/generator/RuleDrivenGeneratorTest.java`

**Interfaces:**
- Consumes: `AbstractAmortizationGenerator`、`RuleSetView`、`PrincipalPlanKind`、`InterestTreatmentKind`
- Produces: `RuleDrivenGenerator`，`code() = "RULE_DRIVEN"`，`order() = 100`，构造参数 `RuleSetView`

- [ ] **Step 1: 写失败测试**

```java
@Test
void applies_segmented_rates() {
    CompiledRuleSet set = compiler().compile(List.of(
            rateRow("R1", 10, 1, 6, "0.03"),
            rateRow("R2", 20, 7, 12, "0.06")), element(12), false);
    Schedule s = new RuleDrivenGenerator(set).generate(context(baseElement().toBuilder()
            .method(RepaymentMethodId.RULE_DRIVEN).build()));

    assertThat(s.installments().get(0).appliedRate()).isEqualByComparingTo("0.03");
    assertThat(s.installments().get(6).appliedRate()).isEqualByComparingTo("0.06");
    assertThat(s.installments()).allSatisfy(i ->
            assertThat(i.memo()).startsWith("R"));
}

@Test
void supports_none_principal_periods() {
    CompiledRuleSet set = compiler().compile(List.of(
            principalRow("R1", 10, 1, 6, PrincipalPlanKind.NONE, "0"),
            principalRow("R2", 20, 7, 12, PrincipalPlanKind.EQUAL, "0")), element(12), false);
    Schedule s = new RuleDrivenGenerator(set).generate(context(baseElement().toBuilder()
            .method(RepaymentMethodId.RULE_DRIVEN).build()));

    assertThat(s.installments().get(0).principalDue()).isEqualByComparingTo("0.00");
    assertThat(s.installments().get(11).closingBalance()).isEqualByComparingTo("0.00");
}

@Test
void last_period_absorbs_remaining_principal() {
    CompiledRuleSet set = compiler().compile(List.of(
            rateRow("R1", 10, 1, 12, "0.06")), element(12), false);
    Schedule s = new RuleDrivenGenerator(set).generate(context(baseElement().toBuilder()
            .method(RepaymentMethodId.RULE_DRIVEN).build()));
    assertThat(s.installments().get(11).closingBalance()).isEqualByComparingTo("0.00");
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-rule test -Dtest=RuleDrivenGeneratorTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

`allocate` 内：`RuleOutcome o = ruleSet.resolve(...)`；`INHERIT` 时回落到 `element.method()` 对应算法（本任务内仅支持 `EQUAL_PRINCIPAL` 兜底，其余抛 `FCS-RULE-2007`）；`lastPeriod` 强制 `principal = remainingPrincipal` 保证归零。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-rule test -Dtest=RuleDrivenGeneratorTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-rule/src
git commit -m "feat: add rule driven schedule generator"
```

---

### Task 29: 引擎编排（CalculationFacade）

**Files:**
- Create: `calc-engine/src/main/java/com/fincalc/engine/CalculationRequest.java`
- Create: `calc-engine/src/main/java/com/fincalc/engine/CalculationResult.java`
- Create: `calc-engine/src/main/java/com/fincalc/engine/CalculationFacade.java`
- Create: `calc-engine/src/main/java/com/fincalc/engine/GeneratorRegistry.java`
- Test: `calc-engine/src/test/java/com/fincalc/engine/CalculationFacadeTest.java`

**Interfaces:**
- Consumes: `ScheduleGenerator`、`DayCountConventions`、`DateRollConventions`、`ValidatorChain`、`CashFlowBuilder`、`MetricRegistry`、`IrrCalculator`、`EffectiveInterestCalculator`、`FunctionRegistry`、`FunctionScriptLoader`
- Produces:
  - `CalculationRequest(element, ruleSet, options)`（`options` 复用 `com.fincalc.domain.model.CalculationOptions`，引擎不重复定义）
  - `CalculationResult(runId, schedule, effectiveInterest)`
  - `CalculationFacade.calculate(CalculationRequest) -> CalculationResult`；`CalculationFacade.defaults()`（内置函数）；`CalculationFacade.defaults(FunctionRegistry)`
  - `CalculationFacade.defaultsWithScripts(Path externalDir)`——加载 `classpath:functions/*.fcs` + 外部目录后冻结
  - `GeneratorRegistry.defaults() -> GeneratorRegistry`（注册四种内置 + 可选注入 `RuleDrivenGenerator`）

**编排顺序（不可调换，后一步依赖前一步）：**

```
0. FunctionRegistry = 内置 defaults() + classpath:functions/*.fcs + ${fcs.functions.dir}/*.fcs
   （无脚本文件时等价于 defaults()；加载失败直接启动失败，不降级）
1. 组装 CalculationContext（口径/滚动缺失 → FCS-ENG-1001）
2. GeneratorRegistry.require(element.method()) → generate → Schedule（此时 header.metrics 为空、cashFlows 为空）
3. CashFlowBuilder.build(schedule, element, options) → flows
4. MetricRegistry.computeAll(MetricContext(flows, schedule, element, m, discountRate, reinvestRate, financeRate)) → metrics
5. schedule = schedule.with(metrics, flows)
6. ValidatorChain.validate(schedule, element) → 追加 issues
7. periodIrr = metric.find(IRR_PERIOD)；非空则 EffectiveInterestCalculator.calculate(...)
8. 返回 CalculationResult(runId, schedule, effectiveInterest)
```

**函数脚本与规则集的关系**：`RuleCompiler` 用同一个 `FunctionRegistry` 编译规则表达式；`CompiledRuleSet.ruleSetHash` 已包含 `functionRegistryHash`。因此**改脚本 = 改规则版本**，底稿可追溯。

> 生成器**不负责**现金流与指标——它只产出计划表。现金流和指标由引擎统一编排，保证"同一张计划表无论用哪种算法生成，指标口径完全一致"。

- [ ] **Step 1: 写失败测试**

```java
@Test
void routes_to_equal_installment_generator_and_enriches_metrics() {
    CalculationResult r = CalculationFacade.defaults().calculate(
            new CalculationRequest(baseElement(), null, CalculationOptions.defaults()));

    assertThat(r.schedule().installments()).hasSize(12);
    FinancialMetrics m = r.schedule().header().metrics();
    assertThat(m.find(MetricCodes.TOTAL_PRINCIPAL)).isEqualByComparingTo("1000000.00");
    assertThat(m.find(MetricCodes.TOTAL_RENT)).isEqualByComparingTo("1032797.16");
    assertThat(m.find(MetricCodes.IRR_PERIOD)).isNotNull();
    assertThat(m.find(MetricCodes.XIRR)).isNotNull();
    assertThat(m.find(MetricCodes.DURATION_MACAULAY)).isNotNull();
    assertThat(r.schedule().cashFlows()).isNotEmpty();
    assertThat(r.schedule().issues()).noneMatch(i -> i.level() == IssueLevel.ERROR);
}

@Test
void every_generator_produces_the_same_metric_set() {
    Set<String> expected = null;
    for (RepaymentMethodId method : List.of(EQUAL_INSTALLMENT, EQUAL_PRINCIPAL, INTEREST_FIRST, PRINCIPAL_FIRST)) {
        CalculationResult r = CalculationFacade.defaults().calculate(
                new CalculationRequest(baseElement().toBuilder().method(method).build(), null,
                        CalculationOptions.defaults()));
        if (expected == null) {
            expected = r.schedule().header().metrics().codes();
        } else {
            assertThat(r.schedule().header().metrics().codes()).isEqualTo(expected);
        }
    }
}

@Test
void routes_to_rule_driven_when_rule_set_present() {
    CalculationResult r = CalculationFacade.defaults().calculate(
            new CalculationRequest(baseElement().toBuilder()
                    .method(RepaymentMethodId.RULE_DRIVEN).build(), compiledRuleSet(), CalculationOptions.defaults()));
    assertThat(r.schedule().installments()).hasSize(12);
}

@Test
void effective_interest_table_available_when_irr_converges() {
    CalculationResult r = CalculationFacade.defaults().calculate(
            new CalculationRequest(baseElement(), null, CalculationOptions.defaults()));
    assertThat(r.effectiveInterest()).isNotNull();
    assertThat(r.effectiveInterest().rows()).hasSize(12);
    assertThat(r.effectiveInterest().rows().get(11).closingNetInvestment()).isEqualByComparingTo("0.00");
}

@Test
void reports_missing_generator_as_business_error() {
    assertThatThrownBy(() -> new GeneratorRegistry().require("UNKNOWN"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("FCS-ENG");
}

@Test
void external_script_function_is_usable_end_to_end() throws IOException {
    Path dir = Files.createTempDirectory("fcs-fn");
    Files.writeString(dir.resolve("lease.fcs"), """
            VERSION 1
            DEFINE 租金净额(RENT, VAT_RATE) = ROUND(RENT / (1 + VAT_RATE), 2)
            """);
    CalculationFacade facade = CalculationFacade.defaultsWithScripts(dir);

    CalculationResult r = facade.calculate(new CalculationRequest(
            baseElement().toBuilder().method(RepaymentMethodId.RULE_DRIVEN).build(),
            ruleSetUsing("租金净额(RENT, VAT_RATE)"),
            CalculationOptions.defaults()));

    assertThat(r.schedule().hasErrors()).isFalse();
    assertThat(r.schedule().header().metrics().find(MetricCodes.TOTAL_RENT)).isNotNull();
}

@Test
void broken_script_fails_fast_at_startup() throws IOException {
    Path dir = Files.createTempDirectory("fcs-bad");
    Files.writeString(dir.resolve("bad.fcs"), "VERSION 1\nDEFINE A(X) = X +\n");
    assertThatThrownBy(() -> CalculationFacade.defaultsWithScripts(dir))
            .isInstanceOf(BusinessException.class);
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-engine test -Dtest=CalculationFacadeTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

按上面 8 步编排。`runId` 用 `UUID.randomUUID().toString().replace("-", "")`。`EffectiveInterestCalculator` 仅在 `IRR_PERIOD` 非空时调用；为空时 `effectiveInterest = null`（不抛异常，因为 IRR 不收敛属于可预期情况）。`CalculationResult.effectiveInterest()` 返回可空值。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-engine test -Dtest=CalculationFacadeTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-engine/src
git commit -m "feat: add calculation facade and generator registry"
```

---

### Task 30: 黄金用例与属性测试

**Files:**
- Create: `calc-testkit/src/main/java/com/fincalc/testkit/Fixtures.java`
- Create: `calc-testkit/src/main/java/com/fincalc/testkit/ReferenceTables.java`
- Create: `calc-testkit/src/test/java/com/fincalc/testkit/GoldenScheduleTest.java`
- Create: `calc-testkit/src/test/java/com/fincalc/testkit/ConservationPropertyTest.java`
- Modify: `pom.xml`（引入 jqwik）

**Interfaces:**
- Consumes: `CalculationFacade`、全部模型
- Produces:
  - `Fixtures.leaseElement(...)`、`Fixtures.ruleSet(...)`
  - `ReferenceTables.equalInstallment()` 等 4 组不可变参考表（`List<ExpectedRow>`）
  - `ExpectedRow(periodNo, openingBalance, principalDue, interestAccrued, rentDue, closingBalance)`
  - `ExpectedMetrics(totalInterest, totalRent, irrAnnualMin, irrAnnualMax, xirrMin, xirrMax, durationMin, durationMax)`
  - `ReferenceTables.metricExpectations()`——4 算法 × 2 口径共 8 组

- [ ] **Step 1: 写黄金用例（每算法 × 关键口径）**

覆盖矩阵：4 算法 × `THIRTY_360` / `ACT_365F`，共 8 组。断言逐期到分：

```java
@ParameterizedTest
@MethodSource("com.fincalc.testkit.ReferenceTables#all")
void every_method_matches_reference_table_to_the_cent(String method, String dayCount, List<ExpectedRow> expected) {
    Schedule s = calculate(method, dayCount);
    assertThat(s.installments()).hasSameSizeAs(expected);
    for (int i = 0; i < expected.size(); i++) {
        ExpectedRow e = expected.get(i);
        Installment a = s.installments().get(i);
        assertThat(a.openingBalance()).isEqualByComparingTo(e.openingBalance());
        assertThat(a.principalDue()).isEqualByComparingTo(e.principalDue());
        assertThat(a.interestAccrued()).isEqualByComparingTo(e.interestAccrued());
        assertThat(a.rentDue()).isEqualByComparingTo(e.rentDue());
        assertThat(a.closingBalance()).isEqualByComparingTo(e.closingBalance());
    }
}
```

同时在 `ReferenceTables` 中为每种算法写死一组**汇总指标**（`totalPrincipal`、`totalInterest`、`totalRent`、`irrAnnual`、`xirr`、`macaulayDuration`），断言到指定精度：

```java
@ParameterizedTest
@MethodSource("com.fincalc.testkit.ReferenceTables#metricExpectations")
void summary_metrics_match_reference(String method, String dayCount, ExpectedMetrics expected) {
    FinancialMetrics m = calculate(method, dayCount).header().metrics();
    assertThat(m.find(MetricCodes.TOTAL_INTEREST)).isEqualByComparingTo(expected.totalInterest());
    assertThat(m.find(MetricCodes.TOTAL_RENT)).isEqualByComparingTo(expected.totalRent());
    assertThat(m.find(MetricCodes.IRR_ANNUAL)).isBetween(expected.irrAnnualMin(), expected.irrAnnualMax());
    assertThat(m.find(MetricCodes.XIRR)).isBetween(expected.xirrMin(), expected.xirrMax());
    assertThat(m.find(MetricCodes.DURATION_MACAULAY)).isBetween(
            expected.durationMin(), expected.durationMax());
}
```

参考表在 `ReferenceTables` 中以字面量写死，由本任务实现者用实现算出后**人工逐行核对前 3 期与末期**，并在类注释注明核对人与日期；收益率/久期类指标给区间而非固定值（求根末位允许 `1e-8` 级抖动）。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl calc-testkit test -Dtest=GoldenScheduleTest`
Expected: 参考表未实现 → 编译失败或断言失败。

- [ ] **Step 3: 写属性测试**

```java
@Property(tries = 500)
void principal_is_always_conserved(
        @ForAll @BigRange(min = "1000", max = "10000000") BigDecimal principal,
        @ForAll @BigRange(min = "0", max = "0.3") BigDecimal rate,
        @ForAll @IntRange(min = 1, max = 120) int periods) {
    Schedule s = calculate(principal, rate, periods, "THIRTY_360", "EQUAL_INSTALLMENT");
    assertThat(MoneyUtils.sum(s.installments().stream().map(Installment::principalDue).collect(toList())))
            .isEqualByComparingTo(MoneyUtils.out(principal));
    assertThat(s.installments().get(periods - 1).closingBalance()).isEqualByComparingTo("0.00");
    assertThat(s.installments()).allSatisfy(i ->
            assertThat(i.closingBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO));
}

@Property(tries = 300)
void single_period_and_zero_rate_never_throw(
        @ForAll @BigRange(min = "1000", max = "1000000") BigDecimal principal,
        @ForAll @BigRange(min = "0", max = "0") BigDecimal rate) {
    assertThatCode(() -> calculate(principal, rate, 1, "ACT_365F", "EQUAL_INSTALLMENT"))
            .doesNotThrowAnyException();
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl calc-testkit test`
Expected: 8 组黄金用例 + 2 个属性测试全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add calc-testkit pom.xml
git commit -m "test: add golden reference tables and conservation properties"
```

---

### Task 31: 端到端冒烟与使用说明

**Files:**
- Create: `calc-engine/src/test/java/com/fincalc/engine/EndToEndSmokeTest.java`
- Create: `README.md`
- Modify: `docs/superpowers/specs/2026-11-02-financial-calc-service-design.md`（仅在实现与设计有偏差时追加 ADR 链接）

**Interfaces:**
- Consumes: 全部
- Produces: 可执行的端到端用例；README 含快速开始代码。

- [ ] **Step 1: 写端到端冒烟测试**

```java
@Test
void calculates_full_schedule_from_code_and_prints_summary() {
    CalculationResult r = CalculationFacade.defaults().calculate(
            new CalculationRequest(Fixtures.standardLease(), null, CalculationOptions.defaults()));

    FinancialMetrics m = r.schedule().header().metrics();

    assertThat(r.schedule().installments()).hasSize(12);
    assertThat(m.find(MetricCodes.TOTAL_RENT)).isEqualByComparingTo("1032797.16");
    assertThat(m.find(MetricCodes.IRR_PERIOD)).isNotNull();
    assertThat(m.find(MetricCodes.IRR_ANNUAL)).isNotNull();
    assertThat(m.find(MetricCodes.XIRR)).isNotNull();
    assertThat(m.find(MetricCodes.DURATION_MACAULAY)).isNotNull();
    assertThat(r.schedule().cashFlows()).isNotEmpty();
    assertThat(r.effectiveInterest().rows()).hasSize(12);
    assertThat(r.schedule().hasErrors()).isFalse();
}
```

> `TOTAL_RENT` 以 Task 30 参考表为准；若与本行不一致，以参考表为准并同步修改本行。

- [ ] **Step 2: 运行确认通过**

Run: `mvn -q test`
Expected: 全模块 BUILD SUCCESS。

- [ ] **Step 3: 写 README 快速开始**

包含：环境要求（JDK 11 / Maven 3.8+）、模块说明、5 行调用示例、四种算法 `code` 对照表、扩展点列表（指向设计文档 §12）、如何新增一种算法（继承 `AbstractAmortizationGenerator` + `ServiceLoader` 注册）。

- [ ] **Step 4: 验证打包**

Run: `mvn -q -DskipTests package`
Expected: `calc-engine/target/calc-engine-0.1.0-SNAPSHOT.jar` 存在；`javap -classpath calc-engine/target/classes com.fincalc.engine.CalculationFacade` 可见 `calculate` 方法。

- [ ] **Step 5: 提交**

```bash
git add calc-engine/src/test README.md
git commit -m "docs: add e2e smoke test and quick start guide"
```

---

## 自查记录（计划完成后执行）

- **Spec 覆盖**：§7 模型 → Task 6/7；§7.4 算法 → Task 13–16；§7.5 分段利率 → Task 28 + `rateChangePolicy` 字段（行为在 Plan 2 接入 Excel 后验证）；§8.2 规则表 → Task 23/26；§8.3 编译 → Task 26；§8.4 表达式 → Task 24；§8.5 Excel 函数库 → Task 25；§8.6 外部脚本 → Task 27；§9.1/9.2 精度尾差 → Task 2/11；§9.3 口径 → Task 8/9；§9.4 现金流 → Task 19；§9.5 金融指标 → Task 20/21/29；§9.6 残值 → Task 6/19；§9.7 实际利率法 → Task 22；§10 编排 → Task 29；§12 扩展点 → Task 5/12/21/25；§13 性能 → 留到 Plan 3（本计划只用 `PmtSolver` 保证算法不退化）。
- **本期未覆盖且属预期**：Excel 解析、持久化、版本链、REST、导出、批量、国产化验证——分别属于 Plan 2 与 Plan 3。
- **类型一致性**：`ScheduleGenerator.code()` 与 `OrderedExtension.code()` 一致；`RuleSetView.resolve` 的 4 个参数在 Task 12 定义、Task 23 实现、Task 28 调用，签名一致；`CashFlow` 的 5 参数构造在 Task 7 定义、Task 19/20 使用；`FinancialMetrics` 在 Task 7 定义、Task 21 填充、Task 29 组装；`ExpressionParser.parse(String, FunctionRegistry)` 在 Task 24 定义、Task 26/27 调用，签名一致。
- **Review Focus 落地**：零利率/单期 → Task 13/17；闰年与月末 → Task 8/10/20/25；首期非整月 → Task 10；尾差 → Task 11/30；规则缺口/重叠/百分比 → Task 26；现金流重复计数 → Task 19；指标不可计算时返回 null → Task 21；脚本递归/覆盖内置/语法错误 → Task 27。
