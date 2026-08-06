import { useState } from 'react'
import { api } from '../api/client'

const MODELS = ['llama3', 'llama3:70b', 'nomic-embed-text', 'mistral']

export function SubmitForm({ apiKey, onSubmitted, onError }) {
  const [model, setModel] = useState(MODELS[0])
  const [type, setType] = useState('COMPLETION')
  const [prompt, setPrompt] = useState('')
  const [priority, setPriority] = useState('')
  const [ttlSeconds, setTtlSeconds] = useState('')
  const [sending, setSending] = useState(false)

  async function submit(event) {
    event.preventDefault()
    if (!prompt.trim()) return
    setSending(true)
    try {
      await api.submitJob(apiKey, {
        model,
        type,
        prompt,
        priority: priority || undefined,
        ttlSeconds: ttlSeconds ? Number(ttlSeconds) : undefined
      })
      setPrompt('')
      onSubmitted?.()
    } catch (e) {
      onError?.(e.message)
    } finally {
      setSending(false)
    }
  }

  return (
    <form className="submit-form" onSubmit={submit}>
      <div className="row">
        <label>
          Modelo
          <select value={model} onChange={(e) => setModel(e.target.value)}>
            {MODELS.map((m) => (
              <option key={m}>{m}</option>
            ))}
          </select>
        </label>
        <label>
          Tipo
          <select value={type} onChange={(e) => setType(e.target.value)}>
            <option>COMPLETION</option>
            <option>EMBEDDING</option>
            <option>CLASSIFICATION</option>
          </select>
        </label>
        <label>
          Prioridad
          <select value={priority} onChange={(e) => setPriority(e.target.value)}>
            <option value="">Según el tier</option>
            <option value="PRIORITY">PRIORITY</option>
            <option value="STANDARD">STANDARD</option>
          </select>
        </label>
        <label>
          TTL (s)
          <input
            type="number"
            min="5"
            placeholder="default"
            value={ttlSeconds}
            onChange={(e) => setTtlSeconds(e.target.value)}
          />
        </label>
      </div>

      <textarea
        rows={3}
        value={prompt}
        placeholder='Prompt… (probá con "fail: x" para forzar reintentos y ver la DLQ)'
        onChange={(e) => setPrompt(e.target.value)}
      />

      <button type="submit" disabled={sending || !prompt.trim()}>
        {sending ? 'Encolando…' : 'Encolar job'}
      </button>
    </form>
  )
}
