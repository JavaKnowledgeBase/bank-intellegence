"""
OpenTelemetry setup — traces, metrics, and logs.
Auto-instruments FastAPI, SQLAlchemy, Redis, and HTTPX.
Exports to OTLP (Jaeger / Datadog) when enabled; no-ops otherwise.
"""
from __future__ import annotations

import logging
from typing import Optional

logger = logging.getLogger(__name__)

tracer = None
meter = None

# Key CSIP metrics (initialised lazily)
issue_detected_counter = None
fix_attempt_histogram = None
llm_tokens_counter = None
llm_cost_counter = None
detection_latency = None


def setup_telemetry(service_name: str, otel_endpoint: str, enabled: bool = True) -> None:
    global tracer, meter
    global issue_detected_counter, fix_attempt_histogram
    global llm_tokens_counter, llm_cost_counter, detection_latency

    try:
        from opentelemetry import trace, metrics
        from opentelemetry.sdk.trace import TracerProvider
        from opentelemetry.sdk.metrics import MeterProvider
        from opentelemetry.sdk.resources import Resource, SERVICE_NAME
        from opentelemetry.sdk.trace.export import BatchSpanProcessor
        from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader

        resource = Resource.create({SERVICE_NAME: service_name, "service.version": "2.0.0"})

        if enabled and otel_endpoint:
            from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
            from opentelemetry.exporter.otlp.proto.grpc.metric_exporter import OTLPMetricExporter

            tp = TracerProvider(resource=resource)
            tp.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=otel_endpoint)))
            trace.set_tracer_provider(tp)

            reader = PeriodicExportingMetricReader(OTLPMetricExporter(endpoint=otel_endpoint))
            mp = MeterProvider(resource=resource, metric_readers=[reader])
            metrics.set_meter_provider(mp)
        else:
            # No-op providers when OTEL is disabled (development)
            from opentelemetry.sdk.trace import TracerProvider
            trace.set_tracer_provider(TracerProvider(resource=resource))

        tracer = trace.get_tracer("csip")
        meter = metrics.get_meter("csip")

        issue_detected_counter = meter.create_counter(
            "csip.issues.detected", description="Total issues detected by category"
        )
        fix_attempt_histogram = meter.create_histogram(
            "csip.fix.duration_ms", description="Time from issue detection to PR creation"
        )
        llm_tokens_counter = meter.create_counter(
            "csip.llm.tokens_used", description="Tokens consumed per agent"
        )
        llm_cost_counter = meter.create_counter(
            "csip.llm.cost_usd", description="Estimated USD cost of LLM calls"
        )
        detection_latency = meter.create_histogram(
            "csip.detection.latency_ms", description="Error occurrence → CSIP detection"
        )

        # Auto-instrument frameworks
        try:
            from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
            FastAPIInstrumentor.instrument()
        except ImportError:
            pass

        try:
            from opentelemetry.instrumentation.sqlalchemy import SQLAlchemyInstrumentor
            SQLAlchemyInstrumentor.instrument()
        except ImportError:
            pass

        try:
            from opentelemetry.instrumentation.redis import RedisInstrumentor
            RedisInstrumentor.instrument()
        except ImportError:
            pass

        logger.info("OpenTelemetry configured for service '%s'", service_name)

    except ImportError:
        logger.warning("opentelemetry-sdk not installed — telemetry disabled.")


def record_issue_detected(category: str, severity: str) -> None:
    if issue_detected_counter:
        issue_detected_counter.add(1, {"category": category, "severity": severity})


def record_llm_usage(agent_type: str, tokens: int, cost_usd: float) -> None:
    if llm_tokens_counter:
        llm_tokens_counter.add(tokens, {"agent": agent_type})
    if llm_cost_counter:
        llm_cost_counter.add(cost_usd, {"agent": agent_type})


def record_detection_latency(ms: float) -> None:
    if detection_latency:
        detection_latency.record(ms)
