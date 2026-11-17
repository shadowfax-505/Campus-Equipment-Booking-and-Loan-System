package edu.campus.equipment.service;

import edu.campus.equipment.common.ApiException;
import edu.campus.equipment.domain.*;
import edu.campus.equipment.repo.*;
import edu.campus.equipment.service.notify.NotificationService;
import edu.campus.equipment.service.policy.CreditScoreStrategy;
import edu.campus.equipment.service.policy.CreditScoreStrategy.*;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Keeps each student's credit profile in step with their verified record. Scores are always recomputed
 * from source facts in the recent-history period (never incremented), so re-running a recalculation after
 * a retried event is harmless.
 */
@Service
public class CreditScoreService {

    public record Factors(Components components, CreditScoreConfig config, Map<String, Long> counts) {}

    private final CreditProfileRepository profiles;
    private final CreditScoreHistoryRepository history;
    private final LoanRepository loans;
    private final ConditionReportRepository reports;
    private final FineRepository fines;
    private final ReservationRepository reservations;
    private final UserRepository users;
    private final CreditScoreStrategy strategy;
    private final PolicyService policies;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Clock clock;

    public CreditScoreService(CreditProfileRepository profiles, CreditScoreHistoryRepository history, LoanRepository loans,
                              ConditionReportRepository reports, FineRepository fines, ReservationRepository reservations,
                              UserRepository users, CreditScoreStrategy strategy, PolicyService policies,
                              NotificationService notifications, AuditService audit, Clock clock) {
        this.profiles = profiles;
        this.history = history;
        this.loans = loans;
        this.reports = reports;
        this.fines = fines;
        this.reservations = reservations;
        this.users = users;
        this.strategy = strategy;
        this.policies = policies;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
    }

    /** New students start at the neutral score, not zero. */
    @Transactional
    public CreditProfile profile(User student) {
        return profiles.findByStudentId(student.getId()).orElseGet(() -> {
            CreditScoreConfig cfg = policies.scoreConfig();
            Components c = strategy.compute(new Inputs(List.of(), List.of(), List.of(), 0, clock.instant()), cfg);
            CreditProfile p = new CreditProfile();
            p.setStudent(student);
            apply(p, c);
            p = profiles.save(p);
            writeHistory(student, 0, c.score(), 0, ScoreEventType.INITIAL, "Neutral starting score for a new student",
                    null, null, null, c, null);
            return p;
        });
    }

    public double currentScore(User student) {
        return profile(student).getScore();
    }

    @EventListener
    public void onActivity(StudentActivityEvent e) {
        users.findById(e.studentId()).ifPresent(u ->
                recalculate(u, e.type(), e.reason(), e.loanId(), e.reservationId(), e.fineId(), null));
    }

    @Transactional
    public CreditProfile recalculate(User student, ScoreEventType type, String reason, Long loanId, Long reservationId,
                                     Long fineId, User actor) {
        CreditProfile p = profile(student);
        Components c = compute(student);
        double old = p.getScore();
        apply(p, c);
        profiles.save(p);
        if (Math.abs(c.score() - old) >= 0.01) {
            writeHistory(student, old, c.score(), 0, type, reason, loanId, reservationId, fineId, c, actor);
            double threshold = policies.scoreConfig().getSignificantChange();
            if (Math.abs(c.score() - old) >= threshold) {
                notifications.notify(student, NotificationType.SCORE_CHANGED, "Your borrowing score changed",
                        String.format("Your score moved from %.1f to %.1f. Reason: %s", old, c.score(), reason));
            }
        }
        return p;
    }

    @Transactional
    public CreditProfile adjust(User student, double points, String reason, User admin) {
        if (student.getRole() != Role.STUDENT) throw ApiException.badRequest("NOT_A_STUDENT", "Only students have credit scores");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("REASON_REQUIRED", "A reason is mandatory for score adjustments");
        if (points == 0 || Math.abs(points) > 100) throw ApiException.badRequest("INVALID_ADJUSTMENT", "Adjustment must be between -100 and 100 and non-zero");
        CreditProfile p = profile(student);
        double old = p.getScore();
        // Record the adjustment first so the recomputation picks it up.
        CreditScoreHistory h = writeHistory(student, old, old, points, ScoreEventType.MANUAL_ADJUSTMENT, reason, null, null, null, null, admin);
        Components c = compute(student);
        apply(p, c);
        h.setNewScore(c.score());
        h.setComponentsSnapshot(snapshot(c));
        audit.record(admin, "SCORE_ADJUSTED", "User", student.getId(),
                String.format("points=%+.2f old=%.2f new=%.2f reason=%s", points, old, c.score(), reason));
        notifications.notify(student, NotificationType.SCORE_CHANGED, "Your borrowing score was adjusted",
                String.format("An administrator adjusted your score by %+.1f (now %.1f). Reason: %s", points, c.score(), reason));
        return profiles.save(p);
    }

    /** Voids a manual adjustment after an upheld dispute. */
    @Transactional
    public void voidAdjustment(CreditScoreHistory entry, User admin, String reason) {
        entry.setVoided(true);
        history.save(entry);
        recalculate(entry.getStudent(), ScoreEventType.DISPUTE_CORRECTION, "Adjustment voided: " + reason, null, null, null, admin);
    }

    @Transactional
    public int recalculateAll(ScoreEventType type, String reason) {
        int n = 0;
        for (User s : users.findByRole(Role.STUDENT)) {
            recalculate(s, type, reason, null, null, null, null);
            n++;
        }
        return n;
    }

    @Transactional(readOnly = true)
    public Factors factors(User student) {
        return new Factors(compute(student), policies.scoreConfig(), counts(student));
    }

    public List<CreditScoreHistory> history(User student) {
        return history.findByStudentIdOrderByCreatedAtDesc(student.getId());
    }

    // ---- computation ---------------------------------------------------------------------------

    private Instant windowStart() {
        return clock.instant().minus(Duration.ofDays(policies.scoreConfig().getRecentHistoryDays()));
    }

    Components compute(User student) {
        CreditScoreConfig cfg = policies.scoreConfig();
        Instant now = clock.instant();
        Instant since = windowStart();

        List<ClosedLoan> closed = new ArrayList<>();
        for (Loan l : closedLoans(student, since)) {
            Instant end = l.getReturnedAt() != null ? l.getReturnedAt() : now;
            long late = Duration.between(l.getDueAt(), end).minusMinutes(l.getAppliedGraceMinutes()).getSeconds();
            Optional<ConditionReport> ret = reports.findFirstByLoanIdAndType(l.getId(), ConditionReportType.RETURN)
                    .filter(r -> !r.isVoided());
            closed.add(new ClosedLoan(late, l.getStatus() == LoanStatus.LOST,
                    ret.map(ConditionReport::isDamageVerified).orElse(false),
                    ret.map(r -> !r.getMissingAccessories().isEmpty()).orElse(false)));
        }

        List<FineRecord> fineRecords = fines.findRecentForStudent(student.getId(), since).stream()
                .filter(f -> f.getStatus() != FineStatus.WAIVED && f.getStatus() != FineStatus.CANCELLED)
                .map(f -> new FineRecord(f.getAssessedAt(), f.getSettledAt(), f.getStatus() == FineStatus.PAID))
                .toList();

        List<ApprovedOutcome> approvals = new ArrayList<>();
        for (Reservation r : reservations.findRecentForStudent(student.getId(), since)) {
            if (r.getStatus() == ReservationStatus.FULFILLED) approvals.add(ApprovedOutcome.COLLECTED);
            else if (r.getStatus() == ReservationStatus.EXPIRED && r.getCollectionDeadline() != null) approvals.add(ApprovedOutcome.MISSED_COLLECTION);
            else if (r.getStatus() == ReservationStatus.CANCELLED && r.isLateCancellation()) approvals.add(ApprovedOutcome.LATE_CANCELLATION);
        }

        double manual = history.findActiveAdjustments(student.getId(), since).stream()
                .mapToDouble(CreditScoreHistory::getAdjustment).sum();

        return strategy.compute(new Inputs(closed, fineRecords, approvals, manual, now), cfg);
    }

    private List<Loan> closedLoans(User student, Instant since) {
        List<Loan> out = new ArrayList<>(loans.findClosedSince(student.getId(), since));
        for (Loan l : loans.findLostSince(student.getId(), since)) {
            if (out.stream().noneMatch(x -> x.getId().equals(l.getId()))) out.add(l);
        }
        return out;
    }

    private Map<String, Long> counts(User student) {
        Instant since = windowStart();
        List<Loan> closed = closedLoans(student, since);
        long onTime = closed.stream().filter(l -> l.getReturnedAt() != null && !Duration.between(l.getDueAt(), l.getReturnedAt())
                .minusMinutes(l.getAppliedGraceMinutes()).isPositive()).count();
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("closedLoans", (long) closed.size());
        m.put("onTimeReturns", onTime);
        m.put("lateReturns", closed.stream().filter(l -> l.getStatus() == LoanStatus.RETURNED).count() - onTime);
        m.put("lostItems", closed.stream().filter(l -> l.getStatus() == LoanStatus.LOST).count());
        List<Reservation> rs = reservations.findRecentForStudent(student.getId(), since);
        m.put("missedCollections", rs.stream().filter(r -> r.getStatus() == ReservationStatus.EXPIRED && r.getCollectionDeadline() != null).count());
        m.put("lateCancellations", rs.stream().filter(r -> r.getStatus() == ReservationStatus.CANCELLED && r.isLateCancellation()).count());
        m.put("finesInPeriod", fines.findRecentForStudent(student.getId(), since).stream()
                .filter(f -> f.getStatus() != FineStatus.WAIVED && f.getStatus() != FineStatus.CANCELLED).count());
        m.put("recentHistoryDays", (long) policies.scoreConfig().getRecentHistoryDays());
        return m;
    }

    private static void apply(CreditProfile p, Components c) {
        p.setScore(c.score());
        p.setPunctuality(c.punctuality());
        p.setCare(c.care());
        p.setPaymentTimeliness(c.payment());
        p.setReliability(c.reliability());
        p.setHistory(c.history());
        p.setManualAdjustment(c.manualAdjustment());
        p.setUpdatedAt(Instant.now());
    }

    private static String snapshot(Components c) {
        return String.format(Locale.ROOT, "T=%.1f C=%.1f P=%.1f R=%.1f H=%.1f adj=%+.1f",
                c.punctuality(), c.care(), c.payment(), c.reliability(), c.history(), c.manualAdjustment());
    }

    private CreditScoreHistory writeHistory(User student, double old, double now, double adj, ScoreEventType type, String reason,
                                            Long loanId, Long reservationId, Long fineId, Components c, User actor) {
        CreditScoreHistory h = new CreditScoreHistory();
        h.setStudent(student);
        h.setPreviousScore(old);
        h.setNewScore(now);
        h.setAdjustment(adj);
        h.setEventType(type);
        h.setReason(reason == null ? type.name() : reason);
        h.setLoanId(loanId);
        h.setReservationId(reservationId);
        h.setFineId(fineId);
        h.setComponentsSnapshot(c == null ? null : snapshot(c));
        h.setChangedBy(actor);
        h.setCreatedAt(clock.instant());
        return history.save(h);
    }
}
