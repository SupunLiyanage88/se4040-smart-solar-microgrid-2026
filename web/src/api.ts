export type Role = 'BACKOFFICE' | 'GRID_OPERATOR' | 'PROSUMER'
export type User = {
  id: string; nic: string; userName: string; email: string; role: Role;
  activation: boolean; activationPending: boolean; deactivationRequested: boolean
}
export type Login = { token: string; expiresAtUtc: string; user: User }
export const roleLabel = (role: Role) => ({ BACKOFFICE: 'Backoffice', GRID_OPERATOR: 'Grid Operator', PROSUMER: 'Prosumer' })[role]
export class ApiError extends Error {
  status: number
  constructor(message: string, status: number) { super(message); this.status = status }
}
const base = (import.meta.env.VITE_API_URL || 'http://localhost:5086').replace(/\/$/, '')
export async function api<T>(path: string, token: string | null, method = 'GET', body?: unknown): Promise<T> {
  let response: Response
  try {
    response = await fetch(`${base}/api${path}`, {
      method, headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(15000),
    })
  } catch { throw new ApiError('Cannot reach the account service. Check your connection and try again.', 0) }
  const data = await response.json().catch(() => ({}))
  if (!response.ok) {
    const message = data.message || (data.errors ? Object.values(data.errors).flat().join(' ') : data.title)
    throw new ApiError(response.status === 401 && token ? 'Your session has expired or your account is inactive. Sign in again.' : message || 'The request could not be completed.', response.status)
  }
  return data as T
}
