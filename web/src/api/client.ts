import type { ApiErrorBody, CalculationRequest, CalculationResult } from './types'

/**
 * Error carrying the backend's unified {code, message} body (R77). Business
 * failures answer HTTP 400/500; the FCS code is surfaced as-is to the UI.
 */
export class ApiError extends Error {
  readonly code: string
  readonly status: number

  constructor(code: string, message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

const BASE_URL = '/api/v1'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(`${BASE_URL}${path}`, init)
  } catch (cause) {
    throw new ApiError('NETWORK_ERROR', `请求失败：${String(cause)}`, 0)
  }

  if (!response.ok) {
    let body: Partial<ApiErrorBody> = {}
    try {
      body = (await response.json()) as Partial<ApiErrorBody>
    } catch {
      // non-JSON error body: fall through with the HTTP status text
    }
    throw new ApiError(body.code ?? 'UNKNOWN_ERROR', body.message ?? response.statusText, response.status)
  }
  return (await response.json()) as T
}

/** POST /api/v1/calculations → 运行一次测算，返回租金表/现金流/金融指标。 */
export function calculate(req: CalculationRequest): Promise<CalculationResult> {
  return request<CalculationResult>('/calculations', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  })
}
