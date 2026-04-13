import React from 'react'
import clsx from 'clsx'
import { format } from 'date-fns'
import type { Issue } from '../../types'
import { issuesApi } from '../../api/client'
import { GitPullRequest, CheckCircle, AlertTriangle, ChevronDown, ChevronUp } from 'lucide-react'
import { useState } from 'react'

interface Props {
  issue: Issue
  onUpdated?: () => void
}

export function IssueDetail({ issue, onUpdated }: Props) {
  const [showRaw, setShowRaw] = useState(false)
  const [loading, setLoading] = useState(false)

  async function handleResolve() {
    setLoading(true)
    try {
      await issuesApi.resolve(issue.id, 'Manually resolved via CSIP UI')
      onUpdated?.()
    } finally {
      setLoading(false)
    }
  }

  async function handleFalsePositive() {
    setLoading(true)
    try {
      await issuesApi.markFalsePositive(issue.id)
      onUpdated?.()
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="p-6 space-y-5 text-sm overflow-y-auto h-full">
      {/* Header */}
      <div>
        <div className="flex items-start justify-between gap-4">
          <h2 className="text-base font-semibold text-slate-100 leading-snug">{issue.title}</h2>
          <span className={clsx(
            'flex-shrink-0 text-xs font-bold px-2 py-1 rounded uppercase',
            issue.severity === 'p0' ? 'bg-red-700 text-red-100' :
            issue.severity === 'p1' ? 'bg-orange-700 text-orange-100' :
            issue.severity === 'p2' ? 'bg-amber-700 text-amber-100' :
            'bg-slate-700 text-slate-300',
          )}>
            {issue.severity}
          </span>
        </div>
        <p className="text-slate-400 mt-1 text-xs">
          {issue.category} · {issue.classification_method} · {Math.round(issue.confidence * 100)}% confidence
        </p>
      </div>

      {/* Root cause */}
      {issue.root_cause_summary && (
        <Section title="Root Cause">
          <p className="text-slate-300">{issue.root_cause_summary}</p>
        </Section>
      )}

      {/* Technical detail */}
      {issue.technical_detail && (
        <Section title="Technical Detail">
          <p className="text-slate-400 whitespace-pre-wrap font-mono text-xs">{issue.technical_detail}</p>
        </Section>
      )}

      {/* Affected location */}
      {(issue.affected_file || issue.affected_class) && (
        <Section title="Affected Location">
          <div className="font-mono text-xs space-y-1 text-slate-300">
            {issue.affected_file && <p>File: <span className="text-blue-400">{issue.affected_file}</span></p>}
            {issue.affected_class && <p>Class: <span className="text-purple-400">{issue.affected_class}</span></p>}
            {issue.affected_method && <p>Method: <span className="text-green-400">{issue.affected_method}</span></p>}
          </div>
        </Section>
      )}

      {/* PR */}
      {issue.fix_pr_url && (
        <Section title="Fix PR">
          <a
            href={issue.fix_pr_url}
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-2 text-emerald-400 hover:text-emerald-300"
          >
            <GitPullRequest className="h-4 w-4" />
            PR #{issue.fix_pr_number} — human approval required
          </a>
        </Section>
      )}

      {/* Metadata */}
      <Section title="Metadata">
        <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-xs">
          <Dt>First seen</Dt><Dd>{format(new Date(issue.first_seen_at), 'MMM d HH:mm:ss')}</Dd>
          <Dt>Last seen</Dt><Dd>{format(new Date(issue.last_seen_at), 'MMM d HH:mm:ss')}</Dd>
          <Dt>Occurrences</Dt><Dd>{issue.error_count}</Dd>
          <Dt>Fix attempts</Dt><Dd>{issue.fix_attempts}</Dd>
          <Dt>LLM tokens</Dt><Dd>{issue.llm_tokens_used.toLocaleString()}</Dd>
          <Dt>LLM cost</Dt><Dd>${issue.llm_cost_usd.toFixed(4)}</Dd>
          <Dt>Status</Dt><Dd className="capitalize">{issue.status.replace(/_/g, ' ')}</Dd>
        </dl>
      </Section>

      {/* Fix attempt history */}
      {issue.fix_attempt_history?.length > 0 && (
        <Section title={`Fix Attempts (${issue.fix_attempt_history.length})`}>
          {issue.fix_attempt_history.map((attempt, idx) => (
            <div key={idx} className="text-xs border border-slate-700 rounded p-3 mb-2">
              <p className="font-medium text-slate-300 mb-1">Attempt #{attempt.attempt_number}</p>
              {attempt.escalated && (
                <p className="text-orange-400">Escalated: {attempt.escalation_reason}</p>
              )}
              {attempt.failure_reason && (
                <p className="text-red-400">Failed: {attempt.failure_reason}</p>
              )}
              {attempt.pr_url && (
                <a href={attempt.pr_url} target="_blank" rel="noreferrer" className="text-emerald-400">
                  PR #{attempt.pr_number}
                </a>
              )}
            </div>
          ))}
        </Section>
      )}

      {/* Actions */}
      {!['resolved', 'false_positive'].includes(issue.status) && (
        <div className="flex gap-3 pt-2">
          <button
            onClick={handleResolve}
            disabled={loading}
            className="flex items-center gap-2 px-3 py-1.5 bg-emerald-700 hover:bg-emerald-600 text-emerald-100 rounded text-xs font-medium disabled:opacity-50"
          >
            <CheckCircle className="h-3.5 w-3.5" /> Mark Resolved
          </button>
          <button
            onClick={handleFalsePositive}
            disabled={loading}
            className="flex items-center gap-2 px-3 py-1.5 bg-slate-700 hover:bg-slate-600 text-slate-300 rounded text-xs font-medium disabled:opacity-50"
          >
            <AlertTriangle className="h-3.5 w-3.5" /> False Positive
          </button>
        </div>
      )}
    </div>
  )
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <h3 className="text-xs font-semibold text-slate-500 uppercase tracking-wide mb-2">{title}</h3>
      {children}
    </div>
  )
}

function Dt({ children }: { children: React.ReactNode }) {
  return <dt className="text-slate-500">{children}</dt>
}

function Dd({ children, className }: { children: React.ReactNode; className?: string }) {
  return <dd className={clsx('text-slate-300', className)}>{children}</dd>
}
