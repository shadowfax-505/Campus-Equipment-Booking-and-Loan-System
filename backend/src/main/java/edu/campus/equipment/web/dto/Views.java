package edu.campus.equipment.web.dto;

import edu.campus.equipment.domain.*;
import edu.campus.equipment.service.AvailabilityService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Response shapes. Entities never leave the service layer directly. */
public final class Views {
    private Views() {}

    public record UserView(Long id, String email, String fullName, String institutionId, String department, String phone,
                           Role role, AccountStatus status, Set<StaffPermission> permissions, List<LocationView> managedLocations,
                           Instant createdAt) {
        public static UserView of(User u) {
            return new UserView(u.getId(), u.getEmail(), u.getFullName(), u.getInstitutionId(), u.getDepartment(), u.getPhone(),
                    u.getRole(), u.getStatus(), u.getPermissions(),
                    u.getManagedLocations().stream().map(LocationView::of).sorted(Comparator.comparing(LocationView::name)).toList(),
                    u.getCreatedAt());
        }
    }

    public record PersonRef(Long id, String fullName, String institutionId) {
        public static PersonRef of(User u) {
            return u == null ? null : new PersonRef(u.getId(), u.getFullName(), u.getInstitutionId());
        }
    }

    public record CategoryView(Long id, String name, String description) {
        public static CategoryView of(Category c) { return new CategoryView(c.getId(), c.getName(), c.getDescription()); }
    }

    public record LocationView(Long id, String name, String department, String room) {
        public static LocationView of(Location l) { return new LocationView(l.getId(), l.getName(), l.getDepartment(), l.getRoom()); }
    }

    public record PolicyView(Long id, String name, boolean defaultPolicy, int maxLoanHours, int minLoanMinutes, int maxAdvanceDays,
                             int maxActiveBookings, BigDecimal dailyOverdueRate, int gracePeriodMinutes, BigDecimal fineCap,
                             int decisionWindowMinutes, int collectionWindowHours, int earlyCollectionMinutes,
                             int cancellationCutoffHours, int maxExtensions, boolean waitlistUnsuccessful,
                             boolean disputedFinesBlock, boolean overdueLoanBlocks) {
        public static PolicyView of(BorrowingPolicy p) {
            return new PolicyView(p.getId(), p.getName(), p.isDefaultPolicy(), p.getMaxLoanHours(), p.getMinLoanMinutes(),
                    p.getMaxAdvanceDays(), p.getMaxActiveBookings(), p.getDailyOverdueRate(), p.getGracePeriodMinutes(), p.getFineCap(),
                    p.getDecisionWindowMinutes(), p.getCollectionWindowHours(), p.getEarlyCollectionMinutes(),
                    p.getCancellationCutoffHours(), p.getMaxExtensions(), p.isWaitlistUnsuccessful(), p.isDisputedFinesBlock(),
                    p.isOverdueLoanBlocks());
        }
    }

    public record EquipmentView(Long id, String assetTag, String name, String description, CategoryView category,
                                LocationView location, String serialNumber, EquipmentCondition condition, OperationalStatus status,
                                PolicyView policy, BigDecimal dailyOverdueRate, List<String> accessories, String borrowingNotes,
                                String imageUrl, boolean availableNow) {
        public static EquipmentView of(Equipment e, boolean availableNow) {
            return new EquipmentView(e.getId(), e.getAssetTag(), e.getName(), e.getDescription(), CategoryView.of(e.getCategory()),
                    LocationView.of(e.getLocation()), e.getSerialNumber(), e.getCondition(), e.getStatus(), PolicyView.of(e.getPolicy()),
                    e.effectiveDailyRate(), e.getAccessories(), e.getBorrowingNotes(), e.getImageUrl(), availableNow);
        }
    }

    public record EquipmentRef(Long id, String assetTag, String name, String location) {
        public static EquipmentRef of(Equipment e) {
            return new EquipmentRef(e.getId(), e.getAssetTag(), e.getName(), e.getLocation().getName());
        }
    }

    public record BlockView(AvailabilityService.BlockType type, Instant start, Instant end, String note, Long reservationId, Long loanId) {
        public static BlockView of(AvailabilityService.Block b, boolean staff) {
            return new BlockView(b.type(), b.start(), b.end(), staff ? b.note() : (b.type() == AvailabilityService.BlockType.MAINTENANCE ? "Maintenance" : null),
                    staff ? b.reservationId() : null, staff ? b.loanId() : null);
        }
    }

    public record ReservationView(Long id, EquipmentRef equipment, PersonRef student, Instant startAt, Instant endAt,
                                  ReservationStatus status, String purpose, Instant createdAt, Instant decisionWindowEndsAt,
                                  Instant decidedAt, PersonRef decidedBy, String decisionReason, Instant collectionDeadline,
                                  boolean lateCancellation, boolean flaggedForReview, String flagReason, Long allocationDecisionId,
                                  Long loanId) {
        public static ReservationView of(Reservation r, Long loanId) {
            return new ReservationView(r.getId(), EquipmentRef.of(r.getEquipment()), PersonRef.of(r.getStudent()), r.getStartAt(),
                    r.getEndAt(), r.getStatus(), r.getPurpose(), r.getCreatedAt(), r.getDecisionWindowEndsAt(), r.getDecidedAt(),
                    PersonRef.of(r.getDecidedBy()), r.getDecisionReason(), r.getCollectionDeadline(), r.isLateCancellation(),
                    r.isFlaggedForReview(), r.getFlagReason(), r.getAllocationDecision() == null ? null : r.getAllocationDecision().getId(),
                    loanId);
        }
    }

    public record ConditionReportView(Long id, ConditionReportType type, EquipmentCondition condition, Set<String> accessoriesPresent,
                                      Set<String> missingAccessories, boolean damageVerified, boolean requiresRepair, String notes,
                                      List<String> photoUrls, boolean voided, PersonRef recordedBy, Instant recordedAt) {
        public static ConditionReportView of(ConditionReport c) {
            return new ConditionReportView(c.getId(), c.getType(), c.getCondition(), c.getAccessoriesPresent(), c.getMissingAccessories(),
                    c.isDamageVerified(), c.isRequiresRepair(), c.getNotes(), c.getPhotoUrls(), c.isVoided(), PersonRef.of(c.getRecordedBy()),
                    c.getRecordedAt());
        }
    }

    public record LoanView(Long id, Long reservationId, EquipmentRef equipment, PersonRef student, LoanStatus status, Instant issuedAt,
                           PersonRef issuedBy, Instant dueAt, Instant originalDueAt, Instant returnedAt, PersonRef receivedBy,
                           int extensionCount, boolean overdue, BigDecimal estimatedLateFee, String lateFeeExplanation,
                           BigDecimal appliedDailyRate, int appliedGraceMinutes, BigDecimal appliedFineCap, String appliedPolicyName,
                           List<ConditionReportView> conditionReports) {
        public static LoanView of(Loan l, Instant now, BigDecimal est, String expl, List<ConditionReport> reports) {
            return new LoanView(l.getId(), l.getReservation().getId(), EquipmentRef.of(l.getEquipment()), PersonRef.of(l.getStudent()),
                    l.getStatus(), l.getIssuedAt(), PersonRef.of(l.getIssuedBy()), l.getDueAt(), l.getOriginalDueAt(), l.getReturnedAt(),
                    PersonRef.of(l.getReceivedBy()), l.getExtensionCount(), l.isOverdue(now), est, expl, l.getAppliedDailyRate(),
                    l.getAppliedGraceMinutes(), l.getAppliedFineCap(), l.getAppliedPolicyName(),
                    reports == null ? List.of() : reports.stream().map(ConditionReportView::of).toList());
        }
    }

    public record FineView(Long id, PersonRef student, Long loanId, String equipment, FineType type, BigDecimal amount, BigDecimal paidAmount,
                           BigDecimal balance, FineStatus status, String description, Instant assessedAt, PersonRef assessedBy,
                           Instant settledAt, String adjustmentReason, boolean disputed) {
        public static FineView of(Fine f, boolean disputed) {
            return new FineView(f.getId(), PersonRef.of(f.getStudent()), f.getLoan() == null ? null : f.getLoan().getId(),
                    f.getLoan() == null ? null : f.getLoan().getEquipment().getName(), f.getType(), f.getAmount(), f.getPaidAmount(),
                    f.balance(), f.getStatus(), f.getDescription(), f.getAssessedAt(), PersonRef.of(f.getAssessedBy()), f.getSettledAt(),
                    f.getAdjustmentReason(), disputed);
        }
    }

    public record AllocationView(Long fineId, BigDecimal amount) {}

    public record PaymentView(Long id, PersonRef student, BigDecimal amount, PaymentChannel channel, PaymentStatus status,
                              String reference, String provider, List<Long> targetFineIds, String notes, String evidenceUrl,
                              Instant submittedAt, Instant completedAt, PersonRef verifiedBy, String failureReason,
                              List<AllocationView> allocations) {
        public static PaymentView of(Payment p, List<PaymentAllocation> allocs) {
            return new PaymentView(p.getId(), PersonRef.of(p.getStudent()), p.getAmount(), p.getChannel(), p.getStatus(), p.getReference(),
                    p.getProvider(), p.getTargetFineIds(), p.getNotes(), p.getEvidenceUrl(), p.getSubmittedAt(), p.getCompletedAt(),
                    PersonRef.of(p.getVerifiedBy()), p.getFailureReason(),
                    allocs.stream().map(a -> new AllocationView(a.getFine().getId(), a.getAmount())).toList());
        }
    }

    public record ExtensionView(Long id, Long loanId, EquipmentRef equipment, PersonRef student, Instant currentDueAt,
                                Instant requestedDueAt, String studentReason, ExtensionStatus status, String decisionReason,
                                boolean exceptionGranted, PersonRef decidedBy, Instant decidedAt, Instant createdAt) {
        public static ExtensionView of(ExtensionRequest x) {
            return new ExtensionView(x.getId(), x.getLoan().getId(), EquipmentRef.of(x.getLoan().getEquipment()),
                    PersonRef.of(x.getLoan().getStudent()), x.getCurrentDueAt(), x.getRequestedDueAt(), x.getStudentReason(),
                    x.getStatus(), x.getDecisionReason(), x.isExceptionGranted(), PersonRef.of(x.getDecidedBy()), x.getDecidedAt(),
                    x.getCreatedAt());
        }
    }

    public record MaintenanceView(Long id, EquipmentRef equipment, String reason, Instant startAt, Instant endAt, boolean completed,
                                  String resolutionNotes, Long relatedLoanId, PersonRef createdBy, PersonRef completedBy, Instant createdAt) {
        public static MaintenanceView of(MaintenanceRecord m) {
            return new MaintenanceView(m.getId(), EquipmentRef.of(m.getEquipment()), m.getReason(), m.getStartAt(), m.getEndAt(),
                    m.isCompleted(), m.getResolutionNotes(), m.getRelatedLoanId(), PersonRef.of(m.getCreatedBy()),
                    PersonRef.of(m.getCompletedBy()), m.getCreatedAt());
        }
    }

    public record NotificationView(Long id, NotificationType type, String title, String message, Instant readAt,
                                   DeliveryStatus emailStatus, Instant createdAt) {
        public static NotificationView of(Notification n) {
            return new NotificationView(n.getId(), n.getType(), n.getTitle(), n.getMessage(), n.getReadAt(), n.getEmailStatus(), n.getCreatedAt());
        }
    }

    public record DisputeView(Long id, PersonRef student, DisputeType type, Long subjectId, String description, DisputeStatus status,
                              String resolution, PersonRef resolvedBy, Instant resolvedAt, Instant createdAt) {
        public static DisputeView of(Dispute d) {
            return new DisputeView(d.getId(), PersonRef.of(d.getStudent()), d.getType(), d.getSubjectId(), d.getDescription(),
                    d.getStatus(), d.getResolution(), PersonRef.of(d.getResolvedBy()), d.getResolvedAt(), d.getCreatedAt());
        }
    }

    public record ScoreHistoryView(Long id, double previousScore, double newScore, double adjustment, ScoreEventType eventType,
                                   String reason, Long loanId, Long reservationId, Long fineId, String components,
                                   PersonRef changedBy, boolean voided, Instant createdAt) {
        public static ScoreHistoryView of(CreditScoreHistory h) {
            return new ScoreHistoryView(h.getId(), h.getPreviousScore(), h.getNewScore(), h.getAdjustment(), h.getEventType(),
                    h.getReason(), h.getLoanId(), h.getReservationId(), h.getFineId(), h.getComponentsSnapshot(),
                    PersonRef.of(h.getChangedBy()), h.isVoided(), h.getCreatedAt());
        }
    }

    public record AuditView(Long id, PersonRef actor, String action, String entityType, Long entityId, String details, Instant createdAt) {
        public static AuditView of(AuditLog a) {
            return new AuditView(a.getId(), PersonRef.of(a.getActor()), a.getAction(), a.getEntityType(), a.getEntityId(), a.getDetails(),
                    a.getCreatedAt());
        }
    }
}
