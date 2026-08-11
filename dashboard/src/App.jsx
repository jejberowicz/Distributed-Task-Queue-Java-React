import { useState } from 'react'
import { ApiKeyGate } from './components/ApiKeyGate'
import { DeadLetterPanel } from './components/DeadLetterPanel'
import { JobTable } from './components/JobTable'
import { StatsBar } from './components/StatsBar'
import { SubmitForm } from './components/SubmitForm'
import { useJobStream } from './hooks/useJobStream'
import { api, clearKey, storeKey, storedKey } from './api/client'

export default function App() {
  const [apiKey, setApiKey] = useState(storedKey())
  const [filter, setFilter] = useState(null)
  const { jobs, stats, connected, error, refresh, setError } = useJobStream(apiKey)

  async function cancel(id) {
    try {
      await api.cancelJob(apiKey, id)
      // No hace falta refrescar: la cancelación emite su propio evento por WS.
    } catch (e) {
      setError(e.message)
    }
  }

  if (!apiKey) {
    return (
      <ApiKeyGate
        onReady={(key) => {
          storeKey(key)
          setApiKey(key)
        }}
      />
    )
  }

  return (
    <div className="app">
      <header className="topbar">
        <div>
          <h1>InferQueue</h1>
          <span className="tagline">Cola de inference sobre Redis Streams</span>
        </div>
        <div className="topbar-right">
          <span className={`conn ${connected ? 'on' : 'off'}`}>
            {connected ? 'WebSocket conectado' : 'Reconectando…'}
          </span>
          <button
            className="ghost"
            onClick={() => {
              clearKey()
              setApiKey('')
            }}
          >
            Cambiar key
          </button>
        </div>
      </header>

      {error && (
        <div className="error banner" onClick={() => setError(null)}>
          {error}
        </div>
      )}

      <StatsBar stats={stats} />
      <SubmitForm apiKey={apiKey} onSubmitted={refresh} onError={setError} />
      <JobTable jobs={jobs} filter={filter} onFilterChange={setFilter} onCancel={cancel} />
      <DeadLetterPanel depth={stats?.streams?.deadLetterDepth} onChanged={refresh} />
    </div>
  )
}
