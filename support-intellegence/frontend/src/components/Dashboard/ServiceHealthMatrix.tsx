import React from 'react'
import clsx from 'clsx'
import type { App } from '../../types'
import { useMonitorStore } from '../../store/useMonitorStore'
import { formatDistanceToNow } from 'date-fns'

const STATUS_COLORS: Record<string, string> = {
  healthy:  'bg-emerald-500',
  degraded: 'bg-amber-500',
  down:     'bg-red-600',
  unknown:  'bg-slate-400',
  paused:   'bg-slate-600',
}

const STATUS_PULSE: Record<string, string> = {
  degraded: 'animate-pulse',
  down:     'animate-pulse',
}

interface Props {
  apps: App[]
  onSelectApp?: (app: App) => void
}

export function ServiceHealthMatrix({ apps, onSelectApp }: Props) {
  const { healthMap } = useMonitorStore()

  if (apps.length === 0) {
    return (
      <div className="flex items-center justify-center h-48 text-slate-400 text-sm">
        No services registered yet. Click "Register Service" to add one.
      </div>
    )
  }

  return (
    <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-6 gap-3">
      {apps.map((app) => {
        const health = healthMap[app.id]
        const status = health?.status ?? app.status ?? 'unknown'
        const responseMs = health?.response_ms
        const lastSeen = health?.timestamp ? new Date(health.timestamp) : null

        return (
          <button
            key={app.id}
            onClick={() => onSelectApp?.(app)}
            className="group relative flex flex-col items-start p-3 rounded-lg border border-slate-700 bg-slate-800 hover:border-slate-500 hover:bg-slate-750 transition-all text-left"
          >
            {/* Status indicator */}
            <div className="flex items-center gap-2 w-full mb-2">
              <span
                className={clsx(
                  'h-2.5 w-2.5 rounded-full flex-shrink-0',
                  STATUS_COLORS[status] ?? STATUS_COLORS.unknown,
                  STATUS_PULSE[status],
                )}
              />
              <span className="text-xs font-mono text-slate-400 uppercase">{app.tier}</span>
            </div>

            {/* Service name */}
            <p className="text-sm font-medium text-slate-100 truncate w-full leading-snug">
              {app.name}
            </p>

            {/* Team */}
            <p className="text-xs text-slate-500 mt-0.5 truncate w-full">{app.team_id}</p>

            {/* Response time */}
            {responseMs !== undefined && (
              <p className={clsx(
                'text-xs mt-1 font-mono',
                responseMs < 500 ? 'text-emerald-400' : responseMs < 2000 ? 'text-amber-400' : 'text-red-400'
              )}>
                {responseMs}ms
              </p>
            )}

            {/* Last seen */}
            {lastSeen && (
              <p className="text-xs text-slate-600 mt-0.5">
                {formatDistanceToNow(lastSeen, { addSuffix: true })}
              </p>
            )}

            {/* Status badge */}
            <span className={clsx(
              'absolute top-2 right-2 text-xs px-1.5 py-0.5 rounded font-medium capitalize',
              status === 'healthy'  ? 'bg-emerald-900 text-emerald-300' :
              status === 'degraded' ? 'bg-amber-900 text-amber-300' :
              status === 'down'     ? 'bg-red-900 text-red-300' :
                                     'bg-slate-700 text-slate-400',
            )}>
              {status}
            </span>
          </button>
        )
      })}
    </div>
  )
}
