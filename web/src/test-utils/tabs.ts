import { flushPromises } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { expect } from 'vitest'

/**
 * AntD Tabs 懒渲染：先点激活目标 Tab，再断言其内容。
 * （Task 1 评审发现：此助手在 CalculationForm.spec 与 ResultPanel.spec 中重复，抽为共享测试工具。）
 */
export async function activateTab(wrapper: VueWrapper, tabLabel: string): Promise<void> {
  const tab = wrapper.findAll('.ant-tabs-tab').find((t) => t.text() === tabLabel)
  expect(tab, `tab 「${tabLabel}」 应存在`).toBeTruthy()
  await tab!.trigger('click')
  await flushPromises()
}
