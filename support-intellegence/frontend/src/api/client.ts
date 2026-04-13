import axios from 'axios'
import type { App, Issue, AuditRecord, SystemHealth } from '../types'

const api = axios.create({
  baseURL: '/api/v1',
  timeout: 30_000,
  headers: { 'Content-Type': 'application/json' },
})

// ── Apps ─────────────────────────────────────────────────────────────────────
export const appsApi = {
  list: (params?: { team_id?: string; tier?: string; status?: string }) =>
    api.get<App[]>('/apps', { params }).then((r) => r.data),
  get: (id: string) => api.get<App>(`/apps/${id}`).then((r) => r.data),
  register: (body: { url: string; description?: string; team_id?: string; tier?: string }) =>
    api.post<App>('/apps', body).then((r) => r.data),
  update: (id: string, body: Partial<App>) =>
    api.patch<App>(`/apps/${id}`, body).then((r) => r.data),
  delete: (id: string) => api.delete(`/apps/${id}`),
  pause: (id: string) => api.post(`/apps/${id}/pause`).then((r) => r.data),
  resume: (id: string) => api.post(`/apps/${id}/resume`).then((r) => r.data),
  health: (id: string) => api.get(`/apps/${id}/health`).then((r) => r.data),
}

// ── Issues ────────────────────────────────────────────────────────────────────
export const issuesApi = {
  list: (params?: {
    status?: string; category?: string; severity?: string
    team_id?: string; app_id?: string; limit?: number; offset?: number
  }) => api.get<Issue[]>('/issues', { params }).then((r) => r.data),
  get: (id: string) => api.get<Issue>(`/issues/${id}`).then((r) => r.data),
  resolve: (id: string, notes?: string) =>
    api.patch(`/issues/${id}/resolve`, { notes }).then((r) => r.data),
  escalate: (id: string, reason: string) =>
    api.post(`/issues/${id}/escalate`, { reason }).then((r) => r.data),
  markFalsePositive: (id: string) =>
    api.post(`/issues/${id}/false-positive`).then((r) => r.data),
}

// ── Audit ─────────────────────────────────────────────────────────────────────
export const auditApi = {
  list: (params?: {
    event_type?: string; app_id?: string; issue_id?: string
    team_id?: string; limit?: number; offset?: number
  }) => api.get<AuditRecord[]>('/audit', { params }).then((r) => r.data),
  get: (id: string, verify?: boolean) =>
    api.get<AuditRecord>(`/audit/${id}`, { params: { verify } }).then((r) => r.data),
}

// ── System ────────────────────────────────────────────────────────────────────
export const systemApi = {
  health: () => api.get<SystemHealth>('/system/health').then((r) => r.data),
  stats: () => api.get('/system/stats').then((r) => r.data),
}

export default api
