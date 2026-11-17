package edu.campus.equipment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.campus.equipment.common.ApiException;
import edu.campus.equipment.domain.*;
import edu.campus.equipment.repo.*;
import edu.campus.equipment.service.notify.NotificationService;
import edu.campus.equipment.service.policy.AllocationStrategy;
import edu.campus.equipment.service.policy.AllocationStrategy.Candidate;
import edu.campus.equipment.service.policy.AllocationStrategy.Ranked;
import edu.campus.equipment.service.policy.BorrowingLimitStrategy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Reservation lifecycle and credit-based allocation.
 *
 * Competing requests for one item are those still open (pending or waitlisted) whose periods overlap.
 * Each request carries a decision window; a group can be decided once the window of its earliest
 * request has closed, so requests made at about the same time are compared rather than first-come-first-served.
 * Approval locks the equipment row, so two staff approving overlapping requests at once cannot both succeed.
 */
@Service
public class ReservationService {

    public record RankedGroup(Equipment equipment, Instant windowClosesAt, boolean decidable, List<RankedEntry> entries) {}

    public record RankedEntry(Reservation reservation, double score, Integer rank, boolean eligible, List<String> reasons,
                              boolean available) {}

    private final ReservationRepository reservations;
    private final EquipmentRepository equipmentRepo;
    private final AllocationDecisionRepository decisions;
    private final AvailabilityService availability;
    private final EligibilityService eligibility;
    private final CreditScoreService scores;
    private final AllocationStrategy allocation;
    private final BorrowingLimitStrategy limits;
    private final NotificationService notifications;
    private final AuditService audit;
    private final StaffScope scope;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;
    private final Clock clock;

    public ReservationService(ReservationRepository reservations, EquipmentRepository equipmentRepo,
                              AllocationDecisionRepository decisions, AvailabilityService availability,
                              EligibilityService eligibility, CreditScoreService scores, AllocationStrategy allocation,
                              BorrowingLimitStrategy limits, NotificationService notifications, AuditService audit,
                              StaffScope scope, ApplicationEventPublisher events, ObjectMapper json, Clock clock) {
        this.reservations = reservations;
        this.equipmentRepo = equipmentRepo;
        this.decisions = decisions;
        this.availability = availability;
        this.eligibility = eligibility;
        this.scores = scores;
        this.allocation = allocation;
        this.limits = limits;
        this.notifications = notifications;
        this.audit = audit;
        this.scope = scope;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    // ---- student actions ---------------------------------------------------------------------

    @Transactional
    public Reservation request(User student, Long equipmentId, Instant start, Instant end, String purpose) {
        if (student.getRole() != Role.STUDENT) throw ApiException.forbidden("Only students can request equipment");
        Instant now = clock.instant();
        Equipment e = equipmentRepo.findById(equipmentId).orElseThrow(() -> ApiException.notFound("Equipment"));
        if (e.getStatus() == OperationalStatus.LOST || e.getStatus() == OperationalStatus.RETIRED) {
            throw ApiException.unprocessable("NOT_BORROWABLE", "This item is no longer in service");
        }
        limits.validatePeriod(e, start, end, now);

        EligibilityService.Result el = eligibility.check(student, EligibilityService.Stage.REQUEST, null);
        if (!el.eligible()) {
            throw ApiException.unprocessable(el.hasBlockingFines() ? "UNPAID_FINES" : "NOT_ELIGIBLE", String.join("; ", el.reasons()));
        }
        if (!availability.isAvailable(e, start, end, null, null)) {
            throw ApiException.conflict("NOT_AVAILABLE", "The item is already booked, on loan or under maintenance during that period");
        }
        boolean duplicate = reservations.findOverlapping(e.getId(),
                        EnumSet.of(ReservationStatus.PENDING, ReservationStatus.WAITLISTED, ReservationStatus.APPROVED), start, end)
                .stream().anyMatch(r -> r.getStudent().getId().equals(student.getId()));
        if (duplicate) throw ApiException.conflict("DUPLICATE_REQUEST", "You already have a request for this item in that period");

        Reservation r = new Reservation();
        r.setStudent(student);
        r.setEquipment(e);
        r.setStartAt(start);
        r.setEndAt(end);
        r.setPurpose(purpose);
        r.setCreatedAt(now);
        Instant windowEnd = now.plus(Duration.ofMinutes(e.getPolicy().getDecisionWindowMinutes()));
        r.setDecisionWindowEndsAt(windowEnd.isAfter(start) ? (start.isAfter(now) ? start : now) : windowEnd);
        r = reservations.save(r);
        scores.profile(student); // make sure a score exists for ranking

        notifications.notify(student, NotificationType.REQUEST_SUBMITTED, "Request submitted",
                "Your request for " + e.getName() + " (" + notifications.at(start) + " to " + notifications.at(end)
                        + ") is pending. Competing requests are compared until " + notifications.at(r.getDecisionWindowEndsAt()) + ".");
        notifications.notifyStaff(e, NotificationType.REQUEST_SUBMITTED, "New equipment request",
                student.getFullName() + " requested " + e.getName() + " (" + e.getAssetTag() + ").");
        return r;
    }

    @Transactional
    public Reservation cancel(User actor, Long reservationId, String reason) {
        Reservation r = get(reservationId);
        if (actor.getRole() == Role.STUDENT && !r.getStudent().getId().equals(actor.getId())) {
            throw ApiException.forbidden("You can only cancel your own reservations");
        }
        if (actor.getRole() != Role.STUDENT) scope.require(actor, r.getEquipment());
        Instant now = clock.instant();
        boolean wasApproved = r.getStatus() == ReservationStatus.APPROVED;
        r.moveTo(ReservationStatus.CANCELLED);
        r.setCancelledAt(now);
        r.setDecisionReason(reason);
        if (wasApproved && actor.getRole() == Role.STUDENT) {
            Instant cutoff = r.getStartAt().minus(Duration.ofHours(r.getEquipment().getPolicy().getCancellationCutoffHours()));
            r.setLateCancellation(now.isAfter(cutoff));
        }
        notifications.notify(r.getStudent(), NotificationType.BOOKING_CANCELLED, "Booking cancelled",
                "Your booking of " + r.getEquipment().getName() + " starting " + notifications.at(r.getStartAt()) + " was cancelled"
                        + (r.isLateCancellation() ? " after the cancellation cutoff, which counts toward your reliability score." : "."));
        if (actor.getRole() != Role.STUDENT) {
            audit.record(actor, "RESERVATION_CANCELLED_BY_STAFF", "Reservation", r.getId(), reason);
        }
        if (wasApproved) {
            promoteWaitlist(r);
            events.publishEvent(new StudentActivityEvent(r.getStudent().getId(), ScoreEventType.RESERVATION_CANCELLED,
                    r.isLateCancellation() ? "Late cancellation" : "Cancellation within policy", null, r.getId(), null));
        }
        return r;
    }

    // ---- staff decisions ---------------------------------------------------------------------

    /** Ranks every open request that competes with {@code target} (including itself). */
    List<RankedEntry> rankCompetitors(Reservation target) {
        List<Reservation> group = reservations.findOverlapping(target.getEquipment().getId(),
                EnumSet.of(ReservationStatus.PENDING, ReservationStatus.WAITLISTED), target.getStartAt(), target.getEndAt());
        if (group.stream().noneMatch(r -> r.getId().equals(target.getId()))) {
            group = new ArrayList<>(group);
            group.add(target);
        }
        return rank(group);
    }

    private List<RankedEntry> rank(List<Reservation> group) {
        Map<Long, Reservation> byId = new HashMap<>();
        Map<Long, EligibilityService.Result> elig = new HashMap<>();
        Map<Long, Boolean> avail = new HashMap<>();
        List<Candidate> candidates = new ArrayList<>();
        for (Reservation r : group) {
            byId.put(r.getId(), r);
            EligibilityService.Result el = eligibility.check(r.getStudent(), EligibilityService.Stage.APPROVAL, r.getId());
            boolean free = availability.isAvailable(r.getEquipment(), r.getStartAt(), r.getEndAt(), r.getId(), null);
            elig.put(r.getId(), el);
            avail.put(r.getId(), free);
            List<String> why = new ArrayList<>(el.reasons());
            if (!free) why.add("Item is no longer free for this period");
            candidates.add(new Candidate(r.getId(), r.getStudent().getId(), scores.currentScore(r.getStudent()),
                    r.getCreatedAt(), why.isEmpty(), why.isEmpty() ? null : String.join("; ", why)));
        }
        List<RankedEntry> out = new ArrayList<>();
        for (Ranked rk : allocation.rank(candidates)) {
            Reservation r = byId.get(rk.candidate().reservationId());
            List<String> reasons = new ArrayList<>(elig.get(r.getId()).reasons());
            if (!avail.get(r.getId())) reasons.add("Item is no longer free for this period");
            out.add(new RankedEntry(r, rk.candidate().score(), rk.rank(), rk.candidate().eligible(), reasons, avail.get(r.getId())));
        }
        return out;
    }

    @Transactional
    public Reservation approve(User staff, Long reservationId, boolean override, String reason) {
        Reservation target = get(reservationId);
        scope.require(staff, target.getEquipment());
        equipmentRepo.lockById(target.getEquipment().getId()); // serialise concurrent decisions for this item
        entityManager.refresh(target); // re-read after acquiring the lock so a concurrent decision is visible
        if (!target.getStatus().isOpenRequest()) {
            throw new InvalidStateTransitionException("Reservation", target.getStatus().name(), ReservationStatus.APPROVED.name());
        }
        Instant now = clock.instant();

        if (!availability.isAvailable(target.getEquipment(), target.getStartAt(), target.getEndAt(), target.getId(), null)) {
            throw ApiException.conflict("NOT_AVAILABLE", "The item is no longer available for this period");
        }
        EligibilityService.Result el = eligibility.check(target.getStudent(), EligibilityService.Stage.APPROVAL, target.getId());
        if (!el.eligible()) {
            throw ApiException.unprocessable(el.hasBlockingFines() ? "UNPAID_FINES" : "NOT_ELIGIBLE",
                    "Student is not eligible: " + String.join("; ", el.reasons()));
        }

        List<RankedEntry> ranking = rankCompetitors(target);
        Instant windowCloses = ranking.stream().map(e -> e.reservation().getDecisionWindowEndsAt()).min(Comparator.naturalOrder()).orElse(now);
        boolean windowOpen = now.isBefore(windowCloses);
        final Long targetId = target.getId();
        RankedEntry mine = ranking.stream().filter(e -> e.reservation().getId().equals(targetId)).findFirst().orElseThrow();
        boolean notTop = mine.rank() == null || mine.rank() != 1;

        boolean needsOverride = windowOpen || notTop;
        if (needsOverride) {
            if (!override) {
                throw ApiException.conflict(windowOpen ? "DECISION_WINDOW_OPEN" : "NOT_TOP_RANKED", windowOpen
                        ? "Competing requests are still being collected until " + notifications.at(windowCloses) + ". Use an override to decide early."
                        : "Another eligible request ranks higher. Approving this one requires an override with a reason.");
            }
            if (!staff.hasPermission(StaffPermission.OVERRIDE_ALLOCATION)) throw ApiException.forbidden("Missing permission OVERRIDE_ALLOCATION");
            if (reason == null || reason.isBlank()) throw ApiException.badRequest("REASON_REQUIRED", "Overrides require a recorded reason");
        }

        target.moveTo(ReservationStatus.APPROVED);
        target.setDecidedAt(now);
        target.setDecidedBy(staff);
        target.setDecisionReason(reason);
        Instant deadline = target.getStartAt().plus(Duration.ofHours(target.getEquipment().getPolicy().getCollectionWindowHours()));
        if (deadline.isAfter(target.getEndAt())) deadline = target.getEndAt();
        target.setCollectionDeadline(deadline);

        AllocationDecision d = new AllocationDecision();
        d.setEquipment(target.getEquipment());
        d.setSelectedReservation(target);
        d.setCandidatesJson(toJson(ranking));
        d.setCandidateCount(ranking.size());
        d.setOverride(needsOverride);
        d.setReason(needsOverride ? reason : (ranking.size() > 1 ? "Top-ranked eligible request (" + allocation.describe() + ")" : "Only request for this period"));
        d.setDecidedBy(staff);
        d.setDecidedAt(now);
        d = decisions.save(d);
        target.setAllocationDecision(d);
        if (needsOverride) {
            audit.record(staff, "ALLOCATION_OVERRIDE", "Reservation", target.getId(),
                    (windowOpen ? "early decision; " : "") + (notTop ? "selected rank " + mine.rank() + "; " : "") + reason);
        }

        notifications.notify(target.getStudent(), NotificationType.REQUEST_APPROVED, "Request approved",
                "Your booking of " + target.getEquipment().getName() + " is confirmed. Collect it from "
                        + target.getEquipment().getLocation().getName() + " between " + notifications.at(target.getStartAt())
                        + " and " + notifications.at(deadline) + ", or the booking expires.");

        // Competitors that now overlap an approved booking are waitlisted or rejected with their own explanation.
        int total = (int) ranking.stream().filter(RankedEntry::eligible).count();
        for (RankedEntry other : ranking) {
            Reservation o = other.reservation();
            if (o.getId().equals(target.getId()) || !o.overlaps(target.getStartAt(), target.getEndAt())) continue;
            String own = String.format("The item was allocated to another request for an overlapping period. "
                            + "Your score at decision time was %.1f%s. Ranking: %s.", other.score(),
                    other.rank() == null ? " (not eligible: " + String.join("; ", other.reasons()) + ")" : ", rank " + other.rank() + " of " + total,
                    allocation.describe());
            o.setAllocationDecision(d);
            o.setDecisionReason(own);
            if (target.getEquipment().getPolicy().isWaitlistUnsuccessful()) {
                if (o.getStatus() != ReservationStatus.WAITLISTED) o.moveTo(ReservationStatus.WAITLISTED);
                notifications.notify(o.getStudent(), NotificationType.REQUEST_WAITLISTED, "You are on the waitlist",
                        own + " You will be reconsidered if the booking is cancelled or not collected.");
            } else {
                o.moveTo(ReservationStatus.REJECTED);
                o.setDecidedAt(now);
                o.setDecidedBy(staff);
                notifications.notify(o.getStudent(), NotificationType.REQUEST_REJECTED, "Request not successful", own);
            }
        }
        return target;
    }

    @Transactional
    public Reservation reject(User staff, Long reservationId, String reason) {
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("REASON_REQUIRED", "A rejection reason is required");
        Reservation r = get(reservationId);
        scope.require(staff, r.getEquipment());
        r.moveTo(ReservationStatus.REJECTED);
        r.setDecidedAt(clock.instant());
        r.setDecidedBy(staff);
        r.setDecisionReason(reason);
        notifications.notify(r.getStudent(), NotificationType.REQUEST_REJECTED, "Request rejected",
                "Your request for " + r.getEquipment().getName() + " was rejected: " + reason);
        return r;
    }

    @Transactional
    public Reservation clearFlag(User staff, Long reservationId, String note) {
        Reservation r = get(reservationId);
        scope.require(staff, r.getEquipment());
        r.setFlaggedForReview(false);
        audit.record(staff, "RESERVATION_FLAG_CLEARED", "Reservation", r.getId(), note);
        r.setFlagReason(null);
        return r;
    }

    /** Waitlisted requests overlapping a released period go back to pending, ready for an immediate decision. */
    void promoteWaitlist(Reservation released) {
        List<Reservation> waiting = reservations.findOverlapping(released.getEquipment().getId(),
                EnumSet.of(ReservationStatus.WAITLISTED), released.getStartAt(), released.getEndAt());
        Instant now = clock.instant();
        for (Reservation w : waiting) {
            if (!w.getStartAt().isAfter(now.minus(Duration.ofHours(w.getEquipment().getPolicy().getCollectionWindowHours())))) continue;
            w.moveTo(ReservationStatus.PENDING);
            w.setDecisionWindowEndsAt(now);
            notifications.notify(w.getStudent(), NotificationType.ALLOCATION_OUTCOME, "Back in consideration",
                    "A competing booking for " + w.getEquipment().getName() + " was released. Your request is being reconsidered.");
        }
        if (!waiting.isEmpty()) {
            notifications.notifyStaff(released.getEquipment(), NotificationType.ALLOCATION_OUTCOME, "Waitlisted requests need a decision",
                    released.getEquipment().getName() + " was released; " + waiting.size() + " waitlisted request(s) are pending again.");
        }
    }

    /** Scheduled: approved bookings not collected by the deadline expire; stale open requests expire too. */
    @Transactional
    public int expireOverdue() {
        Instant now = clock.instant();
        int n = 0;
        for (Reservation r : reservations.findByStatusAndCollectionDeadlineBefore(ReservationStatus.APPROVED, now)) {
            r.moveTo(ReservationStatus.EXPIRED);
            r.setDecisionReason("Not collected by " + notifications.at(r.getCollectionDeadline()));
            notifications.notify(r.getStudent(), NotificationType.BOOKING_EXPIRED, "Booking expired",
                    "You did not collect " + r.getEquipment().getName() + " by the deadline, so the booking expired. Missed collections lower your reliability score.");
            promoteWaitlist(r);
            events.publishEvent(new StudentActivityEvent(r.getStudent().getId(), ScoreEventType.RESERVATION_EXPIRED,
                    "Missed collection", null, r.getId(), null));
            n++;
        }
        for (Reservation r : reservations.findByStatusIn(EnumSet.of(ReservationStatus.PENDING, ReservationStatus.WAITLISTED))) {
            if (r.getStartAt().isBefore(now.minus(Duration.ofHours(r.getEquipment().getPolicy().getCollectionWindowHours())))
                    || r.getEndAt().isBefore(now)) {
                r.moveTo(ReservationStatus.EXPIRED);
                r.setDecisionReason("The requested period passed without a decision");
                notifications.notify(r.getStudent(), NotificationType.BOOKING_EXPIRED, "Request expired",
                        "Your request for " + r.getEquipment().getName() + " expired because its period passed without approval. This does not affect your score.");
                n++;
            }
        }
        return n;
    }

    // ---- queries -------------------------------------------------------------------------------

    public Reservation get(Long id) {
        return reservations.findById(id).orElseThrow(() -> ApiException.notFound("Reservation"));
    }

    public List<Reservation> forStudent(User student) {
        return reservations.findByStudentIdOrderByCreatedAtDesc(student.getId());
    }

    /** Groups open requests by item into sets of mutually overlapping periods, each ranked for staff review. */
    public List<RankedGroup> queue(User staff) {
        Instant now = clock.instant();
        Map<Long, List<Reservation>> byItem = new TreeMap<>();
        for (Reservation r : reservations.findByStatusIn(EnumSet.of(ReservationStatus.PENDING, ReservationStatus.WAITLISTED))) {
            if (scope.covers(staff, r.getEquipment())) byItem.computeIfAbsent(r.getEquipment().getId(), k -> new ArrayList<>()).add(r);
        }
        List<RankedGroup> out = new ArrayList<>();
        for (List<Reservation> rs : byItem.values()) {
            rs.sort(Comparator.comparing(Reservation::getStartAt));
            // connected components of the overlap graph (sweep by start time)
            List<Reservation> current = new ArrayList<>();
            Instant currentEnd = null;
            for (Reservation r : rs) {
                if (currentEnd != null && !r.getStartAt().isBefore(currentEnd)) {
                    out.add(group(current, now));
                    current = new ArrayList<>();
                    currentEnd = null;
                }
                current.add(r);
                currentEnd = currentEnd == null || r.getEndAt().isAfter(currentEnd) ? r.getEndAt() : currentEnd;
            }
            if (!current.isEmpty()) out.add(group(current, now));
        }
        out.sort(Comparator.comparing(RankedGroup::windowClosesAt));
        return out;
    }

    private RankedGroup group(List<Reservation> rs, Instant now) {
        Instant closes = rs.stream().map(Reservation::getDecisionWindowEndsAt).min(Comparator.naturalOrder()).orElse(now);
        return new RankedGroup(rs.get(0).getEquipment(), closes, !now.isBefore(closes), rank(rs));
    }

    public List<Reservation> byStatus(User staff, ReservationStatus status) {
        return reservations.findByStatusIn(EnumSet.of(status)).stream()
                .filter(r -> scope.covers(staff, r.getEquipment()))
                .sorted(Comparator.comparing(Reservation::getStartAt)).toList();
    }

    private String toJson(List<RankedEntry> ranking) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RankedEntry e : ranking) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("reservationId", e.reservation().getId());
            m.put("studentId", e.reservation().getStudent().getId());
            m.put("scoreSnapshot", e.score());
            m.put("submittedAt", e.reservation().getCreatedAt().toString());
            m.put("rank", e.rank());
            m.put("eligible", e.eligible());
            m.put("exclusionReason", e.reasons().isEmpty() ? null : String.join("; ", e.reasons()));
            rows.add(m);
        }
        try {
            return json.writeValueAsString(rows);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
