<script setup lang="ts">
import {
  Alert,
  Button,
  Card,
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
  Typography,
} from 'ant-design-vue'
import type { Rule } from 'ant-design-vue/es/form'
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

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

import FeesEditor from './editors/FeesEditor.vue'
import RuleRowsEditor from './editors/RuleRowsEditor.vue'
import RentsEditor from './editors/RentsEditor.vue'
import type { FeeDraft, RuleRowDraft } from './editors/types'
import ResultPanel from './ResultPanel.vue'
import { nextRuleId } from './ruleIds'

const { t } = useI18n()

/** 计息基准（Day Count Convention）：六个已注册的 DayCountConvention code（calc-domain/support/daycount）。 */
const DAY_COUNT_VALUES = [
  'THIRTY_360',
  'THIRTY_E_360',
  'ACT_360',
  'ACT_365F',
  'ACT_365_25',
  'ACT_ACT',
] as const

/** 日期调整（Business Day Convention）：六个已注册的 DateRollConvention code（calc-domain/support/dateroll）。 */
const DATE_ROLL_VALUES = [
  'NONE',
  'FOLLOWING',
  'MODIFIED_FOLLOWING',
  'PRECEDING',
  'MODIFIED_PRECEDING',
  'END_OF_MONTH',
] as const

const FREQUENCY_VALUES: PaymentFrequency[] = [
  'MONTHLY',
  'QUARTERLY',
  'SEMI_ANNUAL',
  'ANNUAL',
  'IRREGULAR',
]

const TIMING_VALUES: PaymentTiming[] = ['IN_ARREARS', 'IN_ADVANCE']

/** RepaymentMethodId 枚举名（见 GET /api/v1/algorithms）。 */
const METHOD_VALUES = [
  'EQUAL_INSTALLMENT',
  'EQUAL_PRINCIPAL',
  'INTEREST_FIRST',
  'PRINCIPAL_FIRST',
  'RULE_DRIVEN',
  'EXPLICIT_RENT',
] as const

const RESIDUAL_HANDLING_VALUES: ResidualHandling[] = ['SEPARATE', 'BALLOON_IN_RENT']

/** RateChangePolicy 枚举（分段利率生效策略）。 */
const RATE_CHANGE_POLICY_VALUES = ['RESOLVE', 'FIXED_RENT', 'DRIFT'] as const

/**
 * R89：枚举选项标签走 i18n（主文本为本地化名称，内部 code 不再出现在标签中），
 * 随语言切换响应式更新。计息基准为行业标准斜杠记法（ACT/360 等），
 * code 作为次要小字仅出现在下拉项内（触发器只显示主文本，对账用）。
 */
const dayCountOptions = computed(() =>
  DAY_COUNT_VALUES.map((value) => ({ value, label: t(`enums.dayCount.${value}`), code: value })),
)
const dateRollOptions = computed(() =>
  DATE_ROLL_VALUES.map((value) => ({ value, label: t(`enums.dateRoll.${value}`) })),
)
const frequencyOptions = computed(() =>
  FREQUENCY_VALUES.map((value) => ({ value, label: t(`enums.frequency.${value}`) })),
)
const timingOptions = computed(() =>
  TIMING_VALUES.map((value) => ({ value, label: t(`enums.timing.${value}`) })),
)
const methodOptions = computed(() =>
  METHOD_VALUES.map((value) => ({ value, label: t(`enums.method.${value}`) })),
)
const residualHandlingOptions = computed(() =>
  RESIDUAL_HANDLING_VALUES.map((value) => ({ value, label: t(`enums.residualHandling.${value}`) })),
)
const rateChangePolicyOptions = computed(() =>
  RATE_CHANGE_POLICY_VALUES.map((value) => ({ value, label: t(`enums.rateChangePolicy.${value}`) })),
)

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
// 规则仅作兜底（清空时提示阻断提交）。校验文案随语言切换响应式更新。
const rules = computed<Record<string, Rule[]>>(() => ({
  principal: [{ required: true, message: t('form.validation.principal') }],
  nominalRate: [{ required: true, message: t('form.validation.nominalRate') }],
  commenceDate: [{ required: true, message: t('form.validation.commenceDate') }],
  firstRentDate: [{ required: true, message: t('form.validation.firstRentDate') }],
  periods: [{ required: true, message: t('form.validation.periods') }],
  frequency: [{ required: true, message: t('form.validation.frequency') }],
  timing: [{ required: true, message: t('form.validation.timing') }],
  dayCountCode: [{ required: true, message: t('form.validation.dayCountCode') }],
  dateRollCode: [{ required: true, message: t('form.validation.dateRollCode') }],
  method: [{ required: true, message: t('form.validation.method') }],
}))

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

function mapRuleRows(): RuleRow[] {
  return ruleRows.value.map((row, index) => ({
    // 留空时同样派生不重复的 ID（排除自身后取最大序号后继）。
    ruleId:
      row.ruleId.trim() ||
      nextRuleId(ruleRows.value.filter((_, i) => i !== index).map((r) => r.ruleId)),
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
    localError.value = t('form.rentStructure.explicitRents.incomplete', {
      periods: form.periods ?? 0,
    })
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
  <!-- R84 整体布局：宽视口表单左（10）/结果右（14）双栏，窄视口纵向堆叠。 -->
  <Row :gutter="16" data-test="workbench-layout">
    <Col :xs="24" :lg="10">
      <Card :title="t('form.cardTitle')" data-test="calc-form-card">
        <Form
          :model="form"
          :rules="rules"
          :label-col="{ style: { width: '92px' } }"
          class="compact-form"
          @finish="onFinish"
        >
          <!-- 大块 1：租赁要素（基础字段 + 口径 + 还款方式）。 -->
          <Card
            :title="t('form.basic.title')"
            size="small"
            data-test="card-basic"
            class="block-card"
          >
            <Row :gutter="12">
              <Col :span="12">
                <FormItem :label="t('form.basic.principal')" name="principal">
                  <Input
                    v-model:value="form.principal"
                    data-test="principal"
                    :placeholder="t('form.basic.principalPh')"
                  />
                </FormItem>
              </Col>
              <Col :span="12">
                <FormItem :label="t('form.basic.nominalRate')" name="nominalRate">
                  <Input
                    v-model:value="form.nominalRate"
                    data-test="nominal-rate"
                    :placeholder="t('form.basic.nominalRatePh')"
                  />
                </FormItem>
              </Col>
              <Col :span="12">
                <FormItem :label="t('form.basic.commenceDate')" name="commenceDate">
                  <span data-test="commence-date" style="display: block">
                    <DatePicker
                      v-model:value="form.commenceDate"
                      value-format="YYYY-MM-DD"
                      style="width: 100%"
                    />
                  </span>
                </FormItem>
              </Col>
              <Col :span="12">
                <FormItem :label="t('form.basic.firstRentDate')" name="firstRentDate">
                  <span data-test="first-rent-date" style="display: block">
                    <DatePicker
                      v-model:value="form.firstRentDate"
                      value-format="YYYY-MM-DD"
                      style="width: 100%"
                    />
                  </span>
                </FormItem>
              </Col>
              <Col :span="12">
                <FormItem :label="t('form.basic.periods')" name="periods">
                  <InputNumber
                    v-model:value="form.periods"
                    :min="1"
                    style="width: 100%"
                    data-test="periods"
                  />
                </FormItem>
              </Col>
              <Col :span="12">
                <FormItem :label="t('form.basic.currency')" name="currency">
                  <Input
                    v-model:value="form.currency"
                    data-test="currency"
                    :placeholder="t('form.basic.currencyPh')"
                  />
                </FormItem>
              </Col>
              <Col :span="12">
                <FormItem :label="t('form.basic.frequency')" name="frequency">
                  <Select
                    v-model:value="form.frequency"
                    :options="frequencyOptions"
                    :popup-match-select-width="false"
                    style="width: 100%"
                    data-test="frequency"
                  />
                </FormItem>
              </Col>
              <Col :span="12">
                <FormItem :label="t('form.basic.timing')" name="timing">
                  <Select
                    v-model:value="form.timing"
                    :options="timingOptions"
                    :popup-match-select-width="false"
                    style="width: 100%"
                    data-test="timing"
                  />
                </FormItem>
              </Col>
              <Col :span="12">
                <FormItem :label="t('form.basic.dayCountCode')" name="dayCountCode">
                  <!-- R89：触发器只显示斜杠记法主文本；code 以次要小字留在下拉项内（对账用）。 -->
                  <Select
                    v-model:value="form.dayCountCode"
                    :options="dayCountOptions"
                    option-label-prop="label"
                    :popup-match-select-width="false"
                    style="width: 100%"
                    data-test="day-count-code"
                  >
                    <template #option="{ label, code }">
                      <span data-test="day-count-option">
                        <span data-test="day-count-option-name">{{ label }}</span>
                        <span
                          data-test="day-count-option-code"
                          class="day-count-option-code"
                        >{{ code }}</span>
                      </span>
                    </template>
                  </Select>
                </FormItem>
              </Col>
              <Col :span="12">
                <FormItem :label="t('form.basic.dateRollCode')" name="dateRollCode">
                  <Select
                    v-model:value="form.dateRollCode"
                    :options="dateRollOptions"
                    :popup-match-select-width="false"
                    style="width: 100%"
                    data-test="date-roll-code"
                  />
                </FormItem>
              </Col>
              <Col :span="24">
                <FormItem :label="t('form.basic.rateChangePolicy')" name="rateChangePolicy">
                  <Select
                    v-model:value="form.rateChangePolicy"
                    :options="rateChangePolicyOptions"
                    :popup-match-select-width="false"
                    style="width: 100%"
                    data-test="rate-change-policy"
                  />
                </FormItem>
              </Col>
              <Col :span="24">
                <FormItem :label="t('form.basic.method')" name="method">
                  <RadioGroup v-model:value="form.method" data-test="method-group">
                    <Radio v-for="m in methodOptions" :key="m.value" :value="m.value">
                      {{ m.label }}
                    </Radio>
                  </RadioGroup>
                </FormItem>
              </Col>
            </Row>
          </Card>

          <!-- 大块 2：租金结构（规则行 + 显式租金 + 宽限期，按方式联动显隐不变）。 -->
          <Card
            :title="t('form.rentStructure.title')"
            size="small"
            data-test="card-rent-structure"
            class="block-card"
          >
            <!-- 规则行编辑器（子组件）：预设按钮依赖期数。 -->
            <RuleRowsEditor v-model="ruleRows" :periods="form.periods ?? 0" />

            <!-- EXPLICIT_RENT 联动：显式逐期租金编辑器（子组件，长度与期数对齐）。 -->
            <RentsEditor
              v-if="form.method === 'EXPLICIT_RENT'"
              v-model="explicitRents"
              :periods="form.periods ?? 0"
            />

            <Typography.Text type="secondary" style="display: block; margin-top: 12px">
              {{ t('form.rentStructure.grace.title') }}
            </Typography.Text>
            <Row :gutter="12">
              <Col :span="12">
                <FormItem
                  :label="t('form.rentStructure.grace.interestOnlyPeriods')"
                  name="interestOnlyPeriods"
                >
                  <InputNumber
                    v-model:value="form.interestOnlyPeriods"
                    :min="0"
                    style="width: 100%"
                    data-test="interest-only-periods"
                  />
                </FormItem>
              </Col>
              <Col :span="12">
                <FormItem
                  :label="t('form.rentStructure.grace.fullGracePeriods')"
                  name="fullGracePeriods"
                >
                  <InputNumber
                    v-model:value="form.fullGracePeriods"
                    :min="0"
                    style="width: 100%"
                    data-test="full-grace-periods"
                  />
                </FormItem>
              </Col>
            </Row>
          </Card>

          <!-- 大块 3：费用与其他（费用编辑器 + 残值/保证金/税率）。 -->
          <Card
            :title="t('form.feesOther.title')"
            size="small"
            data-test="card-fees-other"
            class="block-card"
          >
            <!-- 费用编辑器（子组件）。 -->
            <FeesEditor v-model="fees" />

            <Row :gutter="12" style="margin-top: 12px">
              <Col :span="12">
                <FormItem :label="t('form.feesOther.residualValue')" name="residualValue">
                  <Input v-model:value="form.residualValue" data-test="residual-value" />
                </FormItem>
              </Col>
              <Col :span="12">
                <FormItem :label="t('form.feesOther.residualHandling')" name="residualHandling">
                  <Select
                    v-model:value="form.residualHandling"
                    :options="residualHandlingOptions"
                    :popup-match-select-width="false"
                    style="width: 100%"
                    data-test="residual-handling"
                  />
                </FormItem>
              </Col>
              <Col :span="12">
                <FormItem :label="t('form.feesOther.deposit')" name="deposit">
                  <Input v-model:value="form.deposit" data-test="deposit" />
                </FormItem>
              </Col>
              <Col :span="12">
                <FormItem :label="t('form.feesOther.vatRate')" name="vatRate">
                  <Input
                    v-model:value="form.vatRate"
                    data-test="vat-rate"
                    :placeholder="t('form.feesOther.vatRatePh')"
                  />
                </FormItem>
              </Col>
            </Row>
          </Card>

          <FormItem style="margin-top: 16px">
            <Button
              type="primary"
              html-type="submit"
              :loading="loading"
              size="large"
              data-test="submit-btn"
            >
              {{ loading ? t('form.submitting') : t('form.submit') }}
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
          :message="t('form.submitFailed', { code: submitError.code })"
          :description="submitError.message"
          data-test="error-alert"
          style="margin-top: 12px"
        />
      </Card>
    </Col>

    <Col :xs="24" :lg="14">
      <ResultPanel v-if="result" :result="result" />
    </Col>
  </Row>
</template>

<style scoped>
/* 紧凑栅格：压低表单项纵向间距与大块卡片间距。 */
.compact-form :deep(.ant-form-item) {
  margin-bottom: 12px;
}
.block-card {
  margin-bottom: 12px;
}
.block-card :deep(.ant-card-head) {
  min-height: 36px;
}
.block-card :deep(.ant-card-head-title) {
  font-size: 14px;
}
.block-card :deep(.ant-card-body) {
  padding-top: 12px;
  padding-bottom: 4px;
}

/* R89：计息基准下拉项内的原码次要小字（仅下拉项内，触发器不显示）。 */
.day-count-option-code {
  margin-left: 8px;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}
</style>
