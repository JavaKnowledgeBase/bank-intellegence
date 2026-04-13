FROM python:3.12-slim

RUN groupadd -r csip && useradd -r -g csip -u 10001 csip \
 && apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir --upgrade pip \
 && pip install --no-cache-dir -r requirements.txt

COPY --chown=csip:csip . .

RUN mkdir -p audit_logs/issues audit_logs/fixes audit_logs/improvements data/chromadb \
 && chown -R csip:csip audit_logs data

USER csip

EXPOSE 8092

HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8092/api/v1/system/health || exit 1

CMD ["python", "-m", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8092", \
     "--workers", "1", "--log-level", "info"]
