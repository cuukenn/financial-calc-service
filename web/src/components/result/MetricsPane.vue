<script setup lang="ts">
import { Card, Statistic, Tag, Tooltip, Typography } from 'ant-design-vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import type { Issue } from '@/api/types'
import type { AppLocale } from '@/i18n'
import { metricPrimaryLabel, metricShowsSecondaryCode, metricTooltip } from '@/i18n/metricCatalog'
import type { ScenarioKey } from '@/scenario'
import { formatMoney, formatNumber, formatPercent } from '@/utils/format'

import { annualRateCaveatCodes } from './annualRateCaveat'
import { STAMP_DUTY_RATE_TEXT, stampDuty as computeStampDuty } from './stampDuty'

/**
 * 指标 pane（D-6 拆分）：独立的子组件，props 引用稳定时切换外层 Tab 不会重渲染，
 * 图与表的开销不再随 Tabs 的内部状态变化而重复付出。
 *
 * R149：`scenario` 决定派生的合同税费要不要出现 —— 印花税是《印花税法》的**融资租赁
 * 合同**税目，房贷 Tab 的一笔住房贷款不是那个税目，不得替它发明一个税额。默认按
 * 融资租赁（与既有单页形态一致，承 `scenario.ts` 的默认场景）。
 */
const props = withDefaults(
  defineProps<{
    metrics: Record<string, string | null>
    issues: Issue[]
    scenario?: ScenarioKey
  }>(),
  { scenario: 'lease' },
)

const { t, te, locale } = useI18n()

/** 指标卡（首屏摘要）。 */
const METRIC_CARD_CODES = [
  'TOTAL_RENT',
  'TOTAL_INTEREST',
  'TOTAL_FEE',
  'TOTAL_TAX',
  'RENTAL_RATE',
  'IRR_ANNUAL',
  'XIRR',
]

/** 百分数指标（率）；其余非 NUMBER_METRICS 走金额 2 位。 */
const PERCENT_METRICS = new Set([
  'IRR_PERIOD',
  'IRR_ANNUAL',
  'IRR_ANNUAL_SIMPLE',
  'XIRR',
  'MIRR',
  'TRUE_ANNUAL_RATE',
  'LEASE_IMPLICIT_RATE',
  'BORROWER_IRR_PERIOD',
  'BORROWER_IRR_ANNUAL',
  'BORROWER_IRR_ANNUAL_SIMPLE',
])

/** 纯数量/倍数类指标（租金率、回收期、久期、凸性）。 */
const NUMBER_METRICS = new Set([
  'RENTAL_RATE',
  'PAYBACK_PERIOD',
  'DISCOUNTED_PAYBACK',
  'DURATION_MACAULAY',
  'DURATION_MODIFIED',
  'CONVEXITY',
])

/**
 * R121：借款方视角的四个 code 只作展示辅助 —— 不进指标卡、不进出租方指标网格、
 * 不与任何出租方金额相加。它们单独成节。
 */
const BORROWER_METRIC_CODES = [
  'BORROWER_IRR_PERIOD',
  'BORROWER_IRR_ANNUAL',
  'BORROWER_IRR_ANNUAL_SIMPLE',
  'BORROWER_TOTAL_REPAYMENT',
]

const activeLocale = computed(() => locale.value as AppLocale)

/** R143：稀疏现金流下按「期」年化的读数不可信 —— 由与 XIRR 的差异判定。 */
const caveatedCodes = computed(() => annualRateCaveatCodes(props.metrics))

/**
 * R149：印花税只在融资租赁场景派生。房贷 Tab 不显示：《印花税法》里 0.005% 是
 * 融资租赁合同的税目，而借款合同的印花税不在 AGENTS.md §4 已批准的口径内 —— 宁缺勿造。
 */
const stampDutyEnabled = computed(() => props.scenario === 'lease')
const stampDuty = computed(() => (stampDutyEnabled.value ? computeStampDuty(props.metrics) : null))

function showsSecondaryCode(code: string): boolean {
  return metricShowsSecondaryCode(code, activeLocale.value)
}

function label(code: string): string {
  return metricPrimaryLabel(code, activeLocale.value)
}

function tooltip(code: string, value: string | null): string {
  const base = metricTooltip(code, activeLocale.value)
  const raw = t('result.raw', { value: value ?? '—' })
  if (!caveatedCodes.value.has(code)) {
    return `${base}｜${raw}`
  }
  return `${base}｜${raw}｜${t('result.irrCaveat.tooltip')}`
}

function formatMetricValue(code: string, value: string | null): string {
  if (value === null) return '—'
  if (PERCENT_METRICS.has(code)) return formatPercent(value)
  if (NUMBER_METRICS.has(code)) return formatNumber(value)
  return formatMoney(value)
}

const metricCards = computed(() =>
  METRIC_CARD_CODES.filter((code) => code in props.metrics).map((code) => {
    const value = props.metrics[code] ?? null
    return {
      code,
      label: label(code),
      showCode: showsSecondaryCode(code),
      tooltip: tooltip(code, value),
      value,
      caveat: caveatedCodes.value.has(code),
    }
  }),
)

const borrowerMetrics = computed(() =>
  BORROWER_METRIC_CODES.filter((code) => props.metrics[code] !== null && props.metrics[code] !== undefined).map(
    (code) => [code, props.metrics[code] as string] as const,
  ),
)

const otherMetrics = computed(() =>
  Object.entries(props.metrics).filter(
    ([code]) => !METRIC_CARD_CODES.includes(code) && !BORROWER_METRIC_CODES.includes(code),
  ),
)

function issueColor(level: string): string {
  if (level === 'ERROR') return 'red'
  if (level === 'WARN') return 'orange'
  return 'default'
}

function issueLevelLabel(level: string): string {
  const key = `result.issues.levels.${level}`
  return te(key) ? t(key) : level
}
</script>

<template>
  <div>
    <div data-test="metric-cards" class="metric-cards">
      <Tooltip v-for="card in metricCards" :key="card.code" :title="card.tooltip">
        <Card size="small" data-test="metric-card">
          <Statistic
            :value="card.value ?? '—'"
            :data-test="`metric-${card.code}`"
            :class="['metric-value', { 'metric-value-muted': card.value === null }]"
          >
            <template #title>
              <span data-test="metric-card-title">{{ card.label }}</span>
              <span v-if="card.showCode" class="metric-header-code" data-test="metric-card-code">
                {{ card.code }}
              </span>
              <span v-if="card.caveat" class="metric-caveat" data-test="metric-irr-caveat">
                {{ t('result.irrCaveat.marker') }}
              </span>
            </template>
            <template #formatter>
              {{ formatMetricValue(card.code, card.value) }}
            </template>
          </Statistic>
        </Card>
      </Tooltip>
    </div>

    <div class="metrics-grid" :style="{ gridTemplateColumns: 'repeat(3, auto)' }" data-test="metrics-list">
      <div v-for="[code, value] in otherMetrics" :key="code" class="metrics-grid-item" data-test="metrics-grid-item">
        <Tooltip :title="tooltip(code, value)">
          <span class="metrics-grid-label" data-test="metric-header">
            <span data-test="metric-header-name">{{ label(code) }}</span>
            <span v-if="showsSecondaryCode(code)" class="metric-header-code" data-test="metric-header-code">
              {{ code }}
            </span>
            <span v-if="caveatedCodes.has(code)" class="metric-caveat" data-test="metric-irr-caveat">
              {{ t('result.irrCaveat.marker') }}
            </span>
          </span>
        </Tooltip>
        <Tooltip :title="tooltip(code, value)">
          <span class="metrics-grid-value" data-test="metric-value">
            {{ formatMetricValue(code, value) }}
          </span>
        </Tooltip>
      </div>
    </div>

    <div v-if="stampDutyEnabled" class="stamp-duty" data-test="stamp-duty-section">
      <Typography.Title :level="5">{{ t('result.stampDuty.title') }}</Typography.Title>
      <Typography.Text type="secondary">{{ t('result.stampDuty.note') }}</Typography.Text>
      <div
        v-if="stampDuty"
        class="metrics-grid"
        :style="{ gridTemplateColumns: 'repeat(3, auto)' }"
        data-test="stamp-duty-grid"
      >
        <div class="metrics-grid-item">
          <span class="metrics-grid-label" data-test="stamp-duty-base-label">
            {{ t('result.stampDuty.base') }}
          </span>
          <Tooltip :title="stampDuty.base">
            <span class="metrics-grid-value" data-test="stamp-duty-base-value">
              {{ formatMoney(stampDuty.base) }}
            </span>
          </Tooltip>
        </div>
        <div class="metrics-grid-item">
          <span class="metrics-grid-label" data-test="stamp-duty-rate-label">
            {{ t('result.stampDuty.rate') }}
          </span>
          <span class="metrics-grid-value" data-test="stamp-duty-rate-value">
            {{ STAMP_DUTY_RATE_TEXT }}
          </span>
        </div>
        <div class="metrics-grid-item">
          <span class="metrics-grid-label" data-test="stamp-duty-amount-label">
            {{ t('result.stampDuty.amount') }}
          </span>
          <Tooltip :title="stampDuty.amount">
            <span class="metrics-grid-value" data-test="stamp-duty-amount-value">
              {{ formatMoney(stampDuty.amount) }}
            </span>
          </Tooltip>
        </div>
      </div>
      <Typography.Text v-else type="secondary" data-test="stamp-duty-unavailable">
        {{ t('result.stampDuty.unavailable') }}
      </Typography.Text>
    </div>

    <div v-if="borrowerMetrics.length > 0" class="borrower-metrics" data-test="borrower-metrics-section">
      <Typography.Title :level="5">{{ t('result.borrowerSection.title') }}</Typography.Title>
      <Typography.Text type="secondary">{{ t('result.borrowerSection.note') }}</Typography.Text>
      <div class="metrics-grid" data-test="borrower-metrics-grid">
        <div
          v-for="[code, value] in borrowerMetrics"
          :key="code"
          class="metrics-grid-item borrower-metric-item"
          data-test="borrower-metric-item"
        >
          <Tooltip :title="tooltip(code, value)">
            <span class="metrics-grid-label" data-test="borrower-metric-header">
              <span data-test="borrower-metric-name">{{ label(code) }}</span>
              <span v-if="showsSecondaryCode(code)" class="metric-header-code" data-test="borrower-metric-code">
                {{ code }}
              </span>
              <span v-if="caveatedCodes.has(code)" class="metric-caveat" data-test="metric-irr-caveat">
                {{ t('result.irrCaveat.marker') }}
              </span>
            </span>
          </Tooltip>
          <Tooltip :title="tooltip(code, value)">
            <span class="metrics-grid-value" data-test="borrower-metric-value">
              {{ formatMetricValue(code, value) }}
            </span>
          </Tooltip>
        </div>
      </div>
    </div>

    <div v-if="issues.length > 0" class="result-issues">
      <Typography.Title :level="5">{{ t('result.issues.title') }}</Typography.Title>
      <ul data-test="issues-list">
        <li v-for="(issue, index) in issues" :key="`${issue.code}-${index}`">
          <Tag :color="issueColor(issue.level)" data-test="issue-level">{{ issueLevelLabel(issue.level) }}</Tag>
          <span class="issue-code">{{ issue.code }}</span>
          <span>{{ issue.message }}</span>
          <span v-if="issue.periodNo !== null">{{ t('result.issues.period', { n: issue.periodNo }) }}</span>
        </li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.metric-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
}

.metric-header-code {
  display: block;
  font-size: 12px;
  color: var(--fcs-text-muted);
}

.metric-caveat {
  display: block;
  font-size: 12px;
  color: var(--fcs-caveat);
}

.metric-value-muted :deep(.ant-statistic-content) {
  color: var(--fcs-text-muted);
}

.metrics-grid {
  display: grid;
  margin-top: 12px;
  overflow-x: auto;
  border-top: 1px solid var(--fcs-hairline);
}

.metrics-grid-item {
  display: flex;
  align-items: baseline;
  border-right: 1px solid var(--fcs-hairline);
  border-bottom: 1px solid var(--fcs-hairline);
}

.metrics-grid-item:nth-child(3n + 1) {
  border-left: 1px solid var(--fcs-hairline);
}

.metrics-grid-label {
  flex: 0 0 auto;
  max-width: 100%;
  padding: 6px 10px;
  background: var(--fcs-fill-alter);
  color: var(--fcs-label-muted);
  white-space: nowrap;
}

.metrics-grid-value {
  flex: 1 1 auto;
  padding: 6px 10px;
  white-space: nowrap;
}

.borrower-metrics {
  margin-top: 16px;
}

.stamp-duty {
  margin-top: 16px;
}

.result-issues {
  margin-top: 16px;
}

.issue-code {
  margin-right: 8px;
  font-family: var(--fcs-mono, monospace);
  color: var(--fcs-text-muted);
}
</style>
