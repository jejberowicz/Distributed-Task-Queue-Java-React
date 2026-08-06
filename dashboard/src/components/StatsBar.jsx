const CARDS = [
  { key: 'QUEUED', label: 'En cola', tone: 'queued' },
  { key: 'PROCESSING', label: 'Procesando', tone: 'processing' },
  { key: 'DONE', label: 'Completados', tone: 'done' },
  { key: 'FAILED', label: 'Reintentando', tone: 'failed' },
  { key: 'DEAD', label: 'Dead letter', tone: 'dead' },
  { key: 'EXPIRED', label: 'Expirados', tone: 'expired' }
]

export function StatsBar({ stats }) {
  const byStatus = stats?.byStatus ?? {}
  const streams = stats?.streams ?? {}

  return (
    <section className="stats">
      <div className="stats-grid">
        {CARDS.map((card) => (
          <article key={card.key} className={`stat-card ${card.tone}`}>
            <span className="stat-value">{byStatus[card.key] ?? 0}</span>
            <span className="stat-label">{card.label}</span>
          </article>
        ))}
      </div>

      <div className="stream-row">
        <StreamStat label="Stream priority" value={streams.priorityDepth} />
        <StreamStat label="Stream standard" value={streams.standardDepth} />
        <StreamStat
          label="En vuelo (PEL)"
          value={(streams.priorityPending ?? 0) + (streams.standardPending ?? 0)}
          hint="Mensajes entregados sin XACK. Si un worker muere, estos son los que se reclaman."
        />
        <StreamStat label="Retries diferidos" value={streams.delayedRetries} hint="Esperando su backoff" />
        <StreamStat label="DLQ" value={streams.deadLetterDepth} />
      </div>
    </section>
  )
}

function StreamStat({ label, value, hint }) {
  return (
    <div className="stream-stat" title={hint ?? ''}>
      <span className="stream-label">{label}</span>
      <span className="stream-value">{value ?? 0}</span>
    </div>
  )
}
