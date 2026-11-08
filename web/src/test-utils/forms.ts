import { flushPromises } from '@vue/test-utils'

/**
 * AntD 表单的校验状态不是纯微任务：FormItem 的 has-error 类经过 `useDebounce`
 * （清空错误时走 setTimeout(…, 10)），错误文案又挂着进出场过渡。
 * `flushPromises()` 只排空微任务，所以断言字段校验状态之前要放一个真实的短等待。
 *
 * （基线里「金额格式无效」那条偶发失败正是同一类时序：断言比过渡收尾先跑。）
 */
export async function settleFormState(): Promise<void> {
  await flushPromises()
  await new Promise((resolve) => {
    setTimeout(resolve, 40)
  })
  await flushPromises()
}

/** 字段级校验状态：读 FormItem 的 has-error 类（调用前须先 settleFormState()）。 */
export function fieldHasError(root: HTMLElement, selector: string): boolean {
  const item = root.querySelector(selector)?.closest('.ant-form-item')
  if (item === undefined || item === null) return false
  return item.classList.contains('ant-form-item-has-error')
}
