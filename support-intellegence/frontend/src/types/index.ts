export type AppStatus = 'healthy' | 'degraded' | 'down' | 'unknown' | 'paused'
export type AppTier = 'p0' | 'p1' | 'p2' | 'p3'
export type IssueCategory = 'infrastructure' | 'configuration' | 'code' | 'unknown'
export type IssueSeverity = 'p0' | 'p1' | 'p2' | 'p3'
export type IssueStatus =
  | 'open' | 'fast_classified' | 'llm_analyzing' | 'fix_queued'
  | 'fix_building' | 'fix_testing' | 'pr_open' | 'pr_merged'
  | 'deployed_staging' | 'resolved' | 'escalated' | 'false_positive'

export interface App {
  id: string
  name: string
  team_id: string
  namespace: string
  tier: AppTier
  base_url: string
  health_path: string
  metrics_path: string | null
  kafka_topics_produced: string[]
  repo_url: string | null
  repo_branch: string
  codeowners: string[]
  tech_stack: string
  description: string | null
  polling_interval_secs: number
  fix_auto_pr: boolean
  fix_require_human: boolean
  status: AppStatus
  created_by: string | null
  created_at: string
  updated_at: string
}

export interface Issue {
  id: string
  app_id: string
  team_id: string
  title: string
  category: IssueCategory
  severity: IssueSeverity
  confidence: number
  root_cause_summary: string | null
  technical_detail: string | null
  classification_method: string
  error_count: number
  affected_file: string | null
  affected_class: string | null
  affected_method: string | null
  fix_branch: string | null
  fix_pr_url: string | null
  fix_pr_number: number | null
  fix_attempts: number
  fix_attempt_history: FixAttempt[]
  pagerduty_incident_id: string | null
  status: IssueStatus
  llm_tokens_used: number
  llm_cost_usd: number
  first_seen_at: string
  last_seen_at: string
  created_at: string
  updated_at: string
  resolved_at: string | null
  resolution_notes: string | null
}

export interface FixAttempt {
  attempt_number: number
  diff: string | null
  escalated: boolean
  escalation_reason: string | null
  build_passed: boolean
  smoke_passed: boolean
  pr_url: string | null
  pr_number: number | null
  failure_reason: string | null
}

export interface AuditRecord {
  id: string
  sequence_number: number
  event_type: string
  app_id: string | null
  issue_id: string | null
  team_id: string | null
  actor: string
  summary: string
  details: Record<string, unknown>
  diff: string | null
  previous_hash: string
  record_hash: string
  timestamp: string
  integrity_valid?: boolean
}

export interface SystemHealth {
  status: 'healthy' | 'degraded' | 'error'
  uptime_seconds: number
  components: Record<string, string>
  version: string
}

export interface BudgetUsage {
  agent_type: string
  used: number
  budget: number
  remaining: number
  pct_used: number
}

export interface WsEvent {
  type: string
  [key: string]: unknown
}
