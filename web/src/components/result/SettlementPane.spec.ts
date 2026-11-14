import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { Installment } from '@/api/types'
import { setLocale } from '@/i18n'
import { mountWithI18n } from '@/test-utils/mount'

import SettlementPane from './SettlementPane.vue'

/** 与 earlySettlement.spec.ts 同款合成 12 期摊销表（THIRTY_360，期利率 0.005）。 */
function schedule(): Installment[] {
  const rows: Installment[] = []
  for (let i = 1; i <= 12; i++) {
    const opening = 1_000_000 - 80_000 * (i - 1)
    const month = String(i).padStart(2, '0')
    const endMonth = String(i === 12 ? 1 : i + 1).padStart(2, '0')
    const endYear = i === 12 ? '2025' : '2024'
    rows.push({
      periodNo: i,
      startDate: `2024-${month}-01`,
      endDate: `${endYear}-${endMonth}-01`,
      days: 30,
      openingBalance: `${opening}.00`,
      principalDue: '80000.00',
      interestAccrued: `${(opening * 5) / 1000}.00`,
      interestPaid: `${(opening * 5) / 1000}.00`,
      fee: '0.00',
      tax: '0.00',
      rentDue: `${opening * 5 / 1000 + 80_000}.00`,
      closingBalance: `${opening - 80_000}.00`,
      cumulativePrincipal: `${80_000 * i}.00`,
      cumulativeInterest: '0.00',
      appliedRate: '0.005',
      memo: null,
    })
  }
  return rows
}

beforeEach(() => setLocale('zh-CN'))

describe('SettlementPane', () => {
  it('quotes the default settlement date (末期前一期期末) with the §2.5 breakdown', async () => {
    const wrapper = mountWithI18n(SettlementPane, {
      props: { installments: schedule(), dayCountCode: 'THIRTY_360' },
    })
    await vi.waitFor(() => {
      expect(wrapper.find('[data-test="settlement-amount"]').exists()).toBe(true)
    })

    const text = wrapper.find('[data-test="settlement-quote"]').text()
    // 默认结清日 = 第 11 期期末 2024-12-01：剩余本金 200,000、整期利息 1,000、结清 201,000。
    expect(text).toContain('201,000.00')
    expect(text).toContain('200,000.00')
    expect(text).toContain('1,000.00')
    expect(wrapper.find('[data-test="settlement-pane"]').text())
      .toContain('按实际借款期间计息')
  })

  it('hides the overdue-penalty and prepay sections on the lease scenario', () => {
    const wrapper = mountWithI18n(SettlementPane, {
      props: { installments: schedule(), dayCountCode: 'THIRTY_360' },
    })
    expect(wrapper.find('[data-test="settlement-overdue-days"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="settlement-prepay-amount"]').exists()).toBe(false)
  })

  it('emits the prepay payload with the holder info on the mortgage scenario', async () => {
    const wrapper = mountWithI18n(SettlementPane, {
      props: { installments: schedule(), dayCountCode: 'THIRTY_360', scenario: 'mortgage' },
    })
    await vi.waitFor(() => {
      expect(wrapper.find('[data-test="settlement-prepay-amount"]').exists()).toBe(true)
    })
    await wrapper.find('[data-test="settlement-prepay-amount"]').setValue('50000')
    await wrapper.find('[data-test="settlement-prepay-run"]').trigger('click')

    const emitted = wrapper.emitted('prepay')
    expect(emitted).toHaveLength(1)
    const payload = emitted![0]![0] as Record<string, unknown>
    expect(payload.settlementDate).toBe('2024-12-01')
    expect(payload.prepayAmount).toBe('50000')
    // 重算要素取下一期（第 12 期）的期初余额 / 期末日 / 剩余期数 —— 还款日当日办理。
    expect(payload.recalcPrincipal).toBe('120000.00')
    expect(payload.nextRentDate).toBe('2025-01-01')
    expect(payload.remainingPeriods).toBe(1)
  })

  it('shows the invalid notice when nothing is quotable', () => {
    // 「结清日不在租期内 → null」由纯函数 spec 覆盖；这里钉 pane 对 null 的展示分支
    //（空表时报价不可算，显示提示而不显示报价）。
    const wrapper = mountWithI18n(SettlementPane, {
      props: { installments: [], dayCountCode: 'THIRTY_360' },
    })
    expect(wrapper.find('[data-test="settlement-invalid"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="settlement-amount"]').exists()).toBe(false)
  })
})
