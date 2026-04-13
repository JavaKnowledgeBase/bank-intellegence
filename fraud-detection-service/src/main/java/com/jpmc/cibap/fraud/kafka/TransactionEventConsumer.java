package com.jpmc.cibap.fraud.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpmc.cibap.fraud.decision.DecisionFusionService;
import com.jpmc.cibap.fraud.model.FraudEvent;
import com.jpmc.cibap.fraud.model.TransactionEvent;
import com.jpmc.cibap.fraud.scoring.RuleEngineService;
import com.jpmc.cibap.fraud.scoring.SageMakerScoringService;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionEventConsumer {

    private final ObjectMapper objectMapper;
    private final RuleEngineService ruleEngineService;
    private final SageMakerScoringService sageMakerService;
    private final DecisionFusionService fusionService;
    private final FraudEventProducer fraudEventProducer;

    @KafkaListener(
            topics = "transaction-events",
            groupId = "fraud-detection-group",
            concurrency = "10",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Timed(value = "fraud.processing.latency", description = "End-to-end fraud scoring latency")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        TransactionEvent tx = null;
        try {
            tx = objectMapper.readValue(record.value(), TransactionEvent.class);
            log.debug("Processing transaction txId={}", tx.getTransactionId());

            final TransactionEvent finalTx = tx;
            RuleEngineService.RuleScore ruleScore;
            double mlScore;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<RuleEngineService.RuleScore> ruleScoreFuture =
                        executor.submit(() -> ruleEngineService.evaluate(finalTx));
                Future<Double> mlScoreFuture =
                        executor.submit(() -> sageMakerService.score(finalTx));
                ruleScore = ruleScoreFuture.get();
                mlScore = mlScoreFuture.get();
            }

            FraudEvent decision = fusionService.fuse(finalTx, ruleScore, mlScore);
            fraudEventProducer.publish(decision);
            ack.acknowledge();
        } catch (ExecutionException e) {
            log.error("Error processing transaction txId={}",
                    tx != null ? tx.getTransactionId() : "unknown", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while processing transaction txId={}",
                    tx != null ? tx.getTransactionId() : "unknown", e);
        } catch (Exception e) {
            log.error("Error processing transaction txId={}",
                    tx != null ? tx.getTransactionId() : "unknown", e);
        }
    }
}
