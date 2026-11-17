package edu.campus.equipment.service.policy;

import edu.campus.equipment.domain.CreditScoreConfig;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.OptionalDouble;

/**
 * S = wT*T + wC*C + wP*P + wR*R + wH*H (+ manual adjustments), clamped to 0..100.
 *
 * Each component is 0..100 and falls back to the neutral value when there is no relevant history:
 * <ul>
 *   <li>T punctuality: per closed loan, 100 if returned within due time + grace, minus latePenaltyPerDay per started
 *       day late (floor 0); a lost item counts 0. Average over loans.</li>
 *   <li>C care: per closed loan, 100 for normal condition or normal wear, 50 if accessories were missing,
 *       0 for verified damage or loss. Average.</li>
 *   <li>P payment timeliness: per fine, 100 if settled within promptPaymentDays, then minus 5 per extra day (floor 0);
 *       an unpaid fine older than promptPaymentDays counts 0, a newer unpaid fine is not counted yet.
 *       With no fines but at least one closed loan, P = 100 (nothing was owed). Otherwise neutral.</li>
 *   <li>R reliability: per approved reservation, 100 if collected, 0 if the collection deadline was missed,
 *       40 for a late cancellation. Timely cancellations are ignored.</li>
 *   <li>H history: neutral + (100 - neutral) * min(1, cleanLoans / historyTarget), where a clean loan was returned
 *       on time without verified damage, loss or missing accessories.</li>
 * </ul>
 */
@Component
public class WeightedCreditScoreStrategy implements CreditScoreStrategy {

    @Override
    public Components compute(Inputs in, CreditScoreConfig cfg) {
        double neutral = cfg.getNeutralValue();

        double t = avg(in.loans().stream().mapToDouble(l -> {
            if (l.lost()) return 0;
            if (l.secondsLateAfterGrace() <= 0) return 100;
            long days = (l.secondsLateAfterGrace() + 86399) / 86400;
            return Math.max(0, 100 - cfg.getLatePenaltyPerDay() * days);
        }).average(), neutral);

        double c = avg(in.loans().stream().mapToDouble(l -> {
            if (l.lost() || l.damageVerified()) return 0;
            if (l.missingAccessories()) return 50;
            return 100;
        }).average(), neutral);

        long promptSeconds = Duration.ofDays(cfg.getPromptPaymentDays()).getSeconds();
        OptionalDouble pAvg = in.fines().stream().filter(f -> f.settled()
                        || Duration.between(f.assessedAt(), in.now()).getSeconds() > promptSeconds)
                .mapToDouble(f -> {
                    if (!f.settled()) return 0;
                    long took = Duration.between(f.assessedAt(), f.settledAt()).getSeconds();
                    if (took <= promptSeconds) return 100;
                    long extraDays = (took - promptSeconds + 86399) / 86400;
                    return Math.max(0, 100 - 5.0 * extraDays);
                }).average();
        double p = pAvg.isPresent() ? pAvg.getAsDouble() : (in.loans().isEmpty() ? neutral : 100);

        double r = avg(in.approvals().stream().mapToDouble(o -> switch (o) {
            case COLLECTED -> 100;
            case MISSED_COLLECTION -> 0;
            case LATE_CANCELLATION -> 40;
        }).average(), neutral);

        long clean = in.loans().stream().filter(l -> !l.lost() && !l.damageVerified() && !l.missingAccessories()
                && l.secondsLateAfterGrace() <= 0).count();
        double h = neutral + (100 - neutral) * Math.min(1.0, cfg.getHistoryTarget() == 0 ? 1.0 : (double) clean / cfg.getHistoryTarget());

        double weighted = cfg.getWeightPunctuality() * t + cfg.getWeightCare() * c + cfg.getWeightPayment() * p
                + cfg.getWeightReliability() * r + cfg.getWeightHistory() * h;
        double score = clamp(weighted + in.manualAdjustment());
        return new Components(round(t), round(c), round(p), round(r), round(h), round(in.manualAdjustment()), round(score));
    }

    private static double avg(OptionalDouble v, double neutral) {
        return v.isPresent() ? v.getAsDouble() : neutral;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(100, v));
    }

    static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
