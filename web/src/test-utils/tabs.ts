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

/**
 * 场景面板的可见性语义：Tabs 只隐藏非活动面板，不销毁它（R130，显式重置是表单自己的事，
 * 隐式丢弃用户已填的要素是错的）。未访问过的面板缺席 —— AntD Tabs 懒渲染；
 * 访问过而切走的面板仍在 DOM 里但被隐藏（AntD 给它 inline display:none +
 * aria-hidden="true"），所以状态跨场景切换保留。
 * （Task 6：App.spec 与 MortgageForm.spec 共用，与 activateTab 同款抽取。）
 */
export function paneState(wrapper: VueWrapper, selector: string): 'active' | 'hidden' | 'absent' {
  const found = wrapper.find(selector)
  if (!found.exists()) return 'absent'
  const pane = found.element.closest('.ant-tabs-tabpane')
  expect(pane, `${selector} 应在一个 TabPane 内`).toBeTruthy()
  const element = pane as HTMLElement
  const hidden = element.getAttribute('aria-hidden') === 'true' || element.style.display === 'none'
  return hidden ? 'hidden' : 'active'
}
