package com.jpmc.cibap.fraud.scoring;

import com.jpmc.cibap.fraud.model.TransactionEvent;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuleEngineService {

    private final KieContainer kieContainer;

    /**
     * Runs all Drools rules against the transaction.
     * Returns a RuleScore in the range 0.0-1.0 with reasons.
     */
    public RuleScore evaluate(TransactionEvent tx) {
        KieSession session = kieContainer.newKieSession();
        RuleScore score = new RuleScore();
        try {
            session.insert(tx);
            session.insert(score);
            session.fireAllRules();
        } finally {
            session.dispose();
        }

        // Clamp the score after the full rule evaluation.
        score.setTotal(Math.min(1.0, score.getTotal()));
        log.debug("Rule score={} reasons={} txId={}", score.getTotal(), score.getReasons(), tx.getTransactionId());
        return score;
    }

    @Data
    public static class RuleScore {
        private double total = 0.0;
        private final List<String> reasons = new ArrayList<>();

        public void addPoints(double points) {
            this.total += points;
        }

        public void addReason(String reason) {
            this.reasons.add(reason);
        }
    }
}
