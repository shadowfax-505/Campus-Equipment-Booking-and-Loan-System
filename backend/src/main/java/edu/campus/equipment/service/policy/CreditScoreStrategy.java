package edu.campus.equipment.service.policy;

import edu.campus.equipment.domain.CreditScoreConfig;

import java.time.Instant;
import java.util.List;

/** Strategy for computing a borrowing-reliability score from a student's recent verified record. */
public interface CreditScoreStrategy {

    /** One closed loan: lateness after grace (seconds, <=0 means on time), whether it was lost, care outcome. */
    record ClosedLoan(long secondsLateAfterGrace, boolean lost, boolean damageVerified, boolean missingAccessories) {}

    /** One fine that counts toward payment timeliness. */
    record FineRecord(Instant assessedAt, Instant settledAt, boolean settled) {}

    /** One reservation that was approved: whether it was collected, expired uncollected, or cancelled late. */
    enum ApprovedOutcome { COLLECTED, MISSED_COLLECTION, LATE_CANCELLATION }

    record Inputs(List<ClosedLoan> loans, List<FineRecord> fines, List<ApprovedOutcome> approvals,
                  double manualAdjustment, Instant now) {}

    record Components(double punctuality, double care, double payment, double reliability, double history,
                      double manualAdjustment, double score) {}

    Components compute(Inputs inputs, CreditScoreConfig config);
}
