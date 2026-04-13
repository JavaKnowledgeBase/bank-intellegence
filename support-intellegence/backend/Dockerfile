# CSIP Backend — multi-stage Docker build
# Stage 1: dependency resolver
FROM python:3.12-slim AS deps

WORKDIR /deps
COPY requirements.txt .

RUN pip install --no-cache-dir --upgrade pip \
 && pip install --no-cache-dir -r requirements.txt \
 && python -m spacy download en_core_web_sm --quiet || true

# Stage 2: production image
FROM python:3.12-slim AS runtime

# Security hardening
RUN groupadd -r csip && useradd -r -g csip -u 10001 csip \
 && apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy installed packages from deps stage
COPY --from=deps /usr/local/lib/python3.12/site-packages /usr/local/lib/python3.12/site-packages
COPY --from=deps /usr/local/bin /usr/local/bin

# Copy application code
COPY --chown=csip:csip . .

# Create runtime directories
RUN mkdir -p audit_logs/issues audit_logs/fixes audit_logs/improvements data/chromadb \
 && chown -R csip:csip audit_logs data

USER csip

EXPOSE 8092

HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8092/api/v1/system/health || exit 1

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8092", \
     "--workers", "1", "--log-level", "info"]
