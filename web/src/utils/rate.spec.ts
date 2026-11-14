import { describe, expect, it } from 'vitest'

import { composeLprRate, isLprStepAligned, parseRateInput } from './rate'

describe('parseRateInput (R92-5/R92-6 percent-style rate input)', () => {
  it('treats a bare number as a percent (6 → 0.06)', () => {
    expect(parseRateInput('6')).toBe('0.06')
    expect(parseRateInput('13')).toBe('0.13')
    expect(parseRateInput('9')).toBe('0.09')
    expect(parseRateInput('0')).toBe('0')
  })

  it('accepts an explicit % suffix (6% → 0.06)', () => {
    expect(parseRateInput('6%')).toBe('0.06')
    expect(parseRateInput('13%')).toBe('0.13')
    expect(parseRateInput('6 %')).toBe('0.06')
    expect(parseRateInput('0%')).toBe('0')
  })

  it('accepts basis-point suffixes (600bp → 0.06, case-insensitive)', () => {
    expect(parseRateInput('600bp')).toBe('0.06')
    expect(parseRateInput('600BP')).toBe('0.06')
    expect(parseRateInput('300bp')).toBe('0.03')
    expect(parseRateInput('0bp')).toBe('0')
    expect(parseRateInput('12.5bp')).toBe('0.00125')
  })

  it('keeps fractional precision without float drift', () => {
    expect(parseRateInput('3.25')).toBe('0.0325')
    expect(parseRateInput('4.35%')).toBe('0.0435')
    expect(parseRateInput('6.25')).toBe('0.0625')
  })

  it('trims surrounding whitespace', () => {
    expect(parseRateInput(' 6 ')).toBe('0.06')
    expect(parseRateInput(' 600bp ')).toBe('0.06')
  })

  it('returns null for empty or invalid input', () => {
    expect(parseRateInput('')).toBeNull()
    expect(parseRateInput('   ')).toBeNull()
    expect(parseRateInput('abc')).toBeNull()
    expect(parseRateInput('6x')).toBeNull()
    expect(parseRateInput('%')).toBeNull()
    expect(parseRateInput('bp')).toBeNull()
    expect(parseRateInput('6%%')).toBeNull()
  })

  it('supports a negative sign for negative rates', () => {
    expect(parseRateInput('-3')).toBe('-0.03')
    expect(parseRateInput('-50bp')).toBe('-0.005')
  })
})

describe('composeLprRate (年利率 = LPR + 加点)', () => {
  it('composes a decimal annual rate from a percent LPR and a bp spread', () => {
    expect(composeLprRate('3.5', '0')).toBe('0.035')
    expect(composeLprRate('3.5', '35')).toBe('0.0385')
    expect(composeLprRate('4.35', '-30')).toBe('0.0405')
  })

  it('accepts a negative spread and keeps full decimal precision', () => {
    expect(composeLprRate('3.5', '-30')).toBe('0.032')
    expect(composeLprRate('3.05', '5')).toBe('0.031')
    expect(composeLprRate('3.45', '-5')).toBe('0.034')
  })

  it('returns null for unparseable input or a negative composed rate', () => {
    expect(composeLprRate('', '0')).toBeNull()
    expect(composeLprRate('3.5', '')).toBeNull()
    expect(composeLprRate('abc', '0')).toBeNull()
    expect(composeLprRate('3.5', 'x')).toBeNull()
    expect(composeLprRate('0', '-30')).toBeNull()
  })
})

describe('isLprStepAligned (LPR 报价步长 0.05 个百分点 = 5bp)', () => {
  it('accepts integer multiples of 5bp, including zero and negatives', () => {
    expect(isLprStepAligned('0')).toBe(true)
    expect(isLprStepAligned('5')).toBe(true)
    expect(isLprStepAligned('-30')).toBe(true)
    expect(isLprStepAligned('35')).toBe(true)
  })

  it('rejects a non-multiple (no silent rounding) and non-integers', () => {
    expect(isLprStepAligned('12')).toBe(false)
    expect(isLprStepAligned('2.5')).toBe(false)
    expect(isLprStepAligned('abc')).toBe(false)
    expect(isLprStepAligned('')).toBe(false)
  })
})
