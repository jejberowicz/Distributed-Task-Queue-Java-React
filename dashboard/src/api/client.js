const API_BASE = import.meta.env.VITE_API_BASE ?? ''

export const KEY_STORAGE = 'inferqueue.apiKey'
const ADMIN_STORAGE = 'inferqueue.adminToken'

export function storedKey() {
  return localStorage.getItem(KEY_STORAGE) ?? ''
}

export function storeKey(key) {
  localStorage.setItem(KEY_STORAGE, key)
}

export function clearKey() {
  localStorage.removeItem(KEY_STORAGE)
}

export function storedAdminToken() {
  return localStorage.getItem(ADMIN_STORAGE) ?? ''
}

export function storeAdminToken(token) {
  if (token) localStorage.setItem(ADMIN_STORAGE, token)
  else localStorage.removeItem(ADMIN_STORAGE)
}

async function request(path, { method = 'GET', body, apiKey, adminToken } = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(apiKey ? { Authorization: `Bearer ${apiKey}` } : {}),
      ...(adminToken ? { 'X-Admin-Token': adminToken } : {})
    },
    body: body ? JSON.stringify(body) : undefined
  })

  const payload = await response.json().catch(() => ({}))
  if (!response.ok) {
    const message = payload.error ?? `HTTP ${response.status}`
    const error = new Error(message)
    error.status = response.status
    // El rate limit trae la ventana en headers; sirve para avisar cuándo reintentar.
    error.retryAt = response.headers.get('X-RateLimit-Reset')
    throw error
  }
  return payload
}

export const api = {
  listJobs: (apiKey, status) =>
    request(`/v1/jobs${status ? `?status=${status}` : ''}`, { apiKey }),
  submitJob: (apiKey, job) => request('/v1/jobs', { method: 'POST', body: job, apiKey }),
  cancelJob: (apiKey, id) => request(`/v1/jobs/${id}`, { method: 'DELETE', apiKey }),
  stats: (apiKey) => request('/v1/stats', { apiKey }),
  usage: (apiKey) => request('/v1/jobs/usage', { apiKey }),
  deadLetters: (adminToken) => request('/admin/dlq?limit=50', { adminToken }),
  requeueDeadLetter: (adminToken, recordId) =>
    request(`/admin/dlq/${encodeURIComponent(recordId)}/requeue`, { method: 'POST', adminToken }),
  discardDeadLetter: (adminToken, recordId) =>
    request(`/admin/dlq/${encodeURIComponent(recordId)}`, { method: 'DELETE', adminToken }),
  issueKey: (adminToken, name, tier) =>
    request('/admin/api-keys', { method: 'POST', body: { name, tier }, adminToken })
}
