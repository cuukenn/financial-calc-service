import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '@/api/client'
import type { CalculationRequest, CalculationResult } from '@/api/types'
import { mountWithI18n } from '@/test-utils/mount'
import { activateTab } from '@/test-utils/tabs'
import CalculationForm from './CalculationForm.vue'

vi.mock('@/api/client', () => ({
  ApiError: class ApiError extends Error {
    code: string
    status: number
    constructor(code: string, message: string, status: number) {
      super(message)
      this.code = code
      this.status = status
    }
  },
  calculate: vi.fn(),
}))

// jsdom 无 canvas：ECharts 仅需在挂载时不炸即可，渲染细节由 options 纯函数测试覆盖。
vi.mock('echarts', () => ({
  init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() })),
}))

import { calculate } from '@/api/client'

/** 与后端 CalculationApiTest 黄金结构同源的最小响应（金额均为字符串）。 */
function goldenResult(): CalculationResult {
  return {
    runId: 'a'.repeat(32),
    schedule: {
      header: {
        periods: 12,
        firstDate: '2024-02-01',
        lastDate: '2025-01-01',
        solver: 'EQUAL_INSTALLMENT',
        metrics: { TOTAL_RENT: '1032797.15', TOTAL_INTEREST: '392797.15' },
        roundingDifference: null,
      },
      installments: [
        {
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
        },
      ],
      cashFlows: [],
      issues: [],
      hasErrors: false,
    },
    effectiveInterest: null,
  }
}

function resultWithIssues(hasErrors: boolean): CalculationResult {
  const base = goldenResult()
  base.schedule.hasErrors = hasErrors
  base.schedule.issues = [
    { level: 'WARNING', code: 'FCS-CHK-2001', message: '尾差超过阈值', periodNo: 12 },
  ]
  return base
}

async function submit(wrapper: ReturnType<typeof mountWithI18n>): Promise<void> {
  await wrapper.find('form').trigger('submit')
  await flushPromises()
  await flushPromises()
}

describe('CalculationForm', () => {
  beforeEach(() => {
    vi.mocked(calculate).mockResolvedValue(goldenResult())
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  it('renders the form pre-filled with the golden fixture defaults', () => {
    const wrapper = mountWithI18n(CalculationForm)

    expect((wrapper.find('[data-test="principal"]').element as HTMLInputElement).value).toBe('1000000')
    expect((wrapper.find('[data-test="nominal-rate"]').element as HTMLInputElement).value).toBe('0.06')
    expect((wrapper.find('[data-test="periods"]').element as HTMLInputElement).value).toBe('12')
    expect((wrapper.find('[data-test="commence-date"] input').element as HTMLInputElement).value).toBe('2024-01-01')
    expect((wrapper.find('[data-test="first-rent-date"] input').element as HTMLInputElement).value).toBe('2024-02-01')
    // 后端必填的口径/方式字段始终有值（Task 1 评审发现）。
    expect(wrapper.find('[data-test="day-count-code"]').text()).toContain('THIRTY_360')
    expect(wrapper.find('[data-test="date-roll-code"]').text()).toContain('NONE')
    const methodRadio = wrapper.find('input[value="EQUAL_INSTALLMENT"]')
    expect((methodRadio.element as HTMLInputElement).checked).toBe(true)
  })

  it('submits a correctly-shaped CalculationRequest on golden defaults', async () => {
    const wrapper = mountWithI18n(CalculationForm)
    await submit(wrapper)

    expect(calculate).toHaveBeenCalledTimes(1)
    const req = vi.mocked(calculate).mock.calls[0][0] as CalculationRequest
    expect(req.principal).toBe('1000000')
    expect(req.nominalRate).toBe('0.06')
    expect(req.commenceDate).toBe('2024-01-01')
    expect(req.firstRentDate).toBe('2024-02-01')
    expect(req.periods).toBe(12)
    expect(req.frequency).toBe('MONTHLY')
    expect(req.timing).toBe('IN_ARREARS')
    expect(req.currency).toBe('CNY')
    expect(req.dayCountCode).toBe('THIRTY_360')
    expect(req.dateRollCode).toBe('NONE')
    expect(req.rateChangePolicy).toBe('RESOLVE')
    expect(req.method).toBe('EQUAL_INSTALLMENT')
    expect(req.residualValue).toBe('0')
    expect(req.residualHandling).toBe('SEPARATE')
    expect(req.deposit).toBe('0')
    expect(req.vatRate).toBe('0.06')
    expect(req).not.toHaveProperty('ruleRows')
    expect(req).not.toHaveProperty('explicitRents')
    expect(req).not.toHaveProperty('gracePeriod')
    expect(req).not.toHaveProperty('fees')
  })

  it('surfaces the backend FCS code verbatim in an error alert', async () => {
    vi.mocked(calculate).mockRejectedValue(
      new ApiError('FCS-DOM-1001', '租赁本金必须为正数', 400),
    )
    const wrapper = mountWithI18n(CalculationForm)
    await submit(wrapper)

    const alert = wrapper.find('[data-test="error-alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('FCS-DOM-1001')
    expect(alert.text()).toContain('租赁本金必须为正数')
    expect(wrapper.find('[data-test="result-card"]').exists()).toBe(false)
  })

  it('renders header metrics, installments and hides the error alert on success', async () => {
    const wrapper = mountWithI18n(CalculationForm)
    await submit(wrapper)

    // Task 3：指标升级为 Statistic 卡（金额字符串原样直显，位于默认「指标」Tab）。
    const cards = wrapper.findAll('[data-test="metric-card"]')
    expect(cards.length).toBeGreaterThanOrEqual(1)
    expect(wrapper.find('[data-test="metric-TOTAL_RENT"]').text()).toContain('1032797.15')

    // Plan 4 Task 1：摊销计划移入同名 Tab（懒渲染，须先激活）。
    await activateTab(wrapper, '摊销计划')
    const rows = wrapper.find('[data-test="installments-table"]').findAll('.ant-table-row')
    expect(rows.length).toBeGreaterThanOrEqual(1)
    expect(rows[0].text()).toContain('84271.24')
    expect(wrapper.find('[data-test="has-errors-alert"]').exists()).toBe(false)
  })

  it('groups the form fields into the three compact block cards (R84)', () => {
    const wrapper = mountWithI18n(CalculationForm)

    // 三个大块卡片均存在。
    const titles = wrapper.findAll('.ant-card-head-title').map((t) => t.text())
    expect(titles).toContain('基础要素')
    expect(titles).toContain('租金结构')
    expect(titles).toContain('费用与其他')

    // 基础要素：基础字段 + 口径 + 方式 radio。
    const basic = wrapper.find('[data-test="card-basic"]')
    expect(basic.find('[data-test="principal"]').exists()).toBe(true)
    expect(basic.find('[data-test="day-count-code"]').exists()).toBe(true)
    expect(basic.find('input[value="EQUAL_INSTALLMENT"]').exists()).toBe(true)

    // 租金结构：规则行 + 宽限期（显式租金按方式联动出现）。
    const rentStructure = wrapper.find('[data-test="card-rent-structure"]')
    expect(rentStructure.find('[data-test="rule-rows-editor"]').exists()).toBe(true)
    expect(rentStructure.find('[data-test="interest-only-periods"]').exists()).toBe(true)
    expect(rentStructure.find('[data-test="rents-editor"]').exists()).toBe(false)

    // 费用与其他：费用编辑器 + 残值/保证金/税率。
    const feesOther = wrapper.find('[data-test="card-fees-other"]')
    expect(feesOther.find('[data-test="fee-add"]').exists()).toBe(true)
    expect(feesOther.find('[data-test="residual-value"]').exists()).toBe(true)
    expect(feesOther.find('[data-test="vat-rate"]').exists()).toBe(true)
  })

  it('shows the cash-flow tab as a placeholder after a successful calculation', async () => {
    const wrapper = mountWithI18n(CalculationForm)
    await submit(wrapper)

    await activateTab(wrapper, '现金流')
    expect(wrapper.find('[data-test="cashflow-empty"]').exists()).toBe(true)
  })

  it('lists issues and shows the warning alert when the schedule hasErrors', async () => {
    vi.mocked(calculate).mockResolvedValue(resultWithIssues(true))
    const wrapper = mountWithI18n(CalculationForm)
    await submit(wrapper)

    expect(wrapper.find('[data-test="has-errors-alert"]').exists()).toBe(true)
    const issues = wrapper.find('[data-test="issues-list"]')
    expect(issues.text()).toContain('FCS-CHK-2001')
    expect(issues.text()).toContain('尾差超过阈值')
  })

  it('gates the submit when a required field is cleared', async () => {
    const wrapper = mountWithI18n(CalculationForm)
    await wrapper.find('[data-test="principal"]').setValue('')
    await submit(wrapper)

    expect(calculate).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请输入租赁本金')
  })

  it('shows the explicit rents editor sized by periods when EXPLICIT_RENT is selected', async () => {
    const wrapper = mountWithI18n(CalculationForm)
    await wrapper.find('input[value="EXPLICIT_RENT"]').setValue()
    await flushPromises()

    const editor = wrapper.find('[data-test="rents-editor"]')
    expect(editor.exists()).toBe(true)
    expect(wrapper.findAll('[data-test="rent-input"]')).toHaveLength(12)
  })

  it('resizes the explicit rents list when periods changes', async () => {
    const wrapper = mountWithI18n(CalculationForm)
    await wrapper.find('input[value="EXPLICIT_RENT"]').setValue()
    await wrapper.find('[data-test="periods"]').setValue('3')
    await flushPromises()

    expect(wrapper.findAll('[data-test="rent-input"]')).toHaveLength(3)
  })

  it('fills the segmented-rate preset rows when the preset button is clicked', async () => {
    const wrapper = mountWithI18n(CalculationForm)
    await wrapper.find('[data-test="rule-preset"]').trigger('click')
    await flushPromises()

    const rows = wrapper.findAll('[data-test="rule-row"]')
    expect(rows).toHaveLength(2)
    const rates = wrapper.findAll('[data-test="rule-annual-rate"]').map(
      (input) => (input.element as HTMLInputElement).value,
    )
    expect(rates).toEqual(['0.03', '0.06'])
  })

  it('sends the rule rows on submit when present (built-in method + segmented rates)', async () => {
    const wrapper = mountWithI18n(CalculationForm)
    await wrapper.find('[data-test="rule-preset"]').trigger('click')
    await submit(wrapper)

    expect(calculate).toHaveBeenCalledTimes(1)
    const req = vi.mocked(calculate).mock.calls[0][0] as CalculationRequest
    expect(req.ruleRows).toEqual([
      {
        ruleId: 'R1',
        priority: 10,
        fromPeriod: 1,
        toPeriod: 6,
        annualRate: '0.03',
        principalPlan: 'INHERIT',
        interestTreatment: 'PAY_AS_INCURRED',
        paymentTiming: 'IN_ARREARS',
        specialAction: 'NONE',
      },
      {
        ruleId: 'R2',
        priority: 20,
        fromPeriod: 7,
        toPeriod: 12,
        annualRate: '0.06',
        principalPlan: 'INHERIT',
        interestTreatment: 'PAY_AS_INCURRED',
        paymentTiming: 'IN_ARREARS',
        specialAction: 'NONE',
      },
    ])
  })

  it('keeps the optional rule-rows editor available for built-in methods', () => {
    const wrapper = mountWithI18n(CalculationForm)

    const editor = wrapper.find('[data-test="rule-rows-editor"]')
    expect(editor.exists()).toBe(true)
    expect(editor.text()).toContain('可选：分段利率')
    const methodRadio = wrapper.find('input[value="EQUAL_INSTALLMENT"]')
    expect((methodRadio.element as HTMLInputElement).checked).toBe(true)
  })

  it('shows the rule-rows editor as the primary editor for RULE_DRIVEN', async () => {
    const wrapper = mountWithI18n(CalculationForm)
    await wrapper.find('input[value="RULE_DRIVEN"]').setValue()
    await flushPromises()

    expect(wrapper.find('[data-test="rule-rows-editor"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="rents-editor"]').exists()).toBe(false)
  })

  it('derives the preset button label from the actual periods (10 → 1-5/6-10)', async () => {
    const wrapper = mountWithI18n(CalculationForm)
    expect(wrapper.find('[data-test="rule-preset"]').text()).toContain(
      '1-6 期 @3%，7-12 期 @6%',
    )

    await wrapper.find('[data-test="periods"]').setValue('10')
    await flushPromises()

    expect(wrapper.find('[data-test="rule-preset"]').text()).toContain('1-5 期 @3%，6-10 期 @6%')
  })

  it('assigns unique ruleIds when adding rows after a removal', async () => {
    const wrapper = mountWithI18n(CalculationForm)

    await wrapper.find('[data-test="rule-add"]').trigger('click')
    await wrapper.find('[data-test="rule-add"]').trigger('click')
    // 删除第一行（R1），保留 R2。
    await wrapper.findAll('[data-test="rule-row"] button')[0].trigger('click')
    await wrapper.find('[data-test="rule-add"]').trigger('click')
    await flushPromises()

    const ids = wrapper
      .findAll('[data-test="rule-row"] input')
      .filter((input) => (input.element as HTMLInputElement).placeholder === '规则 ID')
      .map((input) => (input.element as HTMLInputElement).value)
    expect(ids).toEqual(['R2', 'R3'])
  })
})
