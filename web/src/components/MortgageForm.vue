<script setup lang="ts">
/**
 * 房贷计算器表单（Task 5 / R100 · R101）。
 *
 * R100：房贷与融资租赁共用同一个引擎与同一个 POST /api/v1/calculations 契约 ——
 * 本表单不发新端点、不改 DTO，只把房贷要素映射成一份合法的租赁测算请求。
 * R101 映射：贷款金额→principal、LPR+加点→nominalRate、放款日→commenceDate、
 * 首期还款日→firstRentDate、期限（月）→periods，固定 MONTHLY / IN_ARREARS /
 * THIRTY_360 / NONE / CNY（P-03：30/360 使月利率 = 年利率 ÷ 12）。
 * payload 共 14 键（R101 + R137）：11 个房贷要素 + 三个 `LeaseElement` 必填要素
 * （`residualValue` / `residualHandling` / `rateChangePolicy`，见下方 `buildRequest`）。
 * 其余租赁专属键（保证金、费用、规则行、显式租金、宽限期、舍入、税率）一律缺席 ——
 * 空串会被后端当成真实要素写进摊销表的 费用/税 列。
 *
 * R103：抵押物成交价、评估价、月可支配收入、其他月债务支出、首付比例都是前端派生
 * 与前端校验，不进 payload、不进引擎指标目录；贷款成数（LTV）与 DTI 全程在十进制
 * 字符串上算（P-01），阈值比较用未舍入原值（P-10）。
 */
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Col,
  DatePicker,
  Form,
  FormItem,
  Input,
  InputNumber,
  Radio,
  RadioGroup,
  Row,
  Select,
  Space,
  Typography,
} from 'ant-design-vue'
import type { FormInstance } from 'ant-design-vue'
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import { ApiError, calculate } from '@/api/client'
import type { CalculationRequest, CalculationResult } from '@/api/types'
import { formatMoney } from '@/utils/format'
import { composeLprRate } from '@/utils/rate'
import {
  collateralValue,
  compareDecimal,
  decimalSubtract,
  downPaymentRatio,
  formatDecimalPercent,
  loanToValue,
} from '@/utils/ratio'

import MortgageIncomePanel from './MortgageIncomePanel.vue'
import {
  BULLET_MAX_MONTHS,
  MAX_LOAN_MONTHS,
  buildMortgageRules,
  isBulletRepaymentAllowed,
} from './mortgageRules'
import {
  collateralLeverageStatus,
  downPaymentFloorOutcome,
  downPaymentFloorRatio,
  markerKey,
  statusClass,
} from './ratioStatus'
import {
  NO_REPRICING,
  REPRICING_CYCLE_MONTHS,
  buildRateRuleRows,
  isRepricingCycle,
  nextRepricingDate,
  parseLprSequence,
  planSegmentRates,
  repricingSegments,
  segmentsCoverPeriods,
} from './repricing'
import ResultPanel from './ResultPanel.vue'

const { t } = useI18n()

/** 房贷场景的三种还款方式（AGENTS.md §2.2）；BULLET_LOAN 即 Task 2 的 BulletLoanGenerator。 */
const MORTGAGE_METHOD_VALUES = ['EQUAL_INSTALLMENT', 'EQUAL_PRINCIPAL', 'BULLET_LOAN'] as const

/** 表单状态：金额与利率一律为十进制小数字符串（P-01 禁止 float 参与计算）。 */
interface MortgageFormState {
  /** 贷款金额（AGENTS.md §2.2 规范名）。 */
  principal: string
  /** 当前 LPR，百分数口径（'3.5' = 3.5%）。本项目无 LPR 数据源，由用户自填。 */
  lprPercent: string
  /** 加点（基点，1bp = 0.01 个百分点），只接受 5bp 整数倍（P-04）。 */
  spreadBp: string
  commenceDate: string
  firstRepaymentDate: string
  /**
   * 贷款期限（月），UI 上限 480（R107）。声明与租赁表单同款为 number：
   * AntD InputNumber 清空时实际给到的是 null，规则与门槛一律把非整数视为非法。
   */
  periods: number
  method: string
  transactionPrice: string
  appraisalValue: string
  monthlyIncome: string
  otherMonthlyDebt: string
  /**
   * 重定价周期（月）：0 = 不重定价（固定利率，payload 与本字段引入前逐位一致），
   * 或 3 / 6 / 12（央行公告〔2024〕第 11 号给出的三个周期）。其它取值一律拒绝。
   */
  repricingCycle: number
  /** 重定价日（锚点）。留空 = 放款日对日；填了日期 = 每年/每周期的对应日。 */
  repricingAnchorDate: string
  /** 后续各段的 LPR（百分数，逗号分隔）。缺失且未勾选沿用 → 阻断，不静默沿用。 */
  futureLprPercents: string
  /** 显式勾选「缺段沿用当前 LPR」：这是一条被标注的假设，不是合同事实。 */
  reuseCurrentLpr: boolean
}

/**
 * 表单的初始要素 = Task 5 钉住的边界样例（一份 defaults() 工厂，重置与初始态共用，
 * 不重复写第二遍）：成交 3 000 000 / 评估 2 800 000 → 抵押物价值 2 800 000
 * （成交价与评估价孰低），贷款 2 380 000 → 贷款成数 85.00%、首付比例 15.00%，
 * 恰好达全国统一下限（「以上」含本数）。与租赁表单同款：开箱即得一组合法要素。
 */
function defaults(): MortgageFormState {
  return {
    principal: '2380000',
    lprPercent: '3.5',
    spreadBp: '0',
    commenceDate: '2026-11-01',
    firstRepaymentDate: '2026-12-01',
    periods: 360,
    method: 'EQUAL_INSTALLMENT',
    transactionPrice: '3000000',
    appraisalValue: '2800000',
    monthlyIncome: '',
    otherMonthlyDebt: '',
    repricingCycle: NO_REPRICING,
    repricingAnchorDate: '',
    futureLprPercents: '',
    reuseCurrentLpr: false,
  }
}

const form = reactive<MortgageFormState>(defaults())

/** AntD Form 实例：重置时要 resetFields()，否则上一次被阻断的字段错误会留着。 */
const formRef = ref<FormInstance | null>(null)

const loading = ref(false)
const result = ref<CalculationResult | null>(null)
/** 后端统一错误体（FCS code 原样透出）。 */
const submitError = ref<ApiError | null>(null)
/** 前端 [法] 门槛的阻断提示（引擎不参与，见 mortgage.gates.note）。 */
const localError = ref<string | null>(null)
/** 期限越界导致 BULLET_LOAN 被重置的内联说明（R109）。 */
const bulletResetNotice = ref(false)

// ============ 派生值（源状态最小化，其余全部 computed） ============

/** P-04：年利率 = LPR + 加点（十进制串运算，不经过浮点）。 */
const annualRate = computed(() => composeLprRate(form.lprPercent, form.spreadBp))

/** 提交给引擎的那个数字必须可见：小数字符串 + 2 位百分数（P-02 / P-10）。 */
const annualRateText = computed(() =>
  annualRate.value === null
    ? t('mortgage.basic.composedRateInvalid')
    : `${annualRate.value} (${formatDecimalPercent(annualRate.value)})`,
)

const bulletAllowed = computed(() => isBulletRepaymentAllowed(form.periods))

const methodOptions = computed(() =>
  MORTGAGE_METHOD_VALUES.map((value) => ({
    value,
    label: t(`enums.method.${value}`),
    // R109：期限 > 12 月时该选项禁用（不发送、不静默改写用户的其他选择）。
    disabled: value === 'BULLET_LOAN' && !bulletAllowed.value,
  })),
)

/** 抵押物价值 = min(成交价, 评估价)（§2.2 / 金规〔2026〕5号第十九条）。 */
const collateral = computed(() => collateralValue(form.transactionPrice, form.appraisalValue))
const ltv = computed(() => loanToValue(form.principal, collateral.value))
const downPayment = computed(() => downPaymentRatio(form.principal, collateral.value))

/**
 * Task 6 §3（修复轮 1）：抵押物价值 / 贷款成数（LTV）/ 首付比例这组读数的仪表状态。
 * 判定在未舍入的十进制串上做（P-10），而且判线用的**就是提交闸门用的那个函数**
 * （ratioStatus.ts 里唯一的 `downPaymentFloorBlocked` 调用，下限常量也只在那一处引）：
 * 徽章走 collateralLeverageStatus，闸门走 downPaymentFloorOutcome，同一个模块里的同一次判定。
 * 抵押物价值本身不是阈值读数，所以不挂状态。
 */
const leverageState = computed(() => collateralLeverageStatus(form.principal, collateral.value))
const leverageOutcome = computed(() => downPaymentFloorOutcome(form.principal, collateral.value))

/** 摊销表各期租金：月供口径的输入，交给 MortgageIncomePanel 派生（R103）。 */
const rents = computed(() =>
  result.value === null ? [] : result.value.schedule.installments.map((row) => row.rentDue),
)

/** 校验规则与文案取自纯模块，随语言切换响应式更新。 */
const rules = computed(() =>
  buildMortgageRules((key, named) => t(key, named ?? {}), form),
)

watch(
  () => form.periods,
  (periods) => {
    const termIsInteger = typeof periods === 'number' && Number.isInteger(periods)
    if (termIsInteger && periods > BULLET_MAX_MONTHS && form.method === 'BULLET_LOAN') {
      // R109：期限越过 12 月时「到期一次还本付息」不可选，选择被重置并给出内联说明。
      form.method = 'EQUAL_INSTALLMENT'
      bulletResetNotice.value = true
    } else if (termIsInteger && periods <= BULLET_MAX_MONTHS) {
      bulletResetNotice.value = false
    }
  },
)

/**
 * 重定价分段（R150）。`repricingCycle === 0` 时是 `null` —— 固定利率路径，payload 与本模块
 * 引入前逐位一致。分段只决定「哪一期用哪个利率」：日期用来定位期次，不进入任何金额计算
 * （P-01），利率仍然由引擎从这些边界上的期次算出（§3.2 分段计息）。
 */
const repricingSegmentsList = computed(() => {
  if (form.repricingCycle === NO_REPRICING || !isRepricingCycle(form.repricingCycle)) {
    return null
  }
  try {
    return repricingSegments({
      commenceDate: form.commenceDate,
      firstRepaymentDate: form.firstRepaymentDate,
      periods: form.periods ?? 0,
      cycleMonths: form.repricingCycle,
      // 重定价日留空 = 放款日对日（§2.2「重定价日」的默认口径），不是猜一个日期。
      anchorDate:
        form.repricingAnchorDate.trim() === '' ? form.commenceDate : form.repricingAnchorDate.trim(),
    })
  } catch {
    // 日期或期限非法（表单规则会给字段级文案）：这里只保证不产出一套错误的段。
    return null
  }
})

/** 每段利率 = 该段 LPR + 同一加点（P-04：加点是合同条款，重定价只重定 LPR）。 */
const repricingPlan = computed(() => {
  const segments = repricingSegmentsList.value
  if (segments === null) return null
  return planSegmentRates({
    segmentCount: segments.length,
    currentLprPercent: form.lprPercent,
    spreadBp: form.spreadBp,
    futureLprPercents: parseLprSequence(form.futureLprPercents),
    reuseCurrentLpr: form.reuseCurrentLpr,
  })
})

const nextRepricing = computed(() =>
  repricingSegmentsList.value === null ? null : nextRepricingDate(repricingSegmentsList.value),
)

/** 靠「沿用当前 LPR」补上的段：必须作为假设披露，不得伪装成合同里给过的利率。 */
const assumedRepricingSegments = computed(() => repricingPlan.value?.assumedSegments ?? [])

const cycleOptions = computed(() => [
  { value: NO_REPRICING, label: t('mortgage.repricing.cycles.none') },
  ...REPRICING_CYCLE_MONTHS.map((months) => ({
    value: months,
    label: t('mortgage.repricing.cycles.months', { months }),
  })),
])

/** 段区间读数：让「哪几期用哪个利率」在提交前就是人可核对的。 */
const repricingSegmentReadout = computed(() => {
  const segments = repricingSegmentsList.value
  const plan = repricingPlan.value
  if (segments === null || plan === null) return []
  return segments.map((segment) => ({
    key: segment.index,
    label: t('mortgage.repricing.segmentRange', {
      from: segment.fromPeriod,
      to: segment.toPeriod,
      date: segment.effectiveDate,
    }),
    rate: plan.rates[segment.index] === null
      ? t('mortgage.repricing.rateMissing')
      : formatDecimalPercent(plan.rates[segment.index] as string, 4),
  }))
})

/**
 * 提交（R101 字段映射：14 个键）。R137：`residualHandling` / `rateChangePolicy` 是
 * `LeaseElement` 的必填要素（`LeaseElement.requireField`），`CalculationRequestAssembler`
 * 只给 `rounding` / `gracePeriod` 补默认 —— AGENTS.md §3.1 也规定输入要素必须显式提供、
 * 缺失即报错、不得静默取默认值，所以这两键连同 `residualValue` 一律由表单**显式发送**，
 * 不在服务端补默认。住房按揭没有残值/留购价：`SEPARATE` + `0` 让 §3.4 校验一与校验二
 * 严格成立（Σ本金 = 合同本金、末期余额 = 0）。`RESOLVE` 是重定价的既有姿态（自变更日重算）。
 *
 * R150：重定价不新增契约键。它就是把「周期 + 每段 LPR」写成引擎早就认识的**分段利率规则行**
 * （`ruleRows`），所以 14 键的固定利率路径逐字节不变，只有真的选了周期才多一个 `ruleRows`。
 */
function buildRequest(annualRateDecimal: string): CalculationRequest {
  const request: CalculationRequest = {
    principal: form.principal.trim(),
    nominalRate: annualRateDecimal,
    commenceDate: form.commenceDate,
    firstRentDate: form.firstRepaymentDate,
    periods: form.periods ?? 0,
    // §2.3：期限 > 1 年按月还本付息；30/360 使月利率 = 年利率 ÷ 12（P-03）。
    frequency: 'MONTHLY',
    timing: 'IN_ARREARS',
    dayCountCode: 'THIRTY_360',
    dateRollCode: 'NONE',
    currency: 'CNY',
    method: form.method,
    residualValue: '0',
    residualHandling: 'SEPARATE',
    rateChangePolicy: 'RESOLVE',
    // A1（issue 15）：产品身份声明——引擎据以装配 §2.3 阈值 WARN 披露。
    product: 'MORTGAGE',
  }
  const segments = repricingSegmentsList.value
  const plan = repricingPlan.value
  if (segments === null || plan === null) return request
  // 段利率缺失在这里就已经不可能：闸门在提交前拦（见 onFinish）。规则行不得带空利率。
  request.ruleRows = buildRateRuleRows(segments, plan.rates as string[])
  return request
}

/**
 * 被前端 [法] 门槛阻断（Task 6 §2）：先把上一次跑的结果与 API 错误清掉，再给阻断文案。
 * Task 5 评审点出的 stale readout 路径就在这里 —— 被阻断的一次提交不能把上一次的结果
 * 和 DTI/LTV 读数留在屏幕上。
 */
function blockRun(message: string): void {
  result.value = null
  submitError.value = null
  localError.value = message
}

/** 表单规则把提交拦下（贷款金额 > 0、格式非法等）：同样不得留下上一次跑的结果与读数。 */
function onFinishFailed(): void {
  result.value = null
  submitError.value = null
}

/** AntD Form 的 @finish 只在内置校验通过后触发；此处再跑前端 [法] 门槛。 */
async function onFinish(): Promise<void> {
  // 首付硬下限 15%：用未舍入原值比较（P-10），恰好 15% 放行（「以上」含本数）。
  // 抵押物价值缺失或 ≤ 0 时贷款成数（LTV）/首付比例不可计算 —— 无法判定的
  // 硬下限必须阻断而不是放行（AGENTS.md §2.3「阈值即硬约束」、§9.5 不可计算姿态）。
  // 修复轮 1：闸门读的就是徽章用的那一次结局（ratioStatus.ts 里唯一的判定处），
  // 表单自己不再比一遍下限。
  const floorText = formatDecimalPercent(downPaymentFloorRatio(), 0)
  if (leverageOutcome.value !== 'ok') {
    blockRun(
      leverageOutcome.value === 'unevaluable'
        ? t('mortgage.gates.downPaymentFloorNotEvaluable', { min: floorText })
        : t('mortgage.gates.downPaymentFloor', {
            min: floorText,
            actual: formatDecimalPercent(downPayment.value),
          }),
    )
    return
  }
  // R109 兜底：期限 > 12 月时 BULLET_LOAN 不可能被提交（选项已禁用并被 watch 重置）。
  if (form.method === 'BULLET_LOAN' && !bulletAllowed.value) {
    blockRun(t('mortgage.gates.bulletOnly', { max: BULLET_MAX_MONTHS }))
    return
  }
  const composed = annualRate.value
  if (composed === null) {
    blockRun(t('mortgage.gates.rateNotComposable'))
    return
  }

  // R150 重定价闸门：周期取值、段覆盖、以及「缺段 LPR 不得静默沿用」都要在网络往返之前失败。
  // 引擎不认识「重定价周期」这个词（它只认分段利率），所以 3/6/12 之外的取值只有这里拦得住。
  if (form.repricingCycle !== NO_REPRICING) {
    const segments = repricingSegmentsList.value
    const plan = repricingPlan.value
    if (segments === null || plan === null) {
      blockRun(t('mortgage.gates.repricingCycle'))
      return
    }
    if (!segmentsCoverPeriods(segments, form.periods ?? 0)) {
      blockRun(t('mortgage.gates.repricingCoverage', { periods: form.periods ?? 0 }))
      return
    }
    if (plan.missingSegments.length > 0) {
      blockRun(t('mortgage.gates.repricingMissingLpr', { count: plan.missingSegments.length }))
      return
    }
  }

  localError.value = null
  submitError.value = null
  loading.value = true
  try {
    result.value = await calculate(buildRequest(composed))
  } catch (err: unknown) {
    result.value = null
    submitError.value =
      err instanceof ApiError ? err : new ApiError('UNKNOWN_ERROR', String(err), 0)
  } finally {
    loading.value = false
  }
}

/**
 * 部分提前还款重算剩余期供（ticket 08）：剩余本金 = 结清日承载期期初余额 − 部分还款金额，
 * 剩余期数与下一次还款日由结清试算 pane 给出，年利率沿用本次测算的 LPR + 加点组合值。
 * 重算按**当前年利率固定计息**（不带原重定价分段行 —— 分段相对原起放款日，缩短后的租期
 * 需要重新映射；需要重定价分段时请回表单重跑完整测算）。
 * 金额非法、≥ 剩余本金（那是一次性结清，走结清金额读数）或年利率不可用时阻断并给出文案。
 */
async function onPrepay(payload: {
  settlementDate: string
  prepayAmount: string
  recalcPrincipal: string
  nextRentDate: string
  remainingPeriods: number
}): Promise<void> {
  const annual = annualRate.value
  if (annual === null) return
  // 重算本金 = 下一期期初余额 − 部分还款金额（pane 只在还款日当日放行，见 SettlementPane）。
  const remaining = decimalSubtract(payload.recalcPrincipal, payload.prepayAmount)
  if (remaining === null || compareDecimal(remaining, '0') <= 0) {
    blockRun(t('mortgage.gates.prepayNotPartial'))
    return
  }
  if (payload.remainingPeriods < 1) {
    blockRun(t('mortgage.gates.prepayNoPeriods'))
    return
  }
  const request: CalculationRequest = {
    principal: remaining,
    nominalRate: annual,
    commenceDate: payload.settlementDate,
    firstRentDate: payload.nextRentDate,
    periods: payload.remainingPeriods,
    frequency: 'MONTHLY',
    timing: 'IN_ARREARS',
    dayCountCode: 'THIRTY_360',
    dateRollCode: 'NONE',
    currency: 'CNY',
    method: form.method,
    residualValue: '0',
    residualHandling: 'SEPARATE',
    rateChangePolicy: 'RESOLVE',
    // A1（issue 15）：产品身份声明——引擎据以装配 §2.3 阈值 WARN 披露。
    product: 'MORTGAGE',
  }
  localError.value = null
  submitError.value = null
  loading.value = true
  try {
    result.value = await calculate(request)
  } catch (err: unknown) {
    result.value = null
    submitError.value =
      err instanceof ApiError ? err : new ApiError('UNKNOWN_ERROR', String(err), 0)
  } finally {
    loading.value = false
  }
}

/**
 * 显式重置（Task 6 §2）：模型回到 defaults()、清掉校验态、清掉结果与两条错误提示，
 * 依赖这次结果的读数（月供 / DTI / 全部负债 DTI）随之回到未计算的「—」。
 * 抵押物价值 / 贷款成数 / 首付比例是活输入的实时派生，不是这次跑的结果：
 * 它们回到与初次挂载相同的状态（默认要素 85.00% / 15.00%）。
 * 重置只管这一份表单：不切 Tab、不碰语言偏好。
 */
function onReset(): void {
  Object.assign(form, defaults())
  result.value = null
  submitError.value = null
  localError.value = null
  bulletResetNotice.value = false
  // resetFields 而不是 clearValidate：AntD 的 resetField 会把字段标成「不再响应
  // 这次写入触发的 change 校验」，否则刚被阻断的那次校验还在飞行中，
  // 它回来时会给已经改回合法默认值的字段重新盖上错误。
  formRef.value?.resetFields()
}
</script>

<template>
  <!-- 与租赁表单同款布局：宽视口左表单（10）/ 右结果（14），窄视口纵向堆叠。 -->
  <Row :gutter="16" data-test="mortgage-layout">
    <Col :xs="24" :lg="10">
      <Card :title="t('mortgage.cardTitle')" data-test="mortgage-form-card">
        <Form
          ref="formRef"
          :model="form"
          :rules="rules"
          :label-col="{ style: { width: '92px' } }"
          class="compact-form"
          @finish="onFinish"
          @finish-failed="onFinishFailed"
        >
          <Card
            :title="t('mortgage.basic.title')"
            size="small"
            data-test="mortgage-card-basic"
            class="block-card"
          >
            <Row :gutter="12">
              <Col :span="12">
                <FormItem :label="t('mortgage.basic.principal')" name="principal">
                  <Input
                    v-model:value="form.principal"
                    data-test="mortgage-principal"
                    :placeholder="t('mortgage.basic.principalPh')"
                  />
                </FormItem>
              </Col>
              <Col :span="12">
                <FormItem :label="t('mortgage.basic.periods')" name="periods">
                  <InputNumber
                    v-model:value="form.periods"
                    :min="1"
                    :max="MAX_LOAN_MONTHS"
                    style="width: 100%"
                    data-test="mortgage-periods"
                  />
                </FormItem>
              </Col>
              <Col :span="12">
                <FormItem :label="t('mortgage.basic.lpr')" name="lprPercent">
                  <Input
                    v-model:value="form.lprPercent"
                    data-test="mortgage-lpr"
                    :placeholder="t('mortgage.basic.lprPh')"
                  />
                </FormItem>
              </Col>
              <Col :span="12">
                <FormItem :label="t('mortgage.basic.spread')" name="spreadBp">
                  <Input
                    v-model:value="form.spreadBp"
                    data-test="mortgage-spread"
                    :placeholder="t('mortgage.basic.spreadPh')"
                  />
                  <template #extra>
                    <span data-test="mortgage-composed-rate">
                      <span class="composed-rate">
                        {{ t('mortgage.basic.composedRate') }}: {{ annualRateText }}
                      </span>
                      <span class="composed-rate composed-rate-hint">
                        {{ t('mortgage.basic.composedRateHint') }}
                      </span>
                    </span>
                  </template>
                </FormItem>
              </Col>
              <Col :span="12">
                <FormItem :label="t('mortgage.repricing.cycle')" name="repricingCycle">
                  <Select
                    v-model:value="form.repricingCycle"
                    :options="cycleOptions"
                    data-test="mortgage-repricing-cycle"
                    style="width: 100%"
                  />
                  <template #extra>
                    <span>{{ t('mortgage.repricing.hint') }}</span>
                  </template>
                </FormItem>
              </Col>
              <Col :span="12" data-test="mortgage-repricing-anchor-col">
                <FormItem :label="t('mortgage.repricing.anchorDate')" name="repricingAnchorDate">
                  <span data-test="mortgage-repricing-anchor" style="display: block">
                    <DatePicker
                      v-model:value="form.repricingAnchorDate"
                      value-format="YYYY-MM-DD"
                      allow-clear
                      :disabled="form.repricingCycle === NO_REPRICING"
                      :placeholder="t('mortgage.repricing.anchorPh')"
                      style="width: 100%"
                    />
                  </span>
                </FormItem>
              </Col>
              <Col :span="12" data-test="mortgage-repricing-lprs-col">
                <FormItem :label="t('mortgage.repricing.futureLprs')" name="futureLprPercents">
                  <Input
                    v-model:value="form.futureLprPercents"
                    data-test="mortgage-repricing-lprs"
                    :disabled="form.repricingCycle === NO_REPRICING"
                    :placeholder="t('mortgage.repricing.futureLprsPh')"
                  />
                  <template #extra>
                    <span>{{ t('mortgage.repricing.futureLprsHint') }}</span>
                  </template>
                </FormItem>
              </Col>
              <Col :span="12">
                <FormItem>
                  <Checkbox
                    v-model:checked="form.reuseCurrentLpr"
                    data-test="mortgage-repricing-reuse"
                    :disabled="form.repricingCycle === NO_REPRICING"
                  >
                    {{ t('mortgage.repricing.reuse') }}
                  </Checkbox>
                </FormItem>
              </Col>
              <Col :span="24">
                <FormItem :label="t('mortgage.basic.commenceDate')" name="commenceDate">
                  <span data-test="mortgage-commence-date" style="display: block">
                    <DatePicker
                      v-model:value="form.commenceDate"
                      value-format="YYYY-MM-DD"
                      style="width: 100%"
                    />
                  </span>
                </FormItem>
              </Col>
              <Col :span="12">
                <FormItem :label="t('mortgage.basic.firstRepaymentDate')" name="firstRepaymentDate">
                  <span data-test="mortgage-first-repayment-date" style="display: block">
                    <DatePicker
                      v-model:value="form.firstRepaymentDate"
                      value-format="YYYY-MM-DD"
                      style="width: 100%"
                    />
                  </span>
                </FormItem>
              </Col>
              <Col :span="24">
                <FormItem :label="t('mortgage.basic.method')" name="method">
                  <RadioGroup v-model:value="form.method" data-test="mortgage-method-group">
                    <Radio
                      v-for="option in methodOptions"
                      :key="option.value"
                      :value="option.value"
                      :disabled="option.disabled"
                    >
                      {{ option.label }}
                    </Radio>
                  </RadioGroup>
                  <template #extra>
                    <span>{{ t('mortgage.gates.bulletOnly', { max: BULLET_MAX_MONTHS }) }}</span>
                  </template>
                </FormItem>
              </Col>
              <Col v-if="form.repricingCycle !== NO_REPRICING" :span="24">
                <div class="repricing-readout" data-test="mortgage-repricing-readout">
                  <div class="repricing-readout-title">
                    {{ t('mortgage.repricing.readoutTitle') }}
                  </div>
                  <div data-test="mortgage-repricing-current-rate">
                    {{ t('mortgage.repricing.currentRate') }}: {{ annualRateText }}
                    （{{ t('mortgage.repricing.currentRateFrom') }}:
                    {{ repricingSegmentsList && repricingSegmentsList.length > 0
                      ? repricingSegmentsList[0].effectiveDate : form.commenceDate }}）
                  </div>
                  <div data-test="mortgage-repricing-next-date">
                    {{ t('mortgage.repricing.nextDate') }}:
                    {{ nextRepricing ?? t('mortgage.repricing.nextDateNone') }}
                  </div>
                  <ul class="repricing-segments">
                    <li
                      v-for="row in repricingSegmentReadout"
                      :key="row.key"
                      :data-test="`mortgage-repricing-segment-${row.key}`"
                    >
                      {{ row.label }} — {{ row.rate }}
                    </li>
                  </ul>
                  <div
                    v-if="assumedRepricingSegments.length > 0"
                    class="repricing-assumed"
                    data-test="mortgage-repricing-assumed"
                  >
                    {{ t('mortgage.repricing.assumedNotice', {
                      count: assumedRepricingSegments.length,
                    }) }}
                  </div>
                </div>
              </Col>
            </Row>
          </Card>

          <Card
            :title="t('mortgage.collateral.title')"
            size="small"
            data-test="mortgage-card-collateral"
            class="block-card"
          >
            <Row :gutter="12">
              <Col :span="12">
                <FormItem :label="t('mortgage.collateral.transactionPrice')" name="transactionPrice">
                  <Input v-model:value="form.transactionPrice" data-test="mortgage-transaction-price" />
                </FormItem>
              </Col>
              <Col :span="12">
                <FormItem :label="t('mortgage.collateral.appraisalValue')" name="appraisalValue">
                  <Input v-model:value="form.appraisalValue" data-test="mortgage-appraisal-value" />
                </FormItem>
              </Col>
            </Row>
            <!-- 抵押物价值 / 贷款成数 / 首付比例：前端派生指标，不进引擎（R103）。
                 阈值读数（LTV / 首付比例）的颜色与文案标记见 Task 6 §3。 -->
            <dl class="ratio-list">
              <div class="ratio-item">
                <dt>{{ t('mortgage.collateral.collateralValue') }}</dt>
                <dd data-test="mortgage-collateral-value">{{ formatMoney(collateral) }}</dd>
              </div>
              <div class="ratio-item">
                <dt>{{ t('mortgage.collateral.ltv') }}</dt>
                <dd class="ratio-readout" :class="statusClass(leverageState)" data-test="mortgage-ltv-readout">
                  <span data-test="mortgage-ltv-value">{{ formatDecimalPercent(ltv) }}</span>
                  <span data-test="mortgage-ltv-status" class="ratio-marker">{{ t(markerKey(leverageState)) }}</span>
                </dd>
              </div>
              <div class="ratio-item">
                <dt>{{ t('mortgage.collateral.downPaymentRatio') }}</dt>
                <dd class="ratio-readout" :class="statusClass(leverageState)" data-test="mortgage-down-payment-readout">
                  <span data-test="mortgage-down-payment-value">{{ formatDecimalPercent(downPayment) }}</span>
                  <!-- 下限类读数：breach 的方向是「低于下限」，不是「超限」。 -->
                  <span data-test="mortgage-down-payment-status" class="ratio-marker">{{ t(markerKey(leverageState, true)) }}</span>
                </dd>
              </div>
            </dl>
          </Card>

          <MortgageIncomePanel
            v-model:monthly-income="form.monthlyIncome"
            v-model:other-monthly-debt="form.otherMonthlyDebt"
            :rents="rents"
            :method="form.method"
          />

          <Alert
            v-if="bulletResetNotice"
            type="warning"
            show-icon
            :message="t('mortgage.gates.bulletReset', { max: BULLET_MAX_MONTHS })"
            data-test="bullet-reset-alert"
            style="margin-bottom: 12px"
          />

          <FormItem style="margin-top: 16px">
            <Space>
              <Button
                type="primary"
                html-type="submit"
                :loading="loading"
                size="large"
                data-test="mortgage-submit-btn"
              >
                {{ loading ? t('form.submitting') : t('form.submit') }}
              </Button>
              <!-- Task 6 §2：显式重置。面板只隐藏不销毁（R130），所以清空必须是表单自己的动作。 -->
              <Button size="large" data-test="mortgage-reset-btn" @click="onReset()">
                {{ t('form.reset') }}
              </Button>
            </Space>
          </FormItem>
        </Form>

        <!-- 前端 [法] 门槛的阻断提示：文案明说引擎不校验这些门槛（R107/R109）。 -->
        <Alert
          v-if="localError"
          type="warning"
          show-icon
          :message="localError"
          data-test="mortgage-local-error"
          style="margin-top: 12px"
        />
        <!-- FCS code 原样透出（R77 统一错误体），与租赁表单同款。 -->
        <Alert
          v-if="submitError"
          type="error"
          show-icon
          :message="t('form.submitFailed', { code: submitError.code })"
          :description="submitError.message"
          data-test="mortgage-error-alert"
          style="margin-top: 12px"
        />
        <Typography.Text type="secondary" data-test="mortgage-gates-note" style="display: block; margin-top: 12px">
          {{ t('mortgage.gates.note') }}
        </Typography.Text>
      </Card>
    </Col>

    <Col :xs="24" :lg="14">
      <!-- 结果区：ResultPanel 只是外壳（Card + runId + ERROR 提示 + 四个 Tab），
           各 Tab 的主体已拆成 `components/result/*Pane.vue`（D-6）。 -->
      <ResultPanel v-if="result" :result="result" scenario="mortgage"
        day-count-code="THIRTY_360" :annual-rate="annualRate ?? undefined"
        @prepay="onPrepay" />
    </Col>
  </Row>
</template>

<style scoped>
/* 只留真正组件特有的规则：大块卡片（.block-card）、紧凑栅格（.compact-form）与
   派生读数区（.ratio-list / .ratio-item / .ratio-readout 与阈值配色）都在
   styles/workbench.css 里定义一次（Task 6 §4，scoped 块无法用 :deep 之外的方式
   覆盖 AntD 注入的运行时样式，所以这些规则此前在两个表单里各写了一份）。 */
.composed-rate {
  display: block;
  font-size: 12px;
  color: var(--fcs-text-muted);
}
.composed-rate-hint {
  font-size: 11px;
}
/* 重定价读数（R150）：把「哪几期用哪个利率」写在提交之前，让分段是人可核对的。 */
.repricing-readout {
  border: 1px solid var(--fcs-panel-border);
  border-radius: 4px;
  padding: 8px 10px;
  font-size: 12px;
  color: var(--fcs-text-muted);
}
.repricing-readout-title {
  font-weight: 600;
  margin-bottom: 4px;
}
.repricing-segments {
  margin: 4px 0 0;
  padding-left: 18px;
}
.repricing-assumed {
  margin-top: 4px;
  color: var(--fcs-caveat);
}
</style>
