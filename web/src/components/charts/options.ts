import type { EChartsCoreOption } from 'echarts/core'

import type { CashFlowEvent, Installment } from '@/api/types'

/**
 * 图表 option 构造（纯函数，Task 3 / R82：构成柱图、余额曲线；Plan 4 / R87 增补
 * 累计现金流回收曲线）。
 *
 * 金额字符串仅在此处为坐标轴 parse 为 Number；表格/指标卡的展示字符串保持原样直显。
 * R85：系列名/坐标轴名等图表文案由调用方传入（来自 i18n），本模块保持纯函数无文案。
 */

/** 图表文案（调用方从 locale 组装，随语言切换响应式更新）。 */
export interface ChartLabels {
  /** 构成图两个系列名：本金 / 利息。 */
  principal: string
  interest: string
  /** 余额曲线系列名与 Y 轴名。 */
  closingBalance: string
  /** X 轴名。 */
  periodAxis: string
  /** Y 轴名。 */
  amount: string
  /** 期次刻度文案（如 「第 1 期」/「Period 1」）。 */
  periodLabel: (periodNo: number) => string
}

function toNumber(money: string): number {
  const n = Number(money)
  return Number.isFinite(n) ? n : 0
}

function periodTicks(installments: Installment[], labels: ChartLabels): string[] {
  return installments.map((row) => labels.periodLabel(row.periodNo))
}

/** 构成堆叠柱图：每期本金 vs 利息（含税/费用的租金差异不在此图，保持两系列可加）。 */
export function buildCompositionOption(
  installments: Installment[],
  labels: ChartLabels,
): EChartsCoreOption {
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { data: [labels.principal, labels.interest] },
    grid: { left: 80, right: 24, bottom: 40, containLabel: true },
    xAxis: { type: 'category', data: periodTicks(installments, labels), name: labels.periodAxis },
    yAxis: { type: 'value', name: labels.amount },
    series: [
      {
        name: labels.principal,
        type: 'bar',
        stack: 'rent',
        data: installments.map((r) => toNumber(r.principalDue)),
      },
      {
        name: labels.interest,
        type: 'bar',
        stack: 'rent',
        data: installments.map((r) => toNumber(r.interestAccrued)),
      },
    ],
  }
}

/** 余额曲线：期末余额随期次递减。 */
export function buildBalanceOption(
  installments: Installment[],
  labels: ChartLabels,
): EChartsCoreOption {
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 80, right: 24, bottom: 40, containLabel: true },
    xAxis: { type: 'category', data: periodTicks(installments, labels), name: labels.periodAxis },
    yAxis: { type: 'value', name: labels.closingBalance },
    dataZoom: [{ type: 'inside' }, { type: 'slider', height: 20, bottom: 8 }],
    series: [
      {
        name: labels.closingBalance,
        type: 'line',
        smooth: true,
        data: installments.map((r) => toNumber(r.closingBalance)),
        areaStyle: { opacity: 0.15 },
      },
    ],
  }
}

/** 累计现金流回收曲线文案（调用方从 locale 组装，随语言切换响应式更新）。 */
export interface CumulativeCashflowLabels {
  /** 系列名：累计现金流。 */
  cumulative: string
  /** X 轴名：日期。 */
  dateAxis: string
  /** Y 轴名：金额。 */
  amount: string
}

/**
 * 累计现金流回收曲线（R87）：按日期聚合带符号现金流（OUTFLOW 取负），逐日累加。
 * 曲线与零轴的交点即投资回收（PAYBACK_PERIOD）的可视对应。
 */
export function buildCumulativeCashflowOption(
  cashFlows: CashFlowEvent[],
  labels: CumulativeCashflowLabels,
): EChartsCoreOption {
  /** 同一日期可能多笔事件（租金 + 本金 + 利息 …），先按日期聚合净额。 */
  const netByDate = new Map<string, number>()
  for (const flow of cashFlows) {
    const signed = flow.direction === 'OUTFLOW' ? -toNumber(flow.amount) : toNumber(flow.amount)
    netByDate.set(flow.date, (netByDate.get(flow.date) ?? 0) + signed)
  }

  const dates = [...netByDate.keys()].sort()
  let cumulative = 0
  const data = dates.map((date) => {
    cumulative += netByDate.get(date) as number
    return cumulative
  })

  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 80, right: 24, bottom: 40, containLabel: true },
    xAxis: { type: 'category', data: dates, name: labels.dateAxis },
    yAxis: { type: 'value', name: labels.amount },
    series: [
      {
        name: labels.cumulative,
        type: 'line',
        smooth: false,
        data,
        areaStyle: { opacity: 0.15 },
      },
    ],
  }
}
