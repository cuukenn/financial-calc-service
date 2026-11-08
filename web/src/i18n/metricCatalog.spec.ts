import { describe, expect, it } from 'vitest'

import {
  METRIC_CATALOG,
  METRIC_CODES,
  metricLabel,
  metricPrimaryLabel,
  metricShowsSecondaryCode,
  metricTooltip,
} from './metricCatalog'

/** 全量 25 项：§9.5 指标表 21 项（与设计文档表格一一对应）+ R103 借款方视角家族 4 项。 */
const EXPECTED_CODES = [
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
  'BORROWER_IRR_PERIOD',
  'BORROWER_IRR_ANNUAL',
  'BORROWER_IRR_ANNUAL_SIMPLE',
  'BORROWER_TOTAL_REPAYMENT',
]

describe('metric catalog (R86)', () => {
  it('covers exactly the 25 metric codes: §9.5 table order, then the R103 borrower family', () => {
    expect(METRIC_CODES).toHaveLength(25)
    expect([...METRIC_CODES]).toEqual(EXPECTED_CODES)
  })

  it('every metric carries name / formula / description in both locales (25 × 6 fields)', () => {
    const fields = ['nameZh', 'nameEn', 'formulaZh', 'formulaEn', 'descZh', 'descEn'] as const
    for (const code of EXPECTED_CODES) {
      const info = METRIC_CATALOG[code as keyof typeof METRIC_CATALOG]
      expect(info, `${code} missing from catalog`).toBeTruthy()
      for (const field of fields) {
        expect(info[field]?.trim(), `${code}.${field} must be non-empty`).toBeTruthy()
      }
    }
    // 目录不包含多余的未知 code。
    expect(Object.keys(METRIC_CATALOG).sort()).toEqual([...EXPECTED_CODES].sort())
  })

  it('builds localized labels for metric cards', () => {
    expect(metricLabel('TOTAL_RENT', 'zh-CN')).toBe('租金合计')
    expect(metricLabel('TOTAL_RENT', 'en-US')).toBe('Total Rent')
    expect(metricLabel('IRR_ANNUAL', 'zh-CN')).toContain('年化 IRR')
  })

  it('builds tooltips with name + formula + description (R86 hover content)', () => {
    const zh = metricTooltip('IRR_PERIOD', 'zh-CN')
    expect(zh).toContain('期内含报酬率')
    expect(zh).toContain('公式')
    expect(zh).toContain('Σ CF_t/(1+r)^t = 0')
    expect(zh).toContain('说明')
    expect(zh).toContain('净现值为 0')

    const en = metricTooltip('XIRR', 'en-US')
    expect(en).toContain('XIRR')
    expect(en).toContain('Formula')
    expect(en).toContain('days_i/365')
  })

  it('labels the borrower family without reusing the lease-internal-rate terminology (R103)', () => {
    // AGENTS.md §2.1：借款方视角指标不得复用「租赁内含利率 / 增量借款利率」等规范名。
    for (const code of [
      'BORROWER_IRR_PERIOD',
      'BORROWER_IRR_ANNUAL',
      'BORROWER_IRR_ANNUAL_SIMPLE',
      'BORROWER_TOTAL_REPAYMENT',
    ] as const) {
      const zh = metricLabel(code, 'zh-CN')
      expect(zh, `${code} zh label`).toContain('借款方')
      expect(zh).not.toContain('租赁内含利率')
      expect(zh).not.toContain('增量借款利率')
      expect(metricLabel(code, 'en-US')).toContain('Borrower')
    }
    expect(metricTooltip('BORROWER_TOTAL_REPAYMENT', 'zh-CN')).toContain('Σ rentDue')
  })

  it('passes unknown codes through verbatim (backend may add metrics)', () => {
    expect(metricLabel('FUTURE_METRIC', 'zh-CN')).toBe('FUTURE_METRIC')
    expect(metricTooltip('FUTURE_METRIC', 'en-US')).toBe('FUTURE_METRIC')
  })
})

describe('metric primary display classification (R92-4/R92-8)', () => {
  it('shows genuinely standard abbreviations raw in zh-CN (no forced translation)', () => {
    expect(metricPrimaryLabel('NPV', 'zh-CN')).toBe('NPV')
    expect(metricPrimaryLabel('XNPV', 'zh-CN')).toBe('XNPV')
    expect(metricPrimaryLabel('XIRR', 'zh-CN')).toBe('XIRR')
    expect(metricPrimaryLabel('MIRR', 'zh-CN')).toBe('MIRR')
    // 不再出现「实际日期 XIRR」这类强行翻译的主标签。
    expect(metricPrimaryLabel('XIRR', 'zh-CN')).not.toContain('实际日期')
  })

  it('shows the IRR family as the family abbreviation with a qualifier', () => {
    expect(metricPrimaryLabel('IRR_PERIOD', 'zh-CN')).toBe('IRR（期间）')
    expect(metricPrimaryLabel('IRR_ANNUAL', 'zh-CN')).toBe('IRR（年化 复利）')
    expect(metricPrimaryLabel('IRR_ANNUAL_SIMPLE', 'zh-CN')).toBe('IRR（年化 单利）')
    expect(metricPrimaryLabel('IRR_PERIOD', 'en-US')).toBe('IRR (periodic)')
    expect(metricPrimaryLabel('IRR_ANNUAL', 'en-US')).toBe('IRR (annual, compounded)')
  })

  it('shows custom-code metrics by the catalog localized name (zh-CN)', () => {
    expect(metricPrimaryLabel('TOTAL_RENT', 'zh-CN')).toBe('租金合计')
    expect(metricPrimaryLabel('TOTAL_PRINCIPAL', 'zh-CN')).toBe('本金合计')
    expect(metricPrimaryLabel('RESIDUAL_VALUE', 'zh-CN')).toBe('残值')
    expect(metricPrimaryLabel('PAYBACK_PERIOD', 'zh-CN')).toBe('静态回收期（年）')
    expect(metricPrimaryLabel('CONVEXITY', 'zh-CN')).toBe('凸性')
    expect(metricPrimaryLabel('TRUE_ANNUAL_RATE', 'zh-CN')).toBe('真实年化')
    // 久期：目录中文名（麦考利久期/修正久期）即行业标准中文术语，仍作主文本。
    expect(metricPrimaryLabel('DURATION_MACAULAY', 'zh-CN')).toBe('麦考利久期（年）')
    expect(metricPrimaryLabel('DURATION_MODIFIED', 'zh-CN')).toBe('修正久期')
  })

  it('keeps standard English catalog names as the primary display in en-US', () => {
    expect(metricPrimaryLabel('NPV', 'en-US')).toBe('Net Present Value (NPV)')
    expect(metricPrimaryLabel('XIRR', 'en-US')).toBe('XIRR')
    expect(metricPrimaryLabel('TOTAL_RENT', 'en-US')).toBe('Total Rent')
    expect(metricPrimaryLabel('DURATION_MACAULAY', 'en-US')).toBe('Macaulay Duration (years)')
  })

  it('attaches the secondary raw code only when the primary text is not the code itself', () => {
    // 标准缩写在 zh 下主文本即原码：不重复显示次要原码。
    expect(metricShowsSecondaryCode('NPV', 'zh-CN')).toBe(false)
    expect(metricShowsSecondaryCode('XIRR', 'zh-CN')).toBe(false)
    // IRR 家族与目录名指标附原码（对账用）。
    expect(metricShowsSecondaryCode('IRR_PERIOD', 'zh-CN')).toBe(true)
    expect(metricShowsSecondaryCode('TOTAL_RENT', 'zh-CN')).toBe(true)
    expect(metricShowsSecondaryCode('DURATION_MACAULAY', 'zh-CN')).toBe(true)
    // en-US 主文本是目录英文名（非原码）时附原码。
    expect(metricShowsSecondaryCode('NPV', 'en-US')).toBe(true)
    // 目录外 code 原样透出，不重复显示。
    expect(metricShowsSecondaryCode('CUSTOM_METRIC', 'zh-CN')).toBe(false)
  })

  it('passes unknown codes through verbatim as the primary label', () => {
    expect(metricPrimaryLabel('FUTURE_METRIC', 'zh-CN')).toBe('FUTURE_METRIC')
    expect(metricPrimaryLabel('FUTURE_METRIC', 'en-US')).toBe('FUTURE_METRIC')
  })
})
