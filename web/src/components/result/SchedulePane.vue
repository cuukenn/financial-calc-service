<script setup lang="ts">
import { Table, Tooltip, Typography } from 'ant-design-vue'
import type { TableColumnsType } from 'ant-design-vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import type { Installment } from '@/api/types'
import EChart from '@/components/charts/EChart.vue'
import { buildBalanceOption, buildCompositionOption } from '@/components/charts/options'
import type { ChartLabels } from '@/components/charts/options'
import { formatMoney, formatPercent } from '@/utils/format'

import { RESULT_TABLE_PAGINATION, detachRows } from './tableProps'

/**
 * 摊销计划 pane（D-6 拆分 + 分页）。
 *
 * 行数据由 `detachRows` 解绑成普通对象并缓存在 computed 里 —— 同一份结果只产出
 * 同一个数组引用，子组件 props 稳定，切换外层 Tab 不再重建四千个单元格。
 */
const props = defineProps<{ installments: Installment[] }>()

const { t } = useI18n()

const rows = computed(() => detachRows(props.installments))

/** 金额列（右对齐、2 位小数）。 */
const MONEY_COLUMNS = [
  'openingBalance',
  'principalDue',
  'interestAccrued',
  'interestPaid',
  'fee',
  'tax',
  'rentDue',
  'closingBalance',
  'cumulativePrincipal',
  'cumulativeInterest',
]

const columns = computed<TableColumnsType>(() => [
  { key: 'periodNo', dataIndex: 'periodNo', title: t('result.table.periodNo'), width: 60 },
  { key: 'startDate', dataIndex: 'startDate', title: t('result.table.startDate'), width: 105 },
  { key: 'endDate', dataIndex: 'endDate', title: t('result.table.endDate'), width: 105 },
  { key: 'days', dataIndex: 'days', title: t('result.table.days'), width: 60, align: 'right' },
  { key: 'openingBalance', dataIndex: 'openingBalance', title: t('result.table.openingBalance'), width: 120, align: 'right' },
  { key: 'principalDue', dataIndex: 'principalDue', title: t('result.table.principalDue'), width: 110, align: 'right' },
  { key: 'interestAccrued', dataIndex: 'interestAccrued', title: t('result.table.interestAccrued'), width: 110, align: 'right' },
  { key: 'interestPaid', dataIndex: 'interestPaid', title: t('result.table.interestPaid'), width: 110, align: 'right' },
  { key: 'fee', dataIndex: 'fee', title: t('result.table.fee'), width: 100, align: 'right' },
  { key: 'tax', dataIndex: 'tax', title: t('result.table.tax'), width: 100, align: 'right' },
  { key: 'rentDue', dataIndex: 'rentDue', title: t('result.table.rentDue'), width: 110, align: 'right' },
  { key: 'closingBalance', dataIndex: 'closingBalance', title: t('result.table.closingBalance'), width: 120, align: 'right' },
  { key: 'cumulativePrincipal', dataIndex: 'cumulativePrincipal', title: t('result.table.cumulativePrincipal'), width: 120, align: 'right' },
  { key: 'cumulativeInterest', dataIndex: 'cumulativeInterest', title: t('result.table.cumulativeInterest'), width: 120, align: 'right' },
  { key: 'appliedRate', dataIndex: 'appliedRate', title: t('result.table.appliedRate'), width: 90, align: 'right' },
  { key: 'memo', dataIndex: 'memo', title: t('result.table.memo'), width: 160 },
])

const chartLabels = computed<ChartLabels>(() => ({
  principal: t('charts.principal'),
  interest: t('charts.interest'),
  closingBalance: t('charts.closingBalance'),
  periodAxis: t('charts.periodAxis'),
  amount: t('charts.amount'),
  periodLabel: (periodNo: number) => t('charts.periodLabel', { n: periodNo }),
}))

const compositionOption = computed(() => buildCompositionOption(rows.value, chartLabels.value))
const balanceOption = computed(() => buildBalanceOption(rows.value, chartLabels.value))

function isMoneyColumn(key: string): boolean {
  return MONEY_COLUMNS.includes(key)
}
</script>

<template>
  <div>
    <Typography.Title :level="5">{{ t('result.scheduleTab.compositionTitle') }}</Typography.Title>
    <EChart :option="compositionOption" data-test="composition-chart" />
    <Typography.Title :level="5">{{ t('result.scheduleTab.balanceTitle') }}</Typography.Title>
    <EChart :option="balanceOption" data-test="balance-chart" />
    <Typography.Title :level="5">{{ t('result.scheduleTab.tableTitle') }}</Typography.Title>
    <Table
      :columns="columns"
      :data-source="rows"
      :pagination="RESULT_TABLE_PAGINATION"
      size="small"
      row-key="periodNo"
      :scroll="{ x: 1870 }"
      data-test="installments-table"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'appliedRate'">
          <Tooltip :title="record.appliedRate">
            <span>{{ formatPercent(record.appliedRate) }}</span>
          </Tooltip>
        </template>
        <template v-else-if="isMoneyColumn(String(column.key))">
          <Tooltip :title="record[column.key as string]">
            <span>{{ formatMoney(record[column.key as string]) }}</span>
          </Tooltip>
        </template>
      </template>
    </Table>
  </div>
</template>
