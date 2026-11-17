package edu.campus.equipment.service.policy;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/**
 * F_late = min(F_cap, r * ceil(max(0, t_return - t_due - g) / 24h)).
 * Durations are measured exactly in seconds, so the result does not depend on time zones or daylight saving.
 */
@Component
public class DailyOverdueFineStrategy implements FineCalculationStrategy {
    private static final long DAY_SECONDS = 24L * 3600;

    @Override
    public FineQuote lateFee(Instant dueAt, Instant returnedOrNow, LateFeeTerms t) {
        long lateSeconds = Duration.between(dueAt, returnedOrNow).minusMinutes(t.graceMinutes()).getSeconds();
        if (lateSeconds <= 0) {
            return new FineQuote(BigDecimal.ZERO.setScale(2), 0, false, "Returned within the due time and grace period");
        }
        long days = (lateSeconds + DAY_SECONDS - 1) / DAY_SECONDS; // ceil
        BigDecimal raw = t.dailyRate().multiply(BigDecimal.valueOf(days));
        boolean capped = t.cap() != null && raw.compareTo(t.cap()) > 0;
        BigDecimal amount = (capped ? t.cap() : raw).setScale(2, RoundingMode.HALF_UP);
        String explanation = String.format("%d started day(s) late x %s per day (grace %d min%s)%s",
                days, t.dailyRate().setScale(2, RoundingMode.HALF_UP), t.graceMinutes(),
                t.cap() == null ? ", no cap" : ", cap " + t.cap().setScale(2, RoundingMode.HALF_UP),
                capped ? " - capped" : "");
        return new FineQuote(amount, days, capped, explanation);
    }
}
