import { create } from 'zustand'
import type { App, Issue, WsEvent } from '../types'

interface MonitorState {
  apps: App[]
  issues: Issue[]
  healthMap: Record<string, { status: string; response_ms: number; timestamp: number }>
  wsConnected: boolean
  lastEvent: WsEvent | null

  setApps: (apps: App[]) => void
  upsertApp: (app: App) => void
  setIssues: (issues: Issue[]) => void
  upsertIssue: (issue: Partial<Issue> & { id: string }) => void
  updateHealth: (appId: string, status: string, responseMs: number) => void
  setWsConnected: (v: boolean) => void
  handleWsEvent: (event: WsEvent) => void
}

export const useMonitorStore = create<MonitorState>((set, get) => ({
  apps: [],
  issues: [],
  healthMap: {},
  wsConnected: false,
  lastEvent: null,

  setApps: (apps) => set({ apps }),
  upsertApp: (app) =>
    set((s) => ({
      apps: s.apps.some((a) => a.id === app.id)
        ? s.apps.map((a) => (a.id === app.id ? { ...a, ...app } : a))
        : [...s.apps, app],
    })),

  setIssues: (issues) => set({ issues }),
  upsertIssue: (partial) =>
    set((s) => ({
      issues: s.issues.some((i) => i.id === partial.id)
        ? s.issues.map((i) => (i.id === partial.id ? { ...i, ...partial } : i))
        : [partial as Issue, ...s.issues],
    })),

  updateHealth: (appId, status, responseMs) =>
    set((s) => ({
      healthMap: {
        ...s.healthMap,
        [appId]: { status, response_ms: responseMs, timestamp: Date.now() },
      },
      apps: s.apps.map((a) => (a.id === appId ? { ...a, status: status as App['status'] } : a)),
    })),

  setWsConnected: (v) => set({ wsConnected: v }),

  handleWsEvent: (event) => {
    set({ lastEvent: event })
    switch (event.type) {
      case 'health_update':
        get().updateHealth(
          event.app_id as string,
          event.status as string,
          (event.response_ms as number) ?? 0,
        )
        break
      case 'issue_opened':
        get().upsertIssue(event.issue as Issue)
        break
      case 'fix_pr_ready':
        get().upsertIssue({
          id: event.issue_id as string,
          status: 'pr_open',
          fix_pr_url: event.pr_url as string,
          fix_pr_number: event.pr_number as number,
        })
        break
      case 'fix_failed':
        get().upsertIssue({
          id: event.issue_id as string,
          status: 'escalated',
        })
        break
    }
  },
}))
