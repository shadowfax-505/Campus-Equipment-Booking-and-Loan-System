package edu.campus.equipment.service;

import edu.campus.equipment.common.ApiException;
import edu.campus.equipment.domain.*;
import edu.campus.equipment.repo.FineRepository;
import edu.campus.equipment.repo.ReservationRepository;
import edu.campus.equipment.service.notify.NotificationService;
import edu.campus.equipment.service.policy.FineCalculationStrategy;
import edu.campus.equipment.service.policy.FineCalculationStrategy.FineQuote;
import edu.campus.equipment.service.policy.FineCalculationStrategy.LateFeeTerms;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class FineService {
    private final FineRepository fines;
    private final ReservationRepository reservations;
    private final FineCalculationStrategy calculator;
    private final EligibilityService eligibility;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public FineService(FineRepository fines, ReservationRepository reservations, FineCalculationStrategy calculator,
                       EligibilityService eligibility, NotificationService notifications, AuditService audit,
                       ApplicationEventPublisher events, Clock clock) {
        this.fines = fines;
        this.reservations = reservations;
        this.calculator = calculator;
        this.eligibility = eligibility;
        this.notifications = notifications;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    public static LateFeeTerms termsOf(Loan l) {
        return new LateFeeTerms(l.getAppliedDailyRate(), l.getAppliedGraceMinutes(), l.getAppliedFineCap());
    }

    /** Estimated accrued late fee for an open loan, using the current time in place of the return time. */
    public FineQuote estimate(Loan loan) {
        Instant end = loan.getReturnedAt() != null ? loan.getReturnedAt() : clock.instant();
        return calculator.lateFee(loan.getDueAt(), end, termsOf(loan));
    }

    /** Final late fee, assessed once per loan; the dedupe key makes repeated calls a no-op. */
    @Transactional
    public Optional<Fine> assessLateFee(Loan loan) {
        if (loan.getReturnedAt() == null) throw new IllegalStateException("Late fee is final only once the loan is closed");
        FineQuote q = calculator.lateFee(loan.getDueAt(), loan.getReturnedAt(), termsOf(loan));
        if (!q.isChargeable()) return Optional.empty();
        String key = "LATE_RETURN:loan:" + loan.getId();
        if (fines.existsByDedupeKey(key)) return Optional.empty();
        Fine f = newFine(loan.getStudent(), loan, FineType.LATE_RETURN, key, q.amount(),
                "Late return of " + loan.getEquipment().getName() + " (" + loan.getEquipment().getAssetTag() + "): " + q.explanation(), null);
        return Optional.of(f);
    }

    /** Damage, missing accessories or loss: only by authorised staff, with a documented assessment. */
    @Transactional
    public Fine assessCharge(Loan loan, FineType type, BigDecimal amount, String assessment, User staff) {
        if (type == FineType.LATE_RETURN) throw ApiException.badRequest("INVALID_TYPE", "Late fees are calculated automatically");
        if (!staff.hasPermission(StaffPermission.ASSESS_CHARGES)) throw ApiException.forbidden("Missing permission ASSESS_CHARGES");
        if (amount == null || amount.signum() <= 0) throw ApiException.badRequest("INVALID_AMOUNT", "Amount must be positive");
        if (assessment == null || assessment.isBlank()) throw ApiException.badRequest("ASSESSMENT_REQUIRED", "A documented assessment is required");
        String key = type.name() + ":loan:" + loan.getId();
        if (fines.existsByDedupeKey(key)) {
            throw ApiException.conflict("DUPLICATE_CHARGE", "A " + type + " charge already exists for this loan; correct it instead of adding another");
        }
        Fine f = newFine(loan.getStudent(), loan, type, key, amount.setScale(2, RoundingMode.HALF_UP), assessment, staff);
        audit.record(staff, "CHARGE_ASSESSED", "Fine", f.getId(), type + " " + f.getAmount() + " on loan " + loan.getId() + ": " + assessment);
        return f;
    }

    private Fine newFine(User student, Loan loan, FineType type, String key, BigDecimal amount, String description, User by) {
        Fine f = new Fine();
        f.setStudent(student);
        f.setLoan(loan);
        f.setType(type);
        f.setDedupeKey(key);
        f.setAmount(amount);
        f.setDescription(description);
        f.setAssessedAt(clock.instant());
        f.setAssessedBy(by);
        f = fines.save(f);
        notifications.notify(student, NotificationType.FINE_ASSESSED, "A charge was added to your account",
                String.format("%s: %s. New requests are blocked until it is paid. %s", type.name().replace('_', ' ').toLowerCase(),
                        amount.toPlainString(), description));
        flagFutureBookings(student, "Student has an unpaid " + type.name().replace('_', ' ').toLowerCase() + " charge");
        events.publishEvent(new StudentActivityEvent(student.getId(), ScoreEventType.FINE_ASSESSED,
                "Charge assessed: " + type, loan == null ? null : loan.getId(), null, f.getId()));
        return f;
    }

    /** Existing future bookings are flagged for staff review rather than silently cancelled. */
    void flagFutureBookings(User student, String reason) {
        for (Reservation r : reservations.findByStudentIdAndStatusAndStartAtAfter(student.getId(), ReservationStatus.APPROVED,
                clock.instant().minusSeconds(24 * 3600))) {
            if (!r.isFlaggedForReview()) {
                r.setFlaggedForReview(true);
                r.setFlagReason(reason);
                notifications.notify(student, NotificationType.BOOKING_FLAGGED, "Booking flagged for review",
                        "Your booking of " + r.getEquipment().getName() + " starting " + notifications.at(r.getStartAt())
                                + " cannot be collected until your fines are cleared.");
                notifications.notifyStaff(r.getEquipment(), NotificationType.BOOKING_FLAGGED, "Booking flagged: unpaid fine",
                        "Reservation #" + r.getId() + " for " + r.getEquipment().getName() + " belongs to a student with unpaid fines.");
            }
        }
    }

    /** Called after any payment or waiver: once nothing blocks the student any more, their flags are lifted. */
    void clearFlagsIfSettled(User student) {
        if (eligibility.blockingBalance(student).signum() > 0) return;
        for (Reservation r : reservations.findByStudentIdAndStatusAndStartAtAfter(student.getId(), ReservationStatus.APPROVED,
                clock.instant().minusSeconds(24 * 3600))) {
            if (r.isFlaggedForReview() && r.getFlagReason() != null && r.getFlagReason().startsWith("Student has an unpaid")) {
                r.setFlaggedForReview(false);
                r.setFlagReason(null);
            }
        }
    }

    @Transactional
    public Fine waive(Fine fine, String reason, User admin) {
        requireReason(reason);
        Fine f = fines.findById(fine.getId()).orElseThrow(() -> ApiException.notFound("Fine"));
        if (!f.getStatus().isPayable()) throw ApiException.conflict("NOT_PAYABLE", "Only outstanding fines can be waived");
        BigDecimal before = f.balance();
        f.setStatus(FineStatus.WAIVED);
        f.setSettledAt(clock.instant());
        f.setAdjustmentReason(reason);
        f.setAdjustedBy(admin);
        audit.record(admin, "FINE_WAIVED", "Fine", f.getId(), "waived balance " + before + ": " + reason);
        notifications.notify(f.getStudent(), NotificationType.PAYMENT_CONFIRMED, "Fine waived",
                "A balance of " + before + " was waived. Reason: " + reason);
        clearFlagsIfSettled(f.getStudent());
        events.publishEvent(new StudentActivityEvent(f.getStudent().getId(), ScoreEventType.FINE_WAIVED, "Fine waived: " + reason,
                f.getLoan() == null ? null : f.getLoan().getId(), null, f.getId()));
        return f;
    }

    /** Corrects a fine amount. The new amount cannot go below what has already been paid. */
    @Transactional
    public Fine correct(Fine fine, BigDecimal newAmount, String reason, User admin) {
        requireReason(reason);
        Fine f = fines.findById(fine.getId()).orElseThrow(() -> ApiException.notFound("Fine"));
        if (f.getStatus() == FineStatus.WAIVED || f.getStatus() == FineStatus.CANCELLED) {
            throw ApiException.conflict("NOT_CORRECTABLE", "Waived or cancelled fines cannot be corrected");
        }
        if (newAmount == null || newAmount.signum() < 0) throw ApiException.badRequest("INVALID_AMOUNT", "Amount cannot be negative");
        if (newAmount.compareTo(f.getPaidAmount()) < 0) {
            throw ApiException.unprocessable("BELOW_PAID", "The corrected amount is below the " + f.getPaidAmount() + " already paid; issue a refund outside the system first");
        }
        BigDecimal old = f.getAmount();
        f.setAmount(newAmount.setScale(2, RoundingMode.HALF_UP));
        f.setAdjustmentReason(reason);
        f.setAdjustedBy(admin);
        if (f.getAmount().signum() == 0) {
            f.setStatus(FineStatus.CANCELLED);
            f.setSettledAt(clock.instant());
        } else {
            recomputeStatus(f);
        }
        audit.record(admin, "FINE_CORRECTED", "Fine", f.getId(), "amount " + old + " -> " + f.getAmount() + ": " + reason);
        notifications.notify(f.getStudent(), NotificationType.FINE_ASSESSED, "Fine corrected",
                "A fine was corrected from " + old + " to " + f.getAmount() + ". Reason: " + reason);
        clearFlagsIfSettled(f.getStudent());
        events.publishEvent(new StudentActivityEvent(f.getStudent().getId(), ScoreEventType.DISPUTE_CORRECTION, "Fine corrected: " + reason,
                f.getLoan() == null ? null : f.getLoan().getId(), null, f.getId()));
        return f;
    }

    void recomputeStatus(Fine f) {
        int cmp = f.getPaidAmount().compareTo(f.getAmount());
        if (cmp >= 0) {
            f.setStatus(FineStatus.PAID);
            if (f.getSettledAt() == null) f.setSettledAt(clock.instant());
        } else if (f.getPaidAmount().signum() > 0) {
            f.setStatus(FineStatus.PARTIALLY_PAID);
            f.setSettledAt(null);
        } else {
            f.setStatus(FineStatus.OUTSTANDING);
            f.setSettledAt(null);
        }
    }

    public List<Fine> forStudent(User student) {
        return fines.findByStudentIdOrderByAssessedAtDesc(student.getId());
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("REASON_REQUIRED", "A reason is required");
    }
}
