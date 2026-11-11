/**
 * 结果区三张表的共用口径（D-6：切换内层 Tab 卡顿）。
 *
 * 卡顿的根因不是数据量大，而是每次 `activeKey` 变化都把**已挂载**的表体整棵重建：
 * AntD Tabs 不销毁已访问的 pane，几千个单元格各跑一次 `formatMoney`。两道措施：
 * 1. 行数据用 `detachRows` 解绑成普通对象 —— 与响应式结果对象断开引用，
 *    避免探测/代理开销，也避免下游把行当成可变量改写。
 * 2. 每页 24 行 —— 挂载的单元格从「全表」降到「一页」。
 *
 * 分页配置对象必须是**常量**：每次渲染新建对象会让 AntD Table 认为配置变了而重算。
 */

/** 默认每页行数。 */
export const ROWS_PER_PAGE = 24

/** 可选每页行数（字符串形式，AntD 的 pageSizeOptions 口径）。 */
export const PAGE_SIZE_OPTIONS = ['24', '48', '96'] as const

/** 三张表共用的分页配置（`TablePaginationConfig` 的可赋值子集）；引用稳定。 */
export interface ResultTablePagination {
  pageSize: number
  showSizeChanger: boolean
  pageSizeOptions: string[]
}

export const RESULT_TABLE_PAGINATION: ResultTablePagination = {
  pageSize: ROWS_PER_PAGE,
  showSizeChanger: true,
  pageSizeOptions: [...PAGE_SIZE_OPTIONS],
}

/**
 * 把行数据从响应式结果对象上解绑成普通对象。
 *
 * 浅拷贝即可：行字段全是标量（字符串/数字/null），没有嵌套对象。
 * 调用方应把它放进 `computed`，这样「同一份结果 → 同一个数组引用」，
 * 子组件的 props 保持引用稳定，切换 Tab 时不会被判定为变更。
 */
export function detachRows<T extends object>(rows: readonly T[]): T[] {
  return rows.map((row) => Object.assign({}, row))
}
