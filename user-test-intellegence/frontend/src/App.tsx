import { FormEvent, useEffect, useMemo, useState } from "react";

type Role = "viewer" | "qa" | "admin";
type Session = { username: string; display_name: string; role: Role; token: string };
type TestCase = { id: string; title: string; scenario_description: string; target_service: string; endpoint: string; priority: string; status: string; source: string; tags: string[]; last_run_status: string };
type SuiteRun = { id: string; service: string; status: string; trigger: string; trigger_actor: string; commit_sha?: string | null; total_tests: number; total_shards: number; completed_shards: number; passed: number; failed: number; errored: number; duration_ms: number; started_at: string; completed_at?: string | null; shards: Array<{ shard_number: number; status: string; passed: number; failed: number; duration_ms: number; failure_messages: string[] }> };
type CoverageRow = { service: string; endpoints: Record<string, number>; coverage_gaps: string[] };
type ScoutObservation = { id: string; title: string; domain: string; summary: string; safety_status: string; proposed_service: string; tags: string[]; discovered_at: string; source_url: string };
type Proposal = { id: string; title: string; area: string; summary: string; expected_impact: string; status: string; created_at: string };
type DashboardSummary = { totals: { test_cases: number; suite_runs: number; passing_suites: number; failing_suites: number }; services: Array<{ service: string; test_case_count: number }>; latest_failures: Array<{ suite_run_id: string; service: string; failed: number; commit_sha?: string | null }> };
type ComplianceReport = { month: string; service: string; total_tests: number; suite_runs: number; deployments_blocked: number; deployments_allowed: number; scout_activity: number; self_improvement_proposals: number; evidence_index: Array<{ suite_run_id: string; service: string; evidence_path: string }> };
type FailureAnalysis = { total_failures: number; patterns: Array<{ message: string; count: number }>; recent_failures: Array<{ suite_run_id: string; service: string; message: string; shard_number: number }> };
type SuggestionResponse = { service: string; coverage_gaps: string[]; suggestions: Array<{ title: string; scenario_description: string; endpoint: string; tags: string[] }> };
type ScoutStats = { total: number; by_status: Record<string, number>; by_service: Record<string, number> };
type EvidenceItem = { path: string; suite_run_id: string; service: string; evidence_hash: string };
type ExecutionEvent = { id: number; suite_run_id: string; service: string; event_type: string; level: string; message: string; event_payload: Record<string, unknown>; created_at: string };
type SearchResult = { kind: string; id: string; service: string; title: string; metadata: Record<string, unknown>; score: number; status?: string | null; level?: string | null; event_type?: string | null; recorded_at?: string | null; tags?: string[] };
type SearchResponse = { query: string; count: number; results: SearchResult[]; active_backend: string; mode: string; available: boolean; indexed: boolean; documents: number; documents_indexed: number; last_sync_at?: string | null; sync_state: string };
type SearchAnalytics = { total_documents: number; by_kind: Record<string, number>; by_service: Record<string, number>; active_backend: string; mode: string; available: boolean; indexed: boolean; documents: number; documents_indexed: number; last_sync_at?: string | null; sync_state: string };
type SearchSync = { indexed: boolean; documents: number; documents_indexed: number; active_backend: string; mode: string; available: boolean; last_sync_at?: string | null; sync_state: string };
type SocketPayload = { type: string; suite_run?: SuiteRun };
type SearchKind = "all" | "test_case" | "suite_run" | "execution_event" | "evidence" | "scout_observation" | "improvement_proposal";
type SearchSort = "relevance" | "newest" | "oldest";
type SearchLevel = "all" | "info" | "warning" | "error";
type SearchStatus = "all" | "active" | "validated" | "passed" | "failed" | "queued" | "needs_review" | "accepted" | "proposed" | "shadow_mode";

type SearchFilters = {
  service: string;
  kind: SearchKind;
  status: SearchStatus;
  level: SearchLevel;
  sort: SearchSort;
  limit: number;
};

const API_BASE = (import.meta.env.VITE_API_BASE as string | undefined) ?? "http://localhost:8091";
const SEARCH_DEFAULTS: SearchFilters = { service: "all", kind: "all", status: "all", level: "all", sort: "relevance", limit: 30 };

async function fetchJson<T>(path: string, token: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}`, ...(init?.headers ?? {}) },
    ...init,
  });
  if (!response.ok) {
    const detail = await response.text();
    throw new Error(detail || `Request failed: ${response.status}`);
  }
  return response.json() as Promise<T>;
}

function buildSearchPath(query: string, filters: SearchFilters): string {
  const params = new URLSearchParams();
  params.set("query", query);
  params.set("sort", filters.sort);
  params.set("limit", String(filters.limit));
  if (filters.service !== "all") params.set("service_name", filters.service);
  if (filters.kind !== "all") params.set("kind", filters.kind);
  if (filters.status !== "all") params.set("status", filters.status);
  if (filters.level !== "all") params.set("level", filters.level);
  return `/api/v1/search?${params.toString()}`;
}

function formatDate(value?: string | null): string {
  if (!value) return "n/a";
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString();
}

export default function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [selectedUser, setSelectedUser] = useState("quinn.qa");
  const [searchQuery, setSearchQuery] = useState("customer");
  const [searchFilters, setSearchFilters] = useState<SearchFilters>(SEARCH_DEFAULTS);
  const [searchSyncStatus, setSearchSyncStatus] = useState<SearchSync | null>(null);
  const [testCases, setTestCases] = useState<TestCase[]>([]);
  const [suiteRuns, setSuiteRuns] = useState<SuiteRun[]>([]);
  const [coverageRows, setCoverageRows] = useState<CoverageRow[]>([]);
  const [observations, setObservations] = useState<ScoutObservation[]>([]);
  const [proposals, setProposals] = useState<Proposal[]>([]);
  const [dashboard, setDashboard] = useState<DashboardSummary | null>(null);
  const [compliance, setCompliance] = useState<ComplianceReport | null>(null);
  const [failures, setFailures] = useState<FailureAnalysis | null>(null);
  const [scoutStats, setScoutStats] = useState<ScoutStats | null>(null);
  const [evidence, setEvidence] = useState<EvidenceItem[]>([]);
  const [events, setEvents] = useState<ExecutionEvent[]>([]);
  const [suggestions, setSuggestions] = useState<SuggestionResponse | null>(null);
  const [searchResults, setSearchResults] = useState<SearchResponse | null>(null);
  const [searchAnalytics, setSearchAnalytics] = useState<SearchAnalytics | null>(null);
  const [selectedService, setSelectedService] = useState("all");
  const [statusMessage, setStatusMessage] = useState("Sign in to load the CTIP workspace...");
  const [formState, setFormState] = useState({ title: "", scenario_description: "", target_service: "customer-agent-service", endpoint: "/customers", priority: "high", source: "manual", tags: "TEST_synthetic,TEST_api" });

  const currentMonth = new Date().toISOString().slice(0, 7);
  const canOperate = session?.role === "qa" || session?.role === "admin";
  const isAdmin = session?.role === "admin";

  const login = async (username: string) => {
    const response = await fetch(`${API_BASE}/api/v1/auth/login`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ username }) });
    const data = (await response.json()) as Session;
    setSession(data);
    setStatusMessage(`Signed in as ${data.display_name} (${data.role}).`);
    return data;
  };

  const loadAll = async (activeSession: Session, serviceForSuggestions = "customer-agent-service") => {
    const canOperateForSession = activeSession.role === "qa" || activeSession.role === "admin";
    const isAdminForSession = activeSession.role === "admin";
    const token = activeSession.token;

    const [cases, runs, coverage, scout, summary, failureData, scoutStatsData, suggestionData, eventData, searchData, searchAnalyticsData] = await Promise.all([
      fetchJson<TestCase[]>("/api/v1/test-cases", token),
      fetchJson<SuiteRun[]>("/api/v1/runs/suites", token),
      fetchJson<{ rows: CoverageRow[] }>("/api/v1/coverage", token),
      fetchJson<{ items: ScoutObservation[] }>("/api/v1/scout/observations", token),
      fetchJson<DashboardSummary>("/api/v1/dashboard/summary", token),
      fetchJson<FailureAnalysis>("/api/v1/analysis/failures", token),
      fetchJson<ScoutStats>("/api/v1/analysis/scout-stats", token),
      fetchJson<SuggestionResponse>(`/api/v1/analysis/suggestions/${serviceForSuggestions}`, token),
      fetchJson<{ items: ExecutionEvent[] }>("/api/v1/analysis/execution-events", token),
      fetchJson<SearchResponse>(buildSearchPath(searchQuery, searchFilters), token),
      fetchJson<SearchAnalytics>("/api/v1/search/analytics", token),
    ]);
    setTestCases(cases);
    setSuiteRuns(runs);
    setCoverageRows(coverage.rows);
    setObservations(scout.items);
    setDashboard(summary);
    setFailures(failureData);
    setScoutStats(scoutStatsData);
    setSuggestions(suggestionData);
    setEvents(eventData.items);
    setSearchResults(searchData);
    setSearchAnalytics(searchAnalyticsData);

    if (canOperateForSession) {
      const evidenceData = await fetchJson<{ items: EvidenceItem[] }>("/api/v1/evidence", token);
      setEvidence(evidenceData.items);
    } else {
      setEvidence([]);
    }

    if (isAdminForSession) {
      const [report, improvement] = await Promise.all([
        fetchJson<ComplianceReport>(`/api/v1/compliance/report?month=${currentMonth}&service=all`, token),
        fetchJson<{ items: Proposal[] }>("/api/v1/self-improvement/proposals", token),
      ]);
      setCompliance(report);
      setProposals(improvement.items);
    } else {
      setCompliance(null);
      setProposals([]);
    }

    setStatusMessage(`Loaded ${cases.length} test cases and ${runs.length} suite runs.`);
  };

  useEffect(() => {
    login(selectedUser).then((data) => loadAll(data)).catch((error: Error) => setStatusMessage(error.message));
  }, []);

  useEffect(() => {
    if (!session) return;
    const socket = new WebSocket(API_BASE.replace("http", "ws") + "/api/v1/ws");
    socket.onmessage = (event) => {
      const payload = JSON.parse(event.data) as SocketPayload;
      if (payload.type === "suite_updated" && payload.suite_run) {
        const updatedRun = payload.suite_run;
        setSuiteRuns((current) => [updatedRun, ...current.filter((item) => item.id !== updatedRun.id)]);
        setStatusMessage(`Suite ${updatedRun.id.slice(0, 8)} updated: ${updatedRun.status}`);
        loadAll(session, updatedRun.service).catch(() => undefined);
      }
    };
    return () => socket.close();
  }, [session, searchQuery, searchFilters]);

  const filteredCases = useMemo(() => selectedService === "all" ? testCases : testCases.filter((item) => item.target_service === selectedService), [selectedService, testCases]);
  const latestGate = suiteRuns[0];

  const switchUser = async () => {
    const next = await login(selectedUser);
    await loadAll(next);
  };

  const refreshSearch = async () => {
    if (!session) return;
    const [searchData, analyticsData] = await Promise.all([
      fetchJson<SearchResponse>(buildSearchPath(searchQuery, searchFilters), session.token),
      fetchJson<SearchAnalytics>("/api/v1/search/analytics", session.token),
    ]);
    setSearchResults(searchData);
    setSearchAnalytics(analyticsData);
  };

  const syncSearch = async () => {
    if (!session) return;
    const sync = await fetchJson<SearchSync>("/api/v1/search/sync", session.token, { method: "POST" });
    setSearchSyncStatus(sync);
    await refreshSearch();
  };

  const handleCreate = async (event: FormEvent) => {
    event.preventDefault();
    if (!session || !canOperate) return;
    await fetchJson<TestCase>("/api/v1/test-cases", session.token, { method: "POST", body: JSON.stringify({ ...formState, tags: formState.tags.split(",").map((value) => value.trim()).filter(Boolean), steps: [{ order: 1, action: `TEST_exercise ${formState.endpoint} payload`, expected_response: "TEST_success response" }] }) });
    setFormState({ title: "", scenario_description: "", target_service: "customer-agent-service", endpoint: "/customers", priority: "high", source: "manual", tags: "TEST_synthetic,TEST_api" });
    await loadAll(session, formState.target_service);
    setStatusMessage("Created a new CTIP test case.");
  };

  const triggerRun = async (service: string) => {
    if (!session || !canOperate) return;
    const result = await fetchJson<{ suite_run_id: string }>(`/api/v1/runs/suite/${service}`, session.token, { method: "POST", body: JSON.stringify({ trigger: "manual", trigger_actor: session.username }) });
    setStatusMessage(`Triggered suite ${result.suite_run_id.slice(0, 8)} for ${service}.`);
    await loadAll(session, service);
  };

  return (
    <div className="shell">
      <header className="hero">
        <div>
          <p className="eyebrow">CIBAP Test Intelligence Platform</p>
          <h1>Enterprise testing control tower for CIBAP services.</h1>
          <p className="lede">Local developer edition with role-aware access, queue-backed execution, and Elasticsearch-compatible analytics search.</p>
        </div>
        <div className="heroCard">
          <div className="panelHeader"><strong>{statusMessage}</strong></div>
          <div className="formRow"><select value={selectedUser} onChange={(e) => setSelectedUser(e.target.value)}><option value="olivia.viewer">Olivia Viewer</option><option value="quinn.qa">Quinn QA</option><option value="avery.admin">Avery Admin</option></select><button onClick={switchUser}>Switch User</button></div>
          {session && <p>Signed in as {session.display_name} · role: {session.role}</p>}
        </div>
      </header>

      <section className="metrics">
        <article><span>{dashboard?.totals.test_cases ?? testCases.length}</span><label>Test cases</label></article>
        <article><span>{dashboard?.totals.suite_runs ?? suiteRuns.length}</span><label>Suite runs</label></article>
        <article><span>{dashboard?.totals.passing_suites ?? 0}</span><label>Passing suites</label></article>
        <article><span>{searchAnalytics?.total_documents ?? 0}</span><label>Indexed docs</label></article>
      </section>

      <section className="grid two">
        <article className="panel">
          <div className="panelHeader"><h2>Search Index</h2><div className="buttonRow"><button onClick={refreshSearch}>Refresh Search</button><button onClick={syncSearch}>Sync Index</button></div></div>
          <div className="formRow"><input value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} placeholder="Search customer, failed, evidence..." /><button onClick={refreshSearch}>Search</button></div>
          <div className="searchFilters">
            <select value={searchFilters.service} onChange={(e) => setSearchFilters((current) => ({ ...current, service: e.target.value }))}><option value="all">All services</option><option value="customer-agent-service">customer-agent-service</option><option value="fraud-detection-service">fraud-detection-service</option><option value="loan-prescreen-service">loan-prescreen-service</option><option value="platform">platform</option></select>
            <select value={searchFilters.kind} onChange={(e) => setSearchFilters((current) => ({ ...current, kind: e.target.value as SearchKind }))}><option value="all">All kinds</option><option value="test_case">test_case</option><option value="suite_run">suite_run</option><option value="execution_event">execution_event</option><option value="evidence">evidence</option><option value="scout_observation">scout_observation</option><option value="improvement_proposal">improvement_proposal</option></select>
            <select value={searchFilters.status} onChange={(e) => setSearchFilters((current) => ({ ...current, status: e.target.value as SearchStatus }))}><option value="all">All statuses</option><option value="active">active</option><option value="validated">validated</option><option value="passed">passed</option><option value="failed">failed</option><option value="queued">queued</option><option value="needs_review">needs_review</option><option value="accepted">accepted</option><option value="proposed">proposed</option><option value="shadow_mode">shadow_mode</option></select>
            <select value={searchFilters.level} onChange={(e) => setSearchFilters((current) => ({ ...current, level: e.target.value as SearchLevel }))}><option value="all">All levels</option><option value="info">info</option><option value="warning">warning</option><option value="error">error</option></select>
            <select value={searchFilters.sort} onChange={(e) => setSearchFilters((current) => ({ ...current, sort: e.target.value as SearchSort }))}><option value="relevance">relevance</option><option value="newest">newest</option><option value="oldest">oldest</option></select>
            <select value={searchFilters.limit} onChange={(e) => setSearchFilters((current) => ({ ...current, limit: Number(e.target.value) }))}><option value={10}>10</option><option value={20}>20</option><option value={30}>30</option><option value={50}>50</option></select>
          </div>
          <p className="subtle">Backend: {searchResults?.active_backend ?? searchAnalytics?.active_backend ?? "local"} · mode: {searchResults?.mode ?? searchAnalytics?.mode ?? "auto"} · available: {(searchResults?.available ?? searchAnalytics?.available) ? "yes" : "no"}</p>
          <p className="subtle">Sync state: {searchResults?.sync_state ?? searchAnalytics?.sync_state ?? "unknown"} · docs indexed: {searchResults?.documents_indexed ?? searchAnalytics?.documents_indexed ?? 0} · last sync: {formatDate(searchResults?.last_sync_at ?? searchSyncStatus?.last_sync_at ?? searchAnalytics?.last_sync_at)}</p>
          {searchSyncStatus && <p className="subtle">Last manual sync: {searchSyncStatus.documents} documents · indexed: {String(searchSyncStatus.indexed)}</p>}
          <p className="subtle">Results: {searchResults?.count ?? 0}</p>
          <div className="stack compact">{searchResults?.results.map((item) => <div key={`${item.kind}-${item.id}`} className="runCard"><div className="runTop"><strong>{item.title}</strong><span className="badge badge-running">{item.kind}</span></div><p>{item.service}</p><p className="subtle">score: {item.score} · status: {item.status ?? "n/a"} · level: {item.level ?? "n/a"}</p><p className="subtle">recorded: {formatDate(item.recorded_at)}</p>{item.tags?.length ? <p className="subtle">tags: {item.tags.join(", ")}</p> : null}</div>)}</div>
        </article>

        <article className="panel">
          <div className="panelHeader"><h2>Search Analytics</h2></div>
          <div className="stack compact"><p>Total documents: {searchAnalytics?.total_documents ?? 0}</p><p>By kind: {searchAnalytics ? Object.entries(searchAnalytics.by_kind).map(([k, v]) => `${k} ${v}`).join(" · ") : "loading"}</p><p>By service: {searchAnalytics ? Object.entries(searchAnalytics.by_service).map(([k, v]) => `${k} ${v}`).join(" · ") : "loading"}</p></div>
        </article>
      </section>

      <section className="grid two">
        <article className="panel"><div className="panelHeader"><h2>Deployment Gate</h2>{canOperate && <button onClick={() => latestGate && triggerRun(latestGate.service)}>Re-run latest service</button>}</div>{latestGate ? <div className={`gate gate-${latestGate.status}`}><strong>{latestGate.service}</strong><p>Status: {latestGate.status}</p><p>Passed: {latestGate.passed} | Failed: {latestGate.failed} | Shards: {latestGate.completed_shards}/{latestGate.total_shards}</p><p>Trigger: {latestGate.trigger} by {latestGate.trigger_actor}</p></div> : <p>No suites yet.</p>}</article>
        <article className="panel"><div className="panelHeader"><h2>Compliance Snapshot</h2></div>{isAdmin && compliance ? <div className="stack compact"><p>Month: {compliance.month}</p><p>Deployments allowed: {compliance.deployments_allowed}</p><p>Deployments blocked: {compliance.deployments_blocked}</p><p>Scout activity: {compliance.scout_activity}</p><p>Improvement proposals: {compliance.self_improvement_proposals}</p><p className="subtle">Evidence samples: {compliance.evidence_index.length}</p></div> : <p className="subtle">Admin role required for compliance reporting.</p>}</article>
      </section>

      <section className="grid two">
        <article className="panel"><div className="panelHeader"><h2>Create Test Case</h2></div>{canOperate ? <form className="form" onSubmit={handleCreate}><input value={formState.title} onChange={(e) => setFormState({ ...formState, title: e.target.value })} placeholder="TEST_onboarding_check" required /><textarea value={formState.scenario_description} onChange={(e) => setFormState({ ...formState, scenario_description: e.target.value })} placeholder="TEST_customer_profile validation" required /><div className="formRow"><select value={formState.target_service} onChange={(e) => setFormState({ ...formState, target_service: e.target.value })}><option value="customer-agent-service">customer-agent-service</option><option value="fraud-detection-service">fraud-detection-service</option><option value="loan-prescreen-service">loan-prescreen-service</option></select><input value={formState.endpoint} onChange={(e) => setFormState({ ...formState, endpoint: e.target.value })} placeholder="/endpoint" required /></div><div className="formRow"><select value={formState.priority} onChange={(e) => setFormState({ ...formState, priority: e.target.value })}><option value="critical">critical</option><option value="high">high</option><option value="medium">medium</option><option value="low">low</option></select><select value={formState.source} onChange={(e) => setFormState({ ...formState, source: e.target.value })}><option value="manual">manual</option><option value="generated">generated</option><option value="scout">scout</option></select></div><input value={formState.tags} onChange={(e) => setFormState({ ...formState, tags: e.target.value })} placeholder="TEST_synthetic,TEST_api" /><button type="submit">Create governed test</button></form> : <p className="subtle">QA or admin role required to create test cases.</p>}</article>
        <article className="panel"><div className="panelHeader"><h2>Suggested Coverage Additions</h2></div><div className="stack compact"><p className="subtle">Gaps: {suggestions?.coverage_gaps.join(", ") || "none"}</p>{suggestions?.suggestions.map((item) => <div key={item.endpoint} className="proposalCard"><strong>{item.title}</strong><p>{item.scenario_description}</p><p className="subtle">{item.endpoint}</p></div>)}</div></article>
      </section>

      <section className="grid two">
        <article className="panel"><div className="panelHeader"><h2>Execution Timeline</h2></div><div className="stack compact">{events.map((item) => <div key={item.id} className="runCard"><div className="runTop"><strong>{item.event_type}</strong><span className={`badge badge-${item.level === "error" ? "failed" : "running"}`}>{item.level}</span></div><p>{item.message}</p><p className="subtle">{item.service} · {item.suite_run_id}</p></div>)}</div></article>
        <article className="panel"><div className="panelHeader"><h2>Failure Analysis</h2></div><div className="stack compact">{failures?.patterns.map((pattern) => <div key={pattern.message} className="runCard"><strong>{pattern.message}</strong><p>{pattern.count} occurrence(s)</p></div>)}{!failures?.patterns.length && <p>No failure patterns captured yet.</p>}</div></article>
      </section>

      <section className="grid two">
        <article className="panel"><div className="panelHeader"><h2>Test Library</h2><select value={selectedService} onChange={(e) => setSelectedService(e.target.value)}><option value="all">All services</option><option value="customer-agent-service">customer-agent-service</option><option value="fraud-detection-service">fraud-detection-service</option><option value="loan-prescreen-service">loan-prescreen-service</option></select></div><div className="tableWrap"><table><thead><tr><th>Title</th><th>Service</th><th>Endpoint</th><th>Status</th><th>Last run</th></tr></thead><tbody>{filteredCases.map((item) => <tr key={item.id}><td><strong>{item.title}</strong><div className="subtle">{item.tags.join(", ")}</div></td><td>{item.target_service}</td><td>{item.endpoint}</td><td>{item.status}</td><td>{item.last_run_status}</td></tr>)}</tbody></table></div></article>
        <article className="panel"><div className="panelHeader"><h2>Suite Run History</h2></div><div className="stack">{suiteRuns.map((run) => <div className="runCard" key={run.id}><div className="runTop"><strong>{run.service}</strong><span className={`badge badge-${run.status}`}>{run.status}</span></div><p>{run.trigger} by {run.trigger_actor}{run.commit_sha ? ` · ${run.commit_sha}` : ""}</p><p>{run.passed} passed / {run.failed} failed / {run.errored} errored</p><p>{run.completed_shards}/{run.total_shards} shards complete · {Math.round(run.duration_ms / 1000)}s</p></div>)}</div></article>
      </section>

      <section className="grid two">
        <article className="panel"><div className="panelHeader"><h2>Evidence Vault</h2></div>{canOperate ? <div className="stack compact">{evidence.map((item) => <div key={item.path} className="proposalCard"><strong>{item.service}</strong><p>{item.suite_run_id}</p><p className="subtle evidencePath">{item.path}</p></div>)}</div> : <p className="subtle">QA or admin role required for evidence access.</p>}</article>
        <article className="panel"><div className="panelHeader"><h2>Scout Activity</h2></div><div className="stack compact"><p className="subtle">Signals: {scoutStats?.total ?? observations.length}</p>{observations.map((item) => <div key={item.id} className="scoutCard"><div className="runTop"><strong>{item.title}</strong><span className={`badge badge-${item.safety_status}`}>{item.safety_status}</span></div><p>{item.summary}</p><p>{item.proposed_service} · {item.domain}</p><a href={item.source_url} target="_blank" rel="noreferrer">Open source</a></div>)}</div></article>
      </section>

      <section className="panel"><div className="panelHeader"><h2>Self-Improvement Queue</h2></div>{isAdmin ? <div className="stack horizontal">{proposals.map((item) => <div key={item.id} className="proposalCard"><span className={`badge badge-${item.status}`}>{item.status}</span><strong>{item.title}</strong><p>{item.summary}</p><p className="subtle">Area: {item.area}</p><p>{item.expected_impact}</p></div>)}</div> : <p className="subtle">Admin role required for self-improvement proposals.</p>}</section>
    </div>
  );
}
