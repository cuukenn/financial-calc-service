/**
 * TypeScript mirrors of the calc-server DTOs
 * (calc-server/src/main/java/io/github/cuukenn/fincalc/server/dto/).
 *
 * Contract notes (Plan 2, R76/R77):
 * - Money is transported as JSON strings to preserve BigDecimal precision.
 * - Business failures answer HTTP 400/500 with the unified body {code, message}.
 */

export type PaymentFrequency = 'MONTHLY' | 'QUARTERLY' | 'SEMI_ANNUAL' | 'ANNUAL' | 'IRREGULAR'

export type PaymentTiming = 'IN_ADVANCE' | 'IN_ARREARS'

export type ResidualHandling = 'SEPARATE' | 'BALLOON_IN_RENT'
export type FeeOwner = 'LESSEE' | 'LESSOR'
export type ProductProfile = 'LEASE' | 'MORTGAGE'
export type DepositTiming = 'UPFRONT' | 'WITH_FIRST_RENT'
export type DepositSettlement = 'REFUND' | 'OFFSET_PRINCIPAL' | 'OFFSET_FINAL_RENT'

/** IRREGULAR 频率的一个显式期间（PeriodDateDto）。 */
export interface ExplicitPeriod {
  startDate: string
  endDate: string
  paymentDate: string
}

/** 舍入策略分节（RoundingDto）；缺省字段回退 RoundingPolicy.defaults()。 */
export interface RoundingOptions {
  /** 金额小数位（缺省 2）。 */
  scale?: number
  /** java.math.RoundingMode 枚举名（缺省 HALF_UP）。 */
  mode?: string
  /** 尾差处理：LAST_PERIOD（缺省）/ FIRST_PERIOD / ... */
  tailAdjustPolicy?: string
}

/** 宽限期分节（GracePeriodDto）；缺省 = 无宽限期。 */
export interface GracePeriodOptions {
  /** 只付息期数（本金不还、利息照计）。 */
  interestOnlyPeriods?: number
  /** 全宽限期数（本息均不计）。 */
  fullGracePeriods?: number
}

/** 一笔费用（FeeDto）。 */
export interface FeeInput {
  /** 费用类型标识（自由文本）。 */
  type: string
  /** 费用金额。 */
  amount: string
  /** UPFRONT（期初一次性）/ DEDUCT_FROM_PRINCIPAL（抵减本金）/ AMORTIZED（摊入租金）。 */
  chargeMode: string
  /** 费用承担方：LESSEE（承租人承担、出租人收取，缺省）/ LESSOR（出租人承担，事件表流出）。 */
  owner?: FeeOwner
  /** 是否计入 IRR 现金流；缺省 false。 */
  includeInIrr?: boolean
}

/** 一条规则行（RuleRowDto，设计文档 §8.2 列语义）。 */
export interface RuleRow {
  /** 列 A：规则唯一标识。 */
  ruleId: string
  /** 列 B：优先级，区间冲突时数值小者优先。 */
  priority?: number
  /** 列 E：期次区间起点（1 起）。 */
  fromPeriod?: number
  /** 列 F：期次区间终点（含）。 */
  toPeriod?: number
  /** 列 G：年利率，小数形式。 */
  annualRate?: string
  /** 列 H：计息基数（OPENING_BALANCE 等）。 */
  rateBase?: string
  /** 列 I：本金计划（设计文档 §8.2.1）。 */
  principalPlan?: string
  /** 列 J：配合 principalPlan 使用（金额/百分比/逐期序列）。 */
  principalValue?: string
  /** 列 J 的逐期序列形式（principalPlan=CUSTOM 时，逗号分隔）。 */
  principalSequence?: string
  /** 列 K：利息处理（PAY_AS_INCURRED 等）。 */
  interestTreatment?: string
  /** 列 L：付款时点（IN_ARREARS 等）。 */
  paymentTiming?: string
  specialAction?: string
  actionParam?: string
  stage?: number
  condition?: string
  memo?: string
  rowNumber?: number
  cellRef?: string
}

/** POST /api/v1/calculations 的请求体（CalculationRequestDto）。 */
export interface CalculationRequest {
  /** 租赁本金，必须为正数（FCS-DOM-1001）。 */
  principal: string
  /** CAS 21 计量链（选填）：融资额 = assetValue + initialDirectCosts − downPayment。 */
  assetValue?: string
  downPayment?: string
  initialDirectCosts?: string
  guaranteedResidual?: string
  unguaranteedResidual?: string
  /** 名义年利率，小数形式（0.06 = 6%）。 */
  nominalRate: string
  /** 起租日，ISO-8601（yyyy-MM-dd）。 */
  commenceDate: string
  /** 首期租金日，ISO-8601；不得早于起租日。 */
  firstRentDate: string
  /** 期数，≥1；frequency=IRREGULAR 时忽略、改用 explicitPeriods。 */
  periods?: number
  /** IRREGULAR 频率的显式期间列表。 */
  explicitPeriods?: ExplicitPeriod[]
  frequency: PaymentFrequency
  timing: PaymentTiming
  /** 天数口径 code（DayCountConvention 注册名）。 */
  dayCountCode?: string
  /** 日期滚动 code（DateRollConvention 注册名）。 */
  dateRollCode?: string
  rounding?: RoundingOptions
  /** 残值金额；缺省 = 无残值。 */
  residualValue?: string
  residualHandling?: ResidualHandling
  /** 保证金金额；缺省 = 无保证金。保证金 > 0 时后端现金流基准自动为 INCLUDE_DEPOSIT（进入 IRR）。 */
  deposit?: string
  /** 保证金收取时点：UPFRONT（起租日，缺省）/ WITH_FIRST_RENT（随首期租金）。 */
  depositTiming?: DepositTiming
  /** 保证金退还/抵扣方式：REFUND（缺省）/ OFFSET_PRINCIPAL（租金按净本金）/ OFFSET_FINAL_RENT。 */
  depositSettlement?: DepositSettlement
  /** 保证金计息年利率，小数形式；缺省 = 不计息。 */
  depositInterestRate?: string
  /** 首期是否按 129号①「整月+零头」拆分计息；缺省 false = 129号② 一段计。 */
  firstPeriodStub?: boolean
  /** 业务产品：LEASE（缺省）/ MORTGAGE——仅选择校验装配（引擎 WARN 阈值披露），内核产品盲。 */
  product?: ProductProfile
  /** 还款方式 code（RepaymentMethodId 枚举名，见 GET /api/v1/algorithms）。 */
  method?: string
  gracePeriod?: GracePeriodOptions
  /** 分段利率策略（携带 ruleRows 时生效）。 */
  rateChangePolicy?: string
  /** EXPLICIT_RENT 专用：逐期租金数组，长度 = 期数且每期 > 0。 */
  explicitRents?: string[]
  fees?: FeeInput[]
  ruleRows?: RuleRow[]
  /** 规则集未覆盖全部期间时是否放行；缺省 false。 */
  allowPartialRuleSet?: boolean
}

/** 一期租金明细（InstallmentDto，领域 Installment 的 16 字段映射；金额均为字符串）。 */
export interface Installment {
  periodNo: number
  startDate: string
  endDate: string
  /** 本期计息天数（按 dayCountCode 口径）。 */
  days: number
  openingBalance: string
  principalDue: string
  interestAccrued: string
  interestPaid: string
  fee: string
  tax: string
  /** 本期租金（本金 + 利息 + 费用 + 税）。 */
  rentDue: string
  closingBalance: string
  cumulativePrincipal: string
  cumulativeInterest: string
  /** 本期适用期利率（字符串小数，如 0.005）。 */
  appliedRate: string
  /** 备注（尾差调整、规则行来源等），可为 null。 */
  memo: string | null
}

/** 一笔现金流事件（CashFlowDto）；amount 为正数幅值，方向见 direction。 */
export interface CashFlowEvent {
  /** 事件序号（0 起，含期初事件）。 */
  sequence: number
  date: string
  amount: string
  /** PRINCIPAL / INTEREST / FEE / RENT 等。 */
  type: string
  /** INFLOW / OUTFLOW。 */
  direction: string
}

/** 一条校验发现（IssueDto）；periodNo 为 null 表示全表级问题。 */
export interface Issue {
  level: string
  code: string
  message: string
  periodNo: number | null
}

/** 表头摘要（HeaderDto）；metrics 为 §9.5 全部金融指标的 code → 值映射。 */
export interface ScheduleHeader {
  periods: number
  firstDate: string
  lastDate: string
  /** 租金生成器/求解器标识（如 EQUAL_INSTALLMENT、SEGMENTED_RESOLVE）。 */
  solver: string
  metrics: Record<string, string | null>
  /** 尾差调整额（字符串金额）。 */
  roundingDifference: string | null
}

/** 租金表视图（ScheduleDto）。 */
export interface Schedule {
  header: ScheduleHeader
  installments: Installment[]
  cashFlows: CashFlowEvent[]
  issues: Issue[]
  /** 问题列表中是否含 ERROR 级条目。 */
  hasErrors: boolean
}

/** §9.7 实际利率法摊销表的一行（EffectiveInterestRowDto）。 */
export interface EffectiveInterestRow {
  periodNo: number
  openingNetInvestment: string
  interestIncome: string
  cashReceived: string
  principalRecovery: string
  closingNetInvestment: string
}

/** §9.7 实际利率法摊销表（EffectiveInterestDto）；整体可为 null。 */
export interface EffectiveInterest {
  rows: EffectiveInterestRow[]
  /** 实际利率 EIR（周期利率，字符串小数）。 */
  eir: string
  /** 利息总额调整差（字符串金额）。 */
  totalInterestDifference: string
}

/** POST /api/v1/calculations 的响应体（CalculationResponseDto）。 */
export interface CalculationResult {
  /** 本次测算的运行标识（32 位十六进制，无连字符）。 */
  runId: string
  schedule: Schedule
  /** IRR 不收敛或不可建表时为 null（预期结果，非错误）。 */
  effectiveInterest: EffectiveInterest | null
}

/** 统一错误体（ApiError，R77）。 */
export interface ApiErrorBody {
  code: string
  message: string
}
