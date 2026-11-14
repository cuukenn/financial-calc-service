# SPI 机制清单

测算引擎的扩展点全部收敛在两种机制上，扩展前先在这里确认接口与注册表归属。

## 机制一：注册表扩展（`OrderedExtension` + `ExtensionRegistry<T>`）

所有可枚举扩展实现 `OrderedExtension`（`code()` + `order()`），经泛型注册表按 code
注册、查找与路由；同 code 后注册覆盖先注册；有序扩展按 `order()` 排序（同序按注册序）。

| SPI 接口 | 注册表 | code / 命名空间 | 装配位置 |
|---|---|---|---|
| `ScheduleGenerator` | `GeneratorRegistry` | 还款方式 code（`EQUAL_INSTALLMENT` … `RULE_DRIVEN`） | 编排层（`RULE_DRIVEN` 每次运行经 `withRuleDriven` 新装） |
| `FinancialMetric` | `MetricRegistry` | 指标码（`MetricCodes`，按层次分层 order） | 领域层默认集 |
| `Validator` | `ValidatorChain` | 校验名（如 `PRINCIPAL_CONSERVATION`） | 领域层默认链 |
| `DayCountConvention` | `DayCountConventions` | 计息基准 code（如 `THIRTY_360`） | 领域层默认集 |
| `DateRollConvention` | `DateRollConventions` | 日期调整 code（如 `NONE`、`MODIFIED_FOLLOWING`） | 领域层默认集 |
| `TailAdjuster` | `TailAdjusters` | 尾差策略 code（如 `LAST_PERIOD`、`PROPORTIONAL`） | 领域层默认集 |
| `FormulaFunction` | `FunctionRegistry`（rule 模块） | 函数名（表达式求值与规则编译共用同一实例） | 规则层默认集 |

**装载方式两种**：

1. **显式注册**（内置扩展一律如此）：`registry.defaults().register(...)` 或自组
   `ExtensionRegistry.empty()` 装配；
2. **ServiceLoader 发现**：`ExtensionRegistry.load(Class)` 自动声明 JPMS `uses`
   ——外部扩展走这条路时需提供 `META-INF/services/` 文件并在模块描述符声明
   `provides`。默认集不依赖 ServiceLoader。

## 机制二：注入式端口（无可枚举集合，构造时替换）

| 端口 | 默认实现 | 替换方式 |
|---|---|---|
| `RootSolver`（求根器） | 二分-牛顿混合求解器 | 求根器可注入重载（数值方法可替换，轮次/容差硬约束见 AGENTS.md §1/§6） |
| `ClockProvider`（时钟） | 系统时钟单例 | 经计算上下文注入 |

另有 `CashFlowBuilder`：目前单实现直接装配（非 code 路由），接口化是为了在
现金流口径扩展时不动编排层。

## 扩展纪律

- 每个可枚举扩展的 `code()` 即对外契约的一部分：改 code = 改契约，需过口径裁定。
- 新增扩展前先回答：**它改变哪一期现金流或余额？** 答不出即不是测算要素。
- 新扩展的实现属于所在模块的口径分包（见各分模块文档），注册装配在模块的默认集
  或编排层完成，不在使用方散落注册。
