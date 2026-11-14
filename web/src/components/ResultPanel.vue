<script setup lang="ts">
import { Alert, Card, TabPane, Tabs, Typography } from 'ant-design-vue'
import { useI18n } from 'vue-i18n'

import type { CalculationResult } from '@/api/types'
import type { ScenarioKey } from '@/scenario'

import CashflowPane from './result/CashflowPane.vue'
import EirPane from './result/EirPane.vue'
import MetricsPane from './result/MetricsPane.vue'
import SchedulePane from './result/SchedulePane.vue'
import SettlementPane from './result/SettlementPane.vue'

/**
 * 结果区（D-6）：外壳只留 Card + runId + ERROR 提示 + 四个 TabPane。
 *
 * 每个 pane 的主体是独立子组件，props 引用稳定 —— AntD Tabs 不销毁已访问的 pane，
 * 但子组件在 props 未变时不会重渲染，因此切换内层 Tab 不再重建整棵表格树。
 * 行数据在各自 pane 内 `detach` 成普通对象并分页（每页 12 行），见 `result/tableProps.ts`。
 *
 * R149：`scenario` 只是把「这是哪一笔测算」传进需要它的 pane（目前是指标 pane 的派生
 * 合同税费）。引擎与契约对两个场景完全相同（R100），所以外壳不据场景改变任何引擎数据。
 */
const props = withDefaults(
  defineProps<{
    result: CalculationResult
    scenario?: ScenarioKey
    /** 计息基准 code（提前结清试算的天数口径）；缺省 THIRTY_360（房贷硬编码基准）。 */
    dayCountCode?: string
    /** 合同年利率（小数字符串），提前结清试算的罚息默认占位用。 */
    annualRate?: string
  }>(),
  { scenario: 'lease' },
)

const { t } = useI18n()

/** 提前结清试算（ticket 07/08）往表单侧抛的事件：部分提前还款重算剩余期供。 */
const emit = defineEmits<{
  prepay: [payload: {
    settlementDate: string
    prepayAmount: string
    recalcPrincipal: string
    nextRentDate: string
    remainingPeriods: number
  }]
}>()
</script>

<template>
  <Card :title="t('result.cardTitle')" data-test="result-card">
    <Typography.Text type="secondary">
      {{ t('result.runId', { runId: props.result.runId }) }}
    </Typography.Text>
    <Alert
      v-if="props.result.schedule.hasErrors"
      class="result-alert"
      type="error"
      show-icon
      :message="t('result.hasErrors')"
      data-test="has-errors-alert"
    />
    <Tabs data-test="result-tabs">
      <TabPane key="metrics" :tab="t('result.tabs.metrics')">
        <MetricsPane
          :metrics="props.result.schedule.header.metrics"
          :issues="props.result.schedule.issues"
          :scenario="props.scenario"
        />
      </TabPane>
      <TabPane key="schedule" :tab="t('result.tabs.schedule')">
        <SchedulePane :installments="props.result.schedule.installments" />
      </TabPane>
      <TabPane key="cashflow" :tab="t('result.tabs.cashflow')">
        <CashflowPane :cash-flows="props.result.schedule.cashFlows" />
      </TabPane>
      <TabPane key="settlement" :tab="t('result.tabs.settlement')">
        <SettlementPane
          :installments="props.result.schedule.installments"
          :day-count-code="props.dayCountCode ?? 'THIRTY_360'"
          :annual-rate="props.annualRate"
          :scenario="props.scenario"
          @prepay="(payload) => emit('prepay', payload)"
        />
      </TabPane>
      <TabPane key="eir" :tab="t('result.tabs.eir')">
        <EirPane :effective-interest="props.result.effectiveInterest" />
      </TabPane>
    </Tabs>
  </Card>
</template>

<style scoped>
.result-alert {
  margin: 12px 0;
}
</style>
