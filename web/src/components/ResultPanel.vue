<script setup lang="ts">
import { Alert, Card, Descriptions, Table, Typography } from 'ant-design-vue'

import type { CalculationResult, Installment } from '@/api/types'

/**
 * Task 2 的功能性结果区块：指标键值表 + 摊销计划基础表格（金额字符串原样直显）。
 * Task 3 将在此替换为完整可视化（指标卡 + ECharts 构成/余额曲线）。
 */
defineProps<{ result: CalculationResult }>()

const installmentColumns: { title: string; dataIndex: keyof Installment; width?: number }[] = [
  { title: '期次', dataIndex: 'periodNo', width: 60 },
  { title: '开始日', dataIndex: 'startDate', width: 100 },
  { title: '结束日', dataIndex: 'endDate', width: 100 },
  { title: '天数', dataIndex: 'days', width: 60 },
  { title: '期初余额', dataIndex: 'openingBalance' },
  { title: '本金', dataIndex: 'principalDue' },
  { title: '利息（计提）', dataIndex: 'interestAccrued' },
  { title: '利息（支付）', dataIndex: 'interestPaid' },
  { title: '费用', dataIndex: 'fee' },
  { title: '税', dataIndex: 'tax' },
  { title: '租金', dataIndex: 'rentDue' },
  { title: '期末余额', dataIndex: 'closingBalance' },
  { title: '适用利率', dataIndex: 'appliedRate' },
  { title: '备注', dataIndex: 'memo' },
]
</script>

<template>
  <Card title="测算结果" data-test="result-card">
    <Typography.Text type="secondary">runId：{{ result.runId }}</Typography.Text>

    <!-- hasErrors：问题列表含 ERROR 级条目（预期结果，非请求失败）。 -->
    <Alert
      v-if="result.schedule.hasErrors"
      type="warning"
      show-icon
      message="测算结果包含 ERROR 级问题，请检查下方问题列表"
      data-test="has-errors-alert"
      style="margin-top: 8px"
    />

    <Typography.Title :level="5" style="margin-top: 12px">指标</Typography.Title>
    <Descriptions
      bordered
      size="small"
      :column="3"
      data-test="metrics-list"
    >
      <Descriptions.Item v-for="(value, key) in result.schedule.header.metrics" :key="key" :label="key">
        {{ value ?? '—' }}
      </Descriptions.Item>
    </Descriptions>

    <template v-if="result.schedule.issues.length > 0">
      <Typography.Title :level="5" style="margin-top: 12px">问题列表</Typography.Title>
      <ul data-test="issues-list" style="padding-left: 20px; margin: 0">
        <li v-for="(issue, idx) in result.schedule.issues" :key="idx">
          [{{ issue.level }}] {{ issue.code }} {{ issue.message }}
          <template v-if="issue.periodNo !== null">（第 {{ issue.periodNo }} 期）</template>
        </li>
      </ul>
    </template>

    <Typography.Title :level="5" style="margin-top: 12px">摊销计划</Typography.Title>
    <Table
      :columns="installmentColumns"
      :data-source="result.schedule.installments"
      :pagination="false"
      size="small"
      row-key="periodNo"
      :scroll="{ x: 1400 }"
      data-test="installments-table"
    />
  </Card>
</template>
