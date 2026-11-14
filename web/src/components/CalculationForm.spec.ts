import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '@/api/client'
import type { CalculationRequest, CalculationResult } from '@/api/types'
import { setLocale } from '@/i18n'
import { mountWithI18n } from '@/test-utils/mount'
import { fieldHasError, settleFormState } from '@/test-utils/forms'
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
vi.mock('echarts/core', () => ({
  use: vi.fn(),
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

async function pickOption(
  wrapper: ReturnType<typeof mountWithI18n>,
  selector: string,
  label: string,
): Promise<void> {
  await wrapper.find(selector).find('.ant-select-selector').trigger('mousedown')
  await flushPromises()
  await flushPromises()
  const dropdown = [...document.querySelectorAll('.ant-select-dropdown')].find((d) =>
    d.textContent?.includes(label),
  )
  expect(dropdown).toBeTruthy()
  const option = [...dropdown!.querySelectorAll('.ant-select-item-option')].find((o) =>
    o.textContent?.includes(label),
  )
  expect(option).toBeTruthy()
  option!.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
  option!.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }))
  option!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  await flushPromises()
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
    setLocale('zh-CN')
  })

  it('renders the form pre-filled with the golden fixture defaults', () => {
    const wrapper = mountWithI18n(CalculationForm)

    expect((wrapper.find('[data-test="principal"]').element as HTMLInputElement).value).toBe('1000000')
    // R92-6：名义年利率为百分数口径展示值（提交前换算为 0.06）。
    expect((wrapper.find('[data-test="nominal-rate"]').element as HTMLInputElement).value).toBe('6')
    expect((wrapper.find('[data-test="periods"]').element as HTMLInputElement).value).toBe('12')
    expect((wrapper.find('[data-test="commence-date"] input').element as HTMLInputElement).value).toBe('2024-01-01')
    expect((wrapper.find('[data-test="first-rent-date"] input').element as HTMLInputElement).value).toBe('2024-02-01')
    // 后端必填的口径/方式字段始终有值（Task 1 评审发现）。
    // R89：触发器只显示行业标准斜杠记法，内部下划线 code 不再出现在标签中。
    const dayCountText = wrapper.find('[data-test="day-count-code"]').text()
    expect(dayCountText).toContain('30/360')
    expect(dayCountText).not.toContain('THIRTY_360')
    expect(wrapper.find('[data-test="date-roll-code"]').text()).toContain('不调整')

    // R91：要素名对齐 §4 统一语言与行业术语（旧自造名不再出现）。
    expect(wrapper.text()).toContain('计息基准')
    expect(wrapper.text()).toContain('日期调整')
    expect(wrapper.text()).toContain('支付频率')
    expect(wrapper.text()).toContain('留购价（名义货价）')
    expect(wrapper.text()).not.toContain('天数口径')
    expect(wrapper.text()).not.toContain('还款频率')
    const methodRadio = wrapper.find('input[value="EQUAL_INSTALLMENT"]')
    expect((methodRadio.element as HTMLInputElement).checked).toBe(true)
  })

  it('renders the day-count dropdown with slash-notation names and the code as secondary (R89)', async () => {
    const wrapper = mountWithI18n(CalculationForm)

    await wrapper.find('[data-test="day-count-code"] .ant-select-selector').trigger('mousedown')
    await flushPromises()
    await flushPromises()

    // 下拉项主文本为斜杠记法（行业标准），不含下划线内部 code。
    const names = [...document.querySelectorAll('[data-test="day-count-option-name"]')].map(
      (el) => el.textContent?.trim(),
    )
    expect(names).toContain('30/360')
    expect(names).toContain('ACT/360（实际/360）')
    expect(names.every((n) => !n?.includes('_'))).toBe(true)

    // 原码仅作次要小字保留（映射仍可对账）。
    const codes = [...document.querySelectorAll('[data-test="day-count-option-code"]')].map(
      (el) => el.textContent?.trim(),
    )
    expect(codes).toContain('THIRTY_360')
    expect(codes).toContain('ACT_360')
  })

  it('shows pure localized text in the non-day-count dropdowns with no internal codes (R89)', () => {
    const wrapper = mountWithI18n(CalculationForm)

    // zh-CN 纯中文主文本，内部 code 不再出现。
    expect(wrapper.find('[data-test="frequency"]').text()).toContain('月')
    expect(wrapper.find('[data-test="frequency"]').text()).not.toContain('MONTHLY')
    expect(wrapper.find('[data-test="timing"]').text()).toContain('期末付')
    expect(wrapper.find('[data-test="timing"]').text()).not.toContain('IN_ARREARS')
    expect(wrapper.find('[data-test="rate-change-policy"]').text()).toContain('自变更日重算')
    expect(wrapper.find('[data-test="rate-change-policy"]').text()).not.toContain('RESOLVE')
    expect(wrapper.find('[data-test="residual-handling"]').text()).toContain('期末单独支付')
    expect(wrapper.find('[data-test="residual-handling"]').text()).not.toContain('SEPARATE')
  })

  it('derives the financed principal from asset value, down payment and IDC (CAS 21)', async () => {
    const wrapper = mountWithI18n(CalculationForm)
    await wrapper.find('[data-test="asset-value"]').setValue('2400000')
    await wrapper.find('[data-test="down-payment"]').setValue('400000')
    await wrapper.find('[data-test="initial-direct-costs"]').setValue('10000')
    await flushPromises()

    expect(wrapper.find('[data-test="financed-principal"]').text()).toContain('2010000')

    await submit(wrapper)
    const req = vi.mocked(calculate).mock.calls[0][0] as CalculationRequest
    expect(req.principal).toBe('2010000')
    expect(req.assetValue).toBe('2400000')
    expect(req.downPayment).toBe('400000')
    expect(req.initialDirectCosts).toBe('10000')
  })

  it('sends the guaranteed residual without the asset-value chain', async () => {
    const wrapper = mountWithI18n(CalculationForm)
    await wrapper.find('[data-test="guaranteed-residual"]').setValue('30000')
    await submit(wrapper)

    const req = vi.mocked(calculate).mock.calls[0][0] as CalculationRequest
    expect(req.guaranteedResidual).toBe('30000')
    expect(req.assetValue).toBeUndefined()
    expect(req.principal).toBe('1000000')
  })

  it('sends per-fee includeInIrr from the editor (U5)', async () => {
    const wrapper = mountWithI18n(CalculationForm)
    await wrapper.find('[data-test="fee-add"]').trigger('click')
    await wrapper.find('[data-test="fee-row"] input[placeholder="费用类型"]').setValue('HANDLING')
    await wrapper.find('[data-test="fee-row"] input[placeholder="金额"]').setValue('20000')
    // AntD Checkbox 的 attrs 落点不可靠：按行内 checkbox 类型选择。
    await wrapper.find('[data-test="fee-row"] input[type="checkbox"]').setValue(true)
    await submit(wrapper)

    const req = vi.mocked(calculate).mock.calls[0][0] as CalculationRequest
    expect(req.fees).toHaveLength(1)
    expect(req.fees![0]!.type).toBe('HANDLING')
    expect(req.fees![0]!.amount).toBe('20000')
    expect(req.fees![0]!.includeInIrr).toBe(true)
  })

  it('sends the deposit calibers and the percent-denominated interest rate (ticket 03)', async () => {
    const wrapper = mountWithI18n(CalculationForm)
    await wrapper.find('[data-test="deposit"]').setValue('50000')
    // AntD Select 交互：mousedown 展开弹层，点击选项提交枚举值。
    await pickOption(wrapper, '[data-test="deposit-timing"]', '随首期租金')
    await pickOption(wrapper, '[data-test="deposit-settlement"]', '冲抵末期租金')
    await wrapper.find('[data-test="deposit-interest-rate"]').setValue('3')
    await submit(wrapper)

    const req = vi.mocked(calculate).mock.calls[0][0] as CalculationRequest
    expect(req.deposit).toBe('50000')
    expect(req.depositTiming).toBe('WITH_FIRST_RENT')
    expect(req.depositSettlement).toBe('OFFSET_FINAL_RENT')
    // 百分数口径展示值 3 → 提交小数 0.03（R92-5 同款换算）。
    expect(req.depositInterestRate).toBe('0.03')
  })

  it('omits depositInterestRate when left empty', async () => {
    const wrapper = mountWithI18n(CalculationForm)
    await wrapper.find('[data-test="deposit"]').setValue('50000')
    await wrapper.find('[data-test="deposit-interest-rate"]').setValue('')
    await submit(wrapper)

    const req = vi.mocked(calculate).mock.calls[0][0] as CalculationRequest
    expect(req.deposit).toBe('50000')
    expect(req).not.toHaveProperty('depositInterestRate')
  })

  it('blocks the unguaranteed residual without an asset value (FCS-DOM-1017)', async () => {
    const wrapper = mountWithI18n(CalculationForm)
    await wrapper.find('[data-test="unguaranteed-residual"]').setValue('20000')
    await submit(wrapper)

    expect(calculate).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="local-error-alert"]').text()).toContain('资产价值')
  })
  it('shows the English localized names in en-US (R89)', () => {
    setLocale('en-US')
    const wrapper = mountWithI18n(CalculationForm)

    expect(wrapper.find('[data-test="frequency"]').text()).toContain('Monthly')
    expect(wrapper.find('[data-test="frequency"]').text()).not.toContain('MONTHLY')
    const dayCountText = wrapper.find('[data-test="day-count-code"]').text()
    expect(dayCountText).toContain('30/360')
    expect(dayCountText).not.toContain('THIRTY_360')

    // R91：英文名对齐 §4 术语与行业惯例。
    expect(wrapper.text()).toContain('Day count convention')
    expect(wrapper.text()).toContain('Business day convention')
    expect(wrapper.text()).toContain('Commencement date')
    expect(wrapper.text()).toContain('First rent payment date')
    expect(wrapper.text()).toContain('Purchase option price (nominal)')
  })

  it('converts percent-style rate inputs to decimal strings on submit (R92-6)', async () => {
    const wrapper = mountWithI18n(CalculationForm)
    // 默认展示值 6 → 提交 0.06（黄金请求不变）。
    await submit(wrapper)
    let req = vi.mocked(calculate).mock.calls[0][0] as CalculationRequest
    expect(req.nominalRate).toBe('0.06')

    // 「6%」与「600bp」两种后缀均接受，存储/提交恒为小数字符串。
    vi.mocked(calculate).mockClear()
    await wrapper.find('[data-test="nominal-rate"]').setValue('600bp')
    await submit(wrapper)
    req = vi.mocked(calculate).mock.calls[0][0] as CalculationRequest
    expect(req.nominalRate).toBe('0.06')

    vi.mocked(calculate).mockClear()
    await wrapper.find('[data-test="nominal-rate"]').setValue('6%')
    await submit(wrapper)
    req = vi.mocked(calculate).mock.calls[0][0] as CalculationRequest
    expect(req.nominalRate).toBe('0.06')
  })

  it('blocks submit with a format message when the nominal rate is invalid (R92-6)', async () => {
    const wrapper = mountWithI18n(CalculationForm)
    await wrapper.find('[data-test="nominal-rate"]').setValue('abc')
    await submit(wrapper)

    expect(calculate).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('格式无效：请输入如 6% 或 600bp 的名义年利率')
  })

  it('gives the day-count select its own full-width row, pairing date-roll with rate policy (R93-1)', () => {
    const wrapper = mountWithI18n(CalculationForm)

    // 字段所在的外层栅格 Col：从 FormItem 的 control col 再向上一层的 ant-col
    // （FormItem 内部的 label/control 也是 ant-col，外层工作台布局 Col 在 Card 之外）。
    const colClasses = (sel: string): string[] => {
      const el = wrapper.find(sel).element as HTMLElement
      const control = el.closest('.ant-col')
      const gridCol = control?.parentElement?.closest('.ant-col')
      return gridCol ? gridCol.className.split(/\s+/) : []
    }

    // 计息基准独占一行（span 24）。
    expect(colClasses('[data-test="day-count-code"]')).toContain('ant-col-24')
    // 日期调整与分段利率策略共占一行（各 span 12）。
    expect(colClasses('[data-test="date-roll-code"]')).toContain('ant-col-12')
    expect(colClasses('[data-test="rate-change-policy"]')).toContain('ant-col-12')
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
    expect(req.dayCountCode).toBe('THIRTY_360')
    expect(req.dateRollCode).toBe('NONE')
    expect(req.rateChangePolicy).toBe('RESOLVE')
    expect(req.method).toBe('EQUAL_INSTALLMENT')
    expect(req.residualValue).toBe('0')
    expect(req.residualHandling).toBe('SEPARATE')
    expect(req.deposit).toBe('0')
    // 保证金口径三字段（ticket 03）：默认 v1 姿态（期初收取 / 到期退还 / 不计息）。
    expect(req.depositTiming).toBe('UPFRONT')
    expect(req.depositSettlement).toBe('REFUND')
    expect(req).not.toHaveProperty('depositInterestRate')
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

    // Task 3：指标升级为 Statistic 卡（R90a + R93-2：#,##0.00 显示，原值随 tooltip 透出）。
    const cards = wrapper.findAll('[data-test="metric-card"]')
    expect(cards.length).toBeGreaterThanOrEqual(1)
    expect(wrapper.find('[data-test="metric-TOTAL_RENT"]').text()).toContain('1,032,797.15')

    // Plan 4 Task 1：摊销计划移入同名 Tab（懒渲染，须先激活）。
    await activateTab(wrapper, '摊销计划')
    const rows = wrapper.find('[data-test="installments-table"]').findAll('.ant-table-row')
    expect(rows.length).toBeGreaterThanOrEqual(1)
    // R90a + R93-2：金额列千分位 + 固定 2 位小数。
    expect(rows[0].text()).toContain('84,271.24')
    expect(wrapper.find('[data-test="has-errors-alert"]').exists()).toBe(false)
  })

  it('groups the form fields into the three compact block cards (R84)', () => {
    const wrapper = mountWithI18n(CalculationForm)

    // 三个大块卡片均存在。
    const titles = wrapper.findAll('.ant-card-head-title').map((t) => t.text())
    expect(titles).toContain('租赁要素')
    expect(titles).toContain('租金结构')
    expect(titles).toContain('费用与其他')

    // 租赁要素：基础字段 + 口径 + 方式 radio。
    const basic = wrapper.find('[data-test="card-basic"]')
    expect(basic.find('[data-test="principal"]').exists()).toBe(true)
    expect(basic.find('[data-test="day-count-code"]').exists()).toBe(true)
    expect(basic.find('input[value="EQUAL_INSTALLMENT"]').exists()).toBe(true)

    // 租金结构：规则行 + 宽限期（显式租金按方式联动出现）。
    const rentStructure = wrapper.find('[data-test="card-rent-structure"]')
    expect(rentStructure.find('[data-test="rule-rows-editor"]').exists()).toBe(true)
    expect(rentStructure.find('[data-test="interest-only-periods"]').exists()).toBe(true)
    expect(rentStructure.find('[data-test="rents-editor"]').exists()).toBe(false)

    // 费用与其他：费用编辑器 + 残值/保证金。
    const feesOther = wrapper.find('[data-test="card-fees-other"]')
    expect(feesOther.find('[data-test="fee-add"]').exists()).toBe(true)
    expect(feesOther.find('[data-test="residual-value"]').exists()).toBe(true)
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
    // R92-6：预设回填百分数口径展示值（提交前换算为 0.03 / 0.06）。
    const rates = wrapper.findAll('[data-test="rule-annual-rate"]').map(
      (input) => (input.element as HTMLInputElement).value,
    )
    expect(rates).toEqual(['3', '6'])
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

  it('blocks submission when a rule-row rate is unparseable and shows a per-row error (R95)', async () => {
    const wrapper = mountWithI18n(CalculationForm)
    await wrapper.find('[data-test="rule-add"]').trigger('click')
    await wrapper.findAll('[data-test="rule-annual-rate"]')[0].setValue('6bp%')
    await submit(wrapper)

    // 垃圾值不达后端：提交被阻断并逐行报错。
    expect(calculate).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="local-error-alert"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="local-error-alert"]').text()).toContain('分段利率第 1 行格式无效')

    // 行内即时错误：红字提示 + 输入框 error 状态。
    expect(wrapper.find('[data-test="rule-rate-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="rule-rate-error"]').text()).toContain('利率格式无效')
  })

  it('keeps an empty rule-row rate as inherit (annualRate omitted) on submit (R95)', async () => {
    const wrapper = mountWithI18n(CalculationForm)
    await wrapper.find('[data-test="rule-add"]').trigger('click')
    await submit(wrapper)

    // 空值合法 = 继承后端缺省利率（RuleRow.annualRate 可空），不再提交空串。
    expect(calculate).toHaveBeenCalledTimes(1)
    const req = vi.mocked(calculate).mock.calls[0][0] as CalculationRequest
    expect(req.ruleRows).toHaveLength(1)
    expect(req.ruleRows![0].annualRate).toBeUndefined()
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

/**
 * Task 6 §2：租赁表单的显式重置。面板只隐藏不销毁（R130），所以跑完一次的
 * ResultPanel 会留在原地 —— 清空是表单自己的显式动作，不切 Tab、不碰语言。
 */
describe('CalculationForm 显式重置 (Task 6 §2)', () => {
  beforeEach(() => {
    vi.mocked(calculate).mockResolvedValue(goldenResult())
  })

  afterEach(() => {
    vi.clearAllMocks()
    setLocale('zh-CN')
  })

  it('reset removes the ResultPanel, restores the default elements and empties the editors', async () => {
    const wrapper = mountWithI18n(CalculationForm)
    await submit(wrapper)
    expect(wrapper.find('[data-test="result-card"]').exists()).toBe(true)

    await wrapper.find('[data-test="principal"]').setValue('123456.78')
    await wrapper.find('[data-test="nominal-rate"]').setValue('9%')
    await wrapper.find('[data-test="rule-add"]').trigger('click')
    await wrapper.find('[data-test="fee-add"]').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('[data-test="rule-row"]').length).toBe(1)
    expect(wrapper.findAll('[data-test="fee-row"]').length).toBe(1)

    await wrapper.find('[data-test="calc-form-reset-btn"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="result-card"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="error-alert"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="local-error-alert"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-test="rule-row"]').length).toBe(0)
    expect(wrapper.findAll('[data-test="fee-row"]').length).toBe(0)

    const value = (hook: string) => (wrapper.find(`[data-test="${hook}"]`).element as HTMLInputElement).value
    // 默认要素 = Task-21 黄金样例：重置后开箱提交仍复现黄金 TOTAL_RENT。
    expect(value('principal')).toBe('1000000')
    expect(value('nominal-rate')).toBe('6')
    expect(value('periods')).toBe('12')
    expect(wrapper.find('[data-test="day-count-code"]').text()).toContain('30/360')
    expect(value('residual-value')).toBe('0')
    expect(value('deposit')).toBe('0')
    expect((wrapper.find('input[value="EQUAL_INSTALLMENT"]').element as HTMLInputElement).checked).toBe(true)

    await submit(wrapper)
    expect(calculate).toHaveBeenCalledTimes(2)
    const req = vi.mocked(calculate).mock.calls[1]![0] as CalculationRequest
    expect(req.principal).toBe('1000000')
    expect(req.nominalRate).toBe('0.06')
    expect(req).not.toHaveProperty('ruleRows')
    expect(req).not.toHaveProperty('fees')
  })

  it('reset clears the local block alert and the backend error alert', async () => {
    const wrapper = mountWithI18n(CalculationForm)
    await wrapper.find('[data-test="rule-add"]').trigger('click')
    await wrapper.findAll('[data-test="rule-annual-rate"]')[0].setValue('6bp%')
    await submit(wrapper)
    expect(wrapper.find('[data-test="local-error-alert"]').exists()).toBe(true)

    await wrapper.find('[data-test="calc-form-reset-btn"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="local-error-alert"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="rule-rate-error"]').exists()).toBe(false)

    vi.mocked(calculate).mockRejectedValue(new ApiError('FCS-DOM-1001', '租赁本金必须为正数', 400))
    await submit(wrapper)
    expect(wrapper.find('[data-test="error-alert"]').text()).toContain('FCS-DOM-1001')

    await wrapper.find('[data-test="calc-form-reset-btn"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="error-alert"]').exists()).toBe(false)
  })

  it('a blocked submit clears the previous run before the block message appears', async () => {
    const wrapper = mountWithI18n(CalculationForm)
    await submit(wrapper)
    expect(wrapper.find('[data-test="result-card"]').exists()).toBe(true)

    // EXPLICIT_RENT 但逐期租金没填满 → 本地阻断。
    await wrapper.find('input[value="EXPLICIT_RENT"]').setValue()
    await flushPromises()
    await submit(wrapper)

    expect(calculate).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-test="local-error-alert"]').text()).toContain('须填满全部 12 期')
    expect(wrapper.find('[data-test="result-card"]').exists()).toBe(false)
  })

  it('a failed field validation clears the previous run too', async () => {
    const wrapper = mountWithI18n(CalculationForm)
    await submit(wrapper)
    expect(wrapper.find('[data-test="result-card"]').exists()).toBe(true)

    await wrapper.find('[data-test="principal"]').setValue('')
    await flushPromises()
    await submit(wrapper)

    expect(calculate).toHaveBeenCalledTimes(1)
    // 字段级校验状态（settleFormState：FormItem 的校验类经 setTimeout 收尾）。
    await settleFormState()
    expect(fieldHasError(wrapper.element as HTMLElement, '[data-test="principal"]')).toBe(true)
    expect(wrapper.find('[data-test="result-card"]').exists()).toBe(false)
  })
})
