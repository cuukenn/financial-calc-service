<script setup lang="ts">
import {
  Alert,
  Card,
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
import { metricPrimaryLabel, metricShowsSecondaryCode, metricTooltip } from '@/i18n/metricCatalog'

import { formatMoney, formatNumber, formatPercent } from '@/utils/format'

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

/**
 * R90b：指标数值显示格式分类（money 为缺省）。
 * - percent：利率类指标，×100 固定 4 位小数 + '%'；
 * - number：年数类（回收期/久期/凸性）与租费率比值，固定 4 位小数；
 * - money：金额类，千分位 + 固定 2 位小数（R93-2：贴合现实记账精度）。
 * 原始后端字符串不丢：随 tooltip 透出（对账不丢精度）。
 */
const PERCENT_METRICS = new Set([
  'IRR_PERIOD',
  'IRR_ANNUAL',
  'IRR_ANNUAL_SIMPLE',
  'XIRR',
  'MIRR',
  'TRUE_ANNUAL_RATE',
])
const NUMBER_METRICS = new Set([
  'RENTAL_RATE',
  'PAYBACK_PERIOD',
  'DISCOUNTED_PAYBACK',
  'DURATION_MACAULAY',
  'DURATION_MODIFIED',
  'CONVEXITY',
])

/** 指标值按类别格式化；null → '—'；目录外 code 按金额兜底（原样透出非数值）。 */
function formatMetricValue(code: string, value: string | null): string {
  if (value === null) return '—'
  if (PERCENT_METRICS.has(code)) return formatPercent(value)
  if (NUMBER_METRICS.has(code)) return formatNumber(value)
  return formatMoney(value)
}

/** hover tooltip = 指标目录内容 + 原始字符串（R90a/R90b：显示格式化、对账不丢精度）。 */
function metricTooltipWithRaw(code: string, value: string | null): string {
  const base = metricTooltip(code, locale.value as AppLocale)
  return value === null ? base : `${base}｜${t('result.raw', { value })}`
}

/**
 * R92-8：指标卡主展示名与指标表行头同规则（R92-4 分类：标准缩写原样 / IRR 家族带
 * 口径限定 / 其余目录本地化名），目录内且主文本非原码时附原码次要小字。
 */
const metricCards = computed(() =>
  METRIC_CARD_CODES.map((code) => ({
    code,
    label: metricPrimaryLabel(code, locale.value as AppLocale),
    showCode: metricShowsSecondaryCode(code, locale.value as AppLocale),
    tooltip: metricTooltipWithRaw(code, metrics.value[code] ?? null),
    value: metrics.value[code] ?? null,
  })),
)

/** 未入选指标卡的全量指标（保持 §9.5 全量可见），行头 hover 同样挂指标目录。 */
const otherMetrics = computed(() => {
  const cardCodes = new Set(METRIC_CARD_CODES)
  return Object.entries(metrics.value).filter(([code]) => !cardCodes.has(code))
})

function otherMetricTooltip(code: string, value: string | null): string {
  return metricTooltipWithRaw(code, value)
}

/**
 * R92-4：指标表行头主文本为标准缩写/目录本地化名（与指标卡同规则），
 * 目录外 code 原样透出（后端新增指标的对账约定）。
 */
function otherMetricLabel(code: string): string {
  return metricPrimaryLabel(code, locale.value as AppLocale)
}

/** 目录内且主文本非原码时，以小号灰字在行头附上原码（对账用）；目录外/标准缩写不重复显示。 */
function showsSecondaryCode(code: string): boolean {
  return metricShowsSecondaryCode(code, locale.value as AppLocale)
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

/** R90a：摊销计划表的金额列（期次/天数为整数、appliedRate 为利率另走 formatPercent）。 */
const INSTALLMENT_MONEY_COLUMNS = new Set([
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
])

/** R90a：实际利率法摊销表五列全部为金额。 */
const EIR_MONEY_COLUMNS = new Set([
  'openingNetInvestment',
  'interestIncome',
  'cashReceived',
  'principalRecovery',
  'closingNetInvestment',
])

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
              <!-- 自定义 formatter：绕开 Statistic 内置重排版；R90a/R90b + R93-2 按 #,##0.00 / 百分比 / 4 位小数显示，原值随 tooltip 透出。 -->
              <Statistic
                :value="card.value ?? '—'"
                :data-test="`metric-${card.code}`"
                :value-style="card.value === null ? { color: 'rgba(0,0,0,0.45)' } : undefined"
              >
                <!-- R92-8：主展示名同指标表行头规则；目录内指标附原码次要小字（对账用）。 -->
                <template #title>
                  <span data-test="metric-card-title">
                    {{ card.label }}
                    <span
                      v-if="card.showCode"
                      data-test="metric-card-code"
                      class="metric-header-code"
                    >{{ card.code }}</span>
                  </span>
                </template>
                <template #formatter>{{ formatMetricValue(card.code, card.value) }}</template>
              </Statistic>
            </Card>
          </Tooltip>
        </div>
        <!-- R93-3：指标表改为 3 列网格（每格 = 行头 + 数值），每行恰 3 个指标。
             Descriptions 的列宽随内容计算，在结果列宽度下会不规则挤压/换行；
             网格固定 3 等分列，格子内 nowrap + 省略号，tooltip 仍透出原值。 -->
        <div
          v-if="otherMetrics.length > 0"
          class="metrics-grid"
          data-test="metrics-list"
          style="margin-top: 12px; display: grid; grid-template-columns: repeat(3, auto)"
        >
          <div
            v-for="[code, value] in otherMetrics"
            :key="code"
            class="metrics-grid-item"
            data-test="metrics-grid-item"
          >
            <!-- R92-4：行头主文本为标准缩写（原样）/目录本地化名，R86 hover 不变。 -->
            <Tooltip :title="otherMetricTooltip(code, value)">
              <span data-test="metric-header" class="metrics-grid-label">
                <span data-test="metric-header-name">{{ otherMetricLabel(code) }}</span>
                <span
                  v-if="showsSecondaryCode(code)"
                  data-test="metric-header-code"
                  class="metric-header-code"
                >{{ code }}</span>
              </span>
            </Tooltip>
            <!-- R90a/R90b：数值按类别格式化，原值随 tooltip 透出。 -->
            <Tooltip :title="t('result.raw', { value: value ?? '—' })">
              <span data-test="metric-value" class="metrics-grid-value">
                {{ formatMetricValue(code, value) }}
              </span>
            </Tooltip>
          </div>
        </div>

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
        >
          <!-- R90a/R90b：金额列 #,##0.00（R93-2）、利率列百分比，hover 透出原值（对账不丢精度）。 -->
          <template #bodyCell="{ column, record }">
            <template v-if="INSTALLMENT_MONEY_COLUMNS.has(column.dataIndex as string)">
              <Tooltip :title="t('result.raw', { value: record[column.dataIndex as keyof Installment] })">
                <span>{{ formatMoney(record[column.dataIndex as keyof Installment]) }}</span>
              </Tooltip>
            </template>
            <template v-else-if="column.dataIndex === 'appliedRate'">
              <Tooltip :title="t('result.raw', { value: record.appliedRate })">
                <span>{{ formatPercent(record.appliedRate) }}</span>
              </Tooltip>
            </template>
          </template>
        </Table>
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
              <!-- R90a：金额列 #,##0.00（R93-2），hover 透出原值。 -->
              <template v-else-if="column.dataIndex === 'amount'">
                <Tooltip :title="t('result.raw', { value: record.amount })">
                  <span>{{ formatMoney(record.amount) }}</span>
                </Tooltip>
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
                eir: formatPercent(effectiveInterest.eir),
                diff: formatMoney(effectiveInterest.totalInterestDifference),
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
          >
            <!-- R90a：五列金额均 #,##0.00（R93-2），hover 透出原值。 -->
            <template #bodyCell="{ column, record }">
              <template v-if="EIR_MONEY_COLUMNS.has(column.dataIndex as string)">
                <Tooltip :title="t('result.raw', { value: record[column.dataIndex as keyof EffectiveInterestRow] })">
                  <span>{{ formatMoney(record[column.dataIndex as keyof EffectiveInterestRow]) }}</span>
                </Tooltip>
              </template>
            </template>
          </Table>
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
/* R93-3 + 用户反馈：指标值必须完整可见——列宽随内容自适应（repeat(3, auto)），
   单元格不裁剪；容器 overflow-x 兜底极端长值。数值 nowrap 保持单行。 */
.metrics-grid {
  overflow-x: auto;
}
.metrics-grid-item {
  display: flex;
  align-items: baseline;
  border-right: 1px solid #f0f0f0;
  border-bottom: 1px solid #f0f0f0;
}
.metrics-grid-item:nth-child(3n + 1) {
  border-left: 1px solid #f0f0f0;
}
.metrics-grid {
  border-top: 1px solid #f0f0f0;
}
.metrics-grid-label {
  flex: 0 0 auto;
  max-width: 100%;
  padding: 6px 10px;
  background: #fafafa;
  color: rgba(0, 0, 0, 0.65);
  white-space: nowrap;
}
.metrics-grid-value {
  flex: 1 1 auto;
  padding: 6px 10px;
  white-space: nowrap;
}
</style>
