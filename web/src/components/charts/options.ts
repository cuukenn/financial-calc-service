import type { EChartsOption } from 'echarts'

import type { Installment } from '@/api/types'

/**
 * 图表 option 构造（纯函数，Task 3 / R82：ECharts 仅用于构成与余额曲线）。
 *
 * 金额字符串仅在此处为坐标轴 parse 为 Number；表格/指标卡的展示字符串保持原样直显。
 */

function toNumber(money: string): number {
  const n = Number(money)
  return Number.isFinite(n) ? n : 0
}

/** 构成堆叠柱图：每期本金 vs 利息（含税/费用的租金差异不在此图，保持两系列可加）。 */
export function buildCompositionOption(installments: Installment[]): EChartsOption {
  const periods = installments.map((row) => `第 ${row.periodNo} 期`)
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { data: ['本金', '利息'] },
    grid: { left: 80, right: 24, bottom: 40, containLabel: true },
    xAxis: { type: 'category', data: periods, name: '期次' },
    yAxis: { type: 'value', name: '金额' },
    series: [
      { name: '本金', type: 'bar', stack: 'rent', data: installments.map((r) => toNumber(r.principalDue)) },
      { name: '利息', type: 'bar', stack: 'rent', data: installments.map((r) => toNumber(r.interestAccrued)) },
    ],
  }
}

/** 余额曲线：期末余额随期次递减。 */
export function buildBalanceOption(installments: Installment[]): EChartsOption {
  const periods = installments.map((row) => `第 ${row.periodNo} 期`)
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 80, right: 24, bottom: 40, containLabel: true },
    xAxis: { type: 'category', data: periods, name: '期次' },
    yAxis: { type: 'value', name: '期末余额' },
    dataZoom: [{ type: 'inside' }, { type: 'slider', height: 20, bottom: 8 }],
    series: [
      {
        name: '期末余额',
        type: 'line',
        smooth: true,
        data: installments.map((r) => toNumber(r.closingBalance)),
        areaStyle: { opacity: 0.15 },
      },
    ],
  }
}
