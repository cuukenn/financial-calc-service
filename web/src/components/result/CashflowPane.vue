<script setup lang="ts">
import { Empty, Table, Tag, Typography } from 'ant-design-vue'
import type { TableColumnsType } from 'ant-design-vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import type { CashFlowEvent } from '@/api/types'
import EChart from '@/components/charts/EChart.vue'
import { buildCumulativeCashflowOption } from '@/components/charts/options'
import { formatMoney } from '@/utils/format'

import { RESULT_TABLE_PAGINATION, detachRows } from './tableProps'

/** 现金流 pane（D-6 拆分 + 分页）：行解绑与分页同摊销计划表。 */
const props = defineProps<{ cashFlows: CashFlowEvent[] }>()

const { t, te } = useI18n()

const rows = computed(() => detachRows(props.cashFlows))

const columns = computed<TableColumnsType>(() => [
  { key: 'sequence', dataIndex: 'sequence', title: t('result.cashflowTab.sequence'), width: 70 },
  { key: 'date', dataIndex: 'date', title: t('result.cashflowTab.date'), width: 110 },
  { key: 'type', dataIndex: 'type', title: t('result.cashflowTab.type'), width: 110 },
  { key: 'direction', dataIndex: 'direction', title: t('result.cashflowTab.direction'), width: 100 },
  { key: 'amount', dataIndex: 'amount', title: t('result.cashflowTab.amount'), width: 120, align: 'right' },
])

function typeLabel(type: string): string {
  const key = `result.cashflowTab.types.${type}`
  return te(key) ? t(key) : type
}

function directionLabel(direction: string): string {
  const key = `result.cashflowTab.directions.${direction}`
  return te(key) ? t(key) : direction
}

function directionColor(direction: string): string {
  return direction === 'OUTFLOW' ? 'red' : 'green'
}

const cumulativeOption = computed(() =>
  buildCumulativeCashflowOption(rows.value, {
    cumulative: t('charts.cumulativeCashflow'),
    dateAxis: t('charts.dateAxis'),
    amount: t('charts.amount'),
  }),
)
</script>

<template>
  <div>
    <template v-if="rows.length > 0">
      <Typography.Title :level="5">{{ t('result.cashflowTab.tableTitle') }}</Typography.Title>
      <Table
        :columns="columns"
        :data-source="rows"
        :pagination="RESULT_TABLE_PAGINATION"
        size="small"
        row-key="sequence"
        :scroll="{ x: 510 }"
        data-test="cashflow-table"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'type'">
            <span>{{ typeLabel(record.type) }}</span>
          </template>
          <template v-else-if="column.key === 'direction'">
            <Tag :color="directionColor(record.direction)" data-test="cashflow-direction">
              {{ directionLabel(record.direction) }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'amount'">
            <span>{{ formatMoney(record.amount) }}</span>
          </template>
        </template>
      </Table>
      <Typography.Title :level="5">{{ t('result.cashflowTab.curveTitle') }}</Typography.Title>
      <EChart :option="cumulativeOption" data-test="cumulative-cashflow-chart" />
    </template>
    <Empty v-else :description="t('result.cashflowTab.empty')" data-test="cashflow-empty" />
  </div>
</template>
