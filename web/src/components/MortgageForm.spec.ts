import { DatePicker } from 'ant-design-vue'
import { flushPromises } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { CalculationRequest, CalculationResult } from '@/api/types'
import App from '@/App.vue'
import MortgageForm from '@/components/MortgageForm.vue'
import { setLocale } from '@/i18n'
import { mountWithI18n } from '@/test-utils/mount'
import { fieldHasError, settleFormState } from '@/test-utils/forms'
import { activateTab, paneState } from '@/test-utils/tabs'

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
vi.mock('echarts/core', () => ({
  use: vi.fn(),
  init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() })),
}))

import { ApiError, calculate } from '@/api/client'

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
 * 归还给引擎的房贷请求只允许这 13 个键（R101 + R137），其余租赁专属键必须缺席。
 * `residualValue` / `residualHandling` / `rateChangePolicy` 在这里而不是缺席清单里：
 * 它们是 `LeaseElement` 的必填要素，服务端不为它们补默认（AGENTS.md §3.1 输入要素
 * 必须显式提供、缺失即报错），所以表单显式发送。与
 * `calc-server` 的 `MortgagePayloadContractTest` 同步：那份测试把同一个键集合
 * 真的 POST 给引擎并要求 200。
 */
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
  'method',
  'residualValue',
  'residualHandling',
  'rateChangePolicy',
]

/** 真正缺席的租赁专属键（9 个）：缺席 = 该要素不存在，引擎侧为空/无。 */
const ABSENT_LEASE_ONLY_KEYS = [
  'deposit',
  'fees',
  'ruleRows',
  'explicitRents',
  'explicitPeriods',
  'gracePeriod',
  'allowPartialRuleSet',
  'rounding',
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

  it('shows only the active scenario panel and keeps the inactive one mounted but hidden', async () => {
    const wrapper = mountWithI18n(App)

    // 默认场景 = 融资租赁：现有测算表单在场，房贷面板尚未渲染（Tabs 懒渲染）。
    expect(paneState(wrapper, '[data-test="scenario-lease"]')).toBe('active')
    expect(paneState(wrapper, '[data-test="calc-form-card"]')).toBe('active')
    expect(paneState(wrapper, '[data-test="scenario-mortgage"]')).toBe('absent')

    await activateTab(wrapper, '房贷计算器')

    // 房贷面板在场；租赁面板连同其字段整体隐藏（不是卸载）。
    expect(paneState(wrapper, '[data-test="scenario-mortgage"]')).toBe('active')
    expect(paneState(wrapper, '[data-test="mortgage-form-card"]')).toBe('active')
    expect(paneState(wrapper, '[data-test="calc-form-card"]')).toBe('hidden')
    expect(paneState(wrapper, '[data-test="principal"]')).toBe('hidden')

    // 切回融资租赁：原表单回到在场且可见，房贷面板退到隐藏。
    await activateTab(wrapper, '融资租赁')
    expect(paneState(wrapper, '[data-test="scenario-lease"]')).toBe('active')
    expect(paneState(wrapper, '[data-test="calc-form-card"]')).toBe('active')
    expect(paneState(wrapper, '[data-test="principal"]')).toBe('active')
    expect(paneState(wrapper, '[data-test="mortgage-form-card"]')).toBe('hidden')
  })

  it('keeps a half-filled 租赁 form across a detour to the 房贷 tab (no implicit reset)', async () => {
    const wrapper = mountWithI18n(App)

    // 用户填了一半的租赁本金（默认值是 1 000 000，改成别的数字才证明保留的是状态本身）。
    await wrapper.find('[data-test="principal"]').setValue('123456.78')
    await flushPromises()

    await activateTab(wrapper, '房贷计算器')
    expect(paneState(wrapper, '[data-test="principal"]')).toBe('hidden')

    await activateTab(wrapper, '融资租赁')
    const principal = wrapper.find('[data-test="principal"]').element as HTMLInputElement
    expect(paneState(wrapper, '[data-test="principal"]')).toBe('active')
    expect(principal.value).toBe('123456.78')
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

  it('submits exactly the 14 mortgage keys and omits every lease-only key (14 = 13 + product，issue 15/A1；arch-seams 死字段 currency 移除后 13+product)', async () => {
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
      method: 'EQUAL_INSTALLMENT',
      // R137：这三个是 `LeaseElement` 必填要素，显式发送而非让服务端补默认。
      residualValue: '0',
      residualHandling: 'SEPARATE',
      rateChangePolicy: 'RESOLVE',
      product: 'MORTGAGE',
    })
    // issue 15/A1：13 键 + product = 14 键（currency 死字段已按 2026-11-14 裁定移除）。
    expect(Object.keys(req)).toHaveLength(14)
    expect(Object.keys(req).sort()).toEqual([...MORTGAGE_KEYS, 'product'].sort())
    expect(req.product).toBe('MORTGAGE')
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

describe('MortgageForm 贷款金额 gate (R101: principal > 0 在表单层就把住)', () => {
  beforeEach(() => {
    vi.mocked(calculate).mockResolvedValue(mortgageResult(['8000.00']))
  })

  afterEach(() => {
    vi.clearAllMocks()
    setLocale('zh-CN')
  })

  it('blocks a 0 贷款金额 instead of sending it to the engine', async () => {
    const wrapper = mountWithI18n(MortgageForm)
    // '0' 过格式规则、也过首付闸门（首付 100%），所以必须有一道明确的 > 0 闸门。
    await wrapper.find('[data-test="mortgage-principal"]').setValue('0')
    await flushPromises()
    await submit(wrapper)

    expect(calculate).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('贷款金额须大于 0')
  })

  it('blocks a negative 贷款金额 with the same message', async () => {
    const wrapper = mountWithI18n(MortgageForm)
    await wrapper.find('[data-test="mortgage-principal"]').setValue('-2380000')
    await flushPromises()
    await submit(wrapper)

    expect(calculate).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('贷款金额须大于 0')
  })

  it('keeps a positive 贷款金额 submitting', async () => {
    const wrapper = mountWithI18n(MortgageForm)
    await wrapper.find('[data-test="mortgage-principal"]').setValue('1')
    await flushPromises()
    await submit(wrapper)

    // 1 元贷款：首付闸门（1 − 1/2 800 000 ≈ 99.99%）不会阻断，闸门只拦非正数。
    expect(calculate).toHaveBeenCalledTimes(1)
    expect(lastRequest().principal).toBe('1')
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
    // R137：到期一次还本付息走的是同一份 14 键 payload —— 三个必填要素照旧显式发送，
    // 与 calc-server 的 MortgagePayloadContractTest 的 BULLET 用例同一形状。
    expect(lastRequest()).toMatchObject({
      residualValue: '0',
      residualHandling: 'SEPARATE',
      rateChangePolicy: 'RESOLVE',
    })
    expect(Object.keys(lastRequest())).toHaveLength(14)
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
    // 归位是确定性的：481 被夹到上限，不是「随便一个 ≤ 480 的值」。
    for (const [req] of vi.mocked(calculate).mock.calls) {
      expect((req as CalculationRequest).periods).toBe(480)
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

  it('blocks submit when 抵押物价值 is 0: an unevaluable floor is not a pass', async () => {
    const wrapper = mountWithI18n(MortgageForm)
    // 成交价 = 评估价 = 0 → 抵押物价值 0 → 贷款成数与首付比例都是「—」。
    await wrapper.find('[data-test="mortgage-transaction-price"]').setValue('0')
    await wrapper.find('[data-test="mortgage-appraisal-value"]').setValue('0')
    await flushPromises()

    expect(wrapper.find('[data-test="mortgage-collateral-value"]').text()).toContain('0.00')
    expect(wrapper.find('[data-test="mortgage-ltv-value"]').text()).toBe('—')
    expect(wrapper.find('[data-test="mortgage-down-payment-value"]').text()).toBe('—')

    await submit(wrapper)
    // AGENTS.md §2.3「阈值即硬约束」：无法判定的下限必须阻断，并说明为什么无法判定。
    expect(calculate).not.toHaveBeenCalled()
    const message = wrapper.find('[data-test="mortgage-local-error"]').text()
    expect(message).toContain('15%')
    expect(message).toContain('抵押物价值')
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

  it('flags 50.01% (0.5001) as a warn — above the 50% limit but not yet a breach', async () => {
    vi.mocked(calculate).mockResolvedValue(mortgageResult(['8001.60']))
    const wrapper = mountWithI18n(MortgageForm)
    await wrapper.find('[data-test="mortgage-monthly-income"]').setValue('16000')
    await submit(wrapper)

    expect(wrapper.find('[data-test="mortgage-dti-value"]').text()).toContain('50.01%')
    // AGENTS.md §2.2：月房贷支出/收入比 ≤ 50% 合规、> 50% 接近上限、> 60% 才是超限。
    // 50.01% 落在 warn 档：类名与文案标记都必须是 warn，不能读成 breach。
    expect(wrapper.find('[data-test="mortgage-dti-readout"]').classes()).toContain('ratio-warn')
    expect(wrapper.find('[data-test="mortgage-dti-readout"]').classes()).not.toContain('ratio-breach')
    expect(wrapper.find('[data-test="mortgage-dti-breach"]').text()).toBe('接近上限')
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
    // settleFormState：错误文案挂着进出场过渡，断言要比过渡先跑就会偶发扑空。
    await settleFormState()
    expect(wrapper.text()).toContain('金额格式无效')
  })
})

/**
 * Task 6 §3：监管仪表配色。断言的是**语义类名 + 文案标记**（不是颜色值）：
 * 状态一律从未舍入的十进制串上得出（P-10），恰好等于阈值不视为超限（§2.3 含本数）。
 */
describe('MortgageForm 监管仪表配色 (Task 6 §3: LTV / 首付 / DTI 阈值色)', () => {
  afterEach(() => {
    vi.clearAllMocks()
    setLocale('zh-CN')
  })

  it('marks the default 15.00% 首付 / 85.00% LTV readouts ok with a 合规 marker', () => {
    const wrapper = mountWithI18n(MortgageForm)

    const down = wrapper.find('[data-test="mortgage-down-payment-readout"]')
    expect(down.classes()).toContain('ratio-ok')
    expect(wrapper.find('[data-test="mortgage-down-payment-status"]').text()).toBe('合规')

    const ltv = wrapper.find('[data-test="mortgage-ltv-readout"]')
    expect(ltv.classes()).toContain('ratio-ok')
    expect(wrapper.find('[data-test="mortgage-ltv-status"]').text()).toBe('合规')
  })

  it('marks 14.99% 首付 as breach（低于下限）and the matching 85.01% LTV as 超限', async () => {
    const wrapper = mountWithI18n(MortgageForm)
    await wrapper.find('[data-test="mortgage-principal"]').setValue('2380280')
    await flushPromises()

    expect(wrapper.find('[data-test="mortgage-down-payment-value"]').text()).toContain('14.99%')
    expect(wrapper.find('[data-test="mortgage-down-payment-readout"]').classes()).toContain('ratio-breach')
    expect(wrapper.find('[data-test="mortgage-down-payment-status"]').text()).toBe('低于下限')

    expect(wrapper.find('[data-test="mortgage-ltv-value"]').text()).toContain('85.01%')
    expect(wrapper.find('[data-test="mortgage-ltv-readout"]').classes()).toContain('ratio-breach')
    expect(wrapper.find('[data-test="mortgage-ltv-status"]').text()).toBe('超限')
  })

  it('judges from the unrounded value: a 14.999999% 首付 displayed as 15.00% is still breach (P-10)', async () => {
    const wrapper = mountWithI18n(MortgageForm)
    // 2 380 000.028 / 2 800 000 = 0.8500000100 → 首付 0.14999999，展示 15.00%、原值低于下限。
    await wrapper.find('[data-test="mortgage-principal"]').setValue('2380000.028')
    await flushPromises()

    expect(wrapper.find('[data-test="mortgage-down-payment-value"]').text()).toContain('15.00%')
    expect(wrapper.find('[data-test="mortgage-down-payment-readout"]').classes()).toContain('ratio-breach')
    expect(wrapper.find('[data-test="mortgage-down-payment-status"]').text()).toBe('低于下限')
  })

  it('marks unevaluable readouts neutral with 「无法判断」, never ok and never 0', async () => {
    const wrapper = mountWithI18n(MortgageForm)
    await wrapper.find('[data-test="mortgage-transaction-price"]').setValue('0')
    await wrapper.find('[data-test="mortgage-appraisal-value"]').setValue('0')
    await flushPromises()

    for (const hook of ['mortgage-ltv', 'mortgage-down-payment']) {
      const readout = wrapper.find(`[data-test="${hook}-readout"]`)
      expect(readout.classes()).toContain('ratio-neutral')
      expect(readout.classes()).not.toContain('ratio-ok')
      expect(wrapper.find(`[data-test="${hook}-value"]`).text()).toBe('—')
      expect(wrapper.find(`[data-test="${hook}-status"]`).text()).toBe('无法判断')
    }
  })

  it('colours 月房贷支出/收入比 ok at 50.00% and warn at 50.01% with distinct markers', async () => {
    vi.mocked(calculate).mockResolvedValue(mortgageResult(['8000.00']))
    const compliant = mountWithI18n(MortgageForm)
    await compliant.find('[data-test="mortgage-monthly-income"]').setValue('16000')
    await submit(compliant)

    expect(compliant.find('[data-test="mortgage-dti-readout"]').classes()).toContain('ratio-ok')
    expect(compliant.find('[data-test="mortgage-dti-status"]').text()).toBe('合规')

    vi.mocked(calculate).mockResolvedValue(mortgageResult(['8001.60']))
    const warning = mountWithI18n(MortgageForm)
    await warning.find('[data-test="mortgage-monthly-income"]').setValue('16000')
    await submit(warning)

    expect(warning.find('[data-test="mortgage-dti-value"]').text()).toContain('50.01%')
    expect(warning.find('[data-test="mortgage-dti-readout"]').classes()).toContain('ratio-warn')
    // 越过 50% 但仍在 60% 之内 → warn：文案是「接近上限」，不是「超限」。
    expect(warning.find('[data-test="mortgage-dti-breach"]').text()).toBe('接近上限')
  })

  it('colours 月房贷支出/收入比 breach only past 60%', async () => {
    vi.mocked(calculate).mockResolvedValue(mortgageResult(['9601.60']))
    const wrapper = mountWithI18n(MortgageForm)
    await wrapper.find('[data-test="mortgage-monthly-income"]').setValue('16000')
    await submit(wrapper)

    // 9 601.60 ÷ 16 000 = 0.6001 → 越过 60% 硬顶。
    expect(wrapper.find('[data-test="mortgage-dti-value"]').text()).toContain('60.01%')
    expect(wrapper.find('[data-test="mortgage-dti-readout"]').classes()).toContain('ratio-breach')
    expect(wrapper.find('[data-test="mortgage-dti-breach"]').text()).toBe('超限')
  })

  it('colours 月所有债务支出/收入比 ok at 60.00% and breach at 60.01%', async () => {
    vi.mocked(calculate).mockResolvedValue(mortgageResult(['8000.00']))
    const compliant = mountWithI18n(MortgageForm)
    await compliant.find('[data-test="mortgage-monthly-income"]').setValue('16000')
    await compliant.find('[data-test="mortgage-other-debt"]').setValue('1600')
    await submit(compliant)

    expect(compliant.find('[data-test="mortgage-all-debt-readout"]').classes()).toContain('ratio-ok')
    expect(compliant.find('[data-test="mortgage-all-debt-status"]').text()).toBe('合规')

    await compliant.find('[data-test="mortgage-other-debt"]').setValue('1601')
    await submit(compliant)

    expect(compliant.find('[data-test="mortgage-all-debt-readout"]').classes()).toContain('ratio-breach')
    expect(compliant.find('[data-test="mortgage-all-debt-breach"]').text()).toBe('超限')
  })

  it('keeps an unprovided 月可支配收入 neutral（无法判断）instead of ok or 0', async () => {
    vi.mocked(calculate).mockResolvedValue(mortgageResult(['8000.00']))
    const wrapper = mountWithI18n(MortgageForm)
    await wrapper.find('[data-test="mortgage-monthly-income"]').setValue('0')
    await submit(wrapper)

    const readout = wrapper.find('[data-test="mortgage-dti-readout"]')
    expect(readout.classes()).toContain('ratio-neutral')
    expect(readout.classes()).not.toContain('ratio-ok')
    expect(readout.text()).toContain('无法判断')
    expect(wrapper.find('[data-test="mortgage-dti-status"]').exists()).toBe(false)
  })

  /**
   * 修复轮 1（评审 Important 1 / R133）：徽章是提交闸门的另一面。
   * 同一份输入下，徽章状态与被阻断与否必须一起成立——「合规徽章配被阻断的提交」
   * 正是 AGENTS.md §2.3 禁止的那类漂移。三档：恰好 15% / 低于 15% / 门槛算不出。
   */
  it('badge and 提交闸门 agree on the same input (no 合规 badge on a blocked submit)', async () => {
    vi.mocked(calculate).mockResolvedValue(mortgageResult(['8000.00']))

    // 恰好 15%：ok 徽章，提交放行。
    const okCase = mountWithI18n(MortgageForm)
    expect(okCase.find('[data-test="mortgage-down-payment-readout"]').classes()).toContain('ratio-ok')
    await submit(okCase)
    expect(calculate).toHaveBeenCalledTimes(1)
    expect(okCase.find('[data-test="mortgage-local-error"]').exists()).toBe(false)

    // 14.99%：breach 徽章，提交被阻断（只放行上面那一次）。
    const breachCase = mountWithI18n(MortgageForm)
    await breachCase.find('[data-test="mortgage-principal"]').setValue('2380280')
    await flushPromises()
    expect(breachCase.find('[data-test="mortgage-down-payment-readout"]').classes()).toContain('ratio-breach')
    await submit(breachCase)
    expect(calculate).toHaveBeenCalledTimes(1)
    expect(breachCase.find('[data-test="mortgage-local-error"]').text()).toContain('15%')

    // 抵押物价值 0 → 门槛无法判定：neutral 徽章（不是 ok），提交同样被阻断。
    const neutralCase = mountWithI18n(MortgageForm)
    await neutralCase.find('[data-test="mortgage-transaction-price"]').setValue('0')
    await neutralCase.find('[data-test="mortgage-appraisal-value"]').setValue('0')
    await flushPromises()
    const neutralReadout = neutralCase.find('[data-test="mortgage-down-payment-readout"]')
    expect(neutralReadout.classes()).toContain('ratio-neutral')
    expect(neutralReadout.classes()).not.toContain('ratio-ok')
    await submit(neutralCase)
    expect(calculate).toHaveBeenCalledTimes(1)
    expect(neutralCase.find('[data-test="mortgage-local-error"]').text()).toContain('15%')
  })
})

/**
 * Task 6 §2：显式重置。R130 让面板在场景切换之间存活，所以一次测算跑完之后，
 * ResultPanel 与 DTI/LTV 读数会一直留在那里 —— 清空必须是表单自己的一个显式动作。
 * 重置只清表单：不切 Tab、不碰语言偏好。
 */
describe('MortgageForm 显式重置 (Task 6 §2)', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    setLocale('zh-CN')
  })

  it('reset removes the ResultPanel and returns the run-derived readouts to 「—」', async () => {
    vi.mocked(calculate).mockResolvedValue(mortgageResult(['8000.00']))
    const wrapper = mountWithI18n(MortgageForm)
    await wrapper.find('[data-test="mortgage-monthly-income"]').setValue('16000')
    await wrapper.find('[data-test="mortgage-other-debt"]').setValue('1600')
    await submit(wrapper)

    expect(wrapper.find('[data-test="result-card"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="mortgage-monthly-payment"]').text()).toContain('8,000.00')
    expect(wrapper.find('[data-test="mortgage-dti-value"]').text()).toContain('50.00%')

    await wrapper.find('[data-test="mortgage-reset-btn"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="result-card"]').exists()).toBe(false)
    // 依赖这次跑结果的读数（月供 / DTI / 全部负债 DTI）回到未计算态。
    expect(wrapper.find('[data-test="mortgage-monthly-payment"]').text()).toBe('—')
    expect(wrapper.find('[data-test="mortgage-dti-value"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="mortgage-all-debt-value"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="mortgage-dti-readout"]').classes()).toContain('ratio-neutral')
  })

  it('reset restores the form defaults including the 收入与债务 inputs', async () => {
    vi.mocked(calculate).mockResolvedValue(mortgageResult(['8000.00']))
    const wrapper = mountWithI18n(MortgageForm)
    await wrapper.find('[data-test="mortgage-principal"]').setValue('123456')
    await wrapper.find('[data-test="mortgage-periods"]').setValue('120')
    await wrapper.find('[data-test="mortgage-monthly-income"]').setValue('16000')
    await wrapper.find('[data-test="mortgage-other-debt"]').setValue('1600')
    // 日期也要先改掉：否则「重置恢复默认值」对放款日/首期还款日是无从验证的。
    // DatePicker 在 jsdom 里不认键盘输入（内部按 format 解析，setValue 不提交模型），
    // 直接按 v-model 的契约改值：值格式由 `value-format="YYYY-MM-DD"` 决定。
    // 按 data-test 定位而不是按 findAllComponents 的下标：表单里 DatePicker 的先后次序
    // 属于布局细节（R150 的「重定价日」就排在放款日之前），按下标取会取错控件。
    const pickerAt = (hook: string) => wrapper.find(`[data-test="${hook}"]`).findComponent(DatePicker)
    await pickerAt('mortgage-commence-date').vm.$emit('update:value', '2027-01-05')
    await pickerAt('mortgage-first-repayment-date').vm.$emit('update:value', '2027-02-05')
    await flushPromises()
    await submit(wrapper)

    // 先确认改动确实进了 payload（value-format 生效），再验证重置能还原。
    expect(lastRequest().commenceDate).toBe('2027-01-05')
    expect(lastRequest().firstRentDate).toBe('2027-02-05')

    await wrapper.find('[data-test="mortgage-reset-btn"]').trigger('click')
    await flushPromises()

    const value = (hook: string) => (wrapper.find(`[data-test="${hook}"]`).element as HTMLInputElement).value
    const dateValue = (hook: string) =>
      (wrapper.find(`[data-test="${hook}"] input`).element as HTMLInputElement).value
    expect(value('mortgage-principal')).toBe('2380000')
    expect(value('mortgage-lpr')).toBe('3.5')
    expect(value('mortgage-spread')).toBe('0')
    expect(value('mortgage-periods')).toBe('360')
    expect(value('mortgage-transaction-price')).toBe('3000000')
    expect(value('mortgage-appraisal-value')).toBe('2800000')
    expect(dateValue('mortgage-commence-date')).toBe('2026-11-01')
    expect(dateValue('mortgage-first-repayment-date')).toBe('2026-12-01')
    expect(value('mortgage-monthly-income')).toBe('')
    expect(value('mortgage-other-debt')).toBe('')
    expect((wrapper.find('input[value="EQUAL_INSTALLMENT"]').element as HTMLInputElement).checked).toBe(true)
  })

  it('reset clears the 前端门槛 alert and the field-level validation state', async () => {
    vi.mocked(calculate).mockResolvedValue(mortgageResult(['8000.00']))
    const wrapper = mountWithI18n(MortgageForm)
    const root = wrapper.element as HTMLElement

    // 前端门槛：首付 14.99% → mortgage-local-error。
    await wrapper.find('[data-test="mortgage-principal"]').setValue('2380280')
    await submit(wrapper)
    expect(wrapper.find('[data-test="mortgage-local-error"]').exists()).toBe(true)

    // 表单规则：贷款金额 0 → 字段级校验状态。
    await wrapper.find('[data-test="mortgage-principal"]').setValue('0')
    await flushPromises()
    await submit(wrapper)
    await settleFormState()
    expect(fieldHasError(root, '[data-test="mortgage-principal"]')).toBe(true)

    await wrapper.find('[data-test="mortgage-reset-btn"]').trigger('click')
    await settleFormState()

    expect(wrapper.find('[data-test="mortgage-local-error"]').exists()).toBe(false)
    expect(fieldHasError(root, '[data-test="mortgage-principal"]')).toBe(false)
  })

  it('reset clears the backend error alert too (R77 统一错误体)', async () => {
    vi.mocked(calculate).mockRejectedValue(new ApiError('FCS-DOM-1001', '贷款金额必须为正数', 400))
    const wrapper = mountWithI18n(MortgageForm)
    await submit(wrapper)

    expect(wrapper.find('[data-test="mortgage-error-alert"]').text()).toContain('FCS-DOM-1001')

    await wrapper.find('[data-test="mortgage-reset-btn"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="mortgage-error-alert"]').exists()).toBe(false)
  })

  it('reset does not switch the active scenario tab', async () => {
    vi.mocked(calculate).mockResolvedValue(mortgageResult(['8000.00']))
    const wrapper = mountWithI18n(App)
    await activateTab(wrapper, '房贷计算器')
    await submit(wrapper)

    await wrapper.find('[data-test="mortgage-reset-btn"]').trigger('click')
    await flushPromises()

    expect(paneState(wrapper, '[data-test="scenario-mortgage"]')).toBe('active')
    expect(paneState(wrapper, '[data-test="scenario-lease"]')).toBe('hidden')
    expect(localStorage.getItem('fcs.scenario')).toBe('mortgage')
  })

  it('reset does not clear the language preference', async () => {
    vi.mocked(calculate).mockResolvedValue(mortgageResult(['8000.00']))
    setLocale('en-US')
    const wrapper = mountWithI18n(MortgageForm)
    await submit(wrapper)

    await wrapper.find('[data-test="mortgage-reset-btn"]').trigger('click')
    await flushPromises()

    expect(localStorage.getItem('fcs.locale')).toBe('en-US')
    expect(wrapper.find('[data-test="mortgage-form-card"]').text()).toContain('Mortgage Calculation Form')
  })

  it('a 首付 15% floor block clears the previous run and its stale readouts first', async () => {
    vi.mocked(calculate).mockResolvedValue(mortgageResult(['8000.00']))
    const wrapper = mountWithI18n(MortgageForm)
    await wrapper.find('[data-test="mortgage-monthly-income"]').setValue('16000')
    await submit(wrapper)

    expect(wrapper.find('[data-test="result-card"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="mortgage-dti-value"]').text()).toContain('50.00%')

    // 阻断这次提交：首付 14.99%。
    await wrapper.find('[data-test="mortgage-principal"]').setValue('2380280')
    await flushPromises()
    await submit(wrapper)

    expect(calculate).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-test="mortgage-local-error"]').text()).toContain('15%')
    // 上一次跑的结果与读数必须已经不在（Task 5 评审点出的 stale readout 路径）。
    expect(wrapper.find('[data-test="result-card"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="mortgage-dti-value"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="mortgage-monthly-payment"]').text()).toBe('—')
  })

  it('a 贷款金额 > 0 rule block clears the previous run and its stale readouts too', async () => {
    vi.mocked(calculate).mockResolvedValue(mortgageResult(['8000.00']))
    const wrapper = mountWithI18n(MortgageForm)
    await wrapper.find('[data-test="mortgage-monthly-income"]').setValue('16000')
    await submit(wrapper)

    expect(wrapper.find('[data-test="result-card"]').exists()).toBe(true)

    await wrapper.find('[data-test="mortgage-principal"]').setValue('0')
    await flushPromises()
    await submit(wrapper)

    expect(calculate).toHaveBeenCalledTimes(1)
    // 字段级校验状态（settleFormState：FormItem 的校验类经 setTimeout 收尾）。
    await settleFormState()
    expect(fieldHasError(wrapper.element as HTMLElement, '[data-test="mortgage-principal"]')).toBe(true)
    expect(wrapper.find('[data-test="result-card"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="mortgage-dti-value"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="mortgage-monthly-payment"]').text()).toBe('—')
  })
})

/**
 * Task 6 §5（Task 5 交接的文案所有权）：到期一次还本付息的那笔钱是一次性偿还额，不是月供。
 * 算术不动（§2.2 的月供口径仍取全表最大期，DTI 照旧从未舍入原值判限），但读数必须诚实：
 * 行头改叫「到期一次性偿还额」，并说明把它与月收入直接相比是刻意保守的代理口径。
 * 按月方式（等额本息 / 等额本金）的「月供」语义与口径说明不得被改名。
 */
describe('MortgageForm BULLET 读数文案 (Task 6 §5: 到期一次性偿还额 vs 月供)', () => {
  afterEach(() => {
    vi.clearAllMocks()
    setLocale('zh-CN')
  })

  /** 12 期 BULLET 的摊销形态：递延利息逐期为 0，末期一次还本 + 全部递延利息。 */
  async function mountBullet(): Promise<VueWrapper> {
    vi.mocked(calculate).mockResolvedValue(mortgageResult(['0.00', '0.00', '1012000.00']))
    const wrapper = mountWithI18n(MortgageForm)
    await wrapper.find('[data-test="mortgage-periods"]').setValue('12')
    await flushPromises()
    await wrapper.find('input[value="BULLET_LOAN"]').setValue()
    await flushPromises()
    await wrapper.find('[data-test="mortgage-monthly-income"]').setValue('16000')
    await submit(wrapper)
    return wrapper
  }

  it('labels the lump sum as 到期一次性偿还额 (not 月供) and keeps the arithmetic', async () => {
    const wrapper = await mountBullet()

    expect(wrapper.find('[data-test="mortgage-payment-label"]').text()).toBe('到期一次性偿还额')
    expect(wrapper.find('[data-test="mortgage-monthly-payment"]').text()).toContain('1,012,000.00')
    // 口径说明换成「这笔钱不是月供」，仍然明说它取的是全表最大期。
    expect(wrapper.find('[data-test="mortgage-payment-basis"]').text()).toContain('不是月供')

    // DTI 的规范名不因还款方式而变（AGENTS.md §2.2 不得同义混用）。
    expect(wrapper.text()).toContain('月房贷支出/收入比')
    expect(wrapper.find('[data-test="mortgage-dti-value"]').text()).toContain('6325.00%')
  })

  it('states that comparing the lump sum with monthly income is a deliberately conservative proxy', async () => {
    const wrapper = await mountBullet()
    const note = wrapper.find('[data-test="mortgage-bullet-note"]').text()

    expect(note).toContain('到期一次性偿还额')
    expect(note).toContain('保守')
    expect(note).toContain('代理口径')
  })

  it('keeps the 月供 label and the monthly basis note for the 按月 methods', async () => {
    vi.mocked(calculate).mockResolvedValue(mortgageResult(['8000.00', '8000.00']))
    const wrapper = mountWithI18n(MortgageForm)
    await wrapper.find('[data-test="mortgage-monthly-income"]').setValue('16000')
    await submit(wrapper)

    expect(wrapper.find('[data-test="mortgage-payment-label"]').text()).toBe('月供')
    expect(wrapper.find('[data-test="mortgage-payment-basis"]').text()).toContain('月供口径')
    // 按月方式不需要那条一次性偿还额的说明。
    expect(wrapper.find('[data-test="mortgage-bullet-note"]').exists()).toBe(false)
  })

  it('carries the lump-sum copy in en-US as well (R85 parity)', async () => {
    setLocale('en-US')
    const wrapper = await mountBullet()

    expect(wrapper.find('[data-test="mortgage-payment-label"]').text()).toBe(
      'Lump-sum repayment at maturity',
    )
    expect(wrapper.find('[data-test="mortgage-payment-basis"]').text()).toContain('not a monthly payment')
    expect(wrapper.find('[data-test="mortgage-bullet-note"]').text()).toContain('conservative proxy')
  })
})
