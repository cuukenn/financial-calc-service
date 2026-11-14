/**
 * 房贷比例类指标的精确十进制运算（纯函数）。
 *
 * 禁止 double/float 参与计算；阈值比较用未舍入原值，
 * 不得先舍入再判限」，而展示为 2 位。所以本模块全程在十进制字符串上做长除法 /
 * 加减法 / 比较：内部用 BigInt 承载整数位串（精确整数运算，不是浮点），只在
 * 明确给定的位数上做一次 HALF_UP 舍入。
 *
 * 本模块只产出「值」，不负责展示口径；2 位百分数展示用 formatDecimalPercent。
 * 不引入 Number() / parseFloat / toFixed —— 显示层的 Number 化助手（utils/format.ts）
 * 不能用于这里的阈值判断。
 */

/** 十进制字面量：符号可选、整数位必填、小数位可选。 */
const DECIMAL_RE = /^[+-]?\d+(\.\d+)?$/

/** 内部表示：value = ±digits / 10^scale。 */
interface Decimal {
  negative: boolean
  digits: bigint
  scale: number
}

function pow10(exponent: number): bigint {
  return 10n ** BigInt(exponent)
}

function parseDecimal(raw: string): Decimal | null {
  const text = raw.trim()
  if (!DECIMAL_RE.test(text)) return null
  const unsigned = text.replace(/^[+-]/, '')
  const [intPart, fracPart = ''] = unsigned.split('.')
  const digits = BigInt(intPart + fracPart)
  return {
    negative: text.startsWith('-') && digits !== 0n,
    digits,
    scale: fracPart.length,
  }
}

function mustParse(raw: string): Decimal {
  const parsed = parseDecimal(raw)
  if (parsed === null) throw new Error(`unparseable decimal: "${raw}"`)
  return parsed
}

/** 规范化为最简十进制字符串（去前导零与前导符号、去小数尾随零）。 */
function canonical(value: Decimal): string {
  if (value.digits === 0n) return '0'

  let digits = value.digits.toString()
  if (value.scale > 0) {
    if (digits.length <= value.scale) {
      digits = '0'.repeat(value.scale - digits.length + 1) + digits
    }
    const intPart = digits.slice(0, digits.length - value.scale).replace(/^0+(?=\d)/, '')
    const fracPart = digits.slice(digits.length - value.scale).replace(/0+$/, '')
    digits = fracPart === '' ? intPart : `${intPart}.${fracPart}`
  } else {
    digits = digits.replace(/^0+(?=\d)/, '')
  }
  return value.negative ? `-${digits}` : digits
}

/** 同号整数比较前的对齐：把 value 放大到 scale 位小数（整数倍，无舍入）。 */
function scaledValue(value: Decimal, scale: number): bigint {
  const magnitude = value.digits * pow10(scale - value.scale)
  return value.negative ? -magnitude : magnitude
}

/** 非负整数除法，HALF_UP（四舍五入，远离零）。 */
function roundHalfUp(numerator: bigint, denominator: bigint): bigint {
  const quotient = numerator / denominator
  const remainder = numerator % denominator
  return remainder * 2n >= denominator ? quotient + 1n : quotient
}

/**
 * 精确比较两个十进制字符串：a < b → -1，a = b → 0，a > b → 1。
 * 用于 P-10 的「未舍入原值比较」；非法输入抛错而不是返回一个假的 0。
 */
export function compareDecimal(a: string, b: string): -1 | 0 | 1 {
  const parsedLeft = mustParse(a)
  const parsedRight = mustParse(b)
  const scale = Math.max(parsedLeft.scale, parsedRight.scale)
  const left = scaledValue(parsedLeft, scale)
  const right = scaledValue(parsedRight, scale)
  if (left < right) return -1
  if (left > right) return 1
  return 0
}

/** 精确加法（decimals 字符串），非法输入 → null。 */
export function decimalAdd(a: string, b: string): string | null {
  const left = parseDecimal(a)
  const right = parseDecimal(b)
  if (left === null || right === null) return null
  const scale = Math.max(left.scale, right.scale)
  const sum = scaledValue(left, scale) + scaledValue(right, scale)
  return canonical({ negative: sum < 0n, digits: sum < 0n ? -sum : sum, scale })
}

/** 精确减法（decimals 字符串），非法输入 → null。 */
export function decimalSubtract(a: string, b: string): string | null {
  const left = parseDecimal(a)
  const right = parseDecimal(b)
  if (left === null || right === null) return null
  const scale = Math.max(left.scale, right.scale)
  const difference = scaledValue(left, scale) - scaledValue(right, scale)
  return canonical({
    negative: difference < 0n,
    digits: difference < 0n ? -difference : difference,
    scale,
  })
}

/** 精确乘法（decimals 字符串），非法输入 → null。 */
export function decimalMultiply(a: string, b: string): string | null {
  const left = parseDecimal(a)
  const right = parseDecimal(b)
  if (left === null || right === null) return null
  const negative = left.negative !== right.negative
  const digits = left.digits * right.digits
  return canonical({
    negative: negative && digits !== 0n,
    digits,
    scale: left.scale + right.scale,
  })
}

/**
 * 十进制字符串长除法：numerator ÷ denominator，结果保留 scale 位小数（HALF_UP，保号）。
 * 分母为 0 或任一输入不可解析 → null（调用方决定展示「无法计算」而不是 0）。
 */
export function decimalDivide(numerator: string, denominator: string, scale: number): string | null {
  const dividend = parseDecimal(numerator)
  const divisor = parseDecimal(denominator)
  if (dividend === null || divisor === null || divisor.digits === 0n) return null

  const places = Math.max(0, Math.trunc(scale))
  const negative = dividend.negative !== divisor.negative
  // value = dividend.digits / 10^dividend.scale ÷ (divisor.digits / 10^divisor.scale)
  //       = dividend.digits × 10^(divisor.scale - dividend.scale) / divisor.digits
  // 再乘 10^places 得到定点整数。
  const exponent = divisor.scale - dividend.scale + places
  const absDividend = dividend.digits
  const absDivisor = divisor.digits
  const [scaledNumerator, scaledDenominator] =
    exponent >= 0
      ? [absDividend * pow10(exponent), absDivisor]
      : [absDividend, absDivisor * pow10(-exponent)]

  const quotient = roundHalfUp(scaledNumerator, scaledDenominator)
  return canonical({ negative: negative && quotient !== 0n, digits: quotient, scale: places })
}

/**
 * 十进制字符串谓词（表单校验用，不参与算术）：可解析且非负。
 * 空串 / '1e5' / '1,000' 之类都不是合法金额字面量 —— 拒绝，不猜。
 */
export function isNonNegativeDecimal(raw: string): boolean {
  const parsed = parseDecimal(raw)
  return parsed !== null && !parsed.negative
}

/** 严格正数谓词：分母必须 > 0，否则比值不可计算（界面须说明「无法判断」而不是给 0）。 */
export function isPositiveDecimal(raw: string): boolean {
  const parsed = parseDecimal(raw)
  return parsed !== null && !parsed.negative && parsed.digits > 0n
}

/**
 * 可解析性谓词（任意符号）：表单用它把「字面量非法」交给格式规则、
 * 把「可解析但非正」交给数值门槛规则，两条文案不会互相串台。
 */
export function isDecimalText(raw: string): boolean {
  return parseDecimal(raw) !== null
}

/**
 * 取用于「月供」读数的月供值（由本次摊销表派生，AGENTS.md §2.2 规范名）：
 * - 等额本息：每期租金恒定，取首期 rentDue；
 * - 等额本金 / 到期一次还本付息：取全表最大的 rentDue（最坏月份）。
 */
export function pickMonthlyPayment(rents: string[], method: string): string | null {
  if (rents.length === 0) return null
  if (method === 'EQUAL_INSTALLMENT') {
    const first = parseDecimal(rents[0])
    return first === null ? null : canonical(first)
  }
  let largest: Decimal | null = null
  for (const rent of rents) {
    const parsed = parseDecimal(rent)
    if (parsed === null) return null
    if (largest === null || compareDecimal(rent, canonical(largest)) > 0) largest = parsed
  }
  return largest === null ? null : canonical(largest)
}

/**
 * 比例类指标的 2 位百分数展示（P-10）：×100 后按指定小数位 HALF_UP，
 * 仍然只在十进制串上运算。null / 不可解析 → 「—」（与 utils/format.ts 的兜底一致）。
 */
export function formatDecimalPercent(value: string | null, digits = 2): string {
  if (value === null) return '—'
  const parsed = parseDecimal(value)
  if (parsed === null) return '—'

  // percent × 10^digits = value × 10^(2 + digits)
  const exponent = 2 + digits - parsed.scale
  const [numerator, denominator] =
    exponent >= 0
      ? [parsed.digits * pow10(exponent), 1n]
      : [parsed.digits, pow10(-exponent)]
  const scaled = roundHalfUp(numerator, denominator)

  let text = scaled.toString()
  if (digits > 0) {
    if (text.length <= digits) text = '0'.repeat(digits - text.length + 1) + text
    const intPart = text.slice(0, text.length - digits)
    const fracPart = text.slice(text.length - digits)
    text = `${intPart}.${fracPart}`
  }
  const sign = parsed.negative && scaled !== 0n ? '-' : ''
  return `${sign}${text}%`
}
