import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '@/api/client'
import type { AlgorithmInfo } from '@/api/types'
import { mountWithI18n } from '@/test-utils/mount'
import EngineStatus from './EngineStatus.vue'

vi.mock('@/api/client', () => ({
  ApiError: class ApiError extends Error {
    code: string
    status: number
    constructor(code: string, message: string, status: number) {
      super(message)
      this.code = code
      this.status = status
    }
  },
  ping: vi.fn(),
  getAlgorithms: vi.fn(),
}))

import { getAlgorithms, ping } from '@/api/client'

const ALGORITHMS: AlgorithmInfo[] = [
  { code: 'EQUAL_INSTALLMENT', description: '等额租金（年金）：每期租金相等' },
  { code: 'EQUAL_PRINCIPAL', description: '等额本金：每期归还本金相等' },
  { code: 'EXPLICIT_RENT', description: '显式租金：逐期租金由调用方给定' },
]

describe('EngineStatus', () => {
  beforeEach(() => {
    vi.mocked(ping).mockResolvedValue({ status: 'UP', engine: 'ready' })
    vi.mocked(getAlgorithms).mockResolvedValue(ALGORITHMS)
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  it('renders the ping status and the algorithms fetched from the API client', async () => {
    const wrapper = mountWithI18n(EngineStatus)
    await flushPromises()

    const tag = wrapper.find('[data-test="engine-up"]')
    expect(tag.exists()).toBe(true)
    expect(tag.text()).toContain('UP')
    expect(tag.text()).toContain('engine ready')

    const texts = wrapper.findAll('.ant-list-item').map((row) => row.text())
    expect(texts).toHaveLength(ALGORITHMS.length)
    expect(texts[0]).toContain('EQUAL_INSTALLMENT')
    expect(texts[0]).toContain('等额租金（年金）：每期租金相等')
  })

  it('shows an error alert with the backend code when the algorithm list fails', async () => {
    vi.mocked(getAlgorithms).mockRejectedValue(
      new ApiError('FCS-SYS-9999', '系统繁忙', 500),
    )
    const wrapper = mountWithI18n(EngineStatus)
    await flushPromises()

    const alert = wrapper.find('.ant-alert-error')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('FCS-SYS-9999')
    expect(alert.text()).toContain('系统繁忙')
  })

  it('marks the engine as unreachable when ping fails', async () => {
    vi.mocked(ping).mockRejectedValue(new ApiError('NETWORK_ERROR', '请求失败', 0))
    const wrapper = mountWithI18n(EngineStatus)
    await flushPromises()

    expect(wrapper.find('[data-test="engine-down"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="engine-up"]').exists()).toBe(false)
  })
})
