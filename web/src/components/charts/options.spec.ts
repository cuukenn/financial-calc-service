import { describe, expect, it } from 'vitest'

import type { Installment } from '@/api/types'

import { buildBalanceOption, buildCompositionOption } from './options'

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

describe('chart option builders', () => {
  const rows = [
    installment({ periodNo: 1, principalDue: '78971.24', interestAccrued: '5000.00', closingBalance: '921028.76' }),
    installment({ periodNo: 2, principalDue: '79365.40', interestAccrued: '4605.84', closingBalance: '841663.36' }),
  ]

  it('builds the stacked composition series by parsing money strings to numbers', () => {
    const option = buildCompositionOption(rows)

    const [principal, interest] = option.series as { name: string; data: number[] }[]
    expect(principal.data).toEqual([78971.24, 79365.4])
    expect(interest.data).toEqual([5000, 4605.84])
    expect(option.xAxis).toMatchObject({ type: 'category', data: ['第 1 期', '第 2 期'] })
  })

  it('builds the closing-balance line across periods', () => {
    const option = buildBalanceOption(rows)

    const line = (option.series as { name: string; type: string; data: number[] }[])[0]
    expect(line.type).toBe('line')
    expect(line.data).toEqual([921028.76, 841663.36])
  })
})
