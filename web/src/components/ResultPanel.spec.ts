import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { Tooltip } from 'ant-design-vue'

import type { CalculationResult, CashFlowEvent, Installment } from '@/api/types'
import { setLocale } from '@/i18n'
import { mountWithI18n } from '@/test-utils/mount'
import { activateTab } from '@/test-utils/tabs'

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

function cashFlow(partial: Partial<CashFlowEvent>): CashFlowEvent {
  return {
    sequence: 0,
    date: '2024-01-01',
    amount: '1000000.00',
    type: 'PRINCIPAL',
    direction: 'OUTFLOW',
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

  afterEach(() => {
    setLocale('zh-CN')
  })

  it('renders metric cards with the R92-8 primary-display rule and 「—」 for null metrics', () => {
    const wrapper = mountWithI18n(ResultPanel, { props: { result: result() } })

    const rent = wrapper.find('[data-test="metric-TOTAL_RENT"]')
    // 自定义 code 指标：主文本为目录中文名，原码以次要小字随卡（对账用）。
    expect(rent.text()).toContain('租金合计')
    expect(rent.find('[data-test="metric-card-code"]').text()).toBe('TOTAL_RENT')
    // R90a：金额显示千分位 + 固定 4 位小数（原值随 tooltip 透出）。
    expect(rent.text()).toContain('1,032,797.1500')

    const xirr = wrapper.find('[data-test="metric-XIRR"]')
    // R92-4：标准缩写原样展示，不再强行译作「实际日期 IRR」，也不重复次要原码。
    expect(xirr.find('[data-test="metric-card-title"]').text()).toContain('XIRR')
    expect(xirr.text()).not.toContain('实际日期')
    expect(xirr.find('[data-test="metric-card-code"]').exists()).toBe(false)
    // null 值渲染「—」。
    expect(xirr.text()).toContain('—')

    // IRR 家族：家族缩写为主 + 口径限定语；R90b 利率类显示为百分比（×100 固定 4 位小数）。
    const irr = wrapper.find('[data-test="metric-IRR_ANNUAL"]')
    expect(irr.text()).toContain('IRR（年化 复利）')
    expect(irr.text()).toContain('6.1700%')
  })

  it('wires the metric catalog into tooltips on cards and table row headers (R86)', () => {
    const wrapper = mountWithI18n(ResultPanel, { props: { result: result() } })

    const tooltips = wrapper.findAllComponents(Tooltip)
    expect(tooltips.length).toBeGreaterThan(0)
    const titles = tooltips.map((tp) => tp.props('title') as string)

    // 指标卡：悬浮内容含目录的中文名 + 公式（R86 机制不变）。
    expect(titles.join('\n')).toContain('租金合计｜公式：Σ rentDue')
    expect(titles.join('\n')).toContain('年化 IRR（复利）｜公式：(1 + IRR_PERIOD)^m − 1')
    // 指标表行头（未入选卡片的指标）同样绑定目录；R92-4 后 NPV 主文本为标准缩写。
    const headers = wrapper.findAll('[data-test="metric-header"]').map((h) => h.text())
    expect(headers).toContain('NPV')
    // hover 仍透出目录中文名（不得出现强行翻译的主标签，但悬浮解释保留中文）。
    expect(titles.join('\n')).toContain('净现值')
  })

  it('renders standard abbreviations raw as row-header primary text with no duplicated code (R92-4)', () => {
    // 指标卡精选之外的目录指标（MIRR）也进指标表，与 NPV 一同覆盖。
    const withMIRR = result()
    withMIRR.schedule.header.metrics = { ...withMIRR.schedule.header.metrics, MIRR: '0.058' }
    const wrapper = mountWithI18n(ResultPanel, { props: { result: withMIRR } })

    // zh-CN：标准缩写 NPV / MIRR 原样作为主文本。
    const names = wrapper.findAll('[data-test="metric-header-name"]').map((n) => n.text())
    expect(names).toContain('NPV')
    expect(names).toContain('MIRR')

    // 标准缩写主文本即原码：不重复渲染次要原码。
    const codes = wrapper.findAll('[data-test="metric-header-code"]').map((c) => c.text())
    expect(codes).not.toContain('NPV')
    expect(codes).not.toContain('MIRR')

    // IRR 家族进指标表时：主文本 IRR（期间），原码作次要小字。
    const withFamily = result()
    withFamily.schedule.header.metrics = {
      ...withFamily.schedule.header.metrics,
      IRR_PERIOD: '0.005',
    }
    const familyWrapper = mountWithI18n(ResultPanel, { props: { result: withFamily } })
    const familyNames = familyWrapper.findAll('[data-test="metric-header-name"]').map((n) => n.text())
    expect(familyNames).toContain('IRR（期间）')
    const familyCodes = familyWrapper.findAll('[data-test="metric-header-code"]').map((c) => c.text())
    expect(familyCodes).toContain('IRR_PERIOD')
  })

  it('keeps the English catalog name as the primary row text in en-US (standard names stay English)', () => {
    setLocale('en-US')
    const wrapper = mountWithI18n(ResultPanel, { props: { result: result() } })

    const names = wrapper.findAll('[data-test="metric-header-name"]').map((n) => n.text())
    expect(names).toContain('Net Present Value (NPV)')
    // en-US 主文本为目录英文名（非原码），原码仍以次要小字保留。
    const codes = wrapper.findAll('[data-test="metric-header-code"]').map((c) => c.text())
    expect(codes).toContain('NPV')
  })

  it('passes unknown metric codes through verbatim with no duplicated secondary code (R88)', () => {
    const withUnknown = result()
    withUnknown.schedule.header.metrics = {
      ...withUnknown.schedule.header.metrics,
      CUSTOM_BALLOON_METRIC: '42.00',
    }
    const wrapper = mountWithI18n(ResultPanel, { props: { result: withUnknown } })

    // 目录外 code 原样透出为行头主文本（既有透传约定）。
    const names = wrapper.findAll('[data-test="metric-header-name"]').map((n) => n.text())
    expect(names).toContain('CUSTOM_BALLOON_METRIC')
    // 目录外 code 不再重复渲染次要原码。
    const codes = wrapper.findAll('[data-test="metric-header-code"]').map((c) => c.text())
    expect(codes).not.toContain('CUSTOM_BALLOON_METRIC')
  })

  it('formats metric values per class (R90a/R90b) and keeps the raw string in the tooltip', () => {
    const withClasses = result()
    withClasses.schedule.header.metrics = {
      ...withClasses.schedule.header.metrics,
      MIRR: '0.058',
      PAYBACK_PERIOD: '0.26841',
    }
    const wrapper = mountWithI18n(ResultPanel, { props: { result: withClasses } })

    // 指标表数值按类别格式化：利率 → 百分比；年数 → 4 位小数；null → 「—」。
    const values = wrapper.findAll('[data-test="metric-value"]').map((v) => v.text())
    expect(values).toContain('5.8000%')
    expect(values).toContain('0.2684')
    expect(values).toContain('—')

    // 原始后端字符串不丢：tooltip 透出原值（对账不丢精度）。
    const tooltips = wrapper.findAllComponents(Tooltip).map((tp) => tp.props('title') as string)
    expect(tooltips.join('\n')).toContain('原值：0.058')
    expect(tooltips.join('\n')).toContain('原值：0.26841')
    // 指标卡 tooltip 同样附原值。
    expect(tooltips.join('\n')).toContain('原值：1032797.15')
  })

  it('renders the four tabs with a cash-flow placeholder when no flows exist', async () => {
    const wrapper = mountWithI18n(ResultPanel, { props: { result: result() } })

    const tabs = wrapper.findAll('.ant-tabs-tab')
    expect(tabs.map((t) => t.text())).toEqual(['指标', '摊销计划', '现金流', '实际利率法'])

    // 现金流 Tab：无现金流数据时 Empty 占位（R87）。
    await activateTab(wrapper, '现金流')
    expect(wrapper.find('[data-test="cashflow-empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="cashflow-table"]').exists()).toBe(false)
  })

  it('renders the cash-flow table with localized types and colored direction tags (R87)', async () => {
    const withFlows = result({
      cashFlows: [
        cashFlow({ sequence: 0, date: '2024-01-01', amount: '1000000.00', type: 'PRINCIPAL', direction: 'OUTFLOW' }),
        cashFlow({ sequence: 1, date: '2024-01-01', amount: '5000.00', type: 'FEE', direction: 'OUTFLOW' }),
        cashFlow({ sequence: 2, date: '2024-02-01', amount: '84271.24', type: 'RENT', direction: 'INFLOW' }),
        cashFlow({ sequence: 3, date: '2024-12-01', amount: '80000.00', type: 'RESIDUAL', direction: 'INFLOW' }),
        cashFlow({ sequence: 4, date: '2024-01-01', amount: '50000.00', type: 'DEPOSIT', direction: 'INFLOW' }),
      ],
    })
    const wrapper = mountWithI18n(ResultPanel, { props: { result: withFlows } })

    await activateTab(wrapper, '现金流')

    // 现金流表渲染全部事件行；R90a：金额列千分位 + 固定 4 位小数。
    const table = wrapper.find('[data-test="cashflow-table"]')
    expect(table.exists()).toBe(true)
    const rows = table.findAll('.ant-table-row')
    expect(rows.length).toBe(5)
    expect(rows[0].text()).toContain('1,000,000.0000')
    expect(rows[2].text()).toContain('84,271.2400')

    // 类型标签走 i18n（zh-CN：本金/费用/租金/残值/保证金）。
    const rowText = rows.map((r) => r.text())
    expect(rowText[0]).toContain('本金')
    expect(rowText[1]).toContain('费用')
    expect(rowText[2]).toContain('租金')
    expect(rowText[3]).toContain('残值')
    expect(rowText[4]).toContain('保证金')

    // 方向标签走 i18n 且着色：INFLOW 绿 / OUTFLOW 红。
    const tags = wrapper.findAll('[data-test="cashflow-direction"]')
    expect(tags.map((tag) => tag.text())).toEqual(['流出', '流出', '流入', '流入', '流入'])
    expect(tags[0].attributes('class')).toContain('red')
    expect(tags[2].attributes('class')).toContain('green')

    // 累计回收曲线图占位随表一同渲染。
    expect(wrapper.find('[data-test="cumulative-cashflow-chart"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="result-tabs"]').text()).toContain('累计现金流回收曲线')
  })

  it('renders the effective-interest table when data exists and a placeholder when null', async () => {
    const withEir = result()
    withEir.effectiveInterest = {
      rows: [
        {
          periodNo: 1,
          openingNetInvestment: '1000000.00',
          interestIncome: '5100.00',
          cashReceived: '84271.24',
          principalRecovery: '79171.24',
          closingNetInvestment: '920828.76',
        },
      ],
      eir: '0.0051',
      totalInterestDifference: '1200.00',
    }
    const wrapper = mountWithI18n(ResultPanel, { props: { result: withEir } })

    await activateTab(wrapper, '实际利率法')
    const table = wrapper.find('[data-test="effective-interest-table"]')
    expect(table.exists()).toBe(true)
    // EIR 摘要行与摊销表同 Tab 展示（R90a/R90b：EIR 百分比、金额 #,##0.0000）。
    expect(wrapper.find('[data-test="result-tabs"]').text()).toContain('实际利率（EIR）：0.5100%')
    expect(table.find('.ant-table-row').text()).toContain('920,828.7600')

    // 无数据（IRR 未收敛等）时占位而非空表。
    const nullWrapper = mountWithI18n(ResultPanel, { props: { result: result() } })
    await activateTab(nullWrapper, '实际利率法')
    expect(nullWrapper.find('[data-test="eir-empty"]').exists()).toBe(true)
    expect(nullWrapper.find('[data-test="effective-interest-table"]').exists()).toBe(false)
  })

  it('colors issue levels: ERROR red / WARN orange / INFO grey with localized labels', () => {
    const withIssues = result({
      hasErrors: true,
      issues: [
        { level: 'ERROR', code: 'FCS-CHK-3001', message: '末期本金为负', periodNo: 12 },
        { level: 'WARN', code: 'FCS-CHK-2001', message: '尾差超阈值', periodNo: 12 },
        { level: 'INFO', code: 'FCS-CHK-1001', message: '首期含宽限期', periodNo: null },
      ],
    })
    const wrapper = mountWithI18n(ResultPanel, { props: { result: withIssues } })

    const tags = wrapper.findAll('[data-test="issue-level"]')
    // 级别标签走 i18n（zh-CN：错误/警告/提示）。
    expect(tags.map((tag) => tag.text())).toEqual(['错误', '警告', '提示'])
    expect(tags[0].attributes('class')).toContain('red')
    expect(tags[1].attributes('class')).toContain('orange')
    // INFO 灰：不带任何彩色修饰类。
    expect(tags[2].attributes('class')).not.toContain('red')
    expect(tags[2].attributes('class')).not.toContain('orange')
  })

  it('renders both charts (composition + balance) fed from the installments', async () => {
    const wrapper = mountWithI18n(ResultPanel, { props: { result: result() } })

    // 图表位于「摊销计划」Tab 内（懒渲染，须先激活）。
    await activateTab(wrapper, '摊销计划')
    expect(wrapper.find('[data-test="composition-chart"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="balance-chart"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="installments-table"]').exists()).toBe(true)
  })

  it('keeps an unknown issue level verbatim (backend-added levels pass through)', () => {
    const withUnknown = result({
      hasErrors: true,
      issues: [{ level: 'WARNING', code: 'FCS-CHK-9999', message: '未知级别', periodNo: null }],
    })
    const wrapper = mountWithI18n(ResultPanel, { props: { result: withUnknown } })

    expect(wrapper.find('[data-test="issue-level"]').text()).toBe('WARNING')
  })
})
