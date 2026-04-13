import React, { useState, useEffect, useCallback } from 'react'
import { useQuery, useQueryClient, QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { LayoutDashboard, Bug, FileText, Activity, Wifi, WifiOff, Plus, RefreshCw } from 'lucide-react'
import clsx from 'clsx'

import { appsApi, issuesApi, systemApi } from './api/client'
import { useMonitorStore } from './store/useMonitorStore'
import { useWebSocket } from './hooks/useWebSocket'
import { ServiceHealthMatrix } from './components/Dashboard/ServiceHealthMatrix'
import { IssueList } from './components/IssueExplorer/IssueList'
import { IssueDetail } from './components/IssueExplorer/IssueDetail'
import type { App, Issue } from './types'

const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 30_000, retry: 2 } },
})

type Tab = 'dashboard' | 'issues' | 'audit' | 'system'

function AppContent() {
  const [tab, setTab] = useState<Tab>('dashboard')
  const [selectedIssue, setSelectedIssue] = useState<Issue | null>(null)
  const [showRegister, setShowRegister] = useState(false)
  const [registerUrl, setRegisterUrl] = useState('')
  const [registerLoading, setRegisterLoading] = useState(false)

  const { apps, issues, setApps, setIssues, wsConnected, lastEvent } = useMonitorStore()

  useWebSocket()

  // Initial data fetch
  const { data: appsData, refetch: refetchApps } = useQuery({
    queryKey: ['apps'],
    queryFn: () => appsApi.list(),
  })
  const { data: issuesData, refetch: refetchIssues } = useQuery({
    queryKey: ['issues'],
    queryFn: () => issuesApi.list({ limit: 100 }),
  })
  const { data: healthData } = useQuery({
    queryKey: ['system-health'],
    queryFn: () => systemApi.health(),
    refetchInterval: 15_000,
  })
  const { data: statsData } = useQuery({
    queryKey: ['stats'],
    queryFn: () => systemApi.stats(),
    refetchInterval: 30_000,
  })

  useEffect(() => { if (appsData) setApps(appsData) }, [appsData, setApps])
  useEffect(() => { if (issuesData) setIssues(issuesData) }, [issuesData, setIssues])

  // Refetch on WS events
  useEffect(() => {
    if (!lastEvent) return
    if (['issue_opened', 'fix_pr_ready', 'fix_failed'].includes(lastEvent.type as string)) {
      refetchIssues()
    }
  }, [lastEvent, refetchIssues])

  async function handleRegister() {
    if (!registerUrl.trim()) return
    setRegisterLoading(true)
    try {
      await appsApi.register({ url: registerUrl.trim() })
      setRegisterUrl('')
      setShowRegister(false)
      await refetchApps()
    } finally {
      setRegisterLoading(false)
    }
  }

  const openIssues = issues.filter((i) => !['resolved', 'false_positive'].includes(i.status))
  const prOpen = issues.filter((i) => i.status === 'pr_open')

  return (
    <div className="flex h-screen bg-slate-900 text-slate-100 font-sans">
      {/* Sidebar */}
      <aside className="w-56 flex-shrink-0 bg-slate-950 border-r border-slate-800 flex flex-col">
        <div className="p-4 border-b border-slate-800">
          <h1 className="text-sm font-bold text-slate-100 tracking-wide">CSIP</h1>
          <p className="text-xs text-slate-500 mt-0.5">Support Intelligence</p>
        </div>

        <nav className="flex-1 p-2 space-y-1">
          {([
            { id: 'dashboard', label: 'Dashboard', Icon: LayoutDashboard },
            { id: 'issues',    label: `Issues${openIssues.length > 0 ? ` (${openIssues.length})` : ''}`,
              Icon: Bug },
            { id: 'audit',     label: 'Audit Log', Icon: FileText },
            { id: 'system',    label: 'System', Icon: Activity },
          ] as const).map(({ id, label, Icon }) => (
            <button
              key={id}
              onClick={() => setTab(id)}
              className={clsx(
                'w-full flex items-center gap-3 px-3 py-2 rounded text-sm transition-colors',
                tab === id
                  ? 'bg-blue-600 text-white'
                  : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800',
              )}
            >
              <Icon className="h-4 w-4 flex-shrink-0" />
              {label}
            </button>
          ))}
        </nav>

        {/* WS status */}
        <div className="p-4 border-t border-slate-800 flex items-center gap-2">
          {wsConnected
            ? <Wifi className="h-3.5 w-3.5 text-emerald-400" />
            : <WifiOff className="h-3.5 w-3.5 text-red-400" />}
          <span className="text-xs text-slate-500">
            {wsConnected ? 'Live' : 'Reconnecting...'}
          </span>
        </div>
      </aside>

      {/* Main */}
      <main className="flex-1 flex flex-col min-w-0 overflow-hidden">
        {/* Top bar */}
        <header className="flex-shrink-0 h-12 bg-slate-900 border-b border-slate-800 flex items-center px-6 gap-4">
          <h2 className="text-sm font-semibold text-slate-200 capitalize">
            {tab === 'dashboard' ? 'Service Health Dashboard' :
             tab === 'issues'    ? 'Issue Explorer' :
             tab === 'audit'     ? 'Audit Log' : 'System Status'}
          </h2>

          <div className="ml-auto flex items-center gap-3">
            {tab === 'dashboard' && (
              <>
                <button
                  onClick={() => setShowRegister(true)}
                  className="flex items-center gap-1.5 text-xs bg-blue-600 hover:bg-blue-500 text-white px-3 py-1.5 rounded"
                >
                  <Plus className="h-3.5 w-3.5" /> Register Service
                </button>
                <button
                  onClick={() => refetchApps()}
                  className="text-slate-400 hover:text-slate-200 p-1.5 rounded hover:bg-slate-800"
                >
                  <RefreshCw className="h-4 w-4" />
                </button>
              </>
            )}

            {healthData && (
              <span className={clsx(
                'text-xs px-2 py-1 rounded',
                healthData.status === 'healthy' ? 'bg-emerald-900 text-emerald-300' : 'bg-amber-900 text-amber-300',
              )}>
                CSIP {healthData.status}
              </span>
            )}
          </div>
        </header>

        {/* Content */}
        <div className="flex-1 overflow-hidden">
          {tab === 'dashboard' && (
            <div className="p-6 h-full overflow-y-auto">
              {/* Stats bar */}
              {statsData && (
                <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
                  <StatCard label="Services" value={statsData.apps ?? apps.length} />
                  <StatCard label="Open Issues" value={openIssues.length} highlight={openIssues.length > 0} />
                  <StatCard label="Fix PRs Open" value={prOpen.length} highlight={prOpen.length > 0} />
                  <StatCard label="WS Clients" value={statsData.ws_connections ?? 0} />
                </div>
              )}

              <h3 className="text-xs font-semibold text-slate-500 uppercase tracking-wide mb-3">
                All Services ({apps.length})
              </h3>
              <ServiceHealthMatrix apps={apps} />
            </div>
          )}

          {tab === 'issues' && (
            <div className="flex h-full">
              <div className="w-96 flex-shrink-0 border-r border-slate-700 overflow-hidden">
                <IssueList
                  issues={issues}
                  onSelect={setSelectedIssue}
                  selectedId={selectedIssue?.id}
                />
              </div>
              <div className="flex-1 overflow-hidden">
                {selectedIssue ? (
                  <IssueDetail
                    issue={selectedIssue}
                    onUpdated={() => { refetchIssues(); setSelectedIssue(null) }}
                  />
                ) : (
                  <div className="flex items-center justify-center h-full text-slate-500 text-sm">
                    Select an issue to view details
                  </div>
                )}
              </div>
            </div>
          )}

          {tab === 'audit' && <AuditView />}

          {tab === 'system' && (
            <div className="p-6 space-y-4 overflow-y-auto h-full">
              {healthData && (
                <div className="bg-slate-800 rounded-lg p-4">
                  <h3 className="text-sm font-semibold mb-3">Component Health</h3>
                  <div className="space-y-2">
                    {Object.entries(healthData.components).map(([k, v]) => (
                      <div key={k} className="flex items-center justify-between text-xs">
                        <span className="text-slate-400 capitalize">{k}</span>
                        <span className={clsx(
                          'px-2 py-0.5 rounded',
                          v === 'ok' ? 'bg-emerald-900 text-emerald-300' : 'bg-red-900 text-red-300',
                        )}>
                          {v as string}
                        </span>
                      </div>
                    ))}
                  </div>
                  <p className="text-xs text-slate-500 mt-3">
                    Uptime: {Math.floor((healthData.uptime_seconds ?? 0) / 60)}m · v{healthData.version}
                  </p>
                </div>
              )}

              {statsData?.budgets?.length > 0 && (
                <div className="bg-slate-800 rounded-lg p-4">
                  <h3 className="text-sm font-semibold mb-3">LLM Budget Usage (Today)</h3>
                  <div className="space-y-3">
                    {statsData.budgets.map((b: any) => (
                      <div key={b.agent_type}>
                        <div className="flex justify-between text-xs text-slate-400 mb-1">
                          <span>{b.agent_type}</span>
                          <span>{b.used.toLocaleString()} / {b.budget.toLocaleString()} ({b.pct_used}%)</span>
                        </div>
                        <div className="h-1.5 bg-slate-700 rounded-full overflow-hidden">
                          <div
                            className={clsx('h-full rounded-full transition-all',
                              b.pct_used > 90 ? 'bg-red-500' :
                              b.pct_used > 70 ? 'bg-amber-500' : 'bg-blue-500')}
                            style={{ width: `${Math.min(b.pct_used, 100)}%` }}
                          />
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      </main>

      {/* Register modal */}
      {showRegister && (
        <div className="fixed inset-0 bg-black/60 flex items-center justify-center z-50">
          <div className="bg-slate-800 rounded-lg p-6 w-96 border border-slate-700">
            <h3 className="font-semibold mb-4">Register Service</h3>
            <input
              type="url"
              placeholder="http://my-service:8080"
              value={registerUrl}
              onChange={(e) => setRegisterUrl(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleRegister()}
              className="w-full bg-slate-700 border border-slate-600 rounded px-3 py-2 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:border-blue-500"
              autoFocus
            />
            <p className="text-xs text-slate-500 mt-2">
              CSIP will auto-discover name and metadata from the health endpoint.
            </p>
            <div className="flex gap-3 mt-4 justify-end">
              <button
                onClick={() => setShowRegister(false)}
                className="px-4 py-2 text-sm text-slate-400 hover:text-slate-200"
              >
                Cancel
              </button>
              <button
                onClick={handleRegister}
                disabled={registerLoading || !registerUrl.trim()}
                className="px-4 py-2 text-sm bg-blue-600 hover:bg-blue-500 text-white rounded disabled:opacity-50"
              >
                {registerLoading ? 'Registering...' : 'Register'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

function StatCard({ label, value, highlight }: { label: string; value: number; highlight?: boolean }) {
  return (
    <div className={clsx(
      'bg-slate-800 rounded-lg p-4 border',
      highlight ? 'border-amber-600' : 'border-slate-700',
    )}>
      <p className="text-xs text-slate-500">{label}</p>
      <p className={clsx('text-2xl font-bold mt-1', highlight ? 'text-amber-400' : 'text-slate-100')}>
        {value}
      </p>
    </div>
  )
}

function AuditView() {
  const { data, isLoading } = useQuery({
    queryKey: ['audit'],
    queryFn: () => import('./api/client').then((m) => m.auditApi.list({ limit: 100 })),
  })

  if (isLoading) return <div className="p-6 text-slate-500 text-sm">Loading audit records...</div>

  return (
    <div className="p-6 overflow-y-auto h-full">
      <div className="space-y-2">
        {(data ?? []).map((record) => (
          <div key={record.id} className="bg-slate-800 rounded p-3 text-xs border border-slate-700">
            <div className="flex items-center gap-3 mb-1">
              <span className="font-mono text-slate-500">{record.timestamp?.slice(0, 19)}</span>
              <span className="bg-blue-900 text-blue-300 px-1.5 py-0.5 rounded">{record.event_type}</span>
              <span className="text-slate-400">{record.actor}</span>
            </div>
            <p className="text-slate-300">{record.summary}</p>
            <p className="text-slate-600 font-mono mt-1 truncate">hash: {record.record_hash}</p>
          </div>
        ))}
        {(data ?? []).length === 0 && (
          <p className="text-slate-500">No audit records yet.</p>
        )}
      </div>
    </div>
  )
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AppContent />
    </QueryClientProvider>
  )
}
