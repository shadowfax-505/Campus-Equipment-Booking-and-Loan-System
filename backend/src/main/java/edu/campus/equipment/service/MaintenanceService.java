package edu.campus.equipment.service;

import edu.campus.equipment.common.ApiException;
import edu.campus.equipment.domain.*;
import edu.campus.equipment.repo.*;
import edu.campus.equipment.service.notify.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

@Service
public class MaintenanceService {
    private final MaintenanceRecordRepository records;
    private final EquipmentRepository equipmentRepo;
    private final LoanRepository loans;
    private final ReservationRepository reservations;
    private final NotificationService notifications;
    private final AuditService audit;
    private final StaffScope scope;
    private final Clock clock;

    public MaintenanceService(MaintenanceRecordRepository records, EquipmentRepository equipmentRepo, LoanRepository loans,
                              ReservationRepository reservations, NotificationService notifications, AuditService audit,
                              StaffScope scope, Clock clock) {
        this.records = records;
        this.equipmentRepo = equipmentRepo;
        this.loans = loans;
        this.reservations = reservations;
        this.notifications = notifications;
        this.audit = audit;
        this.scope = scope;
        this.clock = clock;
    }

    /** Blocks the item from startAt (default now) until endAt (or until completed). Affected bookings are flagged, not cancelled. */
    @Transactional
    public MaintenanceRecord start(User staff, Long equipmentId, String reason, Instant startAt, Instant endAt) {
        Equipment e = equipmentRepo.lockById(equipmentId).orElseThrow(() -> ApiException.notFound("Equipment"));
        scope.require(staff, e);
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("REASON_REQUIRED", "Give a reason");
        Instant now = clock.instant();
        Instant start = startAt == null ? now : startAt;
        if (endAt != null && !endAt.isAfter(start)) throw ApiException.badRequest("INVALID_PERIOD", "End must be after start");
        if (e.getStatus() == OperationalStatus.LOST || e.getStatus() == OperationalStatus.RETIRED) {
            throw ApiException.conflict("NOT_OPERATIONAL", "The item is " + e.getStatus());
        }
        boolean startsNow = !start.isAfter(now);
        if (startsNow && !loans.findByEquipmentIdAndStatus(e.getId(), LoanStatus.ACTIVE).isEmpty()) {
            throw ApiException.conflict("ON_LOAN", "The item is on loan; schedule maintenance for after its return");
        }
        MaintenanceRecord m = new MaintenanceRecord();
        m.setEquipment(e);
        m.setReason(reason);
        m.setStartAt(start);
        m.setEndAt(endAt);
        m.setCreatedBy(staff);
        m.setCreatedAt(now);
        m = records.save(m);
        if (startsNow) e.setStatus(OperationalStatus.UNDER_MAINTENANCE);
        flagAffected(e, start, endAt == null ? Instant.MAX : endAt, "Item scheduled for maintenance: " + reason);
        audit.record(staff, "MAINTENANCE_STARTED", "Equipment", e.getId(), reason);
        return m;
    }

    @Transactional
    public MaintenanceRecord complete(User staff, Long recordId, String notes, EquipmentCondition condition) {
        MaintenanceRecord m = records.findById(recordId).orElseThrow(() -> ApiException.notFound("Maintenance record"));
        Equipment e = m.getEquipment();
        scope.require(staff, e);
        if (m.isCompleted()) throw ApiException.conflict("ALREADY_COMPLETED", "This maintenance is already completed");
        Instant now = clock.instant();
        m.setCompleted(true);
        m.setCompletedBy(staff);
        m.setResolutionNotes(notes);
        if (m.getEndAt() == null || m.getEndAt().isAfter(now)) m.setEndAt(now.isBefore(m.getStartAt()) ? m.getStartAt() : now);
        if (condition != null) e.setCondition(condition);
        boolean stillOpen = records.findByEquipmentIdAndCompletedFalse(e.getId()).stream()
                .anyMatch(o -> !o.getId().equals(m.getId()) && !o.getStartAt().isAfter(now));
        if (!stillOpen && e.getStatus() == OperationalStatus.UNDER_MAINTENANCE) e.setStatus(OperationalStatus.ACTIVE);
        audit.record(staff, "MAINTENANCE_COMPLETED", "Equipment", e.getId(), notes);
        return m;
    }

    /** Scheduled: planned maintenance whose start time arrived puts the item out of service. */
    @Transactional
    public int activateScheduled() {
        Instant now = clock.instant();
        int n = 0;
        for (MaintenanceRecord m : records.findByCompletedFalseOrderByStartAt()) {
            Equipment e = m.getEquipment();
            if (!m.getStartAt().isAfter(now) && e.getStatus() == OperationalStatus.ACTIVE
                    && loans.findByEquipmentIdAndStatus(e.getId(), LoanStatus.ACTIVE).isEmpty()) {
                e.setStatus(OperationalStatus.UNDER_MAINTENANCE);
                n++;
            }
        }
        return n;
    }

    @Transactional
    public Equipment changeStatus(User admin, Long equipmentId, OperationalStatus status, String reason) {
        Equipment e = equipmentRepo.lockById(equipmentId).orElseThrow(() -> ApiException.notFound("Equipment"));
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("REASON_REQUIRED", "Give a reason");
        if (status != OperationalStatus.ACTIVE && !loans.findByEquipmentIdAndStatus(e.getId(), LoanStatus.ACTIVE).isEmpty()
                && status != OperationalStatus.LOST) {
            throw ApiException.conflict("ON_LOAN", "The item is on loan");
        }
        OperationalStatus old = e.getStatus();
        e.setStatus(status);
        if (status == OperationalStatus.RETIRED || status == OperationalStatus.LOST) {
            flagAffected(e, clock.instant(), Instant.MAX, "Item is " + status.name().toLowerCase() + ": " + reason);
        }
        audit.record(admin, "EQUIPMENT_STATUS_CHANGED", "Equipment", e.getId(), old + " -> " + status + ": " + reason);
        return e;
    }

    private void flagAffected(Equipment e, Instant from, Instant to, String why) {
        for (Reservation r : reservations.findOverlapping(e.getId(), EnumSet.of(ReservationStatus.APPROVED), from, to)) {
            r.setFlaggedForReview(true);
            r.setFlagReason(why);
            notifications.notify(r.getStudent(), NotificationType.BOOKING_FLAGGED, "Your booking may be affected",
                    e.getName() + " may not be available for your booking starting " + notifications.at(r.getStartAt()) + ". Staff will contact you.");
        }
    }

    public List<MaintenanceRecord> open(User staff) {
        return records.findByCompletedFalseOrderByStartAt().stream().filter(m -> scope.covers(staff, m.getEquipment())).toList();
    }

    public List<MaintenanceRecord> forEquipment(Long equipmentId) {
        return records.findByEquipmentIdOrderByStartAtDesc(equipmentId);
    }
}
