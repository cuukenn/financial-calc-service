<script setup lang="ts">
import { Empty, Table, Tooltip, Typography } from 'ant-design-vue'
import type { TableColumnsType } from 'ant-design-vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import type { EffectiveInterest } from '@/api/types'
import { formatMoney, formatPercent } from '@/utils/format'

import { RESULT_TABLE_PAGINATION, detachRows } from './tableProps'

/** 实际利率法 pane（D-6 拆分 + 分页）。 */
const props = defineProps<{ effectiveInterest: EffectiveInterest | null }>()

const { t } = useI18n()

const rows = computed(() => (props.effectiveInterest ? detachRows(props.effectiveInterest.rows) : []))

const MONEY_COLUMNS = [
  'openingNetInvestment',
  'interestIncome',
  'cashReceived',
  'principalRecovery',
  'closingNetInvestment',
]

const columns = computed<TableColumnsType>(() => [
  { key: 'periodNo', dataIndex: 'periodNo', title: t('result.table.periodNo'), width: 60 },
  { key: 'openingNetInvestment', dataIndex: 'openingNetInvestment', title: t('result.table.eirOpeningNetInvestment'), width: 130, align: 'right' },
  { key: 'interestIncome', dataIndex: 'interestIncome', title: t('result.table.eirInterestIncome'), width: 110, align: 'right' },
  { key: 'cashReceived', dataIndex: 'cashReceived', title: t('result.table.eirCashReceived'), width: 110, align: 'right' },
  { key: 'principalRecovery', dataIndex: 'principalRecovery', title: t('result.table.eirPrincipalRecovery'), width: 110, align: 'right' },
  { key: 'closingNetInvestment', dataIndex: 'closingNetInvestment', title: t('result.table.eirClosingNetInvestment'), width: 130, align: 'right' },
])

function isMoneyColumn(key: string): boolean {
  return MONEY_COLUMNS.includes(key)
}
</script>

<template>
  <div>
    <template v-if="effectiveInterest">
      <Typography.Text type="secondary">
        {{
          t('result.eir.summary', {
            eir: formatPercent(effectiveInterest.eir),
            diff: formatMoney(effectiveInterest.totalInterestDifference),
          })
        }}
      </Typography.Text>
      <Table
        :columns="columns"
        :data-source="rows"
        :pagination="RESULT_TABLE_PAGINATION"
        size="small"
        row-key="periodNo"
        :scroll="{ x: 650 }"
        data-test="effective-interest-table"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="isMoneyColumn(String(column.key))">
            <Tooltip :title="record[column.key as string]">
              <span>{{ formatMoney(record[column.key as string]) }}</span>
            </Tooltip>
          </template>
        </template>
      </Table>
    </template>
    <Empty v-else :description="t('result.eir.empty')" data-test="eir-empty" />
  </div>
</template>
