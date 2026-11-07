import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { CalculationResult, Installment } from '@/api/types'

vi.mock('echarts', () => ({
  init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() })),
}))

import ResultPanel from './ResultPanel.vue'

function installment(partial: Partial<Installment>): Installment {
  return {
    periodNo: 1,
    startDate: '2024-01-01',
    endDate: '2024-02-01',
    days: 30,
    openingBalance: '1000000.00',
    principalDue: '78971.24',
    interestAccrued: '5000.00',
    interestPaid: '5000.00',
    fee: '0.00',
    tax: '300.00',
    rentDue: '84271.24',
    closingBalance: '921028.76',
    cumulativePrincipal: '78971.24',
    cumulativeInterest: '5000.00',
    appliedRate: '0.005',
    memo: null,
    ...partial,
  }
}

function result(overrides: Partial<CalculationResult['schedule']> = {}): CalculationResult {
  return {
    runId: 'a'.repeat(32),
    schedule: {
      header: {
        periods: 12,
        firstDate: '2024-02-01',
        lastDate: '2025-01-01',
        solver: 'EQUAL_INSTALLMENT',
        metrics: {
          TOTAL_RENT: '1032797.15',
          TOTAL_INTEREST: '392797.15',
          TOTAL_FEE: '0.00',
          TOTAL_TAX: '3600.00',
          RENTAL_RATE: '1.03279715',
          IRR_ANNUAL: '0.0617',
          XIRR: null,
          NPV: null,
        },
        roundingDifference: null,
      },
      installments: [installment({})],
      cashFlows: [],
      issues: [],
      hasErrors: false,
      ...overrides,
    },
    effectiveInterest: null,
  }
}

describe('ResultPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders metric cards verbatim and shows 「—」 for null metrics', () => {
    const wrapper = mount(ResultPanel, { props: { result: result() } })

    const rent = wrapper.find('[data-test="metric-TOTAL_RENT"]')
    expect(rent.text()).toContain('总租金')
    // 金额字符串原样直显（不重排版、不加千分位）。
    expect(rent.text()).toContain('1032797.15')

    const xirr = wrapper.find('[data-test="metric-XIRR"]')
    expect(xirr.text()).toContain('XIRR')
    expect(xirr.text()).toContain('—')
  })

  it('colors issue levels: ERROR red / WARN orange / INFO grey', () => {
    const withIssues = result({
      hasErrors: true,
      issues: [
        { level: 'ERROR', code: 'FCS-CHK-3001', message: '末期本金为负', periodNo: 12 },
        { level: 'WARN', code: 'FCS-CHK-2001', message: '尾差超阈值', periodNo: 12 },
        { level: 'INFO', code: 'FCS-CHK-1001', message: '首期含宽限期', periodNo: null },
      ],
    })
    const wrapper = mount(ResultPanel, { props: { result: withIssues } })

    const tags = wrapper.findAll('[data-test="issue-level"]')
    expect(tags.map((tag) => tag.text())).toEqual(['ERROR', 'WARN', 'INFO'])
    expect(tags[0].attributes('class')).toContain('red')
    expect(tags[1].attributes('class')).toContain('orange')
    // INFO 灰：不带任何彩色修饰类。
    expect(tags[2].attributes('class')).not.toContain('red')
    expect(tags[2].attributes('class')).not.toContain('orange')
  })

  it('renders both charts (composition + balance) fed from the installments', () => {
    const wrapper = mount(ResultPanel, { props: { result: result() } })

    expect(wrapper.find('[data-test="composition-chart"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="balance-chart"]').exists()).toBe(true)
  })
})
