/**
 * 房贷表单校验规则（纯模块：不依赖组件，可脱离 DOM 单测）。
 *
 * 分工：这里只放「前端 [法] 门槛」的判定与 AntD 规则装配；数值本身一律是
 * 十进制小数字串（P-01），阈值比较一律用未舍入原值（P-10，见 utils/ratio）。
 * 首付 ≥ 15% 这一档需要派生比例，留在 MortgageForm 的提交闸门里（那里才算出
 * 贷款成数），本模块负责其余与语言无关的判定。
 *
 * 诚实标注（§71）：期限 480 月与「到期一次还本付息只在 ≤ 12 月」都是前端门槛，
 * 引擎不校验（R107/R109），所以文案不得声称后端已校验 —— 见 mortgage.gates.*。
 */

import type { Rule } from 'ant-design-vue/es/form'

import { isLprStepAligned } from '@/utils/rate'
import { isDecimalText, isNonNegativeDecimal, isPositiveDecimal } from '@/utils/ratio'

import { isLprSequence, isRepricingCycle, parseLprSequence } from './repricing'

/** AGENTS.md §2.3 房贷期限 ≤ 40 年 = 480 个月；引擎上限仍是 1200（R107）。 */
export const MAX_LOAN_MONTHS = 480

/**
 * 金规〔2026〕5号：期限 > 1 年须按月还本付息，期限 ≤ 1 年才可到期一次还本付息。
 * 该 ≤ 12 期门槛只是前端的（R109）：引擎对 BULLET_LOAN 只校验 IN_ARREARS。
 */
export const BULLET_MAX_MONTHS = 12

/** 校验文案取自 i18n（随语言切换响应式更新）。 */
export type Translate = (key: string, named?: Record<string, unknown>) => string

/** 装配规则需要的表单要素：首期还款日要与放款日比较。 */
export interface MortgageRuleModel {
  commenceDate: string
  /** R150：重定价日与放款日比较需要知道是否真的启用了重定价。 */
  repricingCycle: number
}

/**
 * 金额/百分数输入：留空交给 required 规则，非空必须是可解析的非负十进制串
 * （P-05 金额以元为单位、恒为非负；'1,000' / '1e5' 这类写法拒绝，不猜、不换算）。
 */
export function isAmountText(value: unknown): boolean {
  if (value === undefined || value === null) return true
  if (typeof value !== 'string') return false
  const text = value.trim()
  return text === '' || isNonNegativeDecimal(text)
}

/** 贷款期限（月）：整数且 1 ≤ 期限 ≤ 480（InputNumber 的 max 是第一道，本规则是第二道）。 */
export function isLoanTermMonths(value: unknown): boolean {
  return (
    typeof value === 'number' &&
    Number.isInteger(value) &&
    value >= 1 &&
    value <= MAX_LOAN_MONTHS
  )
}

/** R109：期限 ≤ 12 月才允许「到期一次还本付息」；期限缺失（null/undefined）时不允许。 */
export function isBulletRepaymentAllowed(periods: number | null | undefined): boolean {
  return typeof periods === 'number' && Number.isInteger(periods)
    ? periods >= 1 && periods <= BULLET_MAX_MONTHS
    : false
}

/** P-04：加点必须是 5bp（0.05 个百分点）的整数倍，12bp 直接拒绝，不静默就近取整。 */
export function isSpreadStepAligned(value: unknown): boolean {
  return typeof value === 'string' && isLprStepAligned(value)
}

/**
 * 贷款金额必须 > 0（R101：principal 是正十进制串；AGENTS.md 补充口径 3 金额恒为非负，
 * 而本金 0 / 负数连测算要素都不成立 —— 引擎的 PRINCIPAL_MUST_BE_POSITIVE 是最后一道，
 * 让 '0' 走到引擎只是一次无谓的网络往返）。
 *
 * 判定只在十进制串上做（isPositiveDecimal，无 Number() 算术）。空值返回 true ——
 * 「缺失」由 required 规则给文案；不可解析的字面量（'1,000'）也返回 true ——
 * 「格式非法」由金额格式规则给文案，本闸门不抢文案。
 */
export function isLoanAmountPositive(value: unknown): boolean {
  if (typeof value !== 'string') return true
  const text = value.trim()
  if (text === '' || !isDecimalText(text)) return true
  return isPositiveDecimal(text)
}

/**
 * 首期还款日不得早于放款日（引擎同样拒绝该组合，所以先在前端拦住）。
 * ISO-8601 的 yyyy-MM-dd 串按字典序比较即按日期比较。留空由 required 规则负责。
 */
export function isRepaymentAfterDisbursement(
  commenceDate: string,
  firstRepaymentDate: string,
): boolean {
  if (commenceDate === '' || firstRepaymentDate === '') return true
  return firstRepaymentDate >= commenceDate
}

/**
 * R150：重定价周期只有 0（不重定价）/ 3 / 6 / 12 个月（央行公告〔2024〕第 11 号）。
 * 引擎不认识「重定价周期」这个词 —— 它只看见分段利率规则行 —— 所以这一条是纯前端的
 * [法] 门槛（与 480 月、≤ 12 期 BULLET 同类，见 §71 的诚实标注），文案不得声称后端已校验。
 */
export function isRepricingCycleAllowed(value: unknown): boolean {
  return isRepricingCycle(value)
}

/** 未来各段 LPR 序列：空 = 没给（由提交闸门决定阻断还是沿用）；给了就必须每项可解析且非负。 */
export function isLprSequenceText(value: unknown): boolean {
  if (value === undefined || value === null) return true
  if (typeof value !== 'string') return false
  const items = parseLprSequence(value)
  return items.length === 0 || isLprSequence(items)
}

/** 重定价日允许留空（= 放款日对日）；给了就不能早于放款日，否则这笔贷款不会重定价。 */
export function isRepricingAnchorAfterDisbursement(
  commenceDate: string,
  anchorDate: string,
): boolean {
  if (commenceDate === '' || anchorDate === '') return true
  return anchorDate >= commenceDate
}

/** 金额格式规则（可选字段共用；必填与否由 required 规则表达）。 */
function amountFormatRule(t: Translate, messageKey: string): Rule {
  const message = t(messageKey)
  return {
    trigger: 'change',
    validator: (_rule, value) =>
      isAmountText(value) ? Promise.resolve() : Promise.reject(new Error(message)),
  }
}

/** 贷款金额 > 0 的闸门：文案点名「贷款金额」，让用户知道是哪一个要素不成立。 */
function loanAmountPositiveRule(t: Translate): Rule {
  const message = t('mortgage.validation.principalPositive')
  return {
    trigger: 'change',
    validator: (_rule, value) =>
      isLoanAmountPositive(value) ? Promise.resolve() : Promise.reject(new Error(message)),
  }
}

/**
 * 房贷表单的规则集。字段名与 MortgageForm 的表单模型一一对应 ——
 * 收入/债务两个字段虽然由 MortgageIncomePanel 渲染，但仍在同一个 Form 的
 * model/rules 里校验（AntD FormItem 通过注入的 Form 上下文按字段名取值）。
 */
export function buildMortgageRules(
  t: Translate,
  model: MortgageRuleModel,
): Record<string, Rule[]> {
  return {
    principal: [
      { required: true, message: t('mortgage.validation.principal'), trigger: 'change' },
      // > 0 闸门排在格式规则之前：负数既非法又非正，必须点名「贷款金额」。
      loanAmountPositiveRule(t),
      amountFormatRule(t, 'mortgage.validation.money'),
    ],
    lprPercent: [
      { required: true, message: t('mortgage.validation.lpr'), trigger: 'change' },
      amountFormatRule(t, 'mortgage.validation.lprFormat'),
    ],
    // 必填与 5bp 步长合成一条：避免同时抛两条文案。
    spreadBp: [
      {
        trigger: 'change',
        validator: (_rule, value) => {
          const text = typeof value === 'string' ? value.trim() : ''
          if (text === '') return Promise.reject(new Error(t('mortgage.validation.spread')))
          return isSpreadStepAligned(text)
            ? Promise.resolve()
            : Promise.reject(new Error(t('mortgage.validation.spreadStep')))
        },
      },
    ],
    commenceDate: [
      { required: true, message: t('mortgage.validation.commenceDate'), trigger: 'change' },
    ],
    firstRepaymentDate: [
      {
        trigger: 'change',
        validator: (_rule, value) => {
          const text = typeof value === 'string' ? value.trim() : ''
          if (text === '') {
            return Promise.reject(new Error(t('mortgage.validation.firstRepaymentDate')))
          }
          return isRepaymentAfterDisbursement(model.commenceDate, text)
            ? Promise.resolve()
            : Promise.reject(new Error(t('mortgage.validation.firstRepaymentBeforeCommence')))
        },
      },
    ],
    // R150：重定价三要素的表单门槛。引擎不认识「重定价周期」，这些只能在前端拦（§71 诚实标注）。
    repricingCycle: [
      {
        trigger: 'change',
        validator: (_rule, value) =>
          isRepricingCycleAllowed(value)
            ? Promise.resolve()
            : Promise.reject(new Error(t('mortgage.validation.repricingCycle'))),
      },
    ],
    repricingAnchorDate: [
      {
        trigger: 'change',
        validator: (_rule, value) => {
          const text = typeof value === 'string' ? value.trim() : ''
          return isRepricingAnchorAfterDisbursement(model.commenceDate, text)
            ? Promise.resolve()
            : Promise.reject(
                new Error(t('mortgage.validation.repricingAnchorBeforeCommence')),
              )
        },
      },
    ],
    futureLprPercents: [
      {
        trigger: 'change',
        validator: (_rule, value) =>
          isLprSequenceText(value)
            ? Promise.resolve()
            : Promise.reject(new Error(t('mortgage.validation.futureLprs'))),
      },
    ],
    periods: [
      { required: true, message: t('mortgage.validation.periods'), trigger: 'change' },
      {
        trigger: 'change',
        validator: (_rule, value) =>
          isLoanTermMonths(value)
            ? Promise.resolve()
            : Promise.reject(
                new Error(t('mortgage.validation.periodsRange', { max: MAX_LOAN_MONTHS })),
              ),
      },
    ],
    transactionPrice: [
      { required: true, message: t('mortgage.validation.transactionPrice'), trigger: 'change' },
      amountFormatRule(t, 'mortgage.validation.money'),
    ],
    appraisalValue: [
      { required: true, message: t('mortgage.validation.appraisalValue'), trigger: 'change' },
      amountFormatRule(t, 'mortgage.validation.money'),
    ],
    monthlyIncome: [amountFormatRule(t, 'mortgage.validation.money')],
    otherMonthlyDebt: [amountFormatRule(t, 'mortgage.validation.money')],
  }
}
