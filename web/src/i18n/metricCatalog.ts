/**
 * 指标目录（R86）：§9.5 全部 21 项金融指标的 code → 中文名 / 英文名 / 公式 / 一行说明。
 *
 * 内容对齐设计文档 §9.5 的指标表
 * （docs/superpowers/specs/2026-11-02-financial-calc-service-design.md），
 * 不在此发明公式。驱动：
 * - ResultPanel 指标卡 Tooltip 与指标表 hover；
 * - 完整性测试（metricCatalog.spec：21 code × 双语字段）。
 */
import type { AppLocale } from './index'

export interface MetricInfo {
  nameZh: string
  nameEn: string
  formulaZh: string
  formulaEn: string
  descZh: string
  descEn: string
}

/** §9.5 FinancialMetrics 的 21 个指标 code（顺序与设计文档表格一致）。 */
export const METRIC_CODES = [
  'TOTAL_PRINCIPAL',
  'TOTAL_INTEREST',
  'TOTAL_FEE',
  'TOTAL_TAX',
  'TOTAL_RENT',
  'RESIDUAL_VALUE',
  'RENTAL_RATE',
  'NPV',
  'XNPV',
  'IRR_PERIOD',
  'IRR_ANNUAL',
  'IRR_ANNUAL_SIMPLE',
  'XIRR',
  'MIRR',
  'PAYBACK_PERIOD',
  'DISCOUNTED_PAYBACK',
  'DURATION_MACAULAY',
  'DURATION_MODIFIED',
  'CONVEXITY',
  'AVG_OUTSTANDING_BALANCE',
  'TRUE_ANNUAL_RATE',
] as const

export type MetricCode = (typeof METRIC_CODES)[number]

export const METRIC_CATALOG: Record<MetricCode, MetricInfo> = {
  TOTAL_PRINCIPAL: {
    nameZh: '本金合计',
    nameEn: 'Total Principal',
    formulaZh: 'Σ principalDue',
    formulaEn: 'Σ principalDue',
    descZh: '各期归还本金之和（scale 2）',
    descEn: 'Sum of principal due across periods (scale 2)',
  },
  TOTAL_INTEREST: {
    nameZh: '利息合计',
    nameEn: 'Total Interest',
    formulaZh: 'Σ interestPaid',
    formulaEn: 'Σ interestPaid',
    descZh: '各期实际支付利息之和（scale 2）',
    descEn: 'Sum of interest actually paid across periods (scale 2)',
  },
  TOTAL_FEE: {
    nameZh: '费用合计',
    nameEn: 'Total Fees',
    formulaZh: 'Σ fee',
    formulaEn: 'Σ fee',
    descZh: '各期费用之和（scale 2）',
    descEn: 'Sum of fees across periods (scale 2)',
  },
  TOTAL_TAX: {
    nameZh: '税额合计',
    nameEn: 'Total Tax',
    formulaZh: 'Σ tax',
    formulaEn: 'Σ tax',
    descZh: '各期增值税额之和（scale 2）',
    descEn: 'Sum of VAT across periods (scale 2)',
  },
  TOTAL_RENT: {
    nameZh: '租金合计',
    nameEn: 'Total Rent',
    formulaZh: 'Σ rentDue',
    formulaEn: 'Σ rentDue',
    descZh: '各期租金（本金 + 利息 + 费用 + 税）之和（scale 2）',
    descEn: 'Sum of rent (principal + interest + fee + tax) across periods (scale 2)',
  },
  RESIDUAL_VALUE: {
    nameZh: '残值',
    nameEn: 'Residual Value',
    formulaZh: 'residualValue',
    formulaEn: 'residualValue',
    descZh: '合同约定的留购价/残值，期末按残值处理方式支付（scale 2）',
    descEn: 'The contractual purchase price / residual value, settled at end of term per residual handling (scale 2)',
  },
  RENTAL_RATE: {
    nameZh: '租费率',
    nameEn: 'Rental Rate',
    formulaZh: 'TOTAL_RENT / principal',
    formulaEn: 'TOTAL_RENT / principal',
    descZh: '租金合计与租赁本金之比（scale 6）',
    descEn: 'Total rent divided by the lease principal (scale 6)',
  },
  NPV: {
    nameZh: '净现值',
    nameEn: 'Net Present Value (NPV)',
    formulaZh: 'Σ CF_t / (1+r)^t',
    formulaEn: 'Σ CF_t / (1+r)^t',
    descZh: 'r 取 options.discountRate，缺省用 IRR_PERIOD',
    descEn: 'r comes from options.discountRate, defaulting to IRR_PERIOD',
  },
  XNPV: {
    nameZh: '实际日期净现值',
    nameEn: 'XNPV (date-based NPV)',
    formulaZh: 'Σ CF_i / (1+r)^(days_i/365)',
    formulaEn: 'Σ CF_i / (1+r)^(days_i/365)',
    descZh: '按现金流实际日期天数折现的净现值（scale 2）',
    descEn: 'NPV discounted by actual-day fractions of a 365-day year (scale 2)',
  },
  IRR_PERIOD: {
    nameZh: '期内含报酬率',
    nameEn: 'Periodic IRR',
    formulaZh: '解 Σ CF_t/(1+r)^t = 0',
    formulaEn: 'solve Σ CF_t/(1+r)^t = 0',
    descZh: '使现金流净现值为 0 的每期折现率（二分 + Newton，容差 1e-10）',
    descEn: 'The per-period discount rate making NPV = 0 (bisection + Newton, tolerance 1e-10)',
  },
  IRR_ANNUAL: {
    nameZh: '年化 IRR（复利）',
    nameEn: 'Annual IRR (compounded)',
    formulaZh: '(1 + IRR_PERIOD)^m − 1',
    formulaEn: '(1 + IRR_PERIOD)^m − 1',
    descZh: '按期复利年化，m 为每年期数（periodsPerYear）',
    descEn: 'Compounded annualization; m is the number of periods per year',
  },
  IRR_ANNUAL_SIMPLE: {
    nameZh: '年化 IRR（单利）',
    nameEn: 'Annual IRR (simple)',
    formulaZh: 'IRR_PERIOD × m',
    formulaEn: 'IRR_PERIOD × m',
    descZh: '单利年化，对齐 Excel IRR()*12',
    descEn: 'Simple annualization, matching Excel IRR()*12',
  },
  XIRR: {
    nameZh: '实际日期 IRR',
    nameEn: 'XIRR',
    formulaZh: '解 Σ CF_i/(1+r)^(days_i/365) = 0',
    formulaEn: 'solve Σ CF_i/(1+r)^(days_i/365) = 0',
    descZh: '按实际日期天数折现的内含报酬率，对齐 Excel XIRR()',
    descEn: 'Date-based IRR matching Excel XIRR()',
  },
  MIRR: {
    nameZh: '修正 IRR',
    nameEn: 'Modified IRR (MIRR)',
    formulaZh: '(FV_pos / −PV_neg)^(1/n) − 1',
    formulaEn: '(FV_pos / −PV_neg)^(1/n) − 1',
    descZh: '正现金流按再投资率复利、负现金流按融资率折现（均可配）',
    descEn: 'Positive flows compound at the reinvestment rate, negative at the financing rate (both configurable)',
  },
  PAYBACK_PERIOD: {
    nameZh: '静态回收期（年）',
    nameEn: 'Static Payback Period (years)',
    formulaZh: '累计未折现现金流首次 ≥ 0 的插值点 / m',
    formulaEn: 'interpolation where cumulative undiscounted CF first ≥ 0, / m',
    descZh: '期末仍未回收时为 null',
    descEn: 'null when the flows are not recovered by the final period',
  },
  DISCOUNTED_PAYBACK: {
    nameZh: '动态回收期（年）',
    nameEn: 'Discounted Payback Period (years)',
    formulaZh: '同静态回收期，用折现现金流',
    formulaEn: 'same as static payback, using discounted cash flows',
    descZh: '以折现后现金流计的回收期',
    descEn: 'Payback measured on discounted cash flows',
  },
  DURATION_MACAULAY: {
    nameZh: '麦考利久期（年）',
    nameEn: 'Macaulay Duration (years)',
    formulaZh: 'Σ(t·PV_t) / ΣPV_t',
    formulaEn: 'Σ(t·PV_t) / ΣPV_t',
    descZh: 't 以年计的现金流加权平均回收期限',
    descEn: 'Cash-flow-weighted average recovery term, t in years',
  },
  DURATION_MODIFIED: {
    nameZh: '修正久期',
    nameEn: 'Modified Duration',
    formulaZh: 'D / (1 + y/m)',
    formulaEn: 'D / (1 + y/m)',
    descZh: '麦考利久期对每期收益率的修正',
    descEn: 'Macaulay duration adjusted for the per-period yield',
  },
  CONVEXITY: {
    nameZh: '凸性',
    nameEn: 'Convexity',
    formulaZh: 'Σ t(t+1)·PV_t / ((1+y/m)^2 · ΣPV_t)',
    formulaEn: 'Σ t(t+1)·PV_t / ((1+y/m)^2 · ΣPV_t)',
    descZh: '度量价格-收益曲线的二阶弯曲，单位年²',
    descEn: 'Second-order curvature of the price-yield curve, in years²',
  },
  AVG_OUTSTANDING_BALANCE: {
    nameZh: '加权平均占用本金',
    nameEn: 'Weighted Average Outstanding Balance',
    formulaZh: 'Σ(opening_k × days_k) / Σdays_k',
    formulaEn: 'Σ(opening_k × days_k) / Σdays_k',
    descZh: '按各期计息天数加权的期初余额',
    descEn: 'Opening balances weighted by each period day count',
  },
  TRUE_ANNUAL_RATE: {
    nameZh: '真实年化',
    nameEn: 'True Annual Rate',
    formulaZh: '默认 = XIRR',
    formulaEn: 'defaults to XIRR',
    descZh: '缺省取 XIRR，可配置为单利年化',
    descEn: 'Defaults to XIRR; configurable to simple annualization',
  },
}

/** 指标本地化名称：未知 code 原样返回。 */
export function metricLabel(code: string, locale: AppLocale): string {
  const info = METRIC_CATALOG[code as MetricCode]
  if (!info) return code
  return locale === 'en-US' ? info.nameEn : info.nameZh
}

/** 指标悬浮解释：本地化名称 + 公式 + 一行说明；未知 code 原样返回。 */
export function metricTooltip(code: string, locale: AppLocale): string {
  const info = METRIC_CATALOG[code as MetricCode]
  if (!info) return code
  if (locale === 'en-US') {
    return `${info.nameEn} | Formula: ${info.formulaEn} | ${info.descEn}`
  }
  return `${info.nameZh}｜公式：${info.formulaZh}｜说明：${info.descZh}`
}
