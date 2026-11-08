import { flushPromises } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { CalculationRequest, CalculationResult } from '@/api/types'
import App from '@/App.vue'
import MortgageForm from '@/components/MortgageForm.vue'
import { setLocale } from '@/i18n'
import { mountWithI18n } from '@/test-utils/mount'
import { activateTab } from '@/test-utils/tabs'

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

// jsdom 无 canvas：ECharts 仅需在挂载时不炸即可。
vi.mock('echarts', () => ({
  init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() })),
}))

import { calculate } from '@/api/client'

/** 最小房贷响应：只关心 rentDue（月供/DTI 的输入），其余列给稳定占位值。 */
function mortgageResult(rents: string[]): CalculationResult {
  return {
    runId: 'b'.repeat(32),
    schedule: {
      header: {
        periods: rents.length,
        firstDate: '2026-12-01',
        lastDate: '2027-01-01',
        solver: 'EQUAL_INSTALLMENT',
        metrics: { TOTAL_RENT: '96000.00', TOTAL_INTEREST: '16000.00' },
        roundingDifference: null,
      },
      installments: rents.map((rent, index) => ({
        periodNo: index + 1,
        startDate: '2026-11-01',
        endDate: '2026-12-01',
        days: 30,
        openingBalance: '1000000.00',
        principalDue: '0.00',
        interestAccrued: '0.00',
        interestPaid: '0.00',
        fee: '0.00',
        tax: '0.00',
        rentDue: rent,
        closingBalance: '1000000.00',
        cumulativePrincipal: '0.00',
        cumulativeInterest: '0.00',
        appliedRate: '0.0029166667',
        memo: null,
      })),
      cashFlows: [],
      issues: [],
      hasErrors: false,
    },
    effectiveInterest: null,
  }
}

async function submit(wrapper: VueWrapper): Promise<void> {
  await wrapper.find('form').trigger('submit')
  await flushPromises()
  await flushPromises()
}

function lastRequest(): CalculationRequest {
  const calls = vi.mocked(calculate).mock.calls
  return calls[calls.length - 1]![0] as CalculationRequest
}

/**
 * 场景面板的可见性语义 = 是否在场：Tabs 用 destroyInactiveTabPane，
 * 非活动场景的面板整体卸载，所以用 exists() 断言，而不是 jsdom 里
 * 跨显示/隐藏周期后会留下过期 computed style 的 isVisible()。
 */
function panelPresent(wrapper: VueWrapper, selector: string): boolean {
  return wrapper.find(selector).exists()
}

/** 归还给引擎的房贷请求只允许这 11 个键（§3），其余租赁专属键必须缺席。 */
const MORTGAGE_KEYS = [
  'principal',
  'nominalRate',
  'commenceDate',
  'firstRentDate',
  'periods',
  'frequency',
  'timing',
  'dayCountCode',
  'dateRollCode',
  'currency',
  'method',
]

const ABSENT_LEASE_ONLY_KEYS = [
  'residualValue',
  'residualHandling',
  'deposit',
  'fees',
  'ruleRows',
  'explicitRents',
  'explicitPeriods',
  'gracePeriod',
  'rateChangePolicy',
  'allowPartialRuleSet',
  'rounding',
  'vatRate',
]

describe('scenario tabs (R100: one page, one engine contract)', () => {
  it('offers exactly the two scenario tabs named from i18n keys', () => {
    const wrapper = mountWithI18n(App)

    expect(wrapper.find('[data-test="scenario-tabs"]').exists()).toBe(true)
    expect(wrapper.findAll('[role="tab"]').map((tab) => tab.text())).toEqual([
      '融资租赁',
      '房贷计算器',
    ])
  })

  it('renders only the active scenario panel and restores the lease form on the way back', async () => {
    const wrapper = mountWithI18n(App)

    // 默认场景 = 融资租赁：现有测算表单在场，房贷面板尚未渲染。
    expect(panelPresent(wrapper, '[data-test="scenario-lease"]')).toBe(true)
    expect(panelPresent(wrapper, '[data-test="calc-form-card"]')).toBe(true)
    expect(panelPresent(wrapper, '[data-test="scenario-mortgage"]')).toBe(false)
    expect(panelPresent(wrapper, '[data-test="mortgage-form-card"]')).toBe(false)

    await activateTab(wrapper, '房贷计算器')

    // 房贷面板在场；租赁专属表单连同其字段（租赁本金等）整体离场。
    expect(panelPresent(wrapper, '[data-test="scenario-mortgage"]')).toBe(true)
    expect(panelPresent(wrapper, '[data-test="mortgage-form-card"]')).toBe(true)
    expect(panelPresent(wrapper, '[data-test="calc-form-card"]')).toBe(false)
    expect(panelPresent(wrapper, '[data-test="principal"]')).toBe(false)

    // 切回融资租赁：原表单重新在场，房贷面板离场。
    await activateTab(wrapper, '融资租赁')
    expect(panelPresent(wrapper, '[data-test="scenario-lease"]')).toBe(true)
    expect(panelPresent(wrapper, '[data-test="calc-form-card"]')).toBe(true)
    expect(panelPresent(wrapper, '[data-test="principal"]')).toBe(true)
    expect(panelPresent(wrapper, '[data-test="mortgage-form-card"]')).toBe(false)
  })
})

describe('MortgageForm payload mapping (R101)', () => {
  beforeEach(() => {
    vi.mocked(calculate).mockResolvedValue(mortgageResult(['8000.00', '8000.00']))
  })

  afterEach(() => {
    vi.clearAllMocks()
    setLocale('zh-CN')
  })

  it('submits exactly the 11 mortgage keys and omits every lease-only key', async () => {
    const wrapper = mountWithI18n(MortgageForm)
    await submit(wrapper)

    expect(calculate).toHaveBeenCalledTimes(1)
    const req = lastRequest()

    expect(req).toEqual({
      principal: '2380000',
      nominalRate: '0.035',
      commenceDate: '2026-11-01',
      firstRentDate: '2026-12-01',
      periods: 360,
      frequency: 'MONTHLY',
      timing: 'IN_ARREARS',
      dayCountCode: 'THIRTY_360',
      dateRollCode: 'NONE',
      currency: 'CNY',
      method: 'EQUAL_INSTALLMENT',
    })
    expect(Object.keys(req).sort()).toEqual([...MORTGAGE_KEYS].sort())
    for (const key of ABSENT_LEASE_ONLY_KEYS) {
      expect(req, `lease-only key ${key} must be absent`).not.toHaveProperty(key)
    }
  })

  it('composes LPR + 加点 into the nominal rate and shows the number that is sent', async () => {
    const wrapper = mountWithI18n(MortgageForm)

    // 默认 LPR 3.5 + 加点 0 → 0.035（3.50%）。
    expect(wrapper.find('[data-test="mortgage-composed-rate"]').text()).toContain('0.035')
    expect(wrapper.find('[data-test="mortgage-composed-rate"]').text()).toContain('3.50%')

    await wrapper.find('[data-test="mortgage-spread"]').setValue('-30')
    await flushPromises()
    expect(wrapper.find('[data-test="mortgage-composed-rate"]').text()).toContain('0.032')

    await submit(wrapper)
    expect(lastRequest().nominalRate).toBe('0.032')

    vi.mocked(calculate).mockClear()
    await wrapper.find('[data-test="mortgage-spread"]').setValue('35')
    await flushPromises()
    await submit(wrapper)
    expect(lastRequest().nominalRate).toBe('0.0385')
  })

  it('rejects a 加点 that is not a multiple of 5bp instead of silently rounding it (P-04)', async () => {
    const wrapper = mountWithI18n(MortgageForm)
    await wrapper.find('[data-test="mortgage-spread"]').setValue('12')
    await submit(wrapper)

    expect(calculate).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('5bp')

    // 15bp 是合法步长：放行。
    await wrapper.find('[data-test="mortgage-spread"]').setValue('15')
    await submit(wrapper)
    expect(calculate).toHaveBeenCalledTimes(1)
    expect(lastRequest().nominalRate).toBe('0.0365')
  })
})

describe('MortgageForm 还款方式 gate (R109: ≤ 12 期 才可选到期一次还本付息)', () => {
  beforeEach(() => {
    vi.mocked(calculate).mockResolvedValue(mortgageResult(['1010000.00']))
  })

  afterEach(() => {
    vi.clearAllMocks()
    setLocale('zh-CN')
  })

  it('offers BULLET_LOAN at 12 months and submits it', async () => {
    const wrapper = mountWithI18n(MortgageForm)
    await wrapper.find('[data-test="mortgage-periods"]').setValue('12')
    await flushPromises()

    const bulletInput = wrapper.find('input[value="BULLET_LOAN"]')
    expect((bulletInput.element as HTMLInputElement).disabled).toBe(false)

    await bulletInput.setValue()
    await flushPromises()
    await submit(wrapper)

    expect(lastRequest().method).toBe('BULLET_LOAN')
  })

  it('disables BULLET_LOAN at 13 months, resets a prior selection and never sends it', async () => {
    const wrapper = mountWithI18n(MortgageForm)
    await wrapper.find('[data-test="mortgage-periods"]').setValue('12')
    await flushPromises()
    await wrapper.find('input[value="BULLET_LOAN"]').setValue()
    await flushPromises()

    await wrapper.find('[data-test="mortgage-periods"]').setValue('13')
    await flushPromises()

    const bulletInput = wrapper.find('input[value="BULLET_LOAN"]')
    expect((bulletInput.element as HTMLInputElement).disabled).toBe(true)
    // 选择被重置为等额本息，并有内联说明（该门槛是前端规则，不是后端校验）。
    expect((wrapper.find('input[value="EQUAL_INSTALLMENT"]').element as HTMLInputElement).checked).toBe(
      true,
    )
    expect(wrapper.find('[data-test="bullet-reset-alert"]').text()).toContain('12')

    await submit(wrapper)
    expect(calculate).toHaveBeenCalledTimes(1)
    expect(lastRequest().method).toBe('EQUAL_INSTALLMENT')
    expect(lastRequest().periods).toBe(13)
    for (const [req] of vi.mocked(calculate).mock.calls) {
      expect((req as CalculationRequest).method).not.toBe('BULLET_LOAN')
    }
  })
})

describe('MortgageForm 期限 cap (R107: UI-only 480 months, engine stays 1200)', () => {
  beforeEach(() => {
    vi.mocked(calculate).mockResolvedValue(mortgageResult(['8000.00']))
  })

  afterEach(() => {
    vi.clearAllMocks()
    setLocale('zh-CN')
  })

  it('accepts 480 months (40 years)', async () => {
    const wrapper = mountWithI18n(MortgageForm)
    await wrapper.find('[data-test="mortgage-periods"]').setValue('480')
    await submit(wrapper)

    expect(calculate).toHaveBeenCalledTimes(1)
    expect(lastRequest().periods).toBe(480)
  })

  it('never lets 481 months reach the engine (InputNumber max commits the cap)', async () => {
    const wrapper = mountWithI18n(MortgageForm)
    await wrapper.find('[data-test="mortgage-periods"]').setValue('481')
    // AntD InputNumber 不在输入过程中提交越界值，blur（真实浏览器里点提交按钮必先 blur）时
    // 按 max 归位：481 被夹回 480，越界期数不可能进入 payload。
    await wrapper.find('[data-test="mortgage-periods"]').trigger('blur')
    await flushPromises()
    await submit(wrapper)

    expect(calculate).toHaveBeenCalledTimes(1)
    expect(lastRequest().periods).toBe(480)
    for (const [req] of vi.mocked(calculate).mock.calls) {
      expect((req as CalculationRequest).periods).toBeLessThanOrEqual(480)
    }
  })

  it('blocks submit when 期限 is emptied (form rule backstop names the 480 cap)', async () => {
    const wrapper = mountWithI18n(MortgageForm)
    await wrapper.find('[data-test="mortgage-periods"]').setValue('')
    await flushPromises()
    await submit(wrapper)

    expect(calculate).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('480')
  })

  it('never lets a pasted 481 期限 reach the engine (R107: UI-only cap)', async () => {
    const wrapper = mountWithI18n(MortgageForm)
    // 粘贴/脚本写入的 481 没有 blur，InputNumber 不提交越界值（模型停留在上一个
    // 合法值），表单规则是第二道防线 —— 481 进不了 payload，也就到不了引擎
    // （引擎上限仍是 LeaseElement.MAX_PERIODS = 1200，本任务不碰后端）。
    await wrapper.find('[data-test="mortgage-periods"]').setValue('481')
    await flushPromises()
    await submit(wrapper)

    expect(calculate).toHaveBeenCalledTimes(1)
    for (const [req] of vi.mocked(calculate).mock.calls) {
      expect((req as CalculationRequest).periods).toBeLessThanOrEqual(480)
    }
  })
})

describe('MortgageForm 首付 / LTV gate (AGENTS.md §2.3: 首付 ≥ 15%)', () => {
  beforeEach(() => {
    vi.mocked(calculate).mockResolvedValue(mortgageResult(['8000.00']))
  })

  afterEach(() => {
    vi.clearAllMocks()
    setLocale('zh-CN')
  })

  it('uses min(成交价, 评估价) and shows LTV 85.00% / 首付 15.00%', () => {
    const wrapper = mountWithI18n(MortgageForm)

    expect(wrapper.find('[data-test="mortgage-collateral-value"]').text()).toContain('2,800,000.00')
    expect(wrapper.find('[data-test="mortgage-ltv-value"]').text()).toContain('85.00%')
    expect(wrapper.find('[data-test="mortgage-down-payment-value"]').text()).toContain('15.00%')
  })

  it('allows exactly 15% 首付 to submit', async () => {
    const wrapper = mountWithI18n(MortgageForm)
    await submit(wrapper)

    expect(calculate).toHaveBeenCalledTimes(1)
  })

  it('blocks 14.99% 首付 (LTV 85.01%) with an inline error', async () => {
    const wrapper = mountWithI18n(MortgageForm)
    // 2 380 280 / 2 800 000 = 0.8501 → 首付 14.99%，低于 15% 下限。
    await wrapper.find('[data-test="mortgage-principal"]').setValue('2380280')
    await flushPromises()

    expect(wrapper.find('[data-test="mortgage-ltv-value"]').text()).toContain('85.01%')
    expect(wrapper.find('[data-test="mortgage-down-payment-value"]').text()).toContain('14.99%')

    await submit(wrapper)
    expect(calculate).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="mortgage-local-error"]').text()).toContain('15%')
  })
})

describe('MortgageForm DTI (AGENTS.md §2.2: 50% / 60%)', () => {
  afterEach(() => {
    vi.clearAllMocks()
    setLocale('zh-CN')
  })

  it('shows 50.00% 月房贷支出/收入比 as compliant (equal to the limit is not a breach)', async () => {
    vi.mocked(calculate).mockResolvedValue(mortgageResult(['8000.00', '8000.00']))
    const wrapper = mountWithI18n(MortgageForm)
    await wrapper.find('[data-test="mortgage-monthly-income"]').setValue('16000')
    await submit(wrapper)

    expect(wrapper.find('[data-test="mortgage-dti-value"]').text()).toContain('50.00%')
    expect(wrapper.find('[data-test="mortgage-dti-breach"]').exists()).toBe(false)
  })

  it('flags 50.01% (0.5001) as a breach', async () => {
    vi.mocked(calculate).mockResolvedValue(mortgageResult(['8001.60']))
    const wrapper = mountWithI18n(MortgageForm)
    await wrapper.find('[data-test="mortgage-monthly-income"]').setValue('16000')
    await submit(wrapper)

    expect(wrapper.find('[data-test="mortgage-dti-value"]').text()).toContain('50.01%')
    expect(wrapper.find('[data-test="mortgage-dti-breach"]').exists()).toBe(true)
  })

  it('flags 月所有债务支出/收入比 at 60.00% as compliant and 60.01% as a breach', async () => {
    vi.mocked(calculate).mockResolvedValue(mortgageResult(['8000.00']))
    const wrapper = mountWithI18n(MortgageForm)
    await wrapper.find('[data-test="mortgage-monthly-income"]').setValue('16000')
    await wrapper.find('[data-test="mortgage-other-debt"]').setValue('1600')
    await submit(wrapper)

    // (8000 + 1600) / 16000 = 0.6 恰好等于上限 → 不算超限。
    expect(wrapper.find('[data-test="mortgage-all-debt-value"]').text()).toContain('60.00%')
    expect(wrapper.find('[data-test="mortgage-all-debt-breach"]').exists()).toBe(false)

    await wrapper.find('[data-test="mortgage-other-debt"]').setValue('1601')
    await submit(wrapper)
    // (8000 + 1601) / 16000 = 0.6000625 → 超限。
    expect(wrapper.find('[data-test="mortgage-all-debt-value"]').text()).toContain('60.01%')
    expect(wrapper.find('[data-test="mortgage-all-debt-breach"]').exists()).toBe(true)
  })

  it('shows 「未提供收入，无法判断 DTI」 for zero income instead of a fake 0', async () => {
    vi.mocked(calculate).mockResolvedValue(mortgageResult(['8000.00']))
    const wrapper = mountWithI18n(MortgageForm)
    await wrapper.find('[data-test="mortgage-monthly-income"]').setValue('0')
    await submit(wrapper)

    expect(wrapper.find('[data-test="mortgage-dti-unavailable"]').text()).toContain(
      '未提供收入，无法判断 DTI',
    )
    expect(wrapper.find('[data-test="mortgage-dti-value"]').exists()).toBe(false)
  })

  it('takes the worst-case month (largest rentDue) for 到期一次还本付息, not the first', async () => {
    // BULLET 的摊销形态：利息逐期递延不支付，前几期 rentDue 为 0，末期一次还本 + 全部递延利息。
    vi.mocked(calculate).mockResolvedValue(mortgageResult(['0.00', '0.00', '1012000.00']))
    const wrapper = mountWithI18n(MortgageForm)
    await wrapper.find('[data-test="mortgage-periods"]').setValue('12')
    await flushPromises()
    await wrapper.find('input[value="BULLET_LOAN"]').setValue()
    await flushPromises()
    await wrapper.find('[data-test="mortgage-monthly-income"]').setValue('16000')
    await submit(wrapper)

    expect(lastRequest().method).toBe('BULLET_LOAN')
    expect(wrapper.find('[data-test="mortgage-monthly-payment"]').text()).toContain('1,012,000.00')
    // 1 012 000 ÷ 16 000 = 63.25 → 远超 50% 上限（月供口径取最坏月份）。
    expect(wrapper.find('[data-test="mortgage-dti-value"]').text()).toContain('6325.00%')
    expect(wrapper.find('[data-test="mortgage-dti-breach"]').exists()).toBe(true)
  })

  it('validates 月可支配收入 / 其他月债务支出 format through the shared Form rules', async () => {
    vi.mocked(calculate).mockResolvedValue(mortgageResult(['8000.00']))
    const wrapper = mountWithI18n(MortgageForm)
    await wrapper.find('[data-test="mortgage-monthly-income"]').setValue('1,000')
    await flushPromises()
    await submit(wrapper)

    expect(calculate).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('金额格式无效')
  })
})
