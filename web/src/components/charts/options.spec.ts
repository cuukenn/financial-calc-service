import { describe, expect, it } from 'vitest'

import type { Installment } from '@/api/types'

import { buildBalanceOption, buildCompositionOption, type ChartLabels } from './options'

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

/** R85：图表文案由调用方传入（构造器保持纯函数、不含文案）。 */
const zhLabels: ChartLabels = {
  principal: '本金',
  interest: '利息',
  closingBalance: '期末余额',
  periodAxis: '期次',
  amount: '金额',
  periodLabel: (n: number) => `第 ${n} 期`,
}

describe('chart option builders', () => {
  const rows = [
    installment({ periodNo: 1, principalDue: '78971.24', interestAccrued: '5000.00', closingBalance: '921028.76' }),
    installment({ periodNo: 2, principalDue: '79365.40', interestAccrued: '4605.84', closingBalance: '841663.36' }),
  ]

  it('builds the stacked composition series by parsing money strings to numbers', () => {
    const option = buildCompositionOption(rows, zhLabels)

    const [principal, interest] = option.series as { name: string; data: number[] }[]
    expect(principal.name).toBe('本金')
    expect(interest.name).toBe('利息')
    expect(principal.data).toEqual([78971.24, 79365.4])
    expect(interest.data).toEqual([5000, 4605.84])
    expect(option.xAxis).toMatchObject({ type: 'category', data: ['第 1 期', '第 2 期'] })
    expect(option.legend).toMatchObject({ data: ['本金', '利息'] })
  })

  it('builds the closing-balance line across periods', () => {
    const option = buildBalanceOption(rows, zhLabels)

    const line = (option.series as { name: string; type: string; data: number[] }[])[0]
    expect(line.name).toBe('期末余额')
    expect(line.type).toBe('line')
    expect(line.data).toEqual([921028.76, 841663.36])
    expect(option.yAxis).toMatchObject({ name: '期末余额' })
  })

  it('takes localized labels verbatim (R85: locale strings passed in)', () => {
    const option = buildCompositionOption(rows, {
      ...zhLabels,
      principal: 'Principal',
      interest: 'Interest',
    })

    expect(option.legend).toMatchObject({ data: ['Principal', 'Interest'] })
    const [principal] = option.series as { name: string }[]
    expect(principal.name).toBe('Principal')
  })
})
