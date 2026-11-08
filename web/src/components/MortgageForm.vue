<script setup lang="ts">
/**
 * 房贷计算器表单（Task 5 / R100 · R101）。
 *
 * R100：房贷与融资租赁共用同一个引擎与同一个 POST /api/v1/calculations 契约 ——
 * 本表单不发新端点、不改 DTO，只把房贷要素映射成一份合法的租赁测算请求。
 * R101 映射：贷款金额→principal、LPR+加点→nominalRate、放款日→commenceDate、
 * 首期还款日→firstRentDate、期限（月）→periods，固定 MONTHLY / IN_ARREARS /
 * THIRTY_360 / NONE / CNY（P-03：30/360 使月利率 = 年利率 ÷ 12）。
 * 租赁专属键（残值、保证金、费用、规则行、显式租金、宽限期、分段策略、舍入、税率）
 * 一律缺席 —— 空串会被后端当成真实要素写进摊销表的 费用/税 列。
 *
 * R103：抵押物成交价、评估价、月可支配收入、其他月债务支出、首付比例都是前端派生
 * 与前端校验，不进 payload、不进引擎指标目录；贷款成数（LTV）与 DTI 全程在十进制
 * 字符串上算（P-01），阈值比较用未舍入原值（P-10）。
 */
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
  Typography,
} from 'ant-design-vue'
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import { ApiError, calculate } from '@/api/client'
import type { CalculationRequest, CalculationResult } from '@/api/types'
import { formatMoney } from '@/utils/format'
import { composeLprRate } from '@/utils/rate'
import {
  MIN_DOWN_PAYMENT_RATIO,
  belowFloor,
  collateralValue,
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
}

/**
 * 默认要素 = Task 5 钉住的边界样例：成交 3 000 000 / 评估 2 800 000 → 抵押物价值
 * 2 800 000（成交价与评估价孰低），贷款 2 380 000 → 贷款成数 85.00%、首付比例
 * 15.00%，恰好达全国统一下限（「以上」含本数）。与租赁表单同款：开箱即得一组合法要素。
 */
const form = reactive<MortgageFormState>({
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
})

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

// ============ 提交（R101 字段映射：11 个键，租赁专属键一律缺席） ============

function buildRequest(annualRateDecimal: string): CalculationRequest {
  return {
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
  }
}

/** AntD Form 的 @finish 只在内置校验通过后触发；此处再跑前端 [法] 门槛。 */
async function onFinish(): Promise<void> {
  // 首付硬下限 15%：用未舍入原值比较（P-10），恰好 15% 放行（「以上」含本数）。
  if (belowFloor(downPayment.value, MIN_DOWN_PAYMENT_RATIO)) {
    localError.value = t('mortgage.gates.downPaymentFloor', {
      min: formatDecimalPercent(MIN_DOWN_PAYMENT_RATIO, 0),
      actual: formatDecimalPercent(downPayment.value),
    })
    return
  }
  // R109 兜底：期限 > 12 月时 BULLET_LOAN 不可能被提交（选项已禁用并被 watch 重置）。
  if (form.method === 'BULLET_LOAN' && !bulletAllowed.value) {
    localError.value = t('mortgage.gates.bulletOnly', { max: BULLET_MAX_MONTHS })
    return
  }
  const composed = annualRate.value
  if (composed === null) {
    localError.value = t('mortgage.gates.rateNotComposable')
    return
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
</script>

<template>
  <!-- 与租赁表单同款布局：宽视口左表单（10）/ 右结果（14），窄视口纵向堆叠。 -->
  <Row :gutter="16" data-test="mortgage-layout">
    <Col :xs="24" :lg="10">
      <Card :title="t('mortgage.cardTitle')" data-test="mortgage-form-card">
        <Form
          :model="form"
          :rules="rules"
          :label-col="{ style: { width: '116px' } }"
          class="compact-form"
          @finish="onFinish"
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
            <!-- 抵押物价值 / 贷款成数 / 首付比例：前端派生指标，不进引擎（R103）。 -->
            <dl class="ratio-list">
              <div class="ratio-item">
                <dt>{{ t('mortgage.collateral.collateralValue') }}</dt>
                <dd data-test="mortgage-collateral-value">{{ formatMoney(collateral) }}</dd>
              </div>
              <div class="ratio-item">
                <dt>{{ t('mortgage.collateral.ltv') }}</dt>
                <dd data-test="mortgage-ltv-value">{{ formatDecimalPercent(ltv) }}</dd>
              </div>
              <div class="ratio-item">
                <dt>{{ t('mortgage.collateral.downPaymentRatio') }}</dt>
                <dd data-test="mortgage-down-payment-value">{{ formatDecimalPercent(downPayment) }}</dd>
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
            <Button
              type="primary"
              html-type="submit"
              :loading="loading"
              size="large"
              data-test="mortgage-submit-btn"
            >
              {{ loading ? t('form.submitting') : t('form.submit') }}
            </Button>
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
      <!-- 结果区共用未改动的 ResultPanel（Task 6 才负责其文案与样式）。 -->
      <ResultPanel v-if="result" :result="result" />
    </Col>
  </Row>
</template>

<style scoped>
/* 与租赁表单同款紧凑栅格。 */
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

/* 派生比例读数：左标签右数值，前端计算结果（不进引擎）在此可见。 */
.ratio-list {
  margin: 0 0 12px;
}
.ratio-item {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  padding: 2px 0;
}
.ratio-item dt {
  color: rgba(0, 0, 0, 0.65);
}
.ratio-item dd {
  margin: 0;
  font-variant-numeric: tabular-nums;
}
.composed-rate {
  display: block;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}
.composed-rate-hint {
  font-size: 11px;
}
</style>
