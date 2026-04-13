import React, { useState } from 'react'
import clsx from 'clsx'
import { formatDistanceToNow } from 'date-fns'
import type { Issue } from '../../types'
import {
  AlertTriangle, CheckCircle, GitPullRequest, Loader, XCircle,
  ChevronRight, Zap, Code2, Settings, Server,
} from 'lucide-react'

const SEVERITY_BADGE: Record<string, string> = {
  p0: 'bg-red-600 text-red-100',
  p1: 'bg-orange-600 text-orange-100',
  p2: 'bg-amber-600 text-amber-100',
  p3: 'bg-slate-600 text-slate-300',
}

const CATEGORY_ICON: Record<string, React.FC<{ className?: string }>> = {
  infrastructure: ({ className }) => <Server className={className} />,
  configuration:  ({ className }) => <Settings className={className} />,
  code:           ({ className }) => <Code2 className={className} />,
  unknown:        ({ className }) => <AlertTriangle className={className} />,
}

const STATUS_ICON: Record<string, React.FC<{ className?: string }>> = {
  open:            ({ className }) => <AlertTriangle className={clsx(className, 'text-red-400')} />,
  llm_analyzing:   ({ className }) => <Loader className={clsx(className, 'text-blue-400 animate-spin')} />,
  fix_building:    ({ className }) => <Loader className={clsx(className, 'text-purple-400 animate-spin')} />,
  fix_testing:     ({ className }) => <Loader className={clsx(className, 'text-purple-400 animate-spin')} />,
  pr_open:         ({ className }) => <GitPullRequest className={clsx(className, 'text-emerald-400')} />,
  resolved:        ({ className }) => <CheckCircle className={clsx(className, 'text-emerald-500')} />,
  escalated:       ({ className }) => <XCircle className={clsx(className, 'text-orange-400')} />,
  false_positive:  ({ className }) => <XCircle className={clsx(className, 'text-slate-500')} />,
}

interface Props {
  issues: Issue[]
  onSelect?: (issue: Issue) => void
  selectedId?: string
}

const FILTER_OPTIONS = {
  status: ['', 'open', 'pr_open', 'escalated', 'resolved'],
  category: ['', 'infrastructure', 'configuration', 'code', 'unknown'],
  severity: ['', 'p0', 'p1', 'p2', 'p3'],
}

export function IssueList({ issues, onSelect, selectedId }: Props) {
  const [filters, setFilters] = useState({ status: '', category: '', severity: '' })

  const filtered = issues.filter((i) => {
    if (filters.status && i.status !== filters.status) return false
    if (filters.category && i.category !== filters.category) return false
    if (filters.severity && i.severity !== filters.severity) return false
    return true
  })

  return (
    <div className="flex flex-col h-full">
      {/* Filters */}
      <div className="flex gap-2 p-3 border-b border-slate-700 flex-wrap">
        {Object.entries(FILTER_OPTIONS).map(([key, opts]) => (
          <select
            key={key}
            value={filters[key as keyof typeof filters]}
            onChange={(e) => setFilters((f) => ({ ...f, [key]: e.target.value }))}
            className="text-xs bg-slate-800 border border-slate-600 text-slate-300 rounded px-2 py-1 focus:outline-none focus:border-blue-500"
          >
            <option value="">All {key}</option>
            {opts.filter(Boolean).map((o) => (
              <option key={o} value={o}>{o}</option>
            ))}
          </select>
        ))}
        <span className="text-xs text-slate-500 self-center ml-auto">
          {filtered.length} / {issues.length}
        </span>
      </div>

      {/* Issue rows */}
      <div className="overflow-y-auto flex-1">
        {filtered.length === 0 ? (
          <p className="text-slate-500 text-sm p-6 text-center">No issues match filters</p>
        ) : (
          filtered.map((issue) => {
            const CatIcon = CATEGORY_ICON[issue.category] ?? CATEGORY_ICON.unknown
            const StatIcon = STATUS_ICON[issue.status] ?? STATUS_ICON.open

            return (
              <button
                key={issue.id}
                onClick={() => onSelect?.(issue)}
                className={clsx(
                  'w-full text-left p-4 border-b border-slate-700 hover:bg-slate-800 transition-colors flex items-start gap-3',
                  selectedId === issue.id && 'bg-slate-800 border-l-2 border-l-blue-500',
                )}
              >
                <StatIcon className="h-4 w-4 mt-0.5 flex-shrink-0" />

                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-1">
                    <span className={clsx(
                      'text-xs font-bold px-1.5 py-0.5 rounded uppercase',
                      SEVERITY_BADGE[issue.severity],
                    )}>
                      {issue.severity}
                    </span>
                    <CatIcon className="h-3 w-3 text-slate-400" />
                    <span className="text-xs text-slate-400 capitalize">{issue.category}</span>
                    {issue.confidence > 0 && (
                      <span className="text-xs text-slate-500">{Math.round(issue.confidence * 100)}%</span>
                    )}
                  </div>

                  <p className="text-sm text-slate-200 font-medium truncate">{issue.title}</p>

                  <div className="flex items-center gap-3 mt-1">
                    <span className="text-xs text-slate-500">
                      {formatDistanceToNow(new Date(issue.created_at), { addSuffix: true })}
                    </span>
                    {issue.error_count > 1 && (
                      <span className="text-xs text-slate-500">×{issue.error_count}</span>
                    )}
                    {issue.fix_pr_url && (
                      <a
                        href={issue.fix_pr_url}
                        target="_blank"
                        rel="noreferrer"
                        onClick={(e) => e.stopPropagation()}
                        className="text-xs text-emerald-400 hover:text-emerald-300 flex items-center gap-1"
                      >
                        <GitPullRequest className="h-3 w-3" />
                        PR #{issue.fix_pr_number}
                      </a>
                    )}
                  </div>
                </div>

                <ChevronRight className="h-4 w-4 text-slate-600 flex-shrink-0 self-center" />
              </button>
            )
          })
        )}
      </div>
    </div>
  )
}
