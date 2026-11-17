package edu.campus.equipment.service;

import edu.campus.equipment.common.ApiException;
import edu.campus.equipment.domain.*;
import edu.campus.equipment.repo.EquipmentRepository;
import edu.campus.equipment.repo.ExtensionRequestRepository;
import edu.campus.equipment.repo.LoanRepository;
import edu.campus.equipment.service.notify.NotificationService;
import edu.campus.equipment.service.policy.BorrowingLimitStrategy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class ExtensionService {
    private final ExtensionRequestRepository requests;
    private final LoanRepository loans;
    private final EquipmentRepository equipmentRepo;
    private final AvailabilityService availability;
    private final EligibilityService eligibility;
    private final BorrowingLimitStrategy limits;
    private final NotificationService notifications;
    private final AuditService audit;
    private final StaffScope scope;
    private final Clock clock;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    public ExtensionService(ExtensionRequestRepository requests, LoanRepository loans, EquipmentRepository equipmentRepo,
                            AvailabilityService availability, EligibilityService eligibility, BorrowingLimitStrategy limits,
                            NotificationService notifications, AuditService audit, StaffScope scope, Clock clock) {
        this.requests = requests;
        this.loans = loans;
        this.equipmentRepo = equipmentRepo;
        this.availability = availability;
        this.eligibility = eligibility;
        this.limits = limits;
        this.notifications = notifications;
        this.audit = audit;
        this.scope = scope;
        this.clock = clock;
    }

    @Transactional
    public ExtensionRequest request(User student, Long loanId, Instant requestedDueAt, String reason) {
        Loan l = loans.findById(loanId).orElseThrow(() -> ApiException.notFound("Loan"));
        if (!l.getStudent().getId().equals(student.getId())) throw ApiException.forbidden("You can only extend your own loans");
        Instant now = clock.instant();
        if (l.getStatus() != LoanStatus.ACTIVE) throw ApiException.conflict("LOAN_CLOSED", "This loan is already closed");
        if (!now.isBefore(l.getDueAt())) throw ApiException.unprocessable("PAST_DUE", "Extensions must be requested before the due time");
        if (requestedDueAt == null || !requestedDueAt.isAfter(l.getDueAt())) {
            throw ApiException.badRequest("INVALID_DUE", "The new due time must be later than the current one");
        }
        if (requests.existsByLoanIdAndStatus(loanId, ExtensionStatus.PENDING)) {
            throw ApiException.conflict("EXTENSION_PENDING", "An extension request for this loan is already pending");
        }
        limits.validateExtension(l.getEquipment(), l.getIssuedAt(), requestedDueAt, l.getExtensionCount());
        checkFree(l, requestedDueAt);

        ExtensionRequest x = new ExtensionRequest();
        x.setLoan(l);
        x.setCurrentDueAt(l.getDueAt());
        x.setRequestedDueAt(requestedDueAt);
        x.setStudentReason(reason);
        x.setCreatedAt(now);
        x = requests.save(x);
        notifications.notifyStaff(l.getEquipment(), NotificationType.EXTENSION_DECISION, "Extension requested",
                l.getStudent().getFullName() + " asked to keep " + l.getEquipment().getAssetTag() + " until " + notifications.at(requestedDueAt) + ".");
        return x;
    }

    private void checkFree(Loan l, Instant requestedDueAt) {
        if (!availability.isAvailable(l.getEquipment(), l.getDueAt(), requestedDueAt, null, l.getId())) {
            throw ApiException.conflict("EXTENSION_CONFLICT",
                    "The item is booked or scheduled for maintenance before " + notifications.at(requestedDueAt) + "; choose an earlier time");
        }
    }

    @Transactional
    public ExtensionRequest approve(User staff, Long requestId, boolean grantException, String reason) {
        ExtensionRequest x = requests.findById(requestId).orElseThrow(() -> ApiException.notFound("Extension request"));
        Loan l = x.getLoan();
        scope.require(staff, l.getEquipment());
        equipmentRepo.lockById(l.getEquipment().getId());
        entityManager.refresh(x);
        entityManager.refresh(l);
        if (x.getStatus() != ExtensionStatus.PENDING) throw new InvalidStateTransitionException("Extension", x.getStatus().name(), "APPROVED");
        if (l.getStatus() != LoanStatus.ACTIVE) throw ApiException.conflict("LOAN_CLOSED", "This loan is already closed");
        limits.validateExtension(l.getEquipment(), l.getIssuedAt(), x.getRequestedDueAt(), l.getExtensionCount());
        checkFree(l, x.getRequestedDueAt());

        EligibilityService.Result el = eligibility.check(l.getStudent(), EligibilityService.Stage.EXTENSION, null);
        if (!el.eligible()) {
            if (!grantException) {
                throw ApiException.unprocessable(el.hasBlockingFines() ? "UNPAID_FINES" : "NOT_ELIGIBLE",
                        "Student is not eligible for an extension: " + String.join("; ", el.reasons()) + ". An authorised exception is required.");
            }
            if (!staff.hasPermission(StaffPermission.GRANT_EXCEPTIONS)) throw ApiException.forbidden("Missing permission GRANT_EXCEPTIONS");
            if (reason == null || reason.isBlank()) throw ApiException.badRequest("REASON_REQUIRED", "Exceptions require a recorded reason");
            x.setExceptionGranted(true);
            audit.record(staff, "EXTENSION_EXCEPTION", "ExtensionRequest", x.getId(), String.join("; ", el.reasons()) + " -> " + reason);
        }
        x.setStatus(ExtensionStatus.APPROVED);
        x.setDecidedBy(staff);
        x.setDecidedAt(clock.instant());
        x.setDecisionReason(reason);
        l.setDueAt(x.getRequestedDueAt());
        l.setExtensionCount(l.getExtensionCount() + 1);
        l.setReturnReminderSentAt(null);
        notifications.notify(l.getStudent(), NotificationType.EXTENSION_DECISION, "Extension approved",
                l.getEquipment().getName() + " is now due " + notifications.at(l.getDueAt()) + ".");
        return x;
    }

    @Transactional
    public ExtensionRequest reject(User staff, Long requestId, String reason) {
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("REASON_REQUIRED", "A reason is required");
        ExtensionRequest x = requests.findById(requestId).orElseThrow(() -> ApiException.notFound("Extension request"));
        scope.require(staff, x.getLoan().getEquipment());
        if (x.getStatus() != ExtensionStatus.PENDING) throw new InvalidStateTransitionException("Extension", x.getStatus().name(), "REJECTED");
        x.setStatus(ExtensionStatus.REJECTED);
        x.setDecidedBy(staff);
        x.setDecidedAt(clock.instant());
        x.setDecisionReason(reason);
        notifications.notify(x.getLoan().getStudent(), NotificationType.EXTENSION_DECISION, "Extension not approved",
                "Your extension was rejected: " + reason + ". The original due time " + notifications.at(x.getLoan().getDueAt()) + " still applies.");
        return x;
    }

    public List<ExtensionRequest> pending(User staff) {
        return requests.findByStatusOrderByCreatedAt(ExtensionStatus.PENDING).stream()
                .filter(x -> scope.covers(staff, x.getLoan().getEquipment())).toList();
    }

    public List<ExtensionRequest> forStudent(User student) {
        return requests.findByLoanStudentIdOrderByCreatedAtDesc(student.getId());
    }
}
