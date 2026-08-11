import { useCallback, useEffect, useState } from 'react'
import { api, storeAdminToken, storedAdminToken } from '../api/client'

/**
 * Vista de operación sobre la DLQ. Va contra /admin, así que necesita el token
 * de admin y no la API key: son dos credenciales distintas a propósito, mirar
 * los jobs muertos de todos los tenants no es algo que pueda hacer un cliente.
 */
export function DeadLetterPanel({ depth, onChanged }) {
  const [adminToken, setAdminToken] = useState(storedAdminToken())
  const [entries, setEntries] = useState([])
  const [error, setError] = useState(null)
  const [open, setOpen] = useState(false)

  const load = useCallback(async () => {
    if (!adminToken) return
    try {
      const payload = await api.deadLetters(adminToken)
      setEntries(payload.items ?? [])
      setError(null)
    } catch (e) {
      setError(e.message)
    }
  }, [adminToken])

  useEffect(() => {
    if (open) load()
  }, [open, load, depth])

  async function act(action, recordId) {
    try {
      await action(adminToken, recordId)
      await load()
      onChanged?.()
    } catch (e) {
      setError(e.message)
    }
  }

  return (
    <section className="dlq">
      <header className="jobs-header">
        <h2>
          Dead letter <span className="dlq-count">{depth ?? 0}</span>
        </h2>
        <button className="ghost" onClick={() => setOpen((current) => !current)}>
          {open ? 'Ocultar' : 'Inspeccionar'}
        </button>
      </header>

      {open && (
        <>
          <label className="admin-token">
            Token de admin
            <input
              value={adminToken}
              placeholder="dev-admin-token"
              onChange={(e) => {
                setAdminToken(e.target.value)
                storeAdminToken(e.target.value)
              }}
            />
          </label>

          {error && <p className="error">{error}</p>}

          {entries.length === 0 ? (
            <p className="empty">
              {adminToken ? 'No hay jobs muertos.' : 'Pegá el token de admin para ver la DLQ.'}
            </p>
          ) : (
            <table>
              <thead>
                <tr>
                  <th>Job</th>
                  <th>Prioridad</th>
                  <th>Intentos</th>
                  <th>Motivo</th>
                  <th>Muerto</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {entries.map((entry) => (
                  <tr key={entry.recordId}>
                    <td className="mono">{entry.jobId.slice(0, 8)}</td>
                    <td>
                      <span className={`priority ${entry.priority?.toLowerCase()}`}>{entry.priority}</span>
                    </td>
                    <td>{entry.attempt}</td>
                    <td className="result">
                      <span className="err">{entry.reason}</span>
                    </td>
                    <td className="mono">{new Date(entry.deadAt).toLocaleTimeString()}</td>
                    <td className="dlq-actions">
                      <button className="small" onClick={() => act(api.requeueDeadLetter, entry.recordId)}>
                        Reencolar
                      </button>
                      <button className="ghost small" onClick={() => act(api.discardDeadLetter, entry.recordId)}>
                        Descartar
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </section>
  )
}
