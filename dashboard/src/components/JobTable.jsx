const STATUSES = ['QUEUED', 'PROCESSING', 'DONE', 'FAILED', 'DEAD', 'EXPIRED', 'CANCELED']

/** Sólo se puede cancelar lo que todavía no terminó. */
const CANCELABLE = new Set(['QUEUED', 'PROCESSING', 'FAILED'])

export function JobTable({ jobs, filter, onFilterChange, onCancel }) {
  const visible = filter ? jobs.filter((job) => job.status === filter) : jobs

  return (
    <section className="jobs">
      <header className="jobs-header">
        <h2>Jobs</h2>
        <div className="filters">
          <button className={!filter ? 'active' : ''} onClick={() => onFilterChange(null)}>
            Todos
          </button>
          {STATUSES.map((status) => (
            <button
              key={status}
              className={filter === status ? 'active' : ''}
              onClick={() => onFilterChange(status)}
            >
              {status}
            </button>
          ))}
        </div>
      </header>

      {visible.length === 0 ? (
        <p className="empty">No hay jobs para mostrar todavía.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Job</th>
              <th>Modelo</th>
              <th>Prioridad</th>
              <th>Estado</th>
              <th>Intentos</th>
              <th>Tokens</th>
              <th>Worker</th>
              <th>Resultado</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {visible.map((job) => (
              <tr key={job.id}>
                <td className="mono">{job.id.slice(0, 8)}</td>
                <td>
                  {job.model}
                  <span className="job-type">{job.type}</span>
                </td>
                <td>
                  <span className={`priority ${job.priority?.toLowerCase()}`}>{job.priority}</span>
                </td>
                <td>
                  <span className={`badge ${job.status?.toLowerCase()}`}>{job.status}</span>
                </td>
                <td>{job.retryCount}</td>
                <td>{job.tokensUsed ?? '—'}</td>
                <td className="mono">{job.claimedBy ?? '—'}</td>
                <td className="result">{job.error ? <span className="err">{job.error}</span> : job.result ?? '—'}</td>
                <td>
                  {CANCELABLE.has(job.status) && (
                    <button className="ghost small" onClick={() => onCancel?.(job.id)}>
                      Cancelar
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}
