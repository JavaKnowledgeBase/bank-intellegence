import { useEffect, useRef } from 'react'
import { useMonitorStore } from '../store/useMonitorStore'

const WS_URL = `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/api/v1/ws/monitor`
const RECONNECT_DELAY_MS = 3000

export function useWebSocket() {
  const wsRef = useRef<WebSocket | null>(null)
  const reconnectTimer = useRef<ReturnType<typeof setTimeout>>()
  const { handleWsEvent, setWsConnected } = useMonitorStore()

  useEffect(() => {
    function connect() {
      const ws = new WebSocket(WS_URL)
      wsRef.current = ws

      ws.onopen = () => {
        setWsConnected(true)
        // Subscribe to all events in dev
        ws.send(JSON.stringify({ type: 'subscribe_all' }))
      }

      ws.onmessage = (e) => {
        try {
          const event = JSON.parse(e.data)
          handleWsEvent(event)
        } catch {
          // ignore parse errors
        }
      }

      ws.onclose = () => {
        setWsConnected(false)
        reconnectTimer.current = setTimeout(connect, RECONNECT_DELAY_MS)
      }

      ws.onerror = () => {
        ws.close()
      }
    }

    connect()
    return () => {
      clearTimeout(reconnectTimer.current)
      wsRef.current?.close()
    }
  }, [handleWsEvent, setWsConnected])
}
