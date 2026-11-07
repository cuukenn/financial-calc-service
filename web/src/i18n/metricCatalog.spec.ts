import { describe, expect, it } from 'vitest'

import { METRIC_CATALOG, METRIC_CODES, metricLabel, metricTooltip } from './metricCatalog'

/** §9.5 指标表全量 21 项（与设计文档表格一一对应）。 */
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
]

describe('metric catalog (R86)', () => {
  it('covers exactly the 21 §9.5 metric codes, in design-doc order', () => {
    expect(METRIC_CODES).toHaveLength(21)
    expect([...METRIC_CODES]).toEqual(EXPECTED_CODES)
  })

  it('every metric carries name / formula / description in both locales (21 × 6 fields)', () => {
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

  it('passes unknown codes through verbatim (backend may add metrics)', () => {
    expect(metricLabel('FUTURE_METRIC', 'zh-CN')).toBe('FUTURE_METRIC')
    expect(metricTooltip('FUTURE_METRIC', 'en-US')).toBe('FUTURE_METRIC')
  })
})
