const API_BASE = import.meta.env.VITE_API_BASE ?? ''

export const KEY_STORAGE = 'inferqueue.apiKey'

export function storedKey() {
  return localStorage.getItem(KEY_STORAGE) ?? ''
}

export function storeKey(key) {
  localStorage.setItem(KEY_STORAGE, key)
}

export function clearKey() {
  localStorage.removeItem(KEY_STORAGE)
}

async function request(path, { method = 'GET', body, apiKey } = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(apiKey ? { Authorization: `Bearer ${apiKey}` } : {})
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
  stats: (apiKey) => request('/v1/stats', { apiKey }),
  usage: (apiKey) => request('/v1/jobs/usage', { apiKey }),
  issueKey: (adminToken, name, tier) =>
    fetch(`${API_BASE}/admin/api-keys`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Admin-Token': adminToken },
      body: JSON.stringify({ name, tier })
    }).then(async (r) => {
      const payload = await r.json().catch(() => ({}))
      if (!r.ok) throw new Error(payload.error ?? `HTTP ${r.status}`)
      return payload
    })
}
