import { useState } from 'react'
import { api } from '../api/client'

/** Pantalla inicial: pegar una API key existente o emitir una nueva con el token de admin. */
export function ApiKeyGate({ onReady }) {
  const [key, setKey] = useState('')
  const [adminToken, setAdminToken] = useState('dev-admin-token')
  const [tier, setTier] = useState('PREMIUM')
  const [issued, setIssued] = useState(null)
  const [error, setError] = useState(null)

  async function issue() {
    setError(null)
    try {
      const created = await api.issueKey(adminToken, 'dashboard', tier)
      setIssued(created)
      setKey(created.apiKey)
    } catch (e) {
      setError(e.message)
    }
  }

  return (
    <div className="gate">
      <h1>InferQueue</h1>
      <p className="tagline">Cola de inference asíncrona sobre Redis Streams.</p>

      <label>
        API key
        <input value={key} onChange={(e) => setKey(e.target.value)} placeholder="iq_…" />
      </label>
      <button disabled={!key.trim()} onClick={() => onReady(key.trim())}>
        Entrar
      </button>

      <details>
        <summary>No tengo una key</summary>
        <div className="issue-box">
          <label>
            Token de admin
            <input value={adminToken} onChange={(e) => setAdminToken(e.target.value)} />
          </label>
          <label>
            Tier
            <select value={tier} onChange={(e) => setTier(e.target.value)}>
              <option>PREMIUM</option>
              <option>FREE</option>
            </select>
          </label>
          <button onClick={issue}>Emitir key</button>
          {issued && (
            <p className="issued">
              Guardala, se muestra una sola vez: <code>{issued.apiKey}</code>
            </p>
          )}
        </div>
      </details>

      {error && <p className="error">{error}</p>}
    </div>
  )
}
