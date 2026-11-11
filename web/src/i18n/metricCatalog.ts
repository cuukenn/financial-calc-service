/**
 * 指标目录（R86）：全部 26 项金融指标的 code → 中文名 / 英文名 / 公式 / 一行说明。
 *
 * 前 21 项对齐设计文档 §9.5 的指标表
 * （docs/superpowers/specs/2026-11-02-financial-calc-service-design.md），不在此发明公式；
 * 末 4 项为 R103 的借款方（承租方）视角家族 BORROWER_*，是展示辅助，不是监管指标，
 * 且仅在 LESSEE 视角的测算中有值（LESSOR 视角下后端返回显式 null，界面显示「—」）。
 * 驱动：
 * - ResultPanel 指标卡 Tooltip 与指标表 hover；
 * - 完整性测试（metricCatalog.spec：25 code × 双语字段）。
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

/** FinancialMetrics 的 26 个指标 code：§9.5 表格顺序，末尾接 R146 内含利率与 R103 借款方视角家族。 */
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
  'LEASE_IMPLICIT_RATE',
  'TRUE_ANNUAL_RATE',
  'BORROWER_IRR_PERIOD',
  'BORROWER_IRR_ANNUAL',
  'BORROWER_IRR_ANNUAL_SIMPLE',
  'BORROWER_TOTAL_REPAYMENT',
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
  LEASE_IMPLICIT_RATE: {
    nameZh: '租赁内含利率',
    nameEn: 'Lease Implicit Rate',
    formulaZh: '求解使 Σ 租赁收款额现值 = 租赁物价值的年利率',
    formulaEn: 'Rate where PV(lease receipts) equals the asset value',
    descZh: 'CAS 21 口径；需填写租赁物价值，未填时为「—」',
    descEn: 'CAS 21 definition; requires the asset value, otherwise shown as —',
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
  // R103 借款方（承租方）视角：展示辅助指标，非监管指标。LESSOR 视角下为 null。
  BORROWER_IRR_PERIOD: {
    nameZh: '借款方期内 IRR',
    nameEn: 'Borrower Periodic IRR',
    formulaZh: '解 Σ CF_t/(1+r)^t = 0（CF 取借款方现金流方向）',
    formulaEn: 'solve Σ CF_t/(1+r)^t = 0 with the borrower-side flow directions',
    descZh: '借款方视角的标注（本金流入、租金流出）：方向整体翻转不移动 NPV 零点，数值恒等于 IRR_PERIOD',
    descEn:
      'Borrower-side label (principal in, rents out): a direction flip leaves NPV zeros in place, so the value always equals IRR_PERIOD',
  },
  BORROWER_IRR_ANNUAL: {
    nameZh: '借款方视角 IRR（年化 复利）',
    nameEn: 'Borrower-side IRR (annual, compounded)',
    formulaZh: '(1 + BORROWER_IRR_PERIOD)^m − 1',
    formulaEn: '(1 + BORROWER_IRR_PERIOD)^m − 1',
    descZh: '与 IRR_ANNUAL 同一个根的年化标注，数值逐位相等；m 为每年期数，不另求根',
    descEn:
      'Annualization of the same root as IRR_ANNUAL, digit-for-digit equal; m is periods per year, no second solve',
  },
  BORROWER_IRR_ANNUAL_SIMPLE: {
    nameZh: '借款方视角 IRR（年化 单利）',
    nameEn: 'Borrower-side IRR (annual, simple)',
    formulaZh: 'BORROWER_IRR_PERIOD × m',
    formulaEn: 'BORROWER_IRR_PERIOD × m',
    descZh: '与 IRR_ANNUAL_SIMPLE 同一个根的单利标注，数值逐位相等（Excel IRR()*12 口径）',
    descEn: 'Simple annualization of the same root as IRR_ANNUAL_SIMPLE, digit-for-digit equal (Excel IRR()*12)',
  },
  BORROWER_TOTAL_REPAYMENT: {
    nameZh: '借款方还款总额',
    nameEn: 'Borrower Total Repayment',
    formulaZh: 'Σ rentDue + 单独结算的留购价',
    formulaEn: 'Σ rentDue + separately settled residual',
    descZh:
      '承租方需偿付的合计（scale 2）：rentDue 已含本金/利息/费用/税，不再重复相加；留购价仅在单独结算时计入，可退还的保证金不计入',
    descEn:
      'Amount the lessee must repay (scale 2); rentDue already contains principal, interest, fee and tax, so they are not added again. The residual counts only when settled separately; a refundable deposit is not a repayment',
  },
}

/** 指标本地化名称：未知 code 原样返回。 */
export function metricLabel(code: string, locale: AppLocale): string {
  const info = METRIC_CATALOG[code as MetricCode]
  if (!info) return code
  return locale === 'en-US' ? info.nameEn : info.nameZh
}

/**
 * R92-4/R92-8 主展示名分类（行业标准名称以原文展示，不得强行翻译）：
 * - RAW_STANDARD：真正通用的行业标准缩写（NPV / XNPV / XIRR / MIRR）——
 *   中文界面下原样展示缩写本身，不译作「净现值」等；
 * - IRR 家族（IRR_PERIOD / IRR_ANNUAL / IRR_ANNUAL_SIMPLE）：家族缩写 IRR 作主文本、
 *   口径限定语作次要后缀（IRR（期间）/ IRR（年化 复利）/ IRR（年化 单利））；
 * - 其余自定义 code（TOTAL_RENT / RESIDUAL_VALUE / PAYBACK_PERIOD / 凸性 / 久期等，
 *   含 DURATION_MACAULAY / DURATION_MODIFIED——「麦考利久期 / 修正久期」即行业标准
 *   中文名）：主文本用目录本地化名，原码作次要小字（R88 行为）。
 * en-US 下目录 nameEn 本就是标准英文名，全部走目录名（不做强行翻译/缩写替换）。
 */
const RAW_STANDARD_METRIC_CODES: ReadonlySet<string> = new Set(['NPV', 'XNPV', 'XIRR', 'MIRR'])

/** IRR 家族：主文本 IRR + 口径限定语（zh 用全角括号，en 用半角括号）。 */
const IRR_FAMILY_LABELS: Record<string, { zh: string; en: string }> = {
  IRR_PERIOD: { zh: 'IRR（期间）', en: 'IRR (periodic)' },
  IRR_ANNUAL: { zh: 'IRR（年化 复利）', en: 'IRR (annual, compounded)' },
  IRR_ANNUAL_SIMPLE: { zh: 'IRR（年化 单利）', en: 'IRR (annual, simple)' },
}

/** 指标主展示名（指标卡与指标表行头共用）。未知 code 原样返回。 */
export function metricPrimaryLabel(code: string, locale: AppLocale): string {
  const family = IRR_FAMILY_LABELS[code]
  if (family) return locale === 'en-US' ? family.en : family.zh
  // en-US：目录英文名即行业标准名（含 NPV/XIRR 等缩写的完整写法），原样使用。
  if (locale === 'en-US') return metricLabel(code, locale)
  if (RAW_STANDARD_METRIC_CODES.has(code)) return code
  return metricLabel(code, locale)
}

/**
 * 是否在主展示名旁附原码次要小字（对账用）：目录内且主文本不是原码本身时附加
 * （RAW_STANDARD 在 zh 下主文本即原码，不重复；IRR 家族与目录名指标附原码）。
 */
export function metricShowsSecondaryCode(code: string, locale: AppLocale): boolean {
  if (!(code in METRIC_CATALOG)) return false
  return metricPrimaryLabel(code, locale) !== code
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
