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
  Tooltip,
  Typography,
} from 'ant-design-vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import type { CalculationResult, CashFlowEvent, EffectiveInterestRow, Installment, Issue } from '@/api/types'
import type { AppLocale } from '@/i18n'
import { METRIC_CATALOG, metricLabel, metricTooltip } from '@/i18n/metricCatalog'

import EChart from './charts/EChart.vue'
import {
  buildBalanceOption,
  buildCompositionOption,
  buildCumulativeCashflowOption,
  type ChartLabels,
} from './charts/options'

/**
 * Task 1（Plan 4）结果区 Tabs 化：指标 / 摊销计划（图表 + 16 列表格，行为不变）/
 * 现金流（Task 3 / R87 填充：现金流表 + 方向着色 + 累计回收曲线）/
 * 实际利率法（有数据即渲染 6 列摊销表，否则 Empty 占位）。
 * 指标 Statistic 卡金额字符串原样直显，null → 「—」。
 * Task 2（Plan 4 / R85-R86）：全量文案 i18n；指标卡与指标表 hover 显示指标目录
 * （中文名/公式/说明，来自 §9.5）。
 */
const props = defineProps<{ result: CalculationResult }>()

const { t, te, locale } = useI18n()

/** 指标卡精选（code 对齐 calc-domain MetricCodes；名称/悬浮解释来自指标目录 §9.5）。 */
const METRIC_CARD_CODES = [
  'TOTAL_RENT',
  'TOTAL_INTEREST',
  'TOTAL_FEE',
  'TOTAL_TAX',
  'RENTAL_RATE',
  'IRR_ANNUAL',
  'XIRR',
]

const metrics = computed(() => props.result.schedule.header.metrics)

const metricCards = computed(() =>
  METRIC_CARD_CODES.map((code) => ({
    code,
    label: metricLabel(code, locale.value as AppLocale),
    tooltip: metricTooltip(code, locale.value as AppLocale),
    value: metrics.value[code] ?? null,
  })),
)

/** 未入选指标卡的全量指标（保持 §9.5 全量可见），行头 hover 同样挂指标目录。 */
const otherMetrics = computed(() => {
  const cardCodes = new Set(METRIC_CARD_CODES)
  return Object.entries(metrics.value).filter(([code]) => !cardCodes.has(code))
})

function otherMetricTooltip(code: string): string {
  return metricTooltip(code, locale.value as AppLocale)
}

/**
 * R88：指标表行头主文本为指标目录的本地化名称（同指标卡），
 * 目录外 code 原样透出（后端新增指标的对账约定）。
 */
function otherMetricLabel(code: string): string {
  return metricLabel(code, locale.value as AppLocale)
}

/** 目录内 code 以小号灰字在行头附上原码，便于与接口字段对账；目录外不重复显示。 */
function isCatalogMetric(code: string): boolean {
  return code in METRIC_CATALOG
}

/** ERROR 红 / WARN 橙 / INFO 灰（后端 IssueLevel 枚举名）。 */
function issueColor(level: string): string {
  if (level === 'ERROR') return 'red'
  if (level === 'WARN') return 'orange'
  return 'default'
}

/** 问题级别本地化标签；目录外的枚举名（后端新增）原样透出。 */
function issueLevelLabel(level: string): string {
  const key = `result.issues.levels.${level}`
  return te(key) ? t(key) : level
}

const installmentColumns = computed<
  { title: string; dataIndex: keyof Installment; width: number; align?: 'right' | 'left' }[]
>(() => [
  { title: t('result.table.periodNo'), dataIndex: 'periodNo', width: 60 },
  { title: t('result.table.startDate'), dataIndex: 'startDate', width: 105 },
  { title: t('result.table.endDate'), dataIndex: 'endDate', width: 105 },
  { title: t('result.table.days'), dataIndex: 'days', width: 60, align: 'right' },
  { title: t('result.table.openingBalance'), dataIndex: 'openingBalance', width: 120, align: 'right' },
  { title: t('result.table.principalDue'), dataIndex: 'principalDue', width: 110, align: 'right' },
  { title: t('result.table.interestAccrued'), dataIndex: 'interestAccrued', width: 110, align: 'right' },
  { title: t('result.table.interestPaid'), dataIndex: 'interestPaid', width: 110, align: 'right' },
  { title: t('result.table.fee'), dataIndex: 'fee', width: 100, align: 'right' },
  { title: t('result.table.tax'), dataIndex: 'tax', width: 100, align: 'right' },
  { title: t('result.table.rentDue'), dataIndex: 'rentDue', width: 110, align: 'right' },
  { title: t('result.table.closingBalance'), dataIndex: 'closingBalance', width: 120, align: 'right' },
  { title: t('result.table.cumulativePrincipal'), dataIndex: 'cumulativePrincipal', width: 120, align: 'right' },
  { title: t('result.table.cumulativeInterest'), dataIndex: 'cumulativeInterest', width: 120, align: 'right' },
  { title: t('result.table.appliedRate'), dataIndex: 'appliedRate', width: 90, align: 'right' },
  { title: t('result.table.memo'), dataIndex: 'memo', width: 160 },
])

/** §9.7 实际利率法摊销表六列（金额均为字符串原样）。 */
const eirColumns = computed<
  { title: string; dataIndex: keyof EffectiveInterestRow; width: number; align?: 'right' | 'left' }[]
>(() => [
  { title: t('result.table.periodNo'), dataIndex: 'periodNo', width: 60 },
  {
    title: t('result.table.eirOpeningNetInvestment'),
    dataIndex: 'openingNetInvestment',
    width: 130,
    align: 'right',
  },
  { title: t('result.table.eirInterestIncome'), dataIndex: 'interestIncome', width: 110, align: 'right' },
  { title: t('result.table.eirCashReceived'), dataIndex: 'cashReceived', width: 110, align: 'right' },
  {
    title: t('result.table.eirPrincipalRecovery'),
    dataIndex: 'principalRecovery',
    width: 110,
    align: 'right',
  },
  {
    title: t('result.table.eirClosingNetInvestment'),
    dataIndex: 'closingNetInvestment',
    width: 130,
    align: 'right',
  },
])

const effectiveInterest = computed(() => props.result.effectiveInterest)

/** R87：现金流表五列（期次/日期/类型/方向/金额），金额字符串原样直显。 */
const cashflowColumns = computed<
  { title: string; dataIndex: keyof CashFlowEvent; width: number; align?: 'right' }[]
>(() => [
  { title: t('result.cashflowTab.sequence'), dataIndex: 'sequence', width: 70 },
  { title: t('result.cashflowTab.date'), dataIndex: 'date', width: 110 },
  { title: t('result.cashflowTab.type'), dataIndex: 'type', width: 110 },
  { title: t('result.cashflowTab.direction'), dataIndex: 'direction', width: 100 },
  { title: t('result.cashflowTab.amount'), dataIndex: 'amount', width: 120, align: 'right' },
])

const cashFlows = computed(() => props.result.schedule.cashFlows)

/** 现金流类型本地化标签；目录外的枚举名（后端新增）原样透出（同问题级别约定）。 */
function cashflowTypeLabel(type: string): string {
  const key = `result.cashflowTab.types.${type}`
  return te(key) ? t(key) : type
}

/** 现金流方向本地化标签；目录外的枚举名原样透出。 */
function cashflowDirectionLabel(direction: string): string {
  const key = `result.cashflowTab.directions.${direction}`
  return te(key) ? t(key) : direction
}

/** 方向着色：INFLOW 绿 / OUTFLOW 红（R87）。 */
function directionColor(direction: string): string {
  return direction === 'OUTFLOW' ? 'red' : 'green'
}

/** R87：回收曲线文案从 locale 组装后传入 option 构造器。 */
const cumulativeOption = computed(() =>
  buildCumulativeCashflowOption(cashFlows.value, {
    cumulative: t('charts.cumulativeCashflow'),
    dateAxis: t('charts.dateAxis'),
    amount: t('charts.amount'),
  }),
)

/** R85：图表系列名/轴名文案从 locale 组装后传入 option 构造器。 */
const chartLabels = computed<ChartLabels>(() => ({
  principal: t('charts.principal'),
  interest: t('charts.interest'),
  closingBalance: t('charts.closingBalance'),
  periodAxis: t('charts.periodAxis'),
  amount: t('charts.amount'),
  periodLabel: (n: number) => t('charts.periodLabel', { n }),
}))

const compositionOption = computed(() =>
  buildCompositionOption(props.result.schedule.installments, chartLabels.value),
)
const balanceOption = computed(() =>
  buildBalanceOption(props.result.schedule.installments, chartLabels.value),
)

function issueKey(issue: Issue, index: number): string {
  return `${issue.code}-${issue.periodNo ?? 'all'}-${index}`
}
</script>

<template>
  <Card :title="t('result.cardTitle')" data-test="result-card">
    <Typography.Text type="secondary">
      {{ t('result.runId', { runId: result.runId }) }}
    </Typography.Text>

    <!-- hasErrors：问题列表含 ERROR 级条目（预期结果，非请求失败）。 -->
    <Alert
      v-if="result.schedule.hasErrors"
      type="warning"
      show-icon
      :message="t('result.hasErrors')"
      data-test="has-errors-alert"
      style="margin-top: 8px"
    />

    <Tabs data-test="result-tabs">
      <!-- Tab 1：指标（Statistic 卡 + 全量指标 + 问题列表）。 -->
      <TabPane key="metrics" :tab="t('result.tabs.metrics')">
        <div
          data-test="metric-cards"
          style="display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px"
        >
          <!-- R86：指标卡 hover 显示指标目录（中文名/公式/说明）。 -->
          <Tooltip v-for="card in metricCards" :key="card.code" :title="card.tooltip">
            <Card size="small" data-test="metric-card">
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
          </Tooltip>
        </div>
        <Descriptions
          v-if="otherMetrics.length > 0"
          bordered
          size="small"
          :column="3"
          data-test="metrics-list"
          style="margin-top: 12px"
        >
          <Descriptions.Item v-for="[code, value] in otherMetrics" :key="code">
            <template #label>
              <!-- R88：行头主文本为目录本地化名，原码作次要小字（对账用）；R86 hover 不变。 -->
              <Tooltip :title="otherMetricTooltip(code)">
                <span data-test="metric-header">
                  <span data-test="metric-header-name">{{ otherMetricLabel(code) }}</span>
                  <span
                    v-if="isCatalogMetric(code)"
                    data-test="metric-header-code"
                    class="metric-header-code"
                  >{{ code }}</span>
                </span>
              </Tooltip>
            </template>
            {{ value ?? '—' }}
          </Descriptions.Item>
        </Descriptions>

        <template v-if="result.schedule.issues.length > 0">
          <Typography.Title :level="5" style="margin-top: 12px">
            {{ t('result.issues.title') }}
          </Typography.Title>
          <ul data-test="issues-list" style="padding-left: 20px; margin: 0">
            <li v-for="(issue, idx) in result.schedule.issues" :key="issueKey(issue, idx)">
              <Tag :color="issueColor(issue.level)" data-test="issue-level">
                {{ issueLevelLabel(issue.level) }}
              </Tag>
              {{ issue.code }} {{ issue.message }}
              <template v-if="issue.periodNo !== null">
                {{ t('result.issues.period', { n: issue.periodNo }) }}
              </template>
            </li>
          </ul>
        </template>
      </TabPane>

      <!-- Tab 2：摊销计划（构成/余额图表在上，16 列表格在下，行为不变）。 -->
      <TabPane key="schedule" :tab="t('result.tabs.schedule')">
        <Typography.Title :level="5" style="margin-top: 4px">
          {{ t('result.scheduleTab.compositionTitle') }}
        </Typography.Title>
        <EChart :option="compositionOption" data-test="composition-chart" />
        <Typography.Title :level="5" style="margin-top: 12px">
          {{ t('result.scheduleTab.balanceTitle') }}
        </Typography.Title>
        <EChart :option="balanceOption" data-test="balance-chart" />

        <Typography.Title :level="5" style="margin-top: 12px">
          {{ t('result.scheduleTab.tableTitle') }}
        </Typography.Title>
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

      <!-- Tab 3：现金流表（R87：类型/方向 i18n 标签，方向着色 Tag）+ 累计回收曲线。 -->
      <TabPane key="cashflow" :tab="t('result.tabs.cashflow')">
        <template v-if="cashFlows.length > 0">
          <Typography.Title :level="5" style="margin-top: 4px">
            {{ t('result.cashflowTab.tableTitle') }}
          </Typography.Title>
          <Table
            :columns="cashflowColumns"
            :data-source="cashFlows"
            :pagination="false"
            size="small"
            row-key="sequence"
            :scroll="{ x: 510 }"
            data-test="cashflow-table"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.dataIndex === 'type'">
                {{ cashflowTypeLabel(record.type) }}
              </template>
              <template v-else-if="column.dataIndex === 'direction'">
                <Tag
                  :color="directionColor(record.direction)"
                  data-test="cashflow-direction"
                >
                  {{ cashflowDirectionLabel(record.direction) }}
                </Tag>
              </template>
            </template>
          </Table>

          <Typography.Title :level="5" style="margin-top: 12px">
            {{ t('result.cashflowTab.curveTitle') }}
          </Typography.Title>
          <EChart :option="cumulativeOption" data-test="cumulative-cashflow-chart" />
        </template>
        <Empty
          v-else
          :description="t('result.cashflowTab.empty')"
          data-test="cashflow-empty"
        />
      </TabPane>

      <!-- Tab 4：实际利率法（§9.7 六列摊销表；IRR 未收敛/不可建表时占位）。 -->
      <TabPane key="eir" :tab="t('result.tabs.eir')">
        <template v-if="effectiveInterest">
          <Typography.Text type="secondary" style="display: block; margin-bottom: 8px">
            {{
              t('result.eir.summary', {
                eir: effectiveInterest.eir,
                diff: effectiveInterest.totalInterestDifference,
              })
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
          :description="t('result.eir.empty')"
          data-test="eir-empty"
        />
      </TabPane>
    </Tabs>
  </Card>
</template>

<style scoped>
/* R88：行头原码次要展示（小号灰字），与 null 值占位同色系。 */
.metric-header-code {
  display: block;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}
</style>
