<script setup lang="ts">
import {
  Alert,
  Card,
  Descriptions,
  Empty,
  Statistic,
  Table,
  TabPane,
  Tabs,
  Tag,
  Typography,
} from 'ant-design-vue'
import { computed } from 'vue'

import type { CalculationResult, EffectiveInterestRow, Installment, Issue } from '@/api/types'

import EChart from './charts/EChart.vue'
import { buildBalanceOption, buildCompositionOption } from './charts/options'

/**
 * Task 1（Plan 4）结果区 Tabs 化：指标 / 摊销计划（图表 + 16 列表格，行为不变）/
 * 现金流占位（Task 3 填充）/ 实际利率法（有数据即渲染 6 列摊销表，否则 Empty 占位）。
 * 指标 Statistic 卡金额字符串原样直显，null → 「—」。
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

/** §9.7 实际利率法摊销表六列（金额均为字符串原样）。 */
const eirColumns: {
  title: string
  dataIndex: keyof EffectiveInterestRow
  width: number
  align?: 'right' | 'left'
}[] = [
  { title: '期次', dataIndex: 'periodNo', width: 60 },
  { title: '期初净投资', dataIndex: 'openingNetInvestment', width: 130, align: 'right' },
  { title: '利息收入', dataIndex: 'interestIncome', width: 110, align: 'right' },
  { title: '现金流入', dataIndex: 'cashReceived', width: 110, align: 'right' },
  { title: '本金回收', dataIndex: 'principalRecovery', width: 110, align: 'right' },
  { title: '期末净投资', dataIndex: 'closingNetInvestment', width: 130, align: 'right' },
]

const effectiveInterest = computed(() => props.result.effectiveInterest)

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

    <Tabs data-test="result-tabs">
      <!-- Tab 1：指标（Statistic 卡 + 全量指标 + 问题列表）。 -->
      <TabPane key="metrics" tab="指标">
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
      </TabPane>

      <!-- Tab 2：摊销计划（构成/余额图表在上，16 列表格在下，行为不变）。 -->
      <TabPane key="schedule" tab="摊销计划">
        <Typography.Title :level="5" style="margin-top: 4px">构成（本金 vs 利息）</Typography.Title>
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
      </TabPane>

      <!-- Tab 3：现金流占位（Task 3 填充现金流表 + 累计回收曲线）。 -->
      <TabPane key="cashflow" tab="现金流">
        <Empty description="现金流表与累计回收曲线将在后续任务中提供" data-test="cashflow-empty" />
      </TabPane>

      <!-- Tab 4：实际利率法（§9.7 六列摊销表；IRR 未收敛/不可建表时占位）。 -->
      <TabPane key="eir" tab="实际利率法">
        <template v-if="effectiveInterest">
          <Typography.Text type="secondary" style="display: block; margin-bottom: 8px">
            实际利率（EIR）：{{ effectiveInterest.eir }} · 利息总额调整差：{{
              effectiveInterest.totalInterestDifference
            }}
          </Typography.Text>
          <Table
            :columns="eirColumns"
            :data-source="effectiveInterest.rows"
            :pagination="false"
            size="small"
            row-key="periodNo"
            :scroll="{ x: 650 }"
            data-test="effective-interest-table"
          />
        </template>
        <Empty
          v-else
          description="IRR 未收敛或不可建表，暂无实际利率法摊销表"
          data-test="eir-empty"
        />
      </TabPane>
    </Tabs>
  </Card>
</template>
