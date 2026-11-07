import { describe, expect, it } from 'vitest'

import { nextRuleId } from './ruleIds'

describe('nextRuleId', () => {
  it('derives the successor of the max R-index among existing ids', () => {
    expect(nextRuleId(['R1', 'R3'])).toBe('R4')
    expect(nextRuleId([])).toBe('R1')
  })

  it('ignores non-R formats and tolerates freed low ids after removal', () => {
    expect(nextRuleId(['custom-7', '', 'r2'])).toBe('R3')
    // 删除 R1/R2 后再新增不会复用 freed id，也不会与 R3 撞号。
    expect(nextRuleId(['R3'])).toBe('R4')
  })
})
