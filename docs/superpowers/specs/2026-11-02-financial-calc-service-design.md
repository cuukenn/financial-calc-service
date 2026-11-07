# 金融测算服务（financial-calc-service）初版设计

| 项目 | 内容 |
|---|---|
| 文档版本 | v1.0（已确认） |
| 日期 | 2026-11-02 |
| 状态 | 已确认，作为实施计划基线 |
| 技术基线 | JDK 11 / Maven 3.8+ / Spring Boot 2.7.x |
| 适用范围 | 融资租赁、经营租赁、分期付款类业务的租金摊销测算 |

---

## 1. 意图与背景

### 1.1 要解决的问题

业务侧的租金测算长期依赖 Excel：

- 每个项目一份 Excel，公式、分期方式、特殊调整各不相同；
- 算法藏在单元格里，无法复用、无法回归测试、无法审计；
- 版本靠"另存为 v3_final_真的final.xlsx"，底稿无法追溯；
- 换一个人接手，看不懂公式，改一个利率要手工拖一整列。

本项目要把这套流程**收进程序**：Excel 继续作为业务人员的输入载体，但**导入时翻译成程序内的规则模型**，运行期由 Java 引擎执行，不再依赖 Excel 公式求值。

> 关键约束（已与业务确认）：**Excel 是"规则的载体 + 数据的载体"，不是运行时计算引擎。** 导入即编译，编译后与 Excel 解耦。

### 1.2 为什么现在做

- 监管与内审对测算过程的可解释性、可追溯性要求提高；
- 业务量增长后，人工 Excel 出错成本高于系统建设成本；
- 信创/国产化替代窗口期，需要一套**不绑定具体中间件**的核心引擎。

### 1.3 成功的样子

1. 业务人员上传一份标准模板 Excel，30 秒内得到完整摊销计划表与校验报告；
2. 同一份输入重算结果**逐分一致**（可复现）；
3. 每一次测算都有不可篡改的底稿与版本链，能回答"这版是谁、什么时候、用什么规则算的"；
4. 新增一种还款方式，**不改核心引擎**，只加一个实现类或一张规则表；
5. 把 POI 换成别的 Excel 库、把 MySQL 换成达梦，核心算法代码零改动。

---

## 2. 目标 / 非目标 / 反目标

### 2.1 目标

| 编号 | 目标 |
|---|---|
| G1 | 支持等额本息、等额本金、先息后本、先本后息四种内置算法 |
| G2 | 支持由 Excel 规则表定义的**不规则**还款安排（分段利率、不规则本金、不规则日期） |
| G3 | 全程 `BigDecimal` 精确计算，金额守恒可校验，差额可解释 |
| G4 | 每次测算生成不可变底稿，支持版本链与版本对比 |
| G5 | 核心算法零外部依赖，可通过 SPI 替换算法、口径、存储、Excel 解析、加密 |
| G6 | 单次测算（120 期）P99 < 50ms；批量 10 万条可水平扩展 |
| G7 | 可嵌入（作为 JAR）与可服务（REST）两种交付形态 |

### 2.2 非目标（本期不做）

- 不做合同全生命周期管理（起租、变更、结清、核销）；
- 不做总账记账与财务凭证生成；
- 不做前端界面（本期只提供 REST + 导出文件，前端另立项）；
- 不做审批流引擎（预留回调钩子）；
- 不做多币种汇率换算（模型预留币种字段，本期仅单币种）；
- 不做税务申报逻辑（只做价税分离展示）。

### 2.3 反目标（做了就算失败）

- **把 Excel 公式在运行时跑起来**——性能、安全、可解释性全部失控，且违背"转换为程序内规则"的初衷；
- **用 `double` 算钱**——一分钱的误差在租赁行业就是事故；
- **算法写成一堆 `if (method == ...)`**——新增方式必须改核心类，扩展点形同虚设；
- **底稿可被覆盖更新**——版本链必须是只追加的；
- **核心模块依赖 Spring / POI / MyBatis**——国产化替换时被绑架。

---

## 3. 约束

| 类别 | 约束 |
|---|---|
| 语言/运行时 | JDK 11（不可用 record / sealed / switch 表达式；可用 `var`） |
| 构建 | Maven 多模块，`maven.compiler.release=11` |
| 框架 | Spring Boot 2.7.x（Boot 3 需要 JDK 17，本期不可用） |
| 精度 | 金额 `BigDecimal`，禁止 `float`/`double` 参与金额运算 |
| 依赖 | 核心模块（`calc-domain`）只依赖 JDK；第三方依赖只允许出现在适配器模块 |
| 部署 | 需支持单机内嵌、容器化、信创服务器（ARM64 / 龙芯） |
| 合规 | 测算过程可追溯，底稿保存期限由业务方确认（建议 ≥ 合同期 + 5 年） |
| 数据 | 客户名称、利率等属敏感信息，需支持字段级加密与脱敏 |

---

## 4. 统一语言（术语表）

| 术语 | 英文 | 定义 |
|---|---|---|
| 租赁要素 | Lease Element | 一次测算的全部输入参数：本金、利率、期数、日期、费用等 |
| 本金 | Principal | 起租日确认的融资本金（不含保证金抵扣前/后需明确，见 §18） |
| 名义利率 | Nominal Rate | 合同约定的年化利率，用于逐期计息 |
| 实际利率 | IRR | 使现金流净现值为 0 的折现率 |
| 计息基准 | Day Count Convention | 天数折算年数的规则，如 ACT/360 |
| 起租日 | Commencement Date | 计息起点 |
| 租金 | Rent | 每期应付总额 = 本金 + 利息 + 费用 + 税 |
| 摊销计划 | Amortization Schedule | 逐期本金/利息/余额明细表 |
| 尾差 | Tail Difference | 因舍入导致的分摊残差，需显式处理 |
| 规则集 | Rule Set | 由 Excel 规则表编译而来的、不可变的分期计算规则 |
| 测算批次 | Calc Run | 一次完整测算的不可变记录，是版本链的节点 |
| 底稿 | Working Paper | 输入原件 + 解析中间态 + 输出结果 + 校验报告的总称 |

---

## 5. 关键设计决策

### 5.1 决策一：Excel 规则如何进入程序

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| A. 固定模板 + 强类型映射 | 只允许"选算法 + 填参数" | 简单、强类型、易校验 | 不规则场景必须改代码发版 | 作为**外壳**采用 |
| **B. 模板外壳 + 规则行 DSL** | 要素走模板，"怎么算"走规则表，导入时编译为不可变规则集 | 覆盖不规则、可追溯到行号、运行期零 Excel 依赖、性能好 | 需先设计规则元模型与编译器 | **采用** |
| C. 运行时公式求值 | POI `FormulaEvaluator` / SpEL / Groovy 直接执行 | 完全跟随 Excel | 性能差、沙箱风险、难定位、难留痕 | 不采用（仅在导入对账时读一次预期值） |

**选择 B 的理由**：它同时满足"规则不定"与"高性能、可审计、可国产化"四个硬约束。规则表在导入时被编译成有序的 `CompiledRuleSet`（预编译谓词 + 区间索引），运行期是纯 Java 对象运算。

### 5.2 决策二：模块如何切分

| 方案 | 做法 | 问题 |
|---|---|---|
| 分层切（api / core / excel / db / web） | 按技术职责分层 | Excel 解析与算法都堆在 core，换库要动核心；国产化替换成本高 |
| **六边形切（端口-适配器）** | 领域零依赖，外部能力全部走端口 | 模块数略多，初期样板代码多 | **采用** |

**选择六边形的理由**：需求第 4 条明确要求"核心组件可切换及国产化"。六边形把"变的部分"（Excel 库、数据库、加密、文件存储、导出格式）全部推到边缘，核心只依赖自己定义的接口。换任何一件，都是换一个适配器模块。

### 5.3 决策三：算法扩展方式

不采用枚举 + switch，采用 **SPI + 模板方法**：

- `ScheduleGenerator` 是扩展点，按 `methodCode` 注册；
- `AbstractAmortizationGenerator` 固化"校验 → 建期 → 逐期计息 → 摊本 → 摊费 → 舍入 → 尾差 → 汇总"骨架；
- 四种常规算法只实现 `allocate(...)` 一个钩子；
- 规则驱动的不规则场景由 `RuleDrivenGenerator` 兜底，内部执行 `CompiledRuleSet`。

### 5.4 决策四：版本滚动模型

**只追加（append-only）的测算批次链**，而不是"更新同一条记录"。

```
CalcRun(v1) ← CalcRun(v2) ← CalcRun(v3)
   prevRunId      prevRunId
```

每次测算写入新记录，`prev_run_id` 指向上一版；原始 Excel、解析结果、引擎版本、规则版本、输出结果全部快照化。对比两版 = 取两个快照做 diff，不依赖任何可变状态。

### 5.5 决策五：公式能力如何扩展

**自研 AST + Excel 兼容函数库 + 外部脚本组合**（方案 B）。

| 方案 | 说明 | 结论 |
|---|---|---|
| A. 引入脚本引擎（Groovy/SpEL/Aviator/QLExpress） | 开发快，但依赖重、可反射、沙箱难做、Excel 语义仍需自补 | 不采用 |
| B. 自研 AST + 函数库 + `.fcs` 外部脚本 | 零依赖、完全沙箱、Excel 语义可控、脚本可版本化 | **采用** |
| C. 运行时求值 Excel 公式（POI） | 与"运行期不做公式求值"的核心决策冲突，性能与可审计性都差 | 不采用 |

代价是函数库需要自己维护（约 70 个 v1 函数，估算 3 人日）。收益是：新增业务口径不必发版，且脚本 hash 能进入版本链，保证历史可复现。

---

## 6. 总体架构

### 6.1 分层视图

```
┌──────────────────────────────────────────────────────────────┐
│  calc-server           Spring Boot 薄壳                       │
│  REST / 鉴权 / 文件上传 / 异步任务 / 定时批处理                 │
└───────────────┬──────────────────────────────────────────────┘
                │ 只依赖端口
┌───────────────▼──────────────────────────────────────────────┐
│  calc-engine           用例编排（纯 Java，可嵌入）              │
│  CalculateUseCase / ImportUseCase / VersionUseCase            │
└───────┬───────────────────────────────┬──────────────────────┘
        │                               │
┌───────▼─────────────┐        ┌────────▼─────────────────────┐
│  calc-rule          │        │  calc-domain                 │
│  规则元模型/编译器   │───────▶│  领域模型 + 算法 SPI + 内置算法 │
│  校验器/表达式       │        │  （仅依赖 JDK）               │
└─────────────────────┘        └────────▲─────────────────────┘
                                        │ 实现端口
        ┌───────────────────────────────┼──────────────────────┐
        │                               │                      │
┌───────▼────────┐  ┌───────────────────▼──┐  ┌───────────────▼──┐
│  calc-excel    │  │  calc-persistence    │  │  calc-export      │
│  POI 适配      │  │  MyBatis + 版本链     │  │  Excel/PDF/CSV    │
└────────────────┘  └──────────────────────┘  └───────────────────┘
```

### 6.2 模块清单

| 模块 | 职责 | 允许依赖 |
|---|---|---|
| `calc-bom` | 第三方版本集中管理（BOM） | 无 |
| `calc-common` | 金额/日期工具、异常体系、SPI 加载、结果包装、哈希 | 仅 JDK |
| `calc-domain` | 领域模型、端口接口、四种内置算法、计息口径、舍入策略 | `calc-common` |
| `calc-rule` | 规则元模型、表达式 AST、编译器、校验器、规则版本 | `calc-domain` |
| `calc-engine` | 用例编排：导入→编译→测算→校验→落库 | `domain`, `rule` |
| `calc-excel` | 模板识别、区域映射、规则表解析、对账区读取 | `domain`, `rule` |
| `calc-persistence` | 仓储端口实现（MyBatis）、版本链、文件元数据 | `domain` |
| `calc-export` | 结果导出 Excel/PDF/CSV | `domain` |
| `calc-server` | Spring Boot 启动、REST、鉴权、异步、配置 | 全部适配器 + `engine` |
| `calc-testkit` | 黄金用例、Excel 样例生成器、内存实现、断言工具 | `domain`, `rule` |
| `calc-benchmark` | JMH 基准 | `engine` |

> 依赖方向铁律：**箭头只能指向 `domain` 和 `common`**。用 `maven-enforcer-plugin` 的 `banTransitiveDependencies` / ArchUnit 测试守住这条线。

### 6.3 包结构约定

```
com.fincalc
├── common        .money .date .exception .spi .result .hash
├── domain
│   ├── model     LeaseElement / Installment / Schedule / FeeItem ...
│   ├── port      ScheduleGenerator / DayCountConvention / Repository ...
│   └── support   AbstractAmortizationGenerator / 四种算法 / 口径实现
├── rule          .model .ast .compiler .validator .expression
├── engine        .usecase .command .assembler
├── excel         .template .reader .mapper .reconcile
├── persistence   .mapper .repository .entity
├── export        .excel .pdf .csv
└── server        .controller .config .security .task
```

---

## 7. 核心领域模型

### 7.1 输入模型

```java
public final class CalculationRequest {
    private final String requestId;
    private final String tenantId;
    private final String contractNo;
    private final LeaseElement leaseElement;
    private final RuleSource ruleSource;      // BUILTIN(method) | INLINE(ruleSet) | REF(ruleSetId)
    private final CalculationOptions options;
}

public final class LeaseElement {
    private final BigDecimal principal;          // 融资本金
    private final BigDecimal nominalRate;        // 名义年利率，如 0.06
    private final LocalDate commenceDate;        // 起租日
    private final LocalDate firstRentDate;       // 首期租金支付日
    private final int periods;                   // 期数
    private final PaymentFrequency frequency;    // MONTHLY/QUARTERLY/SEMI_ANNUAL/ANNUAL/IRREGULAR
    private final PaymentTiming timing;          // IN_ADVANCE 期初 / IN_ARREARS 期末
    private final DayCountConventionId dayCount; // ACT_360 / ACT_365F / ACT_ACT / THIRTY_360 ...
    private final DateRollConventionId dateRoll; // NONE / FOLLOWING / MODIFIED_FOLLOWING / EOM
    private final RoundingPolicy rounding;
    private final BigDecimal residualValue;      // 留购价/残值
    private final BigDecimal deposit;            // 保证金
    private final List<FeeItem> fees;            // 手续费/服务费/咨询费
    private final BigDecimal vatRate;            // 增值税率，null 表示价税不分离
    private final RepaymentMethodId method;      // 内置算法标识
    private final boolean interestCapitalized;   // 利息是否资本化
    private final GracePeriod gracePeriod;       // 宽限期（只付息/本息全免）
    private final String currency;               // 预留，本期 CNY
}
```

`FeeItem`：类型、金额、收取方式（一次性前置 / 从本金扣减 / 分摊到各期）、税率、是否计入 IRR 现金流。

### 7.2 输出模型

```java
public final class Schedule {
    private final ScheduleHeader header;              // 汇总
    private final List<Installment> installments;     // 逐期明细（不可变）
    private final List<CashFlow> cashFlows;           // IRR 现金流
    private final List<ValidationIssue> issues;       // 校验告警
    private final ReconciliationResult reconciliation; // 与 Excel 预期值对账（可选）
}

public final class Installment {
    private final int periodNo;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final int days;
    private final BigDecimal openingBalance;
    private final BigDecimal principalDue;
    private final BigDecimal interestAccrued;
    private final BigDecimal interestPaid;
    private final BigDecimal fee;
    private final BigDecimal tax;
    private final BigDecimal rentDue;             // 本期应付合计
    private final BigDecimal closingBalance;
    private final BigDecimal cumulativePrincipal;
    private final BigDecimal cumulativeInterest;
    private final BigDecimal appliedRate;
    private final String memo;                    // 规则来源说明，如 "R003"
}
```

`ScheduleHeader`：期数、首末期日期、所用求解器、舍入差额，以及一个 `FinancialMetrics` 聚合对象（定义见 §9.5，包含总额类、收益率类、回收期类、久期类共 20 项指标）。`Schedule` 另含 `List<CashFlow>`（构建规则见 §9.4）与 `List<ValidationIssue>`。

> 指标全部收敛进 `FinancialMetrics`，而不是散落在 header 上——新增指标只加字段/注册实现，不破坏 `Schedule` 契约。

> 输出模型是**对外契约**，字段只增不改；破坏性变更走新的 DTO 版本。

### 7.3 算法 SPI

```java
public interface ScheduleGenerator {
    String methodCode();                       // EQUAL_INSTALLMENT / EQUAL_PRINCIPAL / ...
    int order();                               // 同 code 时的优先级
    boolean supports(CalculationContext ctx);
    Schedule generate(CalculationContext ctx);
}
```

`CalculationContext` 携带：要素、已解析规则集、计息口径、舍入策略、`Clock`、校验器链。

模板方法骨架（`AbstractAmortizationGenerator`）：

```
1. validate(element)                     参数合法性
2. buildPeriods(element, ruleSet)        生成期次边界（日期滚动、宽限期）
3. for each period:
     a. accrueInterest(opening, rate, days)     计息
     b. allocate(element, period, ctx)           ← 子类唯一必须实现的钩子
     c. applyFees / applyTax
     d. round(period, policy)
4. adjustTail(schedule, policy)           尾差处理
5. buildCashFlows(schedule)
6. validateConservation(schedule)         Σ本金=本金，Σ租金=Σ明细
7. computeIrr(schedule)
```

### 7.4 四种内置算法的口径

| 算法 | methodCode | 每期本金 | 每期利息 | 备注 |
|---|---|---|---|---|
| 等额本息 | `EQUAL_INSTALLMENT` | `PMT - 利息` | `期初余额 × 期利率` | `PMT = P·i·(1+i)^n / ((1+i)^n − 1)`；末期吸收尾差 |
| 等额本金 | `EQUAL_PRINCIPAL` | `P / n` | `期初余额 × 期利率` | 末期本金 = 剩余，避免残差 |
| 先息后本 | `INTEREST_FIRST` | 前 n−1 期为 0，末期 `P` | `P × 期利率`（全额计息） | 末期租金 = 本金 + 当期利息 |
| 先本后息 | `PRINCIPAL_FIRST` | `P / n` | 逐期计提、**末期一次结清** | 口径待业务确认，见 §18-Q1 |
| 规则驱动 | `RULE_DRIVEN` | 由 `CompiledRuleSet` 决定 | 同左 | 兜底，覆盖一切不规则 |

**期利率换算**：默认 `年利率 × 天数 / 年基准`（跟随 `DayCountConvention`），而不是简单 `年利率 / 12`。这是与 Excel 对账时最常见的差异来源，必须可配置。

示例（本金 100 万、年利率 6%、12 期、期末付、30/360）：

| 期次 | 期初余额 | 本金 | 利息 | 租金 | 期末余额 |
|---:|---:|---:|---:|---:|---:|
| 1 | 1,000,000.00 | 81,066.42 | 5,000.00 | 86,066.42 | 918,933.58 |
| 2 | 918,933.58 | 81,471.75 | 4,594.67 | 86,066.42 | 837,461.83 |
| 3 | 837,461.83 | 81,879.11 | 4,187.31 | 86,066.42 | 755,582.72 |
| … | … | … | … | … | … |
| 12 | 85,491.08 | 85,491.08 | 427.46 | 85,918.54 | 0.00 |

### 7.5 分段利率下的组合语义（等额本息为例）

当规则表只覆盖利率、不覆盖本金计划时，必须明确"等额本息"在利率变化点如何处理。三种可能：

| 语义 | 做法 | 适用 |
|---|---|---|
| **R 方案：重解（推荐默认）** | 在每个利率变化点，以「剩余本金 + 剩余期数 + 新利率」重新求解 PMT，之后各期等额 | 浮动利率、优惠期结束 |
| F 方案：固定租金 | 首期按初始利率求出 PMT，全程租金不变；利率变化只改变本息拆分 | 合同约定"租金固定" |
| D 方案：逐期漂移 | 按期利率逐期计算，每期都重新求解（等价于浮动） | 按日计息的严格口径 |

**默认采用 R 方案**，通过 `CalculationOptions.rateChangePolicy = RESOLVE / FIXED_RENT / DRIFT` 切换。

同时注意：若期利率来自 `年利率 × 实际天数 / 年基准`，则每期利率天然不等，封闭解 PMT 不再严格成立。此时：

- `DayCountConvention` 为 `THIRTY_360` 等"每月天数相等"的口径 → 可用封闭解；
- 为 `ACT_*` 口径 → 用**二分法 / Newton 迭代**求解使 `Σ 折现现金流 = 本金` 的 PMT（迭代上限 100 次，容差 1e-10），或按 D 方案逐期推进。

引擎在 `ScheduleGenerator` 内部根据口径自动选择封闭解或迭代解，并把所用解法写入 `ScheduleHeader.solver`，便于对账。

---

## 8. 规则 DSL 设计

### 8.1 Excel 模板结构

一个 workbook，固定 sheet 命名（模板版本号写在 `00_封面`）：

| Sheet | 用途 | 必需 |
|---|---|---|
| `00_封面` | 模板编码、模板版本、合同号、编制人、编制日期 | 是 |
| `01_租赁要素` | 键值对：本金、利率、起租日、期数、频率、付款时点、口径 | 是 |
| `02_费用` | 费用清单：类型、金额、收取方式、税率 | 否 |
| `03_规则表` | 分段规则：利率、本金计划、利息处理、特殊动作 | 否（缺省走内置算法） |
| `04_现金流` | 显式逐期租金（**v1 保留不解析**，见 §18-Q9） | 否 |
| `05_预期结果` | 业务方手算结果，用于导入对账 | 否 |
| `06_期次表` | 不规则期次的起止日 / 付款日 | 仅 `frequency = IRREGULAR` 时必需 |

**读取方式**：优先按 **命名区域（Named Range）** 定位，其次按表头文本匹配，最后按固定坐标兜底。命名区域由模板定义，业务方不得改动；这样插行删行不会破坏解析。

### 8.2 规则表列定义

| 列 | 字段 | 示例 | 说明 |
|---|---|---|---|
| A | ruleId | R001 | 唯一，参与审计 |
| B | priority | 10 | 冲突时数值小者优先 |
| C | stage | 1 | 阶段序号，仅用于可读性 |
| D | condition | `period >= 1 && period <= 12` | 生效条件（受限表达式） |
| E | fromPeriod | 1 | 期次区间起点 |
| F | toPeriod | 12 | 期次区间终点 |
| G | annualRate | 0.06 | 年利率，支持按区间覆盖 |
| H | rateBase | OPENING_BALANCE | 计息基数：期初余额/原始本金/剩余本金/固定额 |
| I | principalPlan | EQUAL | 本金计划，语义见 §8.2.1 |
| J | principalValue | | 配合 I 使用（金额 / 百分比 / 逐期序列） |
| K | interestTreatment | PAY_AS_INCURRED | 利息处理：按期支付 / 资本化 / 递延 / 末期结清 / 免息 |
| L | paymentTiming | ARREARS | 付款时点 |
| M | specialAction | NONE | 提前还款 / 展期 / 保证金抵扣 / 费用分摊 |
| N | actionParam | | 动作参数（JSON 片段或数值） |
| O | memo | 前 12 期优惠利率 | 备注，进入底稿 |

#### 8.2.1 `principalPlan` 精确定义

设该行覆盖期数为 `m`（`toPeriod − fromPeriod + 1`），覆盖期内的"剩余待摊本金"为 `B`：

| 取值 | 含义 | 每期本金 | 编译期校验 |
|---|---|---|---|
| `EQUAL` | 均摊 | `B / m`（末期取剩余） | 无 |
| `FIXED` | 固定金额 | `principalValue` | `Σ(FIXED期数 × value) + 其他计划 = 本金` |
| `PERCENT` | 总本金百分比 | `本金 × principalValue% / m` | 所有 `PERCENT` 行合计 = 100% |
| `CUSTOM` | 逐期指定 | 按 `principalValue` 逗号分隔序列，长度必须 = `m` | 序列元素之和 + 其他计划 = 本金 |
| `NONE` | 不还本 | `0` | 无 |
| 空白 | 继承默认 | 视为 `EQUAL`（该行仅覆盖利率时） | 无 |

> `NONE` 与空白的区别是显式的：空白表示"这张表没管本金"，`NONE` 表示"明确本期不还本"。

#### 8.2.2 `specialAction` 的 v1 范围

| 取值 | v1 | 说明 |
|---|---|---|
| `NONE` | ✅ | 无特殊动作 |
| `FEE_AMORTIZE` | ✅ | 费用在覆盖期内均摊 |
| `DEPOSIT_OFFSET` | ✅ | 保证金按规则抵扣本金 |
| `PREPAY`（提前还款） | ❌ 预留 | 会改变期次结构，v2 |
| `EXTEND`（展期） | ❌ 预留 | 同上 |

`PREPAY` / `EXTEND` 属于**改变期次结构**的动作，不能塞进"逐期解析规则"的循环里。v2 引入时必须让 `buildPeriods` 感知动作并支持重入，届时 `CompiledRuleSet` 增加 `TermChangePoint` 列表。v1 的规则模型已预留 `specialAction` 字段与枚举位，但编译器遇到未支持的取值直接报 `FCS-RULE-2005`，不做静默忽略。

### 8.3 编译流程

```
读取 sheet
  → 表头校验（缺列/多列 → 阻断性错误）
  → 逐行解析为 RuleRow（保留 rowNumber + cellRef）
  → 语义校验：
       · 区间覆盖性（默认要求 1..n 完整覆盖，缺口为阻断性错误；
         仅当 options.allowPartialRuleSet = true 时允许缺口回落内置算法并告警）
       · 区间重叠（同优先级重叠报错，不同优先级按 priority 覆盖）
       · 金额守恒（PERCENT 合计 = 100%，FIXED 合计 = 本金）
       · 日期单调（start < end，期次连续）
       · 利率区间（0 ≤ r ≤ 1）
  → 表达式编译（字段白名单 → AST → 预编译 Predicate）
  → 生成 CompiledRuleSet（有序规则 + 区间索引 + 校验摘要 + ruleSetHash）
```

`CompiledRuleSet` 是**不可变、可序列化、线程安全**的。`ruleSetHash` 进入测算批次的指纹，保证"同规则同结果"。

### 8.4 表达式引擎

**定位**：规则表的表达式列与外部函数脚本共用同一个引擎。它不是通用编程语言，而是**只做纯计算的声明式公式语言**——没有 I/O、没有状态、没有循环、没有对象。

**语法**（对标 Excel 公式，降低业务迁移成本）：

```
expr       := comparison
comparison := additive (('=' | '<>' | '<' | '>' | '<=' | '>=') additive)?
additive   := term (('+' | '-') term)*
term       := power (('*' | '/') power)*
power      := unary ('^' unary)*
unary      := ('-' | '+' | '!')? primary
primary    := number | string | TRUE | FALSE | BLANK
            | ident | ident '(' args? ')' | '(' expr ')'
```

- 相等用 `=`（Excel 习惯），同时接受 `==`；不等用 `<>`，同时接受 `!=`；
- 逻辑与/或用 `AND()`/`OR()`，也接受 `&&`/`||` 作为语法糖；
- 字符串用双引号，`""` 转义为 `"`；
- 函数名与变量名**大小写不敏感**，内部统一大写；
- **不支持**数组常量与单元格区域（`{1,2,3}`、`A1:B3`）——v1 的求值上下文是**期次标量**；需要聚合时用 `LIST(...)` + `SUM/AVERAGE`。

**求值上下文变量白名单**：

| 变量 | 类型 | 含义 |
|---|---|---|
| `PERIOD` | NUMBER | 期次，1..n |
| `BALANCE` | NUMBER | 期初本金余额 |
| `DAYS` | NUMBER | 本期天数 |
| `RATE` | NUMBER | 本期年利率 |
| `INDEX` | NUMBER | `PERIOD − 1` |
| `IS_LAST` | BOOLEAN | 是否末期 |
| `PRINCIPAL` | NUMBER | 合同本金 |
| `TOTAL_PERIODS` | NUMBER | 总期数 |
| `RESIDUAL` | NUMBER | 残值 |

白名单外的标识符 → 编译期 `FCS-RULE-3001`。禁止赋值、方法调用、反射、`new`、类型转换。

**类型系统**：`NUMBER`（`BigDecimal`）、`TEXT`、`BOOLEAN`、`BLANK`、`ERROR`（携带 Excel 错误码）。编译期完成静态类型推断，运行期不抛 `ClassCastException`。

**精度**：内部统一 `MathContext.DECIMAL128`；**只在写出到 `Installment` 时**按 `MoneyScale` 舍入，避免中间步骤反复舍入放大误差。`ROUND()` 只作用于显式调用处。

**编译产物**：`CompiledExpression`（不可变 AST + 变量槽位表 + 函数引用表），求值为栈式解释，无反射调用。

### 8.5 Excel 兼容函数库

**目标**：函数名、参数顺序、边界语义与 Excel 对齐，业务人员可以直接把 Excel 公式搬进规则表或脚本。

**分层**：

| 层 | 来源 | 示例 |
|---|---|---|
| Excel 兼容层 | 内置 Java 实现 | `PMT`、`ROUND`、`EDATE`、`IF` |
| 领域函数层 | 内置 Java 实现，可访问引擎上下文 | `ACCRUE`、`TAIL_ADJUST` |
| 自定义函数层 | 外部脚本（§8.6），由前两层组合而成 | `含税租金` |

**v1 内置清单（约 70 个）**：

- 数学：`ABS ROUND ROUNDUP ROUNDDOWN INT TRUNC MOD POWER SQRT EXP LN LOG LOG10 SIGN MAX MIN SUM PRODUCT AVERAGE CEILING FLOOR`
- 财务：`PMT IPMT PPMT PV FV RATE NPER NPV IRR XIRR MIRR EFFECT NOMINAL SLN DB DDB SYD`
- 日期：`DATE YEAR MONTH DAY EDATE EOMONTH DAYS DAYS360 YEARFRAC DATEVALUE`
- 逻辑：`IF IFS AND OR NOT XOR IFERROR IFNA SWITCH TRUE FALSE`
- 查找：`CHOOSE INDEX MATCH LOOKUP VLOOKUP`
- 文本：`CONCAT CONCATENATE LEFT RIGHT MID LEN TRIM UPPER LOWER TEXT VALUE`
- 信息：`ISBLANK ISNUMBER ISTEXT ISERROR NA`

**与 Excel 的语义差异（必须逐条测试）**：

| 项 | Excel | 本项目 |
|---|---|---|
| `ROUND` | 半数远离零 | `HALF_UP`，一致 |
| `MOD(n,d)` | `n − d × INT(n/d)` | 同样实现；**不用** Java `%`（负数符号不同） |
| `INT(-1.5)` | −2 | 向下取整，一致 |
| `TRUNC(-1.5)` | −1 | 向零取整，一致 |
| 空单元格 | 算术中当 0，文本中当 `""` | `BLANK` 类型，按上下文处理 |
| 浮点 | IEEE 754 double | `BigDecimal`，无二进制误差 |
| 日期 | 1900 序列号（含 1900-02-29 历史 bug） | 默认 1900 系统并兼容该 bug；可切 1904 |
| 错误值 | `#DIV/0!` 等 | 内部 `FormulaError`，跨界映射为 `FCS-RULE-3xxx` |

**错误映射**：

| Excel | 内部码 |
|---|---|
| `#DIV/0!` | `FCS-RULE-3005` |
| `#VALUE!` | `FCS-RULE-3006` |
| `#NUM!` | `FCS-RULE-3007` |
| `#N/A` | `FCS-RULE-3008` |
| `#NAME?` | `FCS-RULE-3009` |

**扩展点**：

```java
public interface FormulaFunction extends OrderedExtension {
    int minArity();
    int maxArity();                       // -1 = 变长
    Value invoke(List<Value> args, EvalContext ctx);
}
```

内置函数与脚本函数统一注册进 `FunctionRegistry`；编译器在**编译期**校验函数存在性与参数个数，未知函数 → `FCS-RULE-3009`。

### 8.6 外部脚本与函数组合

**用途**：把"业务口径"从 Java 代码里挪出来。新增一个"含税租金折现"口径，不需要发版，改脚本即可。

**格式**（`.fcs`，UTF-8 纯文本）：

```
VERSION 1

DEFINE 含税金额(AMOUNT, VAT_RATE) =
    ROUND(AMOUNT * (1 + VAT_RATE), 2)

DEFINE 等额本息月供(PRINCIPAL, ANNUAL_RATE, MONTHS) =
    PMT(ANNUAL_RATE / 12, MONTHS, -PRINCIPAL)

DEFINE 租金净额(RENT, VAT_RATE) =
    ROUND(含税金额(RENT, -VAT_RATE) / (1 + VAT_RATE), 2)
```

**规则**：

1. `DEFINE 名称(参数...) = 表达式`；名称大小写不敏感，参数名是局部变量；
2. 可调用**先定义或后定义**的函数——加载时做拓扑排序，允许前向引用；
3. **禁止递归**（直接或间接）→ `FCS-RULE-3011`；
4. 不得覆盖内置函数名，除非显式使用 `OVERRIDE` 关键字（默认拒绝，`FCS-RULE-3010`）；
5. 单文件 ≤ 500 个定义，单表达式深度 ≤ 64，单次求值步数 ≤ 10,000；
6. 脚本只能调用白名单函数与参数，**不能**读文件、环境变量、时钟、随机数。

**加载与冻结**：

```
classpath:functions/*.fcs          ← 项目自带默认函数
${fcs.functions.dir}/*.fcs        ← 部署时外部覆盖（按文件名排序，后加载者覆盖同名定义）
```

启动时一次性加载 → 解析 → 类型检查 → 拓扑排序 → 冻结为**不可变 `FunctionRegistry`**。运行期不再读文件，避免"同一次测算中途脚本被改"。

**版本与可复现**：

- `FunctionRegistry.hash()`（所有定义按名称排序后的 SHA-256）纳入 `ruleSetHash`；
- `calc_rule_set` 快照保存脚本原文与 hash；
- 旧 run 用快照重算必须逐分一致——这是版本滚动可审计的前提。

**性能**：

- 脚本函数在加载期**内联展开**为单一 AST（保留原始名称用于报错），避免运行时函数调用栈；
- 内置函数按 `code` 分派到静态方法，**不走反射**；
- 目标：单期表达式求值 < 1μs，1000 期 × 20 列 < 20ms。

**为什么不用现成脚本引擎**：

| 方案 | 问题 |
|---|---|
| Groovy / SpEL | 依赖重、可反射、沙箱难做、国产化审查成本高 |
| AviatorScript / QLExpress | 仍需自行补齐 Excel 语义；新增依赖与版本锁定 |
| GraalJS | JDK11 下体积与启动开销大，double 语义与 `BigDecimal` 不一致 |
| **自研 AST（选定）** | 零依赖、可完全沙箱、Excel 语义可精确控制；代价是函数库需自己维护 |

### 8.7 规则驱动生成器

`RuleDrivenGenerator` 执行顺序：

```
for period in 1..n:
    rule = ruleSet.resolve(period, context)      // 区间索引 + 谓词命中
    rate = rule.annualRate ?: element.nominalRate
    interest = accrue(rateBase(rule, period), rate, days)
    principal = principalPlan.apply(rule, period, balance)
    interestPaid = interestTreatment.apply(interest, period, isLast)
    action = specialAction.apply(...)
    build installment
```

规则未命中任何行 → 默认视为规则集不完整，抛 `FCS-RULE-2001`（阻断）。
仅当显式开启 `options.allowPartialRuleSet` 时，才回落到内置算法并记 `RULE_FALLBACK` 告警。

---

## 9. 精度、舍入与天数口径

### 9.1 精度策略

| 环节 | scale | 舍入 |
|---|---|---|
| 内部计算（利率、天数折算） | 12 | HALF_UP |
| 利息/本金中间值 | 10 | HALF_UP |
| 输出金额 | 2 | HALF_UP（可配置） |
| 汇总合计 | 2 | 由明细求和后舍入，**不允许先舍入再求和** |

### 9.2 尾差处理

```java
public interface TailAdjuster {
    void adjust(List<Installment> installments, BigDecimal expectedTotal, RoundingPolicy policy);
}
```

三种策略：

- `LAST_PERIOD`（默认）：差额全部计入末期；
- `PROPORTIONAL`：按各期金额比例分摊，最大余额法保证合计精确；
- `FIRST_PERIOD`：差额计入首期（少数合同要求）。

**守恒校验**（每次测算必跑）：

```
|Σ principalDue − principal| ≤ 0.01
|Σ rentDue − (Σ principal + Σ interest + Σ fee + Σ tax)| ≤ 0.01
每期 |opening − principalDue + closing| ≤ 0.01
末期 closing == 0（或 == residualValue）
```

任一条不满足 → `ValidationIssue(level=ERROR)`，测算结果标记为不可用，但仍保留底稿。

### 9.3 天数口径

```java
public interface DayCountConvention {
    String code();
    int days(LocalDate start, LocalDate end);           // 实际天数
    BigDecimal yearFraction(LocalDate start, LocalDate end); // 折算年数
}
```

内置：`ACT_360`、`ACT_365F`、`ACT_ACT`（ISDA）、`THIRTY_360`、`THIRTY_E_360`、`ACT_365_25`。

日期滚动：`NONE`、`FOLLOWING`、`MODIFIED_FOLLOWING`、`PRECEDING`、`MODIFIED_PRECEDING`、`END_OF_MONTH`。

> 口径实现必须是**纯函数、无状态、可单测**的。每个口径配一组来自监管/行业惯例的黄金用例（闰年、月末、跨年）。

### 9.4 现金流构建

现金流是**所有金融指标的输入**，必须先定义清楚，指标才有唯一答案。

**视角**：

```java
public enum CashFlowPerspective { LESSOR, LESSEE }   // 默认 LESSOR（出租人/资金方）
```

**符号约定**：统一「流出为负、流入为正」。IRR 对整体符号翻转不敏感，因此换视角不改变 IRR；NPV 互为相反数，需在展示层标注视角。

**事件表**（`LESSOR` 视角）：

| 时点 | 事件 | 方向 | 金额 | 触发条件 |
|---|---|---|---|---|
| t0 | 投放本金 | 流出 | `principal` | 总是 |
| t0 | 收取保证金 | 流入 | `deposit` | `deposit > 0` 且 `INCLUDE_DEPOSIT` |
| t0 | 前置费用 | 流入 | `Σ upfront fee` | `chargeMode = UPFRONT` 且 `includeInIrr` |
| 各期 | 租金 | 流入 | `rentDue` | 总是 |
| 末期 | 留购价 / 残值 | 流入 | `residualValue` | `ResidualHandling = SEPARATE` |
| 末期 | 退还保证金 | 流出 | `deposit` | 收取过保证金 |

**去重规则**（最容易算错的地方）：

- `tax` 已包含在 `rentDue` 内，**不再单列现金流**；价税分离现金流属于 v2（`VAT_SPLIT`）。
- `BALLOON_IN_RENT` 时残值已体现在末期租金里，**不再追加**残值现金流。
- `DEDUCT_FROM_PRINCIPAL` 的保证金已冲减 t0 本金，**不再单独列示**保证金收付。

**口径**：

```java
public enum CashFlowBasis { CONTRACT, INCLUDE_FEES, INCLUDE_DEPOSIT, VAT_SPLIT }
```

默认 `INCLUDE_FEES`。`VAT_SPLIT` 在 v1 抛 `FCS-CALC-3005`（未支持），不静默降级。

**排序**：按日期升序；同日按 `PRINCIPAL → FEE → DEPOSIT → RENT → RESIDUAL` 固定顺序；`sequence` 从 0 连续编号。排序必须稳定，否则同一份输入的 IRR 可能因浮点求和顺序产生末位差异。

`CashFlow` 增加 `direction` 字段（`INFLOW/OUTFLOW`），金额一律存正数，方向单独表达——这样展示层不需要猜符号。

### 9.5 金融指标

`FinancialMetrics` 是不可变聚合对象，所有指标为 `BigDecimal`；无法计算时为 `null`，并同时产生一条 `ValidationIssue`，**不允许静默返回 0**。

| code | 名称 | 公式 | 精度/说明 |
|---|---|---|---|
| `TOTAL_PRINCIPAL` | 本金合计 | `Σ principalDue` | scale 2 |
| `TOTAL_INTEREST` | 利息合计 | `Σ interestPaid` | scale 2 |
| `TOTAL_FEE` | 费用合计 | `Σ fee` | scale 2 |
| `TOTAL_TAX` | 税额合计 | `Σ tax` | scale 2 |
| `TOTAL_RENT` | 租金合计 | `Σ rentDue` | scale 2 |
| `RESIDUAL_VALUE` | 残值 | `residualValue` | scale 2 |
| `RENTAL_RATE` | 租费率 | `TOTAL_RENT / principal` | scale 6 |
| `NPV` | 净现值 | `Σ CF_t / (1+r)^t` | `r` 取 `options.discountRate`，缺省用 `IRR_PERIOD` |
| `XNPV` | 实际日期净现值 | `Σ CF_i / (1+r)^(days_i/365)` | scale 2 |
| `IRR_PERIOD` | 期内含报酬率 | 解 `Σ CF_t/(1+r)^t = 0` | scale 10 |
| `IRR_ANNUAL` | 年化 IRR（复利） | `(1 + IRR_PERIOD)^m − 1` | `m` = `periodsPerYear` |
| `IRR_ANNUAL_SIMPLE` | 年化 IRR（单利） | `IRR_PERIOD × m` | 对齐 Excel `IRR()*12` |
| `XIRR` | 实际日期 IRR | 解 `Σ CF_i/(1+r)^(days_i/365) = 0` | 对齐 Excel `XIRR()` |
| `MIRR` | 修正 IRR | `(FV_pos / −PV_neg)^(1/n) − 1` | 再投资率/融资率可配 |
| `PAYBACK_PERIOD` | 静态回收期（年） | 累计未折现现金流首次 ≥ 0 的插值点 `/ m` | 期末未回收 → `null` |
| `DISCOUNTED_PAYBACK` | 动态回收期（年） | 同上，用折现现金流 | |
| `DURATION_MACAULAY` | 麦考利久期（年） | `Σ(t·PV_t) / ΣPV_t`，`t` 以年计 | |
| `DURATION_MODIFIED` | 修正久期 | `D / (1 + y/m)` | |
| `CONVEXITY` | 凸性 | `Σ t(t+1)·PV_t / ((1+y/m)^2 · ΣPV_t)` | 单位：年² |
| `AVG_OUTSTANDING_BALANCE` | 加权平均占用本金 | `Σ(opening_k × days_k) / Σdays_k` | 按天数加权 |
| `TRUE_ANNUAL_RATE` | 真实年化 | 默认 = `XIRR` | 可配置为单利 |

**求根算法**（IRR / XIRR / MIRR 共用）：

1. 先做符号变化检查；变化次数为 0 → 无解，返回 `null`；
2. 在 `[−0.9999, 10]` 上二分，收敛后用 Newton 加速；
3. 上限 100 次，容差 `1e-10`；
4. 多根时取**最接近名义利率的最小正根**；
5. 不收敛 → `FCS-CALC-3003` 告警，指标置 `null`，**不阻断**计划表输出。

**回收期插值**：设第 `k` 期累计现金流首次 ≥ 0，则

```
paybackPeriods = (k − 1) + |cumulative_{k−1}| / CF_k
paybackYears   = paybackPeriods / m
```

**扩展点**：`FinancialMetric` 是独立 SPI：

```java
public interface FinancialMetric extends OrderedExtension {
    BigDecimal compute(MetricContext ctx);   // 无法计算返回 null
}
```

`MetricRegistry.defaults()` 注册上表全部指标；新增指标只需加一个实现类，不改引擎、不改 DTO。

### 9.6 残值 / 留购价

```java
public enum ResidualHandling {
    SEPARATE,        // 默认：末期单独支付，不计入 rentDue，但计入 IRR 现金流
    BALLOON_IN_RENT  // 作为气球本金：末期余额 = residualValue，PMT 用 FV 求解
}
```

`BALLOON_IN_RENT` 下四种内置算法全部变化：等额本息的 PMT 公式变为

```
PMT = (P − FV/(1+i)^n) × i × (1+i)^n / ((1+i)^n − 1)
```

因此 `ResidualHandling` 必须进入 `LeaseElement`，并参与 `ruleSetHash` / `inputHash`，否则同一份输入在不同配置下会算出不同结果而无法追溯。

### 9.7 实际利率法摊销表

会计入账用实际利率法，与业务侧的"名义利率法"摊销计划并行输出、互相校验。

```java
public final class EffectiveInterestRow {
    int periodNo;
    BigDecimal openingNetInvestment;   // 期初净投资 = 期初余额 − 未摊销费用
    BigDecimal interestIncome;         // = openingNetInvestment × EIR_period
    BigDecimal cashReceived;           // 本期实收租金
    BigDecimal principalRecovery;      // = cashReceived − interestIncome
    BigDecimal closingNetInvestment;
}
```

- `EIR_period = IRR_PERIOD`（与现金流同口径，含费用与保证金）；
- 末期 `closingNetInvestment` 必须归零，否则报 `FCS-CALC-3006`；
- 输出 `EffectiveInterestTable`，并给出与名义利率法的**逐期利息差额**，用于会计/业务对账；
- 该表不参与 `Schedule` 主表的生成，由 `CalculationFacade` 作为附加结果返回，避免主流程耦合。

---

## 10. 计算引擎流程

### 10.1 同步测算时序

```
Client → server: POST /api/v1/calculations (multipart: excel 或 JSON)
server → engine: CalculateCommand
engine:
  1. RuleSourceResolver   确定规则来源（内置 / 内联 / 引用）
  2. ExcelImporter        解析模板 → LeaseElement + raw RuleRows
  3. RuleCompiler         编译 → CompiledRuleSet（含 hash）
  4. GeneratorRegistry    按 methodCode 选 ScheduleGenerator
  5. Generator.generate   生成 Schedule
  6. ValidatorChain       守恒/单调/口径校验
  7. Reconciler           与 05_预期结果 对账（若有）
  8. IrrCalculator        计算 IRR / XIRR
  9. RunRepository        写入 CalcRun（不可变）+ 文件元数据
 10. Exporter             生成结果 Excel（可选）
→ 返回 CalculationResult（含 runId、版本号、汇总、明细、问题列表）
```

### 10.2 幂等与缓存

- `inputHash = SHA-256(canonicalJson(request) + ruleSetHash + engineVersion)`
- 同 `tenantId + contractNo + inputHash` 且规则未变 → 直接返回已有 `runId`（可配置关闭，因为业务可能要求"每次留痕"）；
- `CompiledRuleSet` 按 `ruleSetHash` 走 Caffeine 本地缓存（最大 1000，TTL 1h）。

### 10.3 批量测算

- 输入：一个 Excel 含多行合同要素（一行一合同），或 JSON 数组；
- 分片：按合同号 hash 分片，`ForkJoinPool` / 线程池并行；
- 单条失败不影响整批，产出「成功 / 失败 / 告警」三张清单；
- 大批量走异步任务（`calc_task` 表 + 状态机），提供进度查询与结果下载。

---

## 11. 留底稿与版本滚动

### 11.1 数据模型

```sql
-- 规则集目录（v1 最小实现：存快照 + 版本 + 启停，不做审批流）
calc_rule_set (
  id              varchar(32)  primary key,
  tenant_id       varchar(32)  not null,
  rule_set_code   varchar(64)  not null,
  version_no      int          not null,
  name            varchar(128) not null,
  source_run_id   varchar(32),               -- 由哪次测算导入而来
  rule_json       clob         not null,     -- 编译后的规则集
  rule_set_hash   char(64)     not null,
  status          varchar(16)  not null,     -- DRAFT / ACTIVE / DISABLED
  created_by      varchar(64)  not null,
  created_at      timestamp    not null,
  unique (tenant_id, rule_set_code, version_no)
);

-- 测算批次（只追加）
calc_run (
  id              varchar(32)  primary key,
  tenant_id       varchar(32)  not null,
  contract_no     varchar(64)  not null,
  run_no          int          not null,      -- 同一合同内自增
  prev_run_id     varchar(32),                -- 版本链
  status          varchar(16)  not null,      -- SUCCESS / FAILED / WARN
  template_code   varchar(64),
  template_version varchar(16),
  rule_set_hash   char(64),
  engine_version  varchar(32)  not null,
  input_hash      char(64)     not null,
  element_json    clob,                       -- 解析后的要素快照
  rule_json       clob,                       -- 编译后规则集快照
  result_json     clob,                       -- 输出计划快照
  summary_json    clob,                       -- 汇总指标
  created_by      varchar(64)  not null,
  created_at      timestamp    not null,
  remark          varchar(512),
  unique (tenant_id, contract_no, run_no)
);

-- 底稿文件（原件不可变）
calc_run_file (
  id          varchar(32) primary key,
  run_id      varchar(32) not null,
  file_type   varchar(24) not null,  -- SOURCE_EXCEL / RESULT_EXCEL / REPORT_PDF
  object_key  varchar(512) not null,
  sha256      char(64)    not null,
  size_bytes  bigint      not null,
  created_at  timestamp   not null
);

-- 操作审计
calc_run_audit (
  id          varchar(32) primary key,
  run_id      varchar(32) not null,
  action      varchar(32) not null,  -- CREATE / RECALC / COMPARE / EXPORT / DOWNLOAD
  operator    varchar(64) not null,
  detail      clob,
  created_at  timestamp   not null
);
```

### 11.2 版本行为

| 操作 | 行为 |
|---|---|
| 新建测算 | 插入 `run_no = max + 1`，`prev_run_id` 指向当前最新版 |
| 重算 | 生成新批次（新 `run_no`），旧批次永不修改 |
| 版本对比 | 取两个快照，按 `periodNo` 对齐，输出字段级 diff（本金/利息/租金/余额） |
| 回滚 | 不允许"改历史"，只允许"以历史版本为输入重新生成一条新版本" |
| 删除 | 逻辑删除标记 + 审计，物理数据保留至保存期满 |

### 11.3 底稿内容

一次测算的底稿 = 以下五项打包：

1. 原始 Excel（不可变存储，带 SHA-256）；
2. 解析中间态（`element_json` + `rule_json`）；
3. 输出计划（`result_json` + 可读的 Excel/PDF）；
4. 校验报告（告警、错误、守恒检查结果）；
5. 对账报告（若模板含预期结果，逐期 diff）。

> 底稿的意义不只是"存下来"，而是**任何人拿到它都能复现出同一张表**。因此 `engine_version`、`rule_set_hash`、`day_count`、`rounding` 必须全部落库。

---

## 12. 扩展点清单（端口）

| 端口 | 默认实现 | 替换场景 |
|---|---|---|
| `ScheduleGenerator` | 四种内置 + 规则驱动 | 新增还款方式 |
| `DayCountConvention` | 6 种口径 | 特殊计息约定 |
| `DateRollConvention` | 6 种滚动 | 节假日日历 |
| `RoundingPolicy` / `TailAdjuster` | HALF_UP / 末期调整 | 特殊舍入要求 |
| `FeeAllocator` | 前置/分摊 | 费用资本化规则 |
| `ExpressionEngine` | 自研 AST | 换 Aviator / QLExpress |
| `SpreadsheetReader` | Apache POI | 换 EasyExcel / 厂商控件 |
| `RuleSourceProvider` | Excel / 内联 / 库引用 | 新增规则来源（API、DB、DSL 文件） |
| `PersistencePort` | MyBatis | 换 JPA / 达梦原生 |
| `FileStoragePort` | 本地 / MinIO / OSS | 换对象存储 |
| `CryptoPort` | JCE / BouncyCastle | 国密 SM2/SM3/SM4 |
| `IdGenerator` | 雪花 | 换发号器 |
| `ClockProvider` | 系统时钟 | 测试固定时间 |
| `ResultExporter` | Excel / PDF / CSV | 新增格式 |
| `AuditPort` | 库表 | 接审计平台 |
| `Validator` | 链式 | 新增业务校验 |
| `CashFlowBuilder` | 标准事件表 | 特殊现金流口径 |
| `FinancialMetric` | 21 项内置指标 | 新增金融指标 |
| `FormulaFunction` | ~70 个 Excel 兼容函数 | 新增公式函数（Java 或脚本） |
| `RootSolver` | 二分 + Newton | 换求根库（如 Commons Math） |

**注册机制**：Java `ServiceLoader`（`META-INF/services/...`）+ 自有 `ExtensionRegistry` 做有序装配；Spring 环境下用 `@ConditionalOnMissingBean` 允许 Bean 覆盖。核心模块**不感知 Spring**。

---

## 13. 性能设计

| 指标 | 目标 |
|---|---|
| 单次测算（120 期） | P50 < 10ms，P99 < 50ms |
| Excel 导入（1000 行规则） | < 500ms |
| 批量 10 万条 | < 5min（8 核，分片并行） |
| 内存 | 单次测算常驻 < 1MB |

手段：

- 全链路不可变对象，无锁、线程安全；
- `BigDecimal` 运算避免 `new MathContext` 反复创建，复用常量；
- 期次计算用 `LocalDate`，不做字符串格式化；
- 规则命中用**区间索引 + 预编译谓词**，避免每期线性扫描；
- 规则集/模板元数据缓存；
- 大 Excel 用 SAX（`XSSFReader`）流式读，避免 `XSSFWorkbook` 全量入内存；
- 热路径禁反射、禁日志字符串拼接（用占位符且先判级别）；
- JMH 基准纳入 CI，回归超过 20% 报警。

---

## 14. 国产化适配策略

| 层面 | 策略 |
|---|---|
| 运行时 | 纯 Java，无 JNI / native 依赖（不用 Netty native、RocksDB、LevelDB） |
| 数据库 | SQL 只用 ANSI + 少量方言适配；分页/函数走 `Dialect` 抽象；MyBatis 映射不绑 MySQL 特有语法 |
| Excel | `SpreadsheetReader` 接口隔离 POI；POI 本身纯 Java，可用；如需换 EasyExcel 只换适配器 |
| JSON | Jackson 为主，`JsonCodec` 端口隔离，可换 Fastjson2 |
| 加密 | `CryptoPort` + BouncyCastle，支持 SM2/SM3/SM4；密钥不落配置文件 |
| 缓存 | 本地 Caffeine 优先，分布式缓存走 `CachePort`（Redis / 国产缓存） |
| 文件 | `FileStoragePort`（本地 / MinIO / 对象存储） |
| PDF | 使用可嵌入开源字体（如思源黑体），不依赖操作系统字体 |
| 构建 | 依赖版本全部进 `calc-bom`，锁定版本，离线仓库可用 |
| JDK 兼容 | 不使用 JDK 12+ API；CI 增加"JDK11 + 国产 JDK"双跑（如有环境） |

---

## 15. 安全与审计

- 传输：HTTPS；文件上传做类型/大小/魔数校验，禁止宏（`.xlsm` 拒绝或剥离）；
- 存储：客户名称、合同号等敏感字段支持 AES/SM4 字段级加密；
- 权限：租户隔离（`tenant_id` 行级过滤，MyBatis 拦截器统一注入，禁止手写漏加）；
- 审计：测算、重算、导出、下载、对比全部落 `calc_run_audit`；
- 防注入：表达式白名单 + 规则表不做任何 SQL/脚本拼接；
- 资源限制：单文件 ≤ 20MB、单次 ≤ 10000 期、批量 ≤ 10 万条，超限拒绝并提示。

---

## 16. 技术选型

| 领域 | 选型 | 理由 |
|---|---|---|
| 构建 | Maven 3.10 + `calc-bom` | 需求指定；BOM 统一版本 |
| 语言 | Java 11 | 需求指定 |
| 核心 | 纯 JDK | 零依赖，国产化友好 |
| 服务 | Spring Boot 2.7.18 | JDK11 最高兼容线 |
| Excel | Apache POI 5.2.x（ooxml） | 成熟、纯 Java；接口隔离 |
| JSON | Jackson 2.15.x | 事实标准；端口隔离 |
| 持久化 | MyBatis 3.5.x + HikariCP | 可控 SQL，方言适配容易 |
| 迁移 | Flyway 9.x | 版本化 DDL |
| 缓存 | Caffeine 3.x | 本地高性能 |
| 日志 | SLF4J + Logback | 标准 |
| 测试 | JUnit 5 + AssertJ + Mockito + jqwik | 属性测试保证守恒律 |
| 基准 | JMH 1.37 | 性能回归 |
| 架构守护 | ArchUnit 1.x | 强制依赖方向 |
| API 文档 | springdoc-openapi 1.7.x | 兼容 Boot 2.7 |
| 代码风格 | Spotless + google-java-format | 统一格式 |

**不引入**：Lombok（国产 JDK 注解处理器兼容风险，核心模型手写不可变类 + Builder）、Groovy、Spring Cloud、任何 native 依赖。

---

## 17. 工程规范与测试策略

### 17.1 目录结构

```
financial-calc-service/
├── pom.xml
├── calc-bom/
├── calc-common/
├── calc-domain/
├── calc-rule/
├── calc-engine/
├── calc-excel/
├── calc-persistence/
├── calc-export/
├── calc-server/
├── calc-testkit/
├── calc-benchmark/
├── docs/
│   ├── superpowers/specs/
│   ├── templates/          # Excel 模板与填写说明
│   └── adr/                # 架构决策记录
└── .github/workflows/      # CI：build + test + archunit + jmh
```

### 17.2 测试金字塔

| 层级 | 范围 | 要求 |
|---|---|---|
| 单元测试 | 算法、口径、舍入、表达式 | 覆盖率 ≥ 85%，核心算法 ≥ 95% |
| 属性测试 | 守恒律 | 随机本金/利率/期数，断言 Σ本金守恒、余额非负、末期归零、单调性 |
| 黄金用例 | 每种算法 × 每种口径 | 结果与人工核对表逐分一致 |
| 对账测试 | Excel 样例 | 导入 → 计算 → 与 `05_预期结果` diff = 0 |
| 集成测试 | REST + DB | Testcontainers（MySQL），H2 仅用于纯逻辑 |
| 架构测试 | ArchUnit | `domain` 不得依赖 `excel/persistence/spring` |
| 性能测试 | JMH | 阈值回归告警 |

### 17.3 编码约定

- 不可变优先：`final class` + `final` 字段 + Builder；
- 金额一律 `BigDecimal`，禁止 `double`；工具类 `MoneyUtils` 统一 scale；
- 日期一律 `LocalDate`，禁止 `Date`/`Calendar`；
- 异常分两类：`BusinessException`（可展示给用户，带错误码）与 `SystemException`（告警 + 兜底）；
- 错误码规范：`FCS-<模块>-<4位数字>`，如 `FCS-RULE-1001`；
- 所有对外 DTO 加 `@Schema` 描述，字段只增不改。

---

## 18. 开放问题（需业务确认）

| 编号 | 问题 | 影响 |
|---|---|---|
| Q1 | 「先本后息」的确切口径：每期只还本金、利息逐期计提并在末期一次结清？还是每期付息但本金优先偿还？ | 决定 `PRINCIPAL_FIRST` 的现金流形态与 IRR |
| Q2 | 本金是否扣除保证金/前置手续费？IRR 现金流是否包含保证金与手续费？ | 影响起算本金与收益率口径 |
| Q3 | 是否价税分离？租金是含税价还是不含税价？税率是否分项？ | 影响 `tax` 字段与租金合计 |
| Q4 | 首期/末期是否允许非整月（按实际天数计息）？ | 影响期次边界生成算法 |
| Q5 | 频率为「不规则」时，日期由规则表逐行指定还是由锚点日推演？ | 影响 `buildPeriods` |
| Q6 | 是否需要多币种？ | 模型已预留字段，本期可只做单币种 |
| Q7 | 底稿保存期限与归档介质要求？ | 影响存储策略与清理任务 |
| Q8 | 租户隔离是行级还是库级？ | 影响持久化与连接路由设计 |

> 初版按以下默认推进，业务确认后可调整：Q2 本金不扣保证金（保证金单独列示）、IRR 含全部现金流；Q3 价税分离、默认 6%；Q4 允许非整月、按实际天数；Q5 期次日期由 `06_期次表` 逐行指定；Q6 单币种；Q7 合同期 + 5 年；Q8 行级隔离。

### 18.1 已由设计决策关闭的问题（builder check 后补充）

| 编号 | 问题 | 决策 | 性质 |
|---|---|---|---|
| Q9 | `04_现金流` 显式租金模式是否进 v1？ | **不进 v1**。模板保留工作表但不解析，v2 再定"租金→本息拆分"规则。 | 我的判断 |
| Q10 | 不规则期次的日期从哪来？ | 新增 `06_期次表`（期次、起止日、付款日），`frequency = IRREGULAR` 时必需。规则表只管"怎么算"，期次表只管"什么时候"。 | 我的判断（修正原文档矛盾） |
| Q11 | 规则集是否需要目录？ | **v1 提供最小目录** `calc_rule_set`（快照 + 版本 + 启停），不做审批流。 | 我的判断 |
| Q12 | 规则未覆盖的期次怎么办？ | 默认**阻断**（`FCS-RULE-2001`）；显式开启 `allowPartialRuleSet` 才回落内置算法并告警。 | 我的判断（修正原文档矛盾） |
| Q13 | 分段利率下等额本息怎么算？ | 默认 **R 方案：利率变化点重解 PMT**，可通过 `rateChangePolicy` 切换为固定租金或逐期漂移。 | 我的判断，**待业务确认** |
| Q14 | 残值/留购价是否计入租金？ | 默认 `SEPARATE`（单独支付，不计入 rentDue，计入 IRR 现金流）；可切 `BALLOON_IN_RENT`。 | 我的判断，**待业务确认** |
| Q15 | 期次结构类动作（提前还款/展期）？ | **v1 不支持**，规则模型预留字段，编译器遇到即报错，不静默忽略。 | 我的判断 |

### 18.2 需求方确认结论（2026-11-02）

需求方确认「按推荐默认走」，以下 5 项作为实施基线：

| # | 决策 | 结论 |
|---|---|---|
| D1 | Excel 模板来源 | **由本项目定义模板**，业务试用后微调；不承诺兼容存量非标模板 |
| D2 | 验收基准数据 | 先由项目组用 Excel 公式生成 **6 组基准表**（4 种算法 × 关键口径组合），提交业务复核后作为黄金用例 |
| D3 | 分段利率下等额本息 | 采用 **R 方案：利率变化点重解 PMT**；`rateChangePolicy` 可切换 F/D |
| D4 | 提前还款 / 展期 | **v1 不做**，规则模型预留 `specialAction` 枚举位，编译器遇未支持值报 `FCS-RULE-2005` |
| D5 | 国产数据库验证范围 | v1 只保证 **MySQL 8 + H2**；达梦/金仓在 M6 做兼容性验证，通过后纳入 CI |

> 上述决策影响 `ruleSetHash` 与验收口径，变更需走设计变更记录（ADR）。

---

## 19. 功能大纲

| 编号 | 功能 | 优先级 | 说明 |
|---|---|---|---|
| F1 | 租赁要素管理 | P0 | 手工录入 / JSON 提交 / 从模板解析 |
| F2 | Excel 模板与导入 | P0 | 模板校验、命名区域解析、错误定位到单元格 |
| F3 | 规则编译与校验 | P0 | 规则表 → 不可变规则集；覆盖性/重叠/守恒校验 |
| F4 | 摊销计划生成 | P0 | 四种内置算法 + 规则驱动 |
| F5 | 现金流与金融指标 | P0 | 现金流构建、NPV/XNPV、IRR/XIRR/MIRR、回收期、久期、凸性、实际利率法摊销 |
| F6 | 对账与差异报告 | P1 | 与 Excel 预期值逐期 diff |
| F7 | 留底稿与版本滚动 | P0 | 不可变批次、版本链、版本对比 |
| F8 | 结果导出 | P1 | Excel / PDF / CSV，含校验与对账页 |
| F9 | 批量测算 | P1 | 多合同并行、失败隔离、进度查询 |
| F10 | 扩展与配置 | P1 | 算法/口径注册、规则集目录（版本 + 启停） |
| F11 | 审计与权限 | P0 | 操作审计、租户隔离、字段脱敏 |
| F12 | 可观测性 | P2 | 指标、链路追踪、慢查询 |
| F13 | 公式运行时可配置 | P1 | Excel 兼容函数库、`.fcs` 外部脚本、函数组合、脚本热加载与版本冻结 |

---

## 20. 里程碑

| 阶段 | 内容 | 交付物 | 预估 |
|---|---|---|---|
| M-1 | 模板与基准数据（**条件项**） | Excel 模板定稿、填写说明、每种算法 × 每种口径的人工基准表 | 5 人日（已有则 0） |
| M0 | 工程骨架 | 多模块 POM、BOM、CI、ArchUnit、编码规范 | 3 人日 |
| M1 | 领域核心 | 模型、端口、四种算法、口径、舍入、单测 | 8 人日 |
| M2 | 规则引擎 | 规则元模型、表达式 AST、Excel 函数库、外部脚本组合、编译器、校验器 | 11 人日 |
| M3 | Excel 适配 | 模板、解析、错误定位、对账 | 6 人日 |
| M4 | 引擎编排 + 留底稿 | 用例、版本链、持久化、审计 | 7 人日 |
| M5 | 服务层 + 导出 | REST、鉴权、异步批量、Excel/PDF 导出 | 6 人日 |
| M6 | 性能与国产化 | JMH、压测、方言适配验证、文档 | 5 人日 |
| 合计 | | | **约 46 人日**；若需自制模板与基准数据，追加 5 人日（不含前端与业务联调） |

依赖关系：M0 → M1 → M2 → M3 → M4 → M5 → M6。M2 与 M1 可部分并行，M3 依赖 M2 的规则模型定稿。

---

## 21. 风险登记

| 风险 | 影响 | 应对 |
|---|---|---|
| Excel 模板被业务随意改动 | 解析失败 | 模板版本校验 + 命名区域 + 明确的错误定位 |
| 规则表表达力不足 | 需频繁改代码 | 预留 `specialAction` 扩展 + 规则驱动生成器兜底 |
| 与业务手算结果对不上 | 信任危机 | 导入即对账，差异逐期展示；口径可配置 |
| BigDecimal 性能 | 批量慢 | 基准测试先行，必要时局部用 `long` 分单位计算 |
| 国产数据库方言差异 | 迁移返工 | Dialect 抽象 + 集成测试覆盖目标库 |
| 需求蔓延到合同全生命周期 | 范围失控 | 非目标清单写死，变更走立项 |
| 外部脚本写错导致口径漂移 | 结果不可信 | 加载期全量类型检查 + 脚本 hash 入版本链 + 与内置函数对账用例 |
| Excel 函数语义偏差 | 与业务手算对不上 | 每个函数配 Excel 对照用例；差异表（§8.5）逐条测试 |
| 自研表达式引擎性能/正确性 | 引擎瓶颈 | AST 预编译、无反射、步数上限；JMH 基准进 Plan 3 |

---

## 22. 附录

### A. 内置算法公式

```
等额本息：
  i = 期利率, n = 期数
  PMT = P × i × (1+i)^n / ((1+i)^n − 1)
  第 k 期利息 = 期初余额 × i
  第 k 期本金 = PMT − 利息
  末期本金 = 剩余余额（吸收尾差）

等额本金：
  每期本金 = P / n
  第 k 期利息 = 期初余额 × i

先息后本：
  第 1..n−1 期：本金 0，利息 = P × i
  第 n 期：本金 P，利息 = P × i

先本后息：
  第 k 期本金 = P / n
  第 k 期利息 = 期初余额 × i（计提）
  利息支付：第 1..n−1 期 0，第 n 期 = 累计计提利息
```

### B. 错误码草案

| 错误码 | 含义 |
|---|---|
| `FCS-TPL-1001` | 模板版本不匹配 |
| `FCS-TPL-1002` | 缺少必需 sheet |
| `FCS-TPL-1003` | 命名区域缺失 |
| `FCS-RULE-2001` | 规则区间存在缺口 |
| `FCS-RULE-2002` | 规则区间重叠且优先级相同 |
| `FCS-RULE-2003` | 本金计划合计不等于本金 |
| `FCS-RULE-2004` | 表达式语法错误 |
| `FCS-CALC-3001` | 本金不守恒 |
| `FCS-CALC-3002` | 末期余额不为零 |
| `FCS-CALC-3003` | IRR 不收敛 |
| `FCS-CALC-3004` | 期次日期非单调 |

### C. 与 Excel 对账的典型差异来源

1. 期利率用 `年利率/12` 还是 `年利率×天数/360`；
2. 首期是否按实际天数计息；
3. 每期舍入还是末期统一舍入；
4. 保证金是否冲抵本金；
5. 手续费是否计入 IRR 现金流；
6. 付款时点期初/期末（差一期利息）。

> 对账报告必须能定位到上述哪一条导致差异，而不是只报"差 0.03 元"。

---

*本文档为初版设计，待评审确认后进入实施计划（writing-plans）。*

## 实现偏差记录（Plan 1，2026-11）

实现与本文档存在以下已裁决偏差（裁决台账见 `.superpowers/sdd/2026-11-02-core-calculation-engine/` 各任务报告）：

| 偏差 | 裁决 |
|---|---|
| §B 错误码草案之外新增 `FCS-CALC-3006/3007`（摊销表不收敛 / 实际利率非法） | §B 为草案非封闭清单，按实际需要扩充 |
| §8.2.2 一次性动作 `FEE_AMORTIZE`/`DEPOSIT_OFFSET` 标 ✅，实际未实现 | 降级为"保留枚举值，命中即抛 `FCS-RULE-2005`"（R65），实现后恢复 |
| §8.6 外部脚本 `LIST(...)` 传参 | v1 未实现，DSL 仅标量传参 |
| Task 27 简报测试 2 断言 `94.34` | 系算术错误：脚本实际计算 `100×(1−0.06)=94.00`，断言从脚本（R62） |
| §9.7「末期净投资必须归零」 | 仅对 fully-amortizing/BALLOON 场景强制（R52 接线）；SEPARATE 残值末期等于残值 |
| 标准租约 `TOTAL_RENT = 1 032 797.16` | 更正为 `1 032 797.15`（11×86 066.43 + 末期调整 86 066.42，Task 21） |
| §8.2 列 D 条件规则的求值接线 | 条件规则的 EvalContext 接线在引擎层完成（Plan 1），`RuleSetView` 端口保持 4 参，条件求值走编译器附加的 `withCondition` 路径 |
| 还款方式标识 `RepaymentMethodId` | 封闭枚举为 v1 立场，开放自定义标识待 Plan 2 裁定 |
| 包命名空间 `com.fincalc` | 迁移为 `io.github.cuukenn.fincalc`（用户指令 R67，2026-11-07）；JPMS 模块名同步，Maven artifactId 不变 |
