package edu.campus.equipment.service;

import edu.campus.equipment.common.ApiException;
import edu.campus.equipment.domain.*;
import edu.campus.equipment.repo.*;
import edu.campus.equipment.service.notify.NotificationService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class LoanService {

    public record IssueCommand(EquipmentCondition condition, Set<String> accessoriesPresent, String notes) {}

    public record ReturnCommand(Instant returnedAt, EquipmentCondition condition, Set<String> accessoriesPresent,
                                boolean damageVerified, boolean requiresRepair, String notes, List<String> photoUrls) {}

    public record ReturnResult(Loan loan, ConditionReport report, Optional<Fine> lateFee) {}

    private final LoanRepository loans;
    private final ReservationRepository reservations;
    private final EquipmentRepository equipmentRepo;
    private final ConditionReportRepository reports;
    private final MaintenanceRecordRepository maintenance;
    private final EligibilityService eligibility;
    private final FineService fines;
    private final NotificationService notifications;
    private final AuditService audit;
    private final StaffScope scope;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    public LoanService(LoanRepository loans, ReservationRepository reservations, EquipmentRepository equipmentRepo,
                       ConditionReportRepository reports, MaintenanceRecordRepository maintenance, EligibilityService eligibility,
                       FineService fines, NotificationService notifications, AuditService audit, StaffScope scope,
                       ApplicationEventPublisher events, Clock clock) {
        this.loans = loans;
        this.reservations = reservations;
        this.equipmentRepo = equipmentRepo;
        this.reports = reports;
        this.maintenance = maintenance;
        this.eligibility = eligibility;
        this.fines = fines;
        this.notifications = notifications;
        this.audit = audit;
        this.scope = scope;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public Loan issue(User staff, Long reservationId, IssueCommand cmd) {
        Reservation r = reservations.findById(reservationId).orElseThrow(() -> ApiException.notFound("Reservation"));
        scope.require(staff, r.getEquipment());
        Equipment e = equipmentRepo.lockById(r.getEquipment().getId()).orElseThrow();
        entityManager.refresh(r);
        if (r.getStatus() != ReservationStatus.APPROVED) {
            throw new InvalidStateTransitionException("Reservation", r.getStatus().name(), "issued");
        }
        Instant now = clock.instant();
        Instant earliest = r.getStartAt().minus(Duration.ofMinutes(e.getPolicy().getEarlyCollectionMinutes()));
        if (now.isBefore(earliest)) {
            throw ApiException.unprocessable("TOO_EARLY", "This booking can be collected from " + notifications.at(earliest));
        }
        if (r.getCollectionDeadline() != null && now.isAfter(r.getCollectionDeadline())) {
            throw ApiException.unprocessable("COLLECTION_DEADLINE_PASSED", "The collection deadline has passed");
        }
        EligibilityService.Result el = eligibility.check(r.getStudent(), EligibilityService.Stage.COLLECTION, r.getId());
        if (!el.eligible()) {
            throw ApiException.unprocessable(el.hasBlockingFines() ? "UNPAID_FINES" : "NOT_ELIGIBLE",
                    "Cannot issue: " + String.join("; ", el.reasons()));
        }
        if (e.getStatus() != OperationalStatus.ACTIVE) {
            throw ApiException.conflict("NOT_OPERATIONAL", "The item is " + e.getStatus() + " and cannot be issued");
        }
        if (!loans.findByEquipmentIdAndStatus(e.getId(), LoanStatus.ACTIVE).isEmpty()) {
            throw ApiException.conflict("STILL_ON_LOAN", "The previous borrower has not returned this item yet");
        }
        if (cmd.condition() == null) throw ApiException.badRequest("CONDITION_REQUIRED", "Record the condition at issue");

        r.moveTo(ReservationStatus.FULFILLED);
        Loan l = new Loan();
        l.setReservation(r);
        l.setStudent(r.getStudent());
        l.setEquipment(e);
        l.setIssuedAt(now);
        l.setIssuedBy(staff);
        l.setDueAt(r.getEndAt());
        l.setOriginalDueAt(r.getEndAt());
        l.setAppliedDailyRate(e.effectiveDailyRate());
        l.setAppliedGraceMinutes(e.getPolicy().getGracePeriodMinutes());
        l.setAppliedFineCap(e.getPolicy().getFineCap());
        l.setAppliedPolicyName(e.getPolicy().getName());
        l = loans.save(l);

        ConditionReport cr = new ConditionReport();
        cr.setLoan(l);
        cr.setType(ConditionReportType.ISSUE);
        cr.setCondition(cmd.condition());
        cr.setAccessoriesPresent(cmd.accessoriesPresent() == null ? new HashSet<>(e.getAccessories()) : new HashSet<>(cmd.accessoriesPresent()));
        Set<String> missing = new HashSet<>(e.getAccessories());
        missing.removeAll(cr.getAccessoriesPresent());
        cr.setMissingAccessories(missing);
        cr.setNotes(cmd.notes());
        cr.setRecordedBy(staff);
        cr.setRecordedAt(now);
        reports.save(cr);
        e.setCondition(cmd.condition());

        audit.record(staff, "EQUIPMENT_ISSUED", "Loan", l.getId(), e.getAssetTag() + " to student " + r.getStudent().getId()
                + ", condition " + cmd.condition() + (missing.isEmpty() ? "" : ", issued without " + missing));
        notifications.notify(r.getStudent(), NotificationType.GENERAL, "Equipment collected",
                "You collected " + e.getName() + ". It is due back by " + notifications.at(l.getDueAt()) + ".");
        events.publishEvent(new StudentActivityEvent(r.getStudent().getId(), ScoreEventType.RESERVATION_FULFILLED,
                "Collected on time", l.getId(), r.getId(), null));
        return l;
    }

    /** Returns are always accepted, whatever the student owes. */
    @Transactional
    public ReturnResult receiveReturn(User staff, Long loanId, ReturnCommand cmd) {
        Loan l = get(loanId);
        scope.require(staff, l.getEquipment());
        Instant now = clock.instant();
        Instant at = cmd.returnedAt() == null ? now : cmd.returnedAt();
        if (at.isAfter(now.plusSeconds(60))) throw ApiException.badRequest("INVALID_TIME", "Return time cannot be in the future");
        if (at.isBefore(l.getIssuedAt())) throw ApiException.badRequest("INVALID_TIME", "Return time is before the issue time");
        if (cmd.condition() == null) throw ApiException.badRequest("CONDITION_REQUIRED", "Record the condition on return");
        l.moveTo(LoanStatus.RETURNED);
        l.setReturnedAt(at);
        l.setReceivedBy(staff);

        Equipment e = l.getEquipment();
        ConditionReport cr = new ConditionReport();
        cr.setLoan(l);
        cr.setType(ConditionReportType.RETURN);
        cr.setCondition(cmd.condition());
        Set<String> issued = reports.findFirstByLoanIdAndType(l.getId(), ConditionReportType.ISSUE)
                .map(ConditionReport::getAccessoriesPresent).map(HashSet::new).orElse(new HashSet<>(e.getAccessories()));
        Set<String> present = cmd.accessoriesPresent() == null ? new HashSet<>(issued) : new HashSet<>(cmd.accessoriesPresent());
        Set<String> missing = new HashSet<>(issued);
        missing.removeAll(present);
        cr.setAccessoriesPresent(present);
        cr.setMissingAccessories(missing);
        cr.setDamageVerified(cmd.damageVerified());
        cr.setRequiresRepair(cmd.requiresRepair());
        cr.setNotes(cmd.notes());
        cr.setPhotoUrls(cmd.photoUrls() == null ? new ArrayList<>() : new ArrayList<>(cmd.photoUrls()));
        cr.setRecordedBy(staff);
        cr.setRecordedAt(now);
        reports.save(cr);

        e.setCondition(cmd.condition());
        if (cmd.requiresRepair() || cmd.condition() == EquipmentCondition.UNUSABLE) {
            e.setStatus(OperationalStatus.UNDER_MAINTENANCE);
            MaintenanceRecord m = new MaintenanceRecord();
            m.setEquipment(e);
            m.setReason("Repair after return of loan #" + l.getId() + (cmd.notes() == null ? "" : ": " + cmd.notes()));
            m.setStartAt(now);
            m.setRelatedLoanId(l.getId());
            m.setCreatedBy(staff);
            m.setCreatedAt(now);
            maintenance.save(m);
        }
        audit.record(staff, "RETURN_INSPECTED", "Loan", l.getId(), "condition " + cmd.condition()
                + (cmd.damageVerified() ? ", verified damage" : "") + (missing.isEmpty() ? "" : ", missing " + missing)
                + (cmd.requiresRepair() ? ", sent to repair" : ""));

        boolean lateReturn = fines.estimate(l).isChargeable();
        events.publishEvent(new StudentActivityEvent(l.getStudent().getId(), ScoreEventType.RETURN_COMPLETED,
                lateReturn ? "Late return" : "Return completed", l.getId(), l.getReservation().getId(), null));
        Optional<Fine> late = fines.assessLateFee(l);
        notifications.notify(l.getStudent(), NotificationType.GENERAL, "Return recorded",
                "We received " + e.getName() + " at " + notifications.at(at) + "."
                        + late.map(f -> " It was late; a fee of " + f.getAmount() + " was charged.").orElse(" Thank you for returning it on time.")
                        + (missing.isEmpty() ? "" : " Missing accessories: " + String.join(", ", missing) + "."));
        if (cmd.damageVerified() || !missing.isEmpty()) {
            notifications.notifyStaff(e, NotificationType.GENERAL, "Return needs a charge assessment",
                    "Loan #" + l.getId() + " (" + e.getAssetTag() + ") came back with " + (cmd.damageVerified() ? "verified damage" : "")
                            + (missing.isEmpty() ? "" : " missing " + missing) + ".");
        }
        return new ReturnResult(l, cr, late);
    }

    @Transactional
    public Fine assessCharge(User staff, Long loanId, FineType type, BigDecimal amount, String assessment) {
        Loan l = get(loanId);
        scope.require(staff, l.getEquipment());
        return fines.assessCharge(l, type, amount, assessment, staff);
    }

    @Transactional
    public Loan reportLost(User staff, Long loanId, BigDecimal charge, String notes) {
        Loan l = get(loanId);
        scope.require(staff, l.getEquipment());
        if (notes == null || notes.isBlank()) throw ApiException.badRequest("NOTES_REQUIRED", "Describe the circumstances of the loss");
        l.moveTo(LoanStatus.LOST);
        l.setReturnedAt(null);
        l.setReceivedBy(staff);
        Equipment e = l.getEquipment();
        e.setStatus(OperationalStatus.LOST);
        audit.record(staff, "EQUIPMENT_LOST", "Loan", l.getId(), e.getAssetTag() + ": " + notes);
        if (charge != null && charge.signum() > 0) {
            fines.assessCharge(l, FineType.LOST_EQUIPMENT, charge, "Lost equipment " + e.getAssetTag() + ": " + notes, staff);
        }
        events.publishEvent(new StudentActivityEvent(l.getStudent().getId(), ScoreEventType.RETURN_COMPLETED,
                "Equipment reported lost", l.getId(), null, null));
        return l;
    }

    public Loan get(Long id) {
        return loans.findById(id).orElseThrow(() -> ApiException.notFound("Loan"));
    }

    public List<ConditionReport> reportsFor(Loan l) {
        return reports.findByLoanIdOrderByRecordedAt(l.getId());
    }

    public List<Loan> forStudent(User student) {
        return loans.findByStudentIdOrderByIssuedAtDesc(student.getId());
    }

    public List<Loan> activeFor(User staff, boolean overdueOnly) {
        Instant now = clock.instant();
        return loans.findByStatus(LoanStatus.ACTIVE).stream()
                .filter(l -> scope.covers(staff, l.getEquipment()))
                .filter(l -> !overdueOnly || l.isOverdue(now))
                .sorted(Comparator.comparing(Loan::getDueAt)).toList();
    }

    /** Scheduled: sends one overdue notice per loan. Overdue state itself is derived, not stored. */
    @Transactional
    public int notifyOverdue() {
        Instant now = clock.instant();
        int n = 0;
        for (Loan l : loans.findNewlyOverdue(now)) {
            l.setOverdueNotifiedAt(now);
            var est = fines.estimate(l);
            notifications.notify(l.getStudent(), NotificationType.OVERDUE, "Equipment overdue",
                    l.getEquipment().getName() + " was due " + notifications.at(l.getDueAt()) + ". Please return it as soon as possible. "
                            + "Late fees accrue at " + l.getAppliedDailyRate() + " per started day after a " + l.getAppliedGraceMinutes()
                            + "-minute grace period" + (est.isChargeable() ? " (currently " + est.amount() + ")." : "."));
            notifications.notifyStaff(l.getEquipment(), NotificationType.OVERDUE, "Loan overdue",
                    "Loan #" + l.getId() + " (" + l.getEquipment().getAssetTag() + ") by " + l.getStudent().getFullName() + " is overdue.");
            n++;
        }
        return n;
    }

    @Transactional
    public int sendReturnReminders(Duration lead) {
        Instant now = clock.instant();
        int n = 0;
        for (Loan l : loans.findNeedingReturnReminder(now, now.plus(lead))) {
            l.setReturnReminderSentAt(now);
            notifications.notify(l.getStudent(), NotificationType.RETURN_REMINDER, "Return reminder",
                    l.getEquipment().getName() + " is due back by " + notifications.at(l.getDueAt()) + " at " + l.getEquipment().getLocation().getName() + ".");
            n++;
        }
        return n;
    }

    @Transactional
    public int sendCollectionReminders(Duration lead) {
        Instant now = clock.instant();
        int n = 0;
        for (Reservation r : reservations.findNeedingCollectionReminder(now, now.plus(lead))) {
            r.setCollectionReminderSentAt(now);
            notifications.notify(r.getStudent(), NotificationType.COLLECTION_REMINDER, "Collection reminder",
                    "Collect " + r.getEquipment().getName() + " from " + r.getEquipment().getLocation().getName() + " by "
                            + notifications.at(r.getCollectionDeadline()) + " or the booking will expire.");
            n++;
        }
        return n;
    }
}
