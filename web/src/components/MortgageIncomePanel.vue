<script setup lang="ts">
/**
 * 收入与债务面板（房贷场景）。
 *
 * R103：月可支配收入与其他月债务支出只活在浏览器里 —— 既不进 payload，
 * 也不是引擎指标；本面板是它们的唯一消费者。两个字段仍留在父表单的 model 里，
 * 由同一个 AntD Form 按字段名校验（FormItem 通过注入的 Form 上下文取值）。
 *
 * 派生口径（AGENTS.md §2.2 规范名、§2.3 阈值）：
 * - 月供：等额本息取首期租金；等额本金 / 到期一次还本付息取全表最大租金（最坏月份）。
 * - 月房贷支出/收入比 = 月供 ÷ 月可支配收入，上限 50%。
 * - 月所有债务支出/收入比 = (月供 + 其他月债务支出) ÷ 月可支配收入，上限 60%。
 * 阈值比较一律用未舍入原值（P-10），恰好等于上限不视为超限（§2.3「不足」不含本数）；
 * 展示 2 位百分数（P-10）。月可支配收入 ≤ 0 → 指标不可计算，说明原因而不是给 0。
 * Task 6 §3：仪表状态（ok / warn / breach / neutral）由 components/ratioStatus.ts 从未舍入
 * 串上判定，类名 ratio-* 的颜色在 styles/workbench.css 里定义一次，每个状态配一条文案标记。
 */
import { Card, Col, FormItem, Input, Row, Typography } from 'ant-design-vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import { formatMoney } from '@/utils/format'
import {
  allDebtToIncomeRatio,
  debtToIncomeRatio,
  formatDecimalPercent,
  isPositiveDecimal,
  pickMonthlyPayment,
} from '@/utils/ratio'

import { RATIO_MARKER_KEYS, allDebtRatioStatus, dtiStatus, statusClass } from './ratioStatus'

const { t } = useI18n()

const props = defineProps<{
  /** 摊销表各期租金（rentDue）：月供口径的输入，未提交时为空数组。 */
  rents: string[]
  /** 还款方式 code：决定月供取首期还是全表最大期。 */
  method: string
}>()

const monthlyIncome = defineModel<string>('monthlyIncome', { required: true })
const otherMonthlyDebt = defineModel<string>('otherMonthlyDebt', { required: true })

const monthlyPayment = computed(() => pickMonthlyPayment(props.rents, props.method))
const incomeProvided = computed(() => isPositiveDecimal(monthlyIncome.value))
const dti = computed(() => debtToIncomeRatio(monthlyPayment.value, monthlyIncome.value))
const allDebtDti = computed(() =>
  allDebtToIncomeRatio(monthlyPayment.value, otherMonthlyDebt.value, monthlyIncome.value),
)
/**
 * Task 6 §3：仪表状态从未舍入的十进制串上得出（P-10），类名与文案标记成对出现。
 * neutral（未提供收入 / 未计算）不判 ok 也不显示成 0：该情形由 mortgage.income.unavailable
 * 这条现成的「无法判断」文案承担，不再另挂标记。
 */
const dtiState = computed(() => dtiStatus(dti.value))
const allDebtState = computed(() => allDebtRatioStatus(allDebtDti.value))
</script>

<template>
  <Card :title="t('mortgage.income.title')" size="small" data-test="mortgage-card-income" class="block-card">
    <Row :gutter="12">
      <Col :span="12">
        <FormItem :label="t('mortgage.income.monthlyIncome')" name="monthlyIncome">
          <Input
            v-model:value="monthlyIncome"
            data-test="mortgage-monthly-income"
            :placeholder="t('mortgage.income.monthlyIncomePh')"
          />
        </FormItem>
      </Col>
      <Col :span="12">
        <FormItem :label="t('mortgage.income.otherMonthlyDebt')" name="otherMonthlyDebt">
          <Input
            v-model:value="otherMonthlyDebt"
            data-test="mortgage-other-debt"
            :placeholder="t('mortgage.income.otherMonthlyDebtPh')"
          />
        </FormItem>
      </Col>
    </Row>

    <dl class="ratio-list">
      <div class="ratio-item">
        <dt>{{ t('mortgage.income.monthlyPayment') }}</dt>
        <dd data-test="mortgage-monthly-payment">
          {{ monthlyPayment === null ? '—' : formatMoney(monthlyPayment) }}
        </dd>
      </div>
      <div class="ratio-item">
        <dt>{{ t('mortgage.income.dti') }}</dt>
        <dd class="ratio-readout" :class="statusClass(dtiState)" data-test="mortgage-dti-readout">
          <span v-if="dti !== null" data-test="mortgage-dti-value">{{ formatDecimalPercent(dti) }}</span>
          <span v-else-if="!incomeProvided" data-test="mortgage-dti-unavailable">{{ t('mortgage.income.unavailable') }}</span>
          <span v-else data-test="mortgage-dti-pending">—</span>
          <!-- 文案标记与颜色成对出现。hook 名 mortgage-dti-breach 沿用 Task 5 的语义：
               凡越过 50% 上限都在场，文案按状态分成「接近上限」（≤ 60%）与「超限」（> 60%）。 -->
          <span v-if="dtiState === 'ok'" data-test="mortgage-dti-status" class="ratio-marker">{{ t('mortgage.status.ok') }}</span>
          <span v-else-if="dtiState === 'warn' || dtiState === 'breach'" data-test="mortgage-dti-breach" class="ratio-marker">{{ t(RATIO_MARKER_KEYS[dtiState]) }}</span>
        </dd>
      </div>
      <div class="ratio-item">
        <dt>{{ t('mortgage.income.allDebtRatio') }}</dt>
        <dd class="ratio-readout" :class="statusClass(allDebtState)" data-test="mortgage-all-debt-readout">
          <span v-if="allDebtDti !== null" data-test="mortgage-all-debt-value">{{ formatDecimalPercent(allDebtDti) }}</span>
          <span v-else-if="!incomeProvided" data-test="mortgage-all-debt-unavailable">{{ t('mortgage.income.unavailable') }}</span>
          <span v-else data-test="mortgage-all-debt-pending">—</span>
          <!-- 该比例只有 60% 这一条上限，所以只有 ok / breach 两档标记。 -->
          <span v-if="allDebtState === 'ok'" data-test="mortgage-all-debt-status" class="ratio-marker">{{ t('mortgage.status.ok') }}</span>
          <span v-else-if="allDebtState === 'breach'" data-test="mortgage-all-debt-breach" class="ratio-marker">{{ t('mortgage.status.breach') }}</span>
        </dd>
      </div>
    </dl>

    <Typography.Text type="secondary" data-test="mortgage-dti-thresholds">
      {{ t('mortgage.income.thresholds') }}
    </Typography.Text>
    <Typography.Text type="secondary" data-test="mortgage-payment-basis">
      {{ t('mortgage.income.paymentNote') }}
    </Typography.Text>
  </Card>
</template>
