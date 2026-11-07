<script setup lang="ts">
import { Alert, Card, Descriptions, Statistic, Table, Tag, Typography } from 'ant-design-vue'
import { computed } from 'vue'

import type { CalculationResult, Installment, Issue } from '@/api/types'

import EChart from './charts/EChart.vue'
import { buildBalanceOption, buildCompositionOption } from './charts/options'

/**
 * Task 3 结果区块：指标 Statistic 卡（金额字符串原样直显，null → 「—」）+
 * 分级问题列表 + 摊销计划 16 列表格 + ECharts 构成/余额曲线（仅金额入轴时 parse）。
 */
const props = defineProps<{ result: CalculationResult }>()

/** 指标卡精选（code 对齐 calc-domain MetricCodes；null 值渲染「—」）。 */
const METRIC_CARDS: { code: string; label: string }[] = [
  { code: 'TOTAL_RENT', label: '总租金' },
  { code: 'TOTAL_INTEREST', label: '总利息' },
  { code: 'TOTAL_FEE', label: '总费用' },
  { code: 'TOTAL_TAX', label: '总税额' },
  { code: 'RENTAL_RATE', label: '租金率' },
  { code: 'IRR_ANNUAL', label: 'IRR（年化）' },
  { code: 'XIRR', label: 'XIRR' },
]

const metrics = computed(() => props.result.schedule.header.metrics)

const metricCards = computed(() =>
  METRIC_CARDS.map(({ code, label }) => ({ code, label, value: metrics.value[code] ?? null })),
)

/** 未入选指标卡的全量指标（保持 §9.5 全量可见）。 */
const otherMetrics = computed(() => {
  const cardCodes = new Set(METRIC_CARDS.map((c) => c.code))
  return Object.entries(metrics.value).filter(([code]) => !cardCodes.has(code))
})

/** ERROR 红 / WARN 橙 / INFO 灰（后端 IssueLevel 枚举名）。 */
function issueColor(level: string): string {
  if (level === 'ERROR') return 'red'
  if (level === 'WARN') return 'orange'
  return 'default'
}

const installmentColumns: {
  title: string
  dataIndex: keyof Installment
  width: number
  align?: 'right' | 'left'
}[] = [
  { title: '期次', dataIndex: 'periodNo', width: 60 },
  { title: '开始日', dataIndex: 'startDate', width: 105 },
  { title: '结束日', dataIndex: 'endDate', width: 105 },
  { title: '天数', dataIndex: 'days', width: 60, align: 'right' },
  { title: '期初余额', dataIndex: 'openingBalance', width: 120, align: 'right' },
  { title: '本金', dataIndex: 'principalDue', width: 110, align: 'right' },
  { title: '利息（计提）', dataIndex: 'interestAccrued', width: 110, align: 'right' },
  { title: '利息（支付）', dataIndex: 'interestPaid', width: 110, align: 'right' },
  { title: '费用', dataIndex: 'fee', width: 100, align: 'right' },
  { title: '税', dataIndex: 'tax', width: 100, align: 'right' },
  { title: '租金', dataIndex: 'rentDue', width: 110, align: 'right' },
  { title: '期末余额', dataIndex: 'closingBalance', width: 120, align: 'right' },
  { title: '累计本金', dataIndex: 'cumulativePrincipal', width: 120, align: 'right' },
  { title: '累计利息', dataIndex: 'cumulativeInterest', width: 120, align: 'right' },
  { title: '适用利率', dataIndex: 'appliedRate', width: 90, align: 'right' },
  { title: '备注', dataIndex: 'memo', width: 160 },
]

const compositionOption = computed(() => buildCompositionOption(props.result.schedule.installments))
const balanceOption = computed(() => buildBalanceOption(props.result.schedule.installments))

function issueKey(issue: Issue, index: number): string {
  return `${issue.code}-${issue.periodNo ?? 'all'}-${index}`
}
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
    <div
      data-test="metric-cards"
      style="display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px"
    >
      <Card v-for="card in metricCards" :key="card.code" size="small" data-test="metric-card">
        <!-- 自定义 formatter：金额字符串原样直显，绕开 Statistic 内置的千分位重排版。 -->
        <Statistic
          :title="card.label"
          :value="card.value ?? '—'"
          :data-test="`metric-${card.code}`"
          :value-style="card.value === null ? { color: 'rgba(0,0,0,0.45)' } : undefined"
        >
          <template #formatter>{{ card.value ?? '—' }}</template>
        </Statistic>
      </Card>
    </div>
    <Descriptions
      v-if="otherMetrics.length > 0"
      bordered
      size="small"
      :column="3"
      data-test="metrics-list"
      style="margin-top: 12px"
    >
      <Descriptions.Item v-for="[code, value] in otherMetrics" :key="code" :label="code">
        {{ value ?? '—' }}
      </Descriptions.Item>
    </Descriptions>

    <template v-if="result.schedule.issues.length > 0">
      <Typography.Title :level="5" style="margin-top: 12px">问题列表</Typography.Title>
      <ul data-test="issues-list" style="padding-left: 20px; margin: 0">
        <li v-for="(issue, idx) in result.schedule.issues" :key="issueKey(issue, idx)">
          <Tag :color="issueColor(issue.level)" data-test="issue-level">{{ issue.level }}</Tag>
          {{ issue.code }} {{ issue.message }}
          <template v-if="issue.periodNo !== null">（第 {{ issue.periodNo }} 期）</template>
        </li>
      </ul>
    </template>

    <Typography.Title :level="5" style="margin-top: 12px">构成（本金 vs 利息）</Typography.Title>
    <EChart :option="compositionOption" data-test="composition-chart" />
    <Typography.Title :level="5" style="margin-top: 12px">余额曲线</Typography.Title>
    <EChart :option="balanceOption" data-test="balance-chart" />

    <Typography.Title :level="5" style="margin-top: 12px">摊销计划</Typography.Title>
    <Table
      :columns="installmentColumns"
      :data-source="result.schedule.installments"
      :pagination="false"
      size="small"
      row-key="periodNo"
      :scroll="{ x: 1870 }"
      data-test="installments-table"
    />
  </Card>
</template>
