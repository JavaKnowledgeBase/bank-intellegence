# Runbook: CSIP Kafka Consumer Lag Critical

**Alert:** `CSIPConsumerLagCritical`  
**Severity:** Critical  
**Team:** Platform AI

## Symptoms
- Kafka consumer group `csip-monitoring` lag > 10,000 messages
- Issue detection SLA (P95 < 90s) at risk
- CSIP dashboard shows stale health data

## Immediate Actions

1. **Check consumer group status**
   ```bash
   kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
     --describe --group csip-monitoring
   ```

2. **Check CSIP pod health**
   ```bash
   kubectl get pods -n csip -l app=csip-api
   kubectl logs -n csip -l app=csip-api --tail=100
   ```

3. **Scale up CSIP consumers if pod count is low**
   ```bash
   kubectl scale deployment csip-api -n csip --replicas=5
   ```

4. **Check for processing bottleneck**
   - High LLM latency? → Check `csip_llm_latency_ms` metric
   - DB slow queries? → Check `csip_db_query_duration` metric
   - If LLM budget exhausted → temporarily increase budget via Redis:
     ```bash
     redis-cli SET csip:budget:rca_agent:$(date +%Y-%m-%d) 0
     ```

## Resolution
- Lag should clear within 5 minutes of scaling up
- If lag persists > 15 min, escalate to Platform AI on-call
- Check for upstream Kafka topic retention changes
