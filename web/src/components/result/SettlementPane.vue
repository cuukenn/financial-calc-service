<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  Alert,
  Button,
  Col,
  DatePicker,
  Descriptions,
  FormItem,
  Input,
  InputNumber,
  RadioGroup,
  RadioButton,
  Row,
  Typography,
} from 'ant-design-vue'
import { useI18n } from 'vue-i18n'

import type { Installment } from '@/api/types'
import type { ScenarioKey } from '@/scenario'
import { parseRateInput } from '@/utils/rate'
import { formatMoney } from '@/utils/format'

import { decimalMultiply, formatDecimalPercent } from '@/utils/ratio'
import { quoteEarlySettlement } from './earlySettlement'

/**
 * 提前结清试算 pane（form-field-gaps 07 租赁 / 08 房贷）：输入结清日 + 违约金（率/金额），
 * 房贷场景另给罚息（逾期天数 + 罚息利率）。全部读数由摊销表与结清日推出（B 类派生，
 * M-02 可复算），公式与口径见 `./earlySettlement.ts`。
 */
const props = withDefaults(
  defineProps<{
    installments: Installment[]
    dayCountCode: string
    scenario?: ScenarioKey
    /** 合同年利率（小数字符串）；只用于罚息利率的默认占位（上浮 50%）。 */
    annualRate?: string
  }>(),
  { scenario: 'lease' },
)

const { t } = useI18n()

/** 默认结清日 = 末期前一期期末（「末期前一个月」的月频实现）；空表给今天占位。 */
const defaultSettlementDate = computed(() => {
  const rows = props.installments
  if (rows.length >= 2) return rows[rows.length - 2]!.endDate
  if (rows.length === 1) return rows[0]!.startDate
  return ''
})

const settlementDate = ref(defaultSettlementDate.value)
const penaltyMode = ref<'RATE' | 'AMOUNT'>('RATE')
const penaltyRate = ref('')
const penaltyAmount = ref('')
const overdueDays = ref<number>(0)
const overdueRate = ref('')

// 新结果到达时把结清日重置回默认（表单重算后旧结清日多半已不在新租期内）。
watch(defaultSettlementDate, (value) => {
  settlementDate.value = value
})

/** 罚息利率的默认占位 = 合同年利率上浮 50%（行业惯例），可覆盖；留空 = 不计罚息。 */
const overdueRatePlaceholder = computed(() => {
  const annual = props.annualRate
  if (annual === undefined || annual.trim() === '') return ''
  const floated = decimalMultiply(annual.trim(), '1.5')
  return floated === null ? '' : formatDecimalPercent(floated, 2)
})

const quote = computed(() => {
  if (props.installments.length === 0) return null
  // 罚息利率为百分数口径展示值（R92-5），提交给纯函数前换算为小数字符串。
  const rate = overdueRate.value.trim() === ''
    ? undefined
    : (parseRateInput(overdueRate.value) ?? undefined)
  return quoteEarlySettlement(
    props.installments,
    {
      settlementDate: settlementDate.value,
      penaltyMode: penaltyMode.value,
      // 违约金比例同为百分数口径展示值（1 = 1% → 0.01）。
      penaltyRate: penaltyRate.value.trim() === ''
        ? ''
        : (parseRateInput(penaltyRate.value) ?? penaltyRate.value.trim()),
      penaltyAmount: penaltyAmount.value.trim(),
      overdueDays: props.scenario === 'mortgage' ? overdueDays.value : 0,
      penaltyInterestRate: props.scenario === 'mortgage' ? rate : undefined,
    },
    props.dayCountCode,
  )
})

/**
 * 部分提前还款（ticket 08）：金额 + 重算要素抛给表单侧。重算只在**还款日当日**可办
 * （结清日 = 某期期末日）：该期本息当日结清，重算要素取下一期的期初余额、期末日与
 * 剩余期数 —— 期中办理会有已计提利息与下一期租金重叠的口径问题，v1 不开。
 */
const emit = defineEmits<{
  prepay: [payload: {
    settlementDate: string
    prepayAmount: string
    recalcPrincipal: string
    nextRentDate: string
    remainingPeriods: number
  }]
}>()

const prepayAmount = ref('')

const prepayPlan = computed(() => {
  const date = settlementDate.value
  const index = props.installments.findIndex((row) => row.endDate === date)
  if (index === -1 || index === props.installments.length - 1) return null
  const next = props.installments[index + 1]!
  return {
    recalcPrincipal: next.openingBalance,
    nextRentDate: next.endDate,
    remainingPeriods: props.installments.length - (index + 1),
  }
})

function onPrepay(): void {
  const amount = prepayAmount.value.trim()
  const plan = prepayPlan.value
  if (amount === '' || plan === null) return
  emit('prepay', {
    settlementDate: settlementDate.value,
    prepayAmount: amount,
    recalcPrincipal: plan.recalcPrincipal,
    nextRentDate: plan.nextRentDate,
    remainingPeriods: plan.remainingPeriods,
  })
}

const money = (value: string) => formatMoney(value)
</script>

<template>
  <div data-test="settlement-pane">
    <Row :gutter="[12, 0]">
      <Col :span="8">
        <FormItem :label="t('result.settlement.settlementDate')">
          <span data-test="settlement-date" style="display: block">
            <DatePicker
              v-model:value="settlementDate"
              value-format="YYYY-MM-DD"
              style="width: 100%"
            />
          </span>
        </FormItem>
      </Col>
      <Col :span="8">
        <FormItem :label="t('result.settlement.penaltyMode')">
          <RadioGroup v-model:value="penaltyMode" data-test="settlement-penalty-mode">
            <RadioButton value="RATE">{{ t('result.settlement.penaltyRate') }}</RadioButton>
            <RadioButton value="AMOUNT">{{ t('result.settlement.penaltyAmount') }}</RadioButton>
          </RadioGroup>
        </FormItem>
      </Col>
      <Col :span="8">
        <FormItem
          v-if="penaltyMode === 'RATE'"
          :label="t('result.settlement.penaltyRateLabel')"
        >
          <Input
            v-model:value="penaltyRate"
            :placeholder="t('result.settlement.penaltyRatePh')"
            data-test="settlement-penalty-rate"
          />
        </FormItem>
        <FormItem v-else :label="t('result.settlement.penaltyAmountLabel')">
          <Input
            v-model:value="penaltyAmount"
            :placeholder="t('result.settlement.penaltyAmountPh')"
            data-test="settlement-penalty-amount"
          />
        </FormItem>
      </Col>
    </Row>
    <Row v-if="props.scenario === 'mortgage'" :gutter="[12, 0]">
      <Col :span="8">
        <FormItem :label="t('result.settlement.overdueDays')">
          <InputNumber
            v-model:value="overdueDays"
            :min="0"
            style="width: 100%"
            data-test="settlement-overdue-days"
          />
        </FormItem>
      </Col>
      <Col :span="8">
        <FormItem :label="t('result.settlement.overdueRate')">
          <Input
            v-model:value="overdueRate"
            :placeholder="overdueRatePlaceholder || t('result.settlement.overdueRatePh')"
            data-test="settlement-overdue-rate"
          />
        </FormItem>
      </Col>
    </Row>

    <Row v-if="props.scenario === 'mortgage'" :gutter="[12, 0]">
      <Col :span="8">
        <FormItem :label="t('result.settlement.prepayAmount')">
          <Input
            v-model:value="prepayAmount"
            :placeholder="t('result.settlement.prepayPh')"
            data-test="settlement-prepay-amount"
          />
        </FormItem>
      </Col>
      <Col :span="8">
        <FormItem :label="' '">
          <Button
            type="primary"
            :disabled="quote === null || prepayPlan === null || prepayAmount.trim() === ''"
            data-test="settlement-prepay-run"
            @click="onPrepay"
          >
            {{ t('result.settlement.prepayRun') }}
          </Button>
          <Typography.Text v-if="prepayPlan === null" type="secondary"
            style="display: block; margin-top: 4px"
          >
            {{ t('result.settlement.prepayPaymentDateOnly') }}
          </Typography.Text>
        </FormItem>
      </Col>
    </Row>

    <Alert
      v-if="quote === null"
      type="info"
      show-icon
      :message="t('result.settlement.invalid')"
      data-test="settlement-invalid"
    />
    <template v-else>
      <Descriptions :column="2" size="small" bordered data-test="settlement-quote">
        <Descriptions.Item :label="t('result.settlement.periodNo')">
          {{ quote.periodNo }}
        </Descriptions.Item>
        <Descriptions.Item :label="t('result.settlement.remainingPeriods')">
          {{ quote.remainingPeriods }}
        </Descriptions.Item>
        <Descriptions.Item :label="t('result.settlement.remainingPrincipal')">
          {{ money(quote.remainingPrincipal) }}
        </Descriptions.Item>
        <Descriptions.Item :label="t('result.settlement.accruedDays')">
          {{ quote.accruedDays }}
        </Descriptions.Item>
        <Descriptions.Item :label="t('result.settlement.accruedInterest')">
          {{ money(quote.accruedInterest) }}
        </Descriptions.Item>
        <Descriptions.Item :label="t('result.settlement.penalty')">
          {{ money(quote.penalty) }}
        </Descriptions.Item>
        <Descriptions.Item v-if="props.scenario === 'mortgage'"
          :label="t('result.settlement.overduePenalty')"
        >
          {{ money(quote.overduePenalty) }}
        </Descriptions.Item>
        <Descriptions.Item :label="t('result.settlement.savedInterest')">
          {{ money(quote.savedInterest) }}
        </Descriptions.Item>
        <Descriptions.Item :span="2" :label="t('result.settlement.settlementAmount')">
          <Typography.Text strong data-test="settlement-amount">
            {{ money(quote.settlementAmount) }}
          </Typography.Text>
        </Descriptions.Item>
      </Descriptions>
      <Typography.Text type="secondary" style="display: block; margin-top: 8px">
        {{ t('result.settlement.caliber') }}
      </Typography.Text>
    </template>
  </div>
</template>
