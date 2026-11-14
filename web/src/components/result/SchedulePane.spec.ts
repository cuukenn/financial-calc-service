import { describe, expect, it, vi } from 'vitest'

// EChart 会真的 init 一个 canvas：结果区的图表与分页无关，mock 掉即可。
vi.mock('echarts/core', () => ({
  use: vi.fn(),
  init: () => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() }),
}))

import type { Installment } from '@/api/types'
import { mountWithI18n } from '@/test-utils/mount'

import SchedulePane from './SchedulePane.vue'

function installment(periodNo: number): Installment {
  return {
    periodNo,
    startDate: `2024-01-${String(periodNo).padStart(2, '0')}`,
    endDate: `2024-02-${String(periodNo).padStart(2, '0')}`,
    days: 30,
    openingBalance: '1000.00',
    principalDue: '100.00',
    interestAccrued: '5.00',
    interestPaid: '5.00',
    fee: '0.00',
    tax: '0.00',
    rentDue: '105.00',
    closingBalance: '900.00',
    cumulativePrincipal: '100.00',
    cumulativeInterest: '5.00',
    appliedRate: '0.005',
    memo: null,
  }
}

/**
 * D-6：摊销计划表默认每页 12 行 —— 360 期的一次测算不再把 360 行同时挂载。
 */
describe('SchedulePane pagination (D-6)', () => {
  const rows = Array.from({ length: 30 }, (_, index) => installment(index + 1))

  it('renders only the first 12 of 30 rows and exposes paging', () => {
    const wrapper = mountWithI18n(SchedulePane, { props: { installments: rows } })

    const bodyRows = wrapper.findAll('[data-test="installments-table"] .ant-table-tbody tr.ant-table-row')
    expect(bodyRows).toHaveLength(12)
    expect(wrapper.find('.ant-pagination').exists()).toBe(true)
    // 分页配置是模块级常量：12 行一页。
    expect(wrapper.find('.ant-pagination-item-2').exists()).toBe(true)
  })

  it('pages through the remainder 12 rows at a time', async () => {
    const wrapper = mountWithI18n(SchedulePane, { props: { installments: rows } })

    await wrapper.find('.ant-pagination-item-2').trigger('click')
    let bodyRows = wrapper.findAll('[data-test="installments-table"] .ant-table-tbody tr.ant-table-row')
    expect(bodyRows).toHaveLength(12)
    expect(bodyRows[0].text()).toContain('13')

    // 30 行 → 第 3 页只剩 6 行。
    await wrapper.find('.ant-pagination-item-3').trigger('click')
    bodyRows = wrapper.findAll('[data-test="installments-table"] .ant-table-tbody tr.ant-table-row')
    expect(bodyRows).toHaveLength(6)
    expect(bodyRows[0].text()).toContain('25')
  })
})
