package com.jpmc.cibap.fraud.decision;

import com.jpmc.cibap.fraud.model.FraudEvent;
import com.jpmc.cibap.fraud.model.TransactionEvent;
import com.jpmc.cibap.fraud.scoring.RuleEngineService.RuleScore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
@Service
public class DecisionFusionService {

    // Weighted blend: 40% rule engine, 60% ML model
    private static final double RULE_WEIGHT = 0.40;
    private static final double ML_WEIGHT = 0.60;
    private static final double ALLOW_THRESHOLD = 0.30;
    private static final double BLOCK_THRESHOLD = 0.70;
    /**
     * Combines rule and ML scores into a final fraud decision.
     */
    public FraudEvent fuse(TransactionEvent tx, RuleScore ruleScore, double mlScore) {
        double finalScore = (ruleScore.getTotal() * RULE_WEIGHT) + (mlScore * ML_WEIGHT);
        finalScore = Math.min(1.0, Math.max(0.0, finalScore));
        String disposition;
        if (finalScore < ALLOW_THRESHOLD) {
            disposition = "ALLOW";
        } else if (finalScore < BLOCK_THRESHOLD) {
            disposition = "FLAG";
        } else {
            disposition = "BLOCK";
        }
        log.info("Fraud decision txId={} ruleScore={} mlScore={} finalScore={} disposition={}",
                tx.getTransactionId(), ruleScore.getTotal(), mlScore, finalScore, disposition);
        return FraudEvent.builder()
                .transactionId(tx.getTransactionId())
                .accountId(tx.getAccountId())
                .customerId(tx.getCustomerId())
                .ruleScore(ruleScore.getTotal())
                .mlScore(mlScore)
                .finalScore(finalScore)
                .disposition(disposition)
                .reasons(ruleScore.getReasons())
                .evaluatedAt(Instant.now())
                .build();
    }

}
