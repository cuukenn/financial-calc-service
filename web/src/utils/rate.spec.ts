import { describe, expect, it } from 'vitest'

import { parseRateInput } from './rate'

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
