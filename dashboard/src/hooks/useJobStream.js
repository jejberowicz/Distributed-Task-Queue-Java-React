import { useCallback, useEffect, useRef, useState } from 'react'
import { Client } from '@stomp/stompjs'
import { api } from '../api/client'

const WS_URL =
  import.meta.env.VITE_WS_URL ??
  `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/ws`

const MAX_JOBS = 200

/**
 * Estado de los jobs: se hidrata con un GET inicial y desde ahí se mantiene
 * vivo por WebSocket. El polling de stats queda como red de seguridad para el
 * caso en que el socket se caiga.
 */
export function useJobStream(apiKey) {
  const [jobs, setJobs] = useState([])
  const [stats, setStats] = useState(null)
  const [connected, setConnected] = useState(false)
  const [error, setError] = useState(null)
  // El topic es por API key, así que hay que saber el id antes de suscribirse.
  const [apiKeyId, setApiKeyId] = useState(null)

  const upsert = useCallback((event) => {
    setJobs((current) => {
      const index = current.findIndex((job) => job.id === event.jobId)
      const incoming = {
        id: event.jobId,
        model: event.model,
        type: event.jobType,
        priority: event.priority,
        status: event.status,
        result: event.result,
        error: event.error,
        tokensUsed: event.tokensUsed,
        retryCount: event.retryCount,
        claimedBy: event.claimedBy,
        createdAt: event.createdAt,
        completedAt: event.completedAt
      }
      if (index === -1) {
        return [incoming, ...current].slice(0, MAX_JOBS)
      }
      const next = [...current]
      next[index] = { ...next[index], ...incoming }
      return next
    })
  }, [])

  /**
   * Los fragmentos pueden llegar desordenados o repetidos (el pub/sub no
   * garantiza orden), así que se indexan por seq en vez de concatenarse a ciegas.
   */
  const appendChunk = useCallback((event) => {
    setJobs((current) => {
      const index = current.findIndex((job) => job.id === event.jobId)
      if (index === -1) return current
      const next = [...current]
      const chunks = { ...(next[index].chunks ?? {}), [event.seq]: event.chunk }
      next[index] = {
        ...next[index],
        chunks,
        streaming: Object.keys(chunks)
          .map(Number)
          .sort((a, b) => a - b)
          .map((seq) => chunks[seq])
          .join('')
      }
      return next
    })
  }, [])

  const refresh = useCallback(async () => {
    if (!apiKey) return
    try {
      const [jobsPage, currentStats] = await Promise.all([api.listJobs(apiKey), api.stats(apiKey)])
      setJobs(jobsPage.items ?? [])
      setStats(currentStats)
      setError(null)
    } catch (e) {
      setError(e.message)
    }
  }, [apiKey])

  useEffect(() => {
    refresh()
  }, [refresh])

  useEffect(() => {
    if (!apiKey) return
    api
      .usage(apiKey)
      .then((usage) => setApiKeyId(usage.apiKeyId))
      .catch((e) => setError(e.message))
  }, [apiKey])

  // Refresco periódico de las métricas agregadas: los eventos traen jobs
  // individuales, no la profundidad de los streams ni el PEL.
  useEffect(() => {
    if (!apiKey) return undefined
    const interval = setInterval(() => {
      api
        .stats(apiKey)
        .then(setStats)
        .catch(() => {})
    }, 3000)
    return () => clearInterval(interval)
  }, [apiKey])

  useEffect(() => {
    if (!apiKey || !apiKeyId) return undefined

    const client = new Client({
      brokerURL: WS_URL,
      reconnectDelay: 3000,
      // La API key viaja en el CONNECT: el gateway la valida ahí y sólo después
      // deja suscribirse al topic de esa key.
      connectHeaders: { Authorization: `Bearer ${apiKey}` },
      onConnect: () => {
        setConnected(true)
        client.subscribe(`/topic/keys/${apiKeyId}/jobs`, (message) => upsert(JSON.parse(message.body)))
        // Fragmentos de la respuesta mientras el modelo la genera. Van por un
        // topic aparte porque son muchos y no cambian el estado del job.
        client.subscribe(`/topic/keys/${apiKeyId}/jobs/tokens`, (message) => appendChunk(JSON.parse(message.body)))
      },
      onWebSocketClose: () => setConnected(false),
      onStompError: (frame) => setError(frame.headers?.message ?? 'Error STOMP')
    })

    client.activate()
    return () => client.deactivate()
  }, [apiKey, apiKeyId, upsert, appendChunk])

  return { jobs, stats, connected, error, refresh, setError }
}
