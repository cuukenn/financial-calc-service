import { describe, expect, it } from 'vitest'

import {
  PAGE_SIZE_OPTIONS,
  RESULT_TABLE_PAGINATION,
  ROWS_PER_PAGE,
  detachRows,
} from './tableProps'

/**
 * D-6：结果区列表的两条口径 —— 行数据与响应式结果解绑、默认每页 12 行。
 */
describe('result table props (D-6)', () => {
  it('defaults to 12 rows per page and offers larger page sizes', () => {
    expect(ROWS_PER_PAGE).toBe(12)
    expect(RESULT_TABLE_PAGINATION.pageSize).toBe(12)
    expect(RESULT_TABLE_PAGINATION.showSizeChanger).toBe(true)
    expect([...PAGE_SIZE_OPTIONS]).toEqual(['12', '24', '48'])
    expect([...RESULT_TABLE_PAGINATION.pageSizeOptions]).toEqual(['12', '24', '48'])
  })

  it('keeps the pagination object referentially stable (a new object every render would re-render the table)', () => {
    expect(RESULT_TABLE_PAGINATION).toBe(RESULT_TABLE_PAGINATION)
    expect(Object.isFrozen(RESULT_TABLE_PAGINATION)).toBe(false)
  })

  it('detaches rows into fresh plain objects so the table never writes back into the response', () => {
    const source = [
      { periodNo: 1, rentDue: '100.00' },
      { periodNo: 2, rentDue: '200.00' },
    ]

    const detached = detachRows(source)

    expect(detached).toHaveLength(2)
    expect(detached).toEqual(source)
    // 每行都是新对象：改表里的行不会回写响应体。
    expect(detached[0]).not.toBe(source[0])
    expect(detached[1]).not.toBe(source[1])

    detached[0].rentDue = '0.00'
    expect(source[0].rentDue).toBe('100.00')
  })

  it('returns an empty array for an empty schedule', () => {
    expect(detachRows([])).toEqual([])
  })
})
