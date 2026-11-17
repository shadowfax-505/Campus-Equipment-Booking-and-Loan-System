package edu.campus.equipment.service.policy;

import java.math.BigDecimal;
import java.time.Instant;

/** Strategy for turning lateness into a charge. */
public interface FineCalculationStrategy {

    record LateFeeTerms(BigDecimal dailyRate, int graceMinutes, BigDecimal cap) {}

    record FineQuote(BigDecimal amount, long chargeableDays, boolean capped, String explanation) {
        public boolean isChargeable() { return amount.signum() > 0; }
    }

    FineQuote lateFee(Instant dueAt, Instant returnedOrNow, LateFeeTerms terms);
}
