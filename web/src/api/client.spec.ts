import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, calculate } from './client'
import type { CalculationRequest } from './types'

function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: status === 400 ? 'Bad Request' : '',
    json: () => Promise.resolve(body),
  } as unknown as Response
}

describe('api client', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('calculate() posts the request body as JSON', async () => {
    vi.mocked(fetch).mockResolvedValue(
      jsonResponse({ runId: 'abc', schedule: null, effectiveInterest: null }),
    )
    const req = { principal: '1000000', nominalRate: '0.06' }

    await calculate(req as unknown as CalculationRequest)

    const [url, init] = vi.mocked(fetch).mock.calls[0]
    expect(url).toBe('/api/v1/calculations')
    expect(init?.method).toBe('POST')
    expect(init?.headers).toEqual({ 'Content-Type': 'application/json' })
    expect(JSON.parse(init?.body as string)).toEqual(req)
  })

  it('throws ApiError carrying {code, message} on non-2xx responses', async () => {
    vi.mocked(fetch).mockResolvedValue(
      jsonResponse({ code: 'FCS-DOM-1001', message: '本金必须为正数' }, 400),
    )

    const promise = calculate({ principal: '1' } as unknown as CalculationRequest)
    await expect(promise).rejects.toBeInstanceOf(ApiError)
    const err = (await promise.catch((e) => e)) as ApiError
    expect(err.code).toBe('FCS-DOM-1001')
    expect(err.message).toBe('本金必须为正数')
    expect(err.status).toBe(400)
  })

  it('throws ApiError with NETWORK_ERROR when fetch itself fails', async () => {
    vi.mocked(fetch).mockRejectedValue(new TypeError('Failed to fetch'))

    const err = (await calculate({ principal: '1' } as unknown as CalculationRequest).catch(
      (e) => e,
    )) as ApiError
    expect(err).toBeInstanceOf(ApiError)
    expect(err.code).toBe('NETWORK_ERROR')
  })
})
