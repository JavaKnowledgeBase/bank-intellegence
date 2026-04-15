from fastapi.testclient import TestClient

from main import create_app


ADMIN = {"Authorization": "Bearer ctip-local-avery.admin"}
QA = {"Authorization": "Bearer ctip-local-quinn.qa"}
VIEWER = {"Authorization": "Bearer ctip-local-olivia.viewer"}


def test_login() -> None:
    with TestClient(create_app()) as client:
        response = client.post("/api/v1/auth/login", json={"username": "quinn.qa"})
    assert response.status_code == 200
    assert response.json()["role"] == "qa"


def test_health() -> None:
    with TestClient(create_app()) as client:
        response = client.get("/api/v1/health")
    assert response.status_code == 200
    assert response.json()["status"] == "ok"


def test_list_test_cases() -> None:
    with TestClient(create_app()) as client:
        response = client.get("/api/v1/test-cases", headers=VIEWER)
    assert response.status_code == 200
    assert len(response.json()) >= 3


def test_start_suite_run_requires_qa() -> None:
    with TestClient(create_app()) as client:
        forbidden = client.post("/api/v1/runs/suite/customer-agent-service", json={"trigger": "manual", "trigger_actor": "viewer"}, headers=VIEWER)
        allowed = client.post("/api/v1/runs/suite/customer-agent-service", json={"trigger": "manual", "trigger_actor": "pytest"}, headers=QA)
    assert forbidden.status_code == 403
    assert allowed.status_code == 202


def test_dashboard_summary() -> None:
    with TestClient(create_app()) as client:
        response = client.get("/api/v1/dashboard/summary", headers=VIEWER)
    assert response.status_code == 200
    assert response.json()["totals"]["test_cases"] >= 3


def test_compliance_report_requires_admin() -> None:
    with TestClient(create_app()) as client:
        forbidden = client.get("/api/v1/compliance/report?month=2026-04&service=all", headers=QA)
        allowed = client.get("/api/v1/compliance/report?month=2026-04&service=all", headers=ADMIN)
    assert forbidden.status_code == 403
    assert allowed.status_code == 200


def test_failure_analysis() -> None:
    with TestClient(create_app()) as client:
        response = client.get("/api/v1/analysis/failures", headers=VIEWER)
    assert response.status_code == 200
    assert "patterns" in response.json()


def test_scout_stats() -> None:
    with TestClient(create_app()) as client:
        response = client.get("/api/v1/analysis/scout-stats", headers=VIEWER)
    assert response.status_code == 200
    assert response.json()["total"] >= 1


def test_generation_suggestions() -> None:
    with TestClient(create_app()) as client:
        response = client.get("/api/v1/analysis/suggestions/customer-agent-service", headers=VIEWER)
    assert response.status_code == 200
    assert response.json()["service"] == "customer-agent-service"


def test_execution_events_endpoint() -> None:
    with TestClient(create_app()) as client:
        run_response = client.post("/api/v1/runs/suite/customer-agent-service", json={"trigger": "manual", "trigger_actor": "pytest"}, headers=QA)
        suite_run_id = run_response.json()["suite_run_id"]
        events_response = client.get(f"/api/v1/analysis/execution-events?suite_run_id={suite_run_id}", headers=VIEWER)
    assert events_response.status_code == 200
    assert len(events_response.json()["items"]) >= 1


def test_search_endpoint() -> None:
    with TestClient(create_app()) as client:
        response = client.get("/api/v1/search?query=customer", headers=VIEWER)
    assert response.status_code == 200
    assert response.json()["count"] >= 1
    assert response.json()["active_backend"] in {"local", "elasticsearch"}


def test_search_analytics() -> None:
    with TestClient(create_app()) as client:
        response = client.get("/api/v1/search/analytics", headers=VIEWER)
    assert response.status_code == 200
    assert response.json()["total_documents"] >= 1


def test_search_sync() -> None:
    with TestClient(create_app()) as client:
        response = client.post("/api/v1/search/sync", headers=VIEWER)
    assert response.status_code == 200
    assert response.json()["documents"] >= 1


def test_rejects_pii() -> None:
    with TestClient(create_app()) as client:
        response = client.post(
            "/api/v1/test-cases",
            headers=QA,
            json={
                "title": "TEST_create_case",
                "scenario_description": "Contact john@example.com during test",
                "target_service": "customer-agent-service",
                "endpoint": "/customers",
                "priority": "high",
                "source": "manual",
                "tags": ["TEST_synthetic"],
                "steps": [{"order": 1, "action": "TEST_submit payload", "expected_response": "TEST_success"}],
            },
        )
    assert response.status_code == 400


def test_accepts_synthetic_payload_for_qa() -> None:
    with TestClient(create_app()) as client:
        response = client.post(
            "/api/v1/test-cases",
            headers=QA,
            json={
                "title": "TEST_create_case",
                "scenario_description": "TEST_customer_profile validation",
                "target_service": "customer-agent-service",
                "endpoint": "/customers",
                "priority": "high",
                "source": "manual",
                "tags": ["TEST_synthetic"],
                "steps": [{"order": 1, "action": "TEST_submit payload", "expected_response": "TEST_success"}],
            },
        )
    assert response.status_code == 201


def test_self_improvement_requires_admin() -> None:
    with TestClient(create_app()) as client:
        forbidden = client.get("/api/v1/self-improvement/proposals", headers=QA)
        allowed = client.get("/api/v1/self-improvement/proposals", headers=ADMIN)
    assert forbidden.status_code == 403
    assert allowed.status_code == 200
