<script setup lang="ts">
import {
  Alert,
  Button,
  Card,
  DatePicker,
  Divider,
  Form,
  FormItem,
  Input,
  InputNumber,
  Radio,
  RadioGroup,
  Select,
  Space,
  Typography,
} from 'ant-design-vue'
import type { Rule } from 'ant-design-vue/es/form'
import { reactive, ref, watch } from 'vue'

import { ApiError, calculate } from '@/api/client'
import type {
  CalculationRequest,
  CalculationResult,
  FeeInput,
  PaymentFrequency,
  PaymentTiming,
  ResidualHandling,
  RuleRow,
} from '@/api/types'

import ResultPanel from './ResultPanel.vue'

interface Option {
  label: string
  value: string
}

/** 天数口径：六个已注册的 DayCountConvention code（calc-domain/support/daycount）。 */
const DAY_COUNT_CODES: Option[] = [
  { label: 'THIRTY_360（30/360）', value: 'THIRTY_360' },
  { label: 'THIRTY_E_360（30E/360）', value: 'THIRTY_E_360' },
  { label: 'ACT_360（实际/360）', value: 'ACT_360' },
  { label: 'ACT_365F（实际/365 固定）', value: 'ACT_365F' },
  { label: 'ACT_365_25（实际/365.25）', value: 'ACT_365_25' },
  { label: 'ACT_ACT（实际/实际）', value: 'ACT_ACT' },
]

/** 日期滚动：六个已注册的 DateRollConvention code（calc-domain/support/dateroll）。 */
const DATE_ROLL_CODES: Option[] = [
  { label: 'NONE（不滚动）', value: 'NONE' },
  { label: 'FOLLOWING（向后）', value: 'FOLLOWING' },
  { label: 'MODIFIED_FOLLOWING（修正向后）', value: 'MODIFIED_FOLLOWING' },
  { label: 'PRECEDING（向前）', value: 'PRECEDING' },
  { label: 'MODIFIED_PRECEDING（修正向前）', value: 'MODIFIED_PRECEDING' },
  { label: 'END_OF_MONTH（对齐月末）', value: 'END_OF_MONTH' },
]

const FREQUENCIES: Option[] = [
  { label: 'MONTHLY（月）', value: 'MONTHLY' },
  { label: 'QUARTERLY（季）', value: 'QUARTERLY' },
  { label: 'SEMI_ANNUAL（半年）', value: 'SEMI_ANNUAL' },
  { label: 'ANNUAL（年）', value: 'ANNUAL' },
  { label: 'IRREGULAR（不规则）', value: 'IRREGULAR' },
]

const TIMINGS: Option[] = [
  { label: 'IN_ARREARS（期末付）', value: 'IN_ARREARS' },
  { label: 'IN_ADVANCE（期初付）', value: 'IN_ADVANCE' },
]

/** RepaymentMethodId 枚举名（见 GET /api/v1/algorithms）。 */
const METHODS: Option[] = [
  { label: '等额本息', value: 'EQUAL_INSTALLMENT' },
  { label: '等额本金', value: 'EQUAL_PRINCIPAL' },
  { label: '先息后本', value: 'INTEREST_FIRST' },
  { label: '先本后息', value: 'PRINCIPAL_FIRST' },
  { label: '规则驱动', value: 'RULE_DRIVEN' },
  { label: '显式租金', value: 'EXPLICIT_RENT' },
]

const RESIDUAL_HANDLINGS: Option[] = [
  { label: 'SEPARATE（期末单独回收）', value: 'SEPARATE' },
  { label: 'BALLOON_IN_RENT（摊入租金）', value: 'BALLOON_IN_RENT' },
]

/** RateChangePolicy 枚举（分段利率生效策略）。 */
const RATE_CHANGE_POLICIES: Option[] = [
  { label: 'RESOLVE（自变更日重算）', value: 'RESOLVE' },
  { label: 'FIXED_RENT（租金不变，尾差吸收）', value: 'FIXED_RENT' },
  { label: 'DRIFT（差额滚入期末余额）', value: 'DRIFT' },
]

const CHARGE_MODES: Option[] = [
  { label: 'UPFRONT（期初一次性）', value: 'UPFRONT' },
  { label: 'DEDUCT_FROM_PRINCIPAL（抵减本金）', value: 'DEDUCT_FROM_PRINCIPAL' },
  { label: 'AMORTIZED（摊入租金）', value: 'AMORTIZED' },
]

interface FormState {
  principal: string
  nominalRate: string
  commenceDate: string
  firstRentDate: string
  periods: number
  frequency: PaymentFrequency
  timing: PaymentTiming
  currency: string
  dayCountCode: string
  dateRollCode: string
  rateChangePolicy: string
  method: string
  residualValue: string
  residualHandling: ResidualHandling
  deposit: string
  vatRate: string
  interestOnlyPeriods: number
  fullGracePeriods: number
}

/**
 * 默认值 = Task-21 黄金要素（Fixtures.leaseElement）：1M @ 6%，2024-01-01 起租、
 * 2024-02-01 首期租金，12 期月付、期末付、30/360，等额本息 —— 开箱提交即复现
 * 黄金 TOTAL_RENT 1 032 797.15。
 */
const form = reactive<FormState>({
  principal: '1000000',
  nominalRate: '0.06',
  commenceDate: '2024-01-01',
  firstRentDate: '2024-02-01',
  periods: 12,
  frequency: 'MONTHLY',
  timing: 'IN_ARREARS',
  currency: 'CNY',
  dayCountCode: 'THIRTY_360',
  dateRollCode: 'NONE',
  rateChangePolicy: 'RESOLVE',
  method: 'EQUAL_INSTALLMENT',
  residualValue: '0',
  residualHandling: 'SEPARATE',
  deposit: '0',
  vatRate: '0.06',
  interestOnlyPeriods: 0,
  fullGracePeriods: 0,
})

// dayCountCode/dateRollCode/method/periods 是后端必填字段：表单始终持有默认值，
// 规则仅作兜底（清空时以中文提示阻断提交）。
const rules: Record<string, Rule[]> = {
  principal: [{ required: true, message: '请输入租赁本金' }],
  nominalRate: [{ required: true, message: '请输入名义年利率' }],
  commenceDate: [{ required: true, message: '请选择起租日' }],
  firstRentDate: [{ required: true, message: '请选择首期租金日' }],
  periods: [{ required: true, message: '请输入期数' }],
  frequency: [{ required: true, message: '请选择还款频率' }],
  timing: [{ required: true, message: '请选择付款时点' }],
  dayCountCode: [{ required: true, message: '请选择天数口径' }],
  dateRollCode: [{ required: true, message: '请选择日期滚动规则' }],
  method: [{ required: true, message: '请选择还款方式' }],
}

interface RuleRowDraft {
  ruleId: string
  priority?: number
  fromPeriod?: number
  toPeriod?: number
  annualRate: string
}

interface FeeDraft {
  type: string
  amount: string
  chargeMode: string
}

/** EXPLICIT_RENT 的逐期租金（长度始终与期数对齐）。 */
const explicitRents = ref<string[]>([])
/** 分段利率规则行（对全部方式开放，作为可选分段利率）。 */
const ruleRows = ref<RuleRowDraft[]>([])
const fees = ref<FeeDraft[]>([])

watch(
  () => form.periods,
  (periods) => {
    const count = Math.max(periods ?? 0, 0)
    const rents = explicitRents.value.slice(0, count)
    while (rents.length < count) rents.push('')
    explicitRents.value = rents
  },
  { immediate: true },
)

const loading = ref(false)
const result = ref<CalculationResult | null>(null)
/** 后端统一错误体（FCS code 原样透出）。 */
const submitError = ref<ApiError | null>(null)
/** 提交前的本地校验提示（如显式租金未填满）。 */
const localError = ref<string | null>(null)

function addRuleRow(): void {
  ruleRows.value.push({
    ruleId: `R${ruleRows.value.length + 1}`,
    priority: (ruleRows.value.length + 1) * 10,
    annualRate: '',
  })
}

function removeRuleRow(index: number): void {
  ruleRows.value.splice(index, 1)
}

/** 预设：1 ~ 半程 @3%、半程+1 ~ 期末 @6%（期数 12 时即黄金分段 1-6 / 7-12）。 */
function presetSegmentedRates(): void {
  const total = form.periods ?? 0
  const half = Math.floor(total / 2)
  ruleRows.value = [
    { ruleId: 'R1', priority: 10, fromPeriod: 1, toPeriod: half, annualRate: '0.03' },
    { ruleId: 'R2', priority: 20, fromPeriod: half + 1, toPeriod: total, annualRate: '0.06' },
  ]
}

function addFee(): void {
  fees.value.push({ type: '', amount: '', chargeMode: 'UPFRONT' })
}

function removeFee(index: number): void {
  fees.value.splice(index, 1)
}

function mapRuleRows(): RuleRow[] {
  return ruleRows.value.map((row, index) => ({
    ruleId: row.ruleId.trim() || `R${index + 1}`,
    priority: row.priority ?? (index + 1) * 10,
    fromPeriod: row.fromPeriod ?? undefined,
    toPeriod: row.toPeriod ?? undefined,
    annualRate: row.annualRate.trim(),
    // 与后端 segmented 用例同款缺省列：继承本金计划、付息即付、期末付、无特殊动作。
    principalPlan: 'INHERIT',
    interestTreatment: 'PAY_AS_INCURRED',
    paymentTiming: 'IN_ARREARS',
    specialAction: 'NONE',
  }))
}

function buildRequest(): CalculationRequest {
  const req: CalculationRequest = {
    // 金额以 JSON 字符串传输（R76：保留 BigDecimal 精度）。
    principal: form.principal.trim(),
    nominalRate: form.nominalRate.trim(),
    // 日期为 ISO-8601 字符串（DatePicker value-format 绑定）。
    commenceDate: form.commenceDate,
    firstRentDate: form.firstRentDate,
    periods: form.periods ?? 0,
    frequency: form.frequency,
    timing: form.timing,
    currency: form.currency.trim(),
    dayCountCode: form.dayCountCode,
    dateRollCode: form.dateRollCode,
    rateChangePolicy: form.rateChangePolicy,
    method: form.method,
    residualValue: form.residualValue.trim(),
    residualHandling: form.residualHandling,
    deposit: form.deposit.trim(),
    vatRate: form.vatRate.trim(),
  }

  if (form.method === 'EXPLICIT_RENT') {
    req.explicitRents = explicitRents.value.map((rent) => rent.trim())
  }
  if (ruleRows.value.length > 0) {
    req.ruleRows = mapRuleRows()
  }
  if (fees.value.length > 0) {
    req.fees = fees.value.map((fee) => {
      const feeInput: FeeInput = {
        type: fee.type.trim(),
        amount: fee.amount.trim(),
        chargeMode: fee.chargeMode,
      }
      return feeInput
    })
  }
  if ((form.interestOnlyPeriods ?? 0) > 0 || (form.fullGracePeriods ?? 0) > 0) {
    req.gracePeriod = {}
    if ((form.interestOnlyPeriods ?? 0) > 0) {
      req.gracePeriod.interestOnlyPeriods = form.interestOnlyPeriods ?? 0
    }
    if ((form.fullGracePeriods ?? 0) > 0) {
      req.gracePeriod.fullGracePeriods = form.fullGracePeriods ?? 0
    }
  }
  return req
}

/** AntD Form 的 @finish 仅在内置校验通过后触发（无效表单被阻断）。 */
async function onFinish(): Promise<void> {
  if (form.method === 'EXPLICIT_RENT' && explicitRents.value.some((rent) => rent.trim() === '')) {
    localError.value = `显式租金方式须填满全部 ${form.periods ?? 0} 期租金且每期大于 0`
    return
  }

  localError.value = null
  submitError.value = null
  loading.value = true
  try {
    result.value = await calculate(buildRequest())
  } catch (err: unknown) {
    result.value = null
    submitError.value =
      err instanceof ApiError ? err : new ApiError('UNKNOWN_ERROR', String(err), 0)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Card title="测算表单" data-test="calc-form-card">
    <Form :model="form" :rules="rules" layout="vertical" @finish="onFinish">
      <Card type="inner" title="基础要素">
        <div style="display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px">
          <FormItem label="租赁本金" name="principal">
            <Input v-model:value="form.principal" data-test="principal" placeholder="如 1000000" />
          </FormItem>
          <FormItem label="名义年利率（小数）" name="nominalRate">
            <Input v-model:value="form.nominalRate" data-test="nominal-rate" placeholder="如 0.06" />
          </FormItem>
          <FormItem label="起租日" name="commenceDate">
            <span data-test="commence-date" style="display: block">
              <DatePicker
                v-model:value="form.commenceDate"
                value-format="YYYY-MM-DD"
                style="width: 100%"
              />
            </span>
          </FormItem>
          <FormItem label="首期租金日" name="firstRentDate">
            <span data-test="first-rent-date" style="display: block">
              <DatePicker
                v-model:value="form.firstRentDate"
                value-format="YYYY-MM-DD"
                style="width: 100%"
              />
            </span>
          </FormItem>
          <FormItem label="期数" name="periods">
            <InputNumber
              v-model:value="form.periods"
              :min="1"
              style="width: 100%"
              data-test="periods"
            />
          </FormItem>
          <FormItem label="还款频率" name="frequency">
            <Select v-model:value="form.frequency" :options="FREQUENCIES" data-test="frequency" />
          </FormItem>
          <FormItem label="付款时点" name="timing">
            <Select v-model:value="form.timing" :options="TIMINGS" data-test="timing" />
          </FormItem>
          <FormItem label="币种" name="currency">
            <Input v-model:value="form.currency" data-test="currency" placeholder="ISO-4217，如 CNY" />
          </FormItem>
        </div>
      </Card>

      <Divider type="horizontal" />
      <Card type="inner" title="口径">
        <div style="display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px">
          <FormItem label="天数口径" name="dayCountCode">
            <Select
              v-model:value="form.dayCountCode"
              :options="DAY_COUNT_CODES"
              data-test="day-count-code"
            />
          </FormItem>
          <FormItem label="日期滚动" name="dateRollCode">
            <Select
              v-model:value="form.dateRollCode"
              :options="DATE_ROLL_CODES"
              data-test="date-roll-code"
            />
          </FormItem>
          <FormItem label="利率变更策略" name="rateChangePolicy">
            <Select
              v-model:value="form.rateChangePolicy"
              :options="RATE_CHANGE_POLICIES"
              data-test="rate-change-policy"
            />
          </FormItem>
        </div>
      </Card>

      <Divider type="horizontal" />
      <Card type="inner" title="方式">
        <FormItem label="还款方式" name="method">
          <RadioGroup v-model:value="form.method" data-test="method-group">
            <Radio v-for="m in METHODS" :key="m.value" :value="m.value">{{ m.label }}</Radio>
          </RadioGroup>
        </FormItem>

        <!-- EXPLICIT_RENT 联动：显式逐期租金编辑器（长度与期数对齐）。 -->
        <div v-if="form.method === 'EXPLICIT_RENT'" data-test="rents-editor" style="margin-bottom: 12px">
          <Typography.Text type="secondary">
            逐期租金（共 {{ form.periods ?? 0 }} 期，每期须大于 0）
          </Typography.Text>
          <div style="display: flex; flex-wrap: wrap; gap: 8px; margin-top: 8px">
            <Input
              v-for="(_, idx) in explicitRents"
              :key="idx"
              v-model:value="explicitRents[idx]"
              :placeholder="`第 ${idx + 1} 期`"
              style="width: 120px"
              data-test="rent-input"
            />
          </div>
        </div>
      </Card>

      <Divider type="horizontal" />
      <Card type="inner" title="规则行（可选：分段利率）" data-test="rule-rows-editor">
        <Typography.Text type="secondary">
          对全部方式开放：携带规则行即启用分段利率（RULE_DRIVEN 则为必填规则集）
        </Typography.Text>
        <div style="margin-top: 8px">
          <div
            v-for="(row, idx) in ruleRows"
            :key="idx"
            style="display: flex; gap: 8px; margin-bottom: 8px"
            data-test="rule-row"
          >
            <Input v-model:value="row.ruleId" placeholder="规则 ID" style="width: 90px" />
            <InputNumber
              v-model:value="row.priority"
              placeholder="优先级"
              style="width: 90px"
            />
            <InputNumber
              v-model:value="row.fromPeriod"
              :min="1"
              placeholder="起始期"
              style="width: 100px"
            />
            <InputNumber
              v-model:value="row.toPeriod"
              :min="1"
              placeholder="截止期"
              style="width: 100px"
            />
            <Input
              v-model:value="row.annualRate"
              placeholder="年利率（如 0.03）"
              style="width: 150px"
              data-test="rule-annual-rate"
            />
            <Button danger @click="removeRuleRow(idx)">删除</Button>
          </div>
        </div>
        <Space>
          <Button type="dashed" data-test="rule-add" @click="addRuleRow()">+ 添加规则行</Button>
          <Button data-test="rule-preset" @click="presetSegmentedRates()">
            分段费率预设（1-6 期 @3%，7-12 期 @6%）
          </Button>
        </Space>
      </Card>

      <Divider type="horizontal" />
      <Card type="inner" title="其他">
        <div style="display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px">
          <FormItem label="残值金额" name="residualValue">
            <Input v-model:value="form.residualValue" data-test="residual-value" />
          </FormItem>
          <FormItem label="残值处理" name="residualHandling">
            <Select
              v-model:value="form.residualHandling"
              :options="RESIDUAL_HANDLINGS"
              data-test="residual-handling"
            />
          </FormItem>
          <FormItem label="保证金" name="deposit">
            <Input v-model:value="form.deposit" data-test="deposit" />
          </FormItem>
          <FormItem label="增值税率（小数）" name="vatRate">
            <Input v-model:value="form.vatRate" data-test="vat-rate" />
          </FormItem>
          <FormItem label="宽限期：只付息期数" name="interestOnlyPeriods">
            <InputNumber
              v-model:value="form.interestOnlyPeriods"
              :min="0"
              style="width: 100%"
              data-test="interest-only-periods"
            />
          </FormItem>
          <FormItem label="宽限期：全免期数" name="fullGracePeriods">
            <InputNumber
              v-model:value="form.fullGracePeriods"
              :min="0"
              style="width: 100%"
              data-test="full-grace-periods"
            />
          </FormItem>
        </div>

        <Typography.Text type="secondary">费用（可选）</Typography.Text>
        <div style="margin-top: 8px">
          <div
            v-for="(fee, idx) in fees"
            :key="idx"
            style="display: flex; gap: 8px; margin-bottom: 8px"
            data-test="fee-row"
          >
            <Input v-model:value="fee.type" placeholder="费用类型" style="width: 160px" />
            <Input v-model:value="fee.amount" placeholder="金额" style="width: 140px" />
            <Select v-model:value="fee.chargeMode" :options="CHARGE_MODES" style="width: 220px" />
            <Button danger @click="removeFee(idx)">删除</Button>
          </div>
        </div>
        <Button type="dashed" data-test="fee-add" @click="addFee()">+ 添加费用</Button>
      </Card>

      <FormItem style="margin-top: 16px">
        <Button
          type="primary"
          html-type="submit"
          :loading="loading"
          size="large"
          data-test="submit-btn"
        >
          {{ loading ? '测算中…' : '开始测算' }}
        </Button>
      </FormItem>
    </Form>

    <Alert
      v-if="localError"
      type="warning"
      show-icon
      :message="localError"
      data-test="local-error-alert"
      style="margin-top: 12px"
    />
    <!-- FCS code（如 FCS-DOM-1001）原样透出（R77 统一错误体）。 -->
    <Alert
      v-if="submitError"
      type="error"
      show-icon
      :message="`测算失败（${submitError.code}）`"
      :description="submitError.message"
      data-test="error-alert"
      style="margin-top: 12px"
    />

    <ResultPanel v-if="result" :result="result" style="margin-top: 12px" />
  </Card>
</template>
