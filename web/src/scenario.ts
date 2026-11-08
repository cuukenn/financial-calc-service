/**
 * 场景选中态（Task 6 / §1）：两个测算场景（R100 同一个引擎、同一个契约）之间的
 * 选中 Tab 记忆到 localStorage，形态完全对齐 i18n 的语言偏好持久化
 * （`i18n/index.ts` 的 fcs.locale：读取用 try/catch 回落默认，写入 try/catch 吞掉失败）。
 *
 * 只有这一个 key、只有这两个值 —— 没有 Pinia、没有 Router、没有别的持久化（R100）。
 * 记忆的是「选中哪个场景」，不是测算结果：面板本身仍只隐藏不销毁（R130），
 * 显式的清空由两个表单自己的重置按钮负责（Task 6 §2）。
 */

export type ScenarioKey = 'lease' | 'mortgage'

export const SCENARIO_STORAGE_KEY = 'fcs.scenario'

/** 合法取值集合：存储里出现别的值一律按「不认识」处理。 */
const SCENARIO_VALUES: readonly ScenarioKey[] = ['lease', 'mortgage']

/** 默认场景 = 融资租赁（与既有单页形态一致）。 */
export const DEFAULT_SCENARIO: ScenarioKey = 'lease'

function isScenarioKey(value: unknown): value is ScenarioKey {
  return typeof value === 'string' && (SCENARIO_VALUES as readonly string[]).includes(value)
}

/** 读取选中态：不可用（隐私模式）或值不认识时回落默认，不猜、不抛。 */
export function readScenario(): ScenarioKey {
  try {
    const stored = localStorage.getItem(SCENARIO_STORAGE_KEY)
    return isScenarioKey(stored) ? stored : DEFAULT_SCENARIO
  } catch {
    return DEFAULT_SCENARIO
  }
}

/** 写入选中态：localStorage 不可用时只保留本次会话的切换。 */
export function writeScenario(scenario: ScenarioKey): void {
  try {
    localStorage.setItem(SCENARIO_STORAGE_KEY, scenario)
  } catch {
    // 隐私模式 / 配额耗尽：选中态仍在内存里，只是不跨会话。
  }
}
