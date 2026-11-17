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
import java.util.EnumSet;
import java.util.List;

/**
 * Disputes are tracked separately from fine status, so "being reviewed" never masquerades as "paid".
 * Whether a disputed fine keeps blocking borrowing is the default policy's disputedFinesBlock setting.
 */
@Service
public class DisputeService {

    /** For upheld fine disputes: null correctedAmount means waive the balance. */
    public record Resolution(boolean upheld, String resolution, BigDecimal correctedAmount, boolean waiveRelatedCharges) {}

    private final DisputeRepository disputes;
    private final FineRepository fines;
    private final ConditionReportRepository reports;
    private final CreditScoreHistoryRepository scoreHistory;
    private final FineService fineService;
    private final CreditScoreService scores;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public DisputeService(DisputeRepository disputes, FineRepository fines, ConditionReportRepository reports,
                          CreditScoreHistoryRepository scoreHistory, FineService fineService, CreditScoreService scores,
                          NotificationService notifications, AuditService audit, ApplicationEventPublisher events, Clock clock) {
        this.disputes = disputes;
        this.fines = fines;
        this.reports = reports;
        this.scoreHistory = scoreHistory;
        this.fineService = fineService;
        this.scores = scores;
        this.notifications = notifications;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public Dispute open(User student, DisputeType type, Long subjectId, String description) {
        if (description == null || description.isBlank()) throw ApiException.badRequest("DESCRIPTION_REQUIRED", "Explain what is wrong");
        Long owner = switch (type) {
            case FINE -> fines.findById(subjectId).map(f -> f.getStudent().getId()).orElseThrow(() -> ApiException.notFound("Fine"));
            case CONDITION_REPORT -> reports.findById(subjectId).map(r -> r.getLoan().getStudent().getId()).orElseThrow(() -> ApiException.notFound("Condition report"));
            case SCORE -> scoreHistory.findById(subjectId).map(h -> h.getStudent().getId()).orElseThrow(() -> ApiException.notFound("Score change"));
        };
        if (!owner.equals(student.getId())) throw ApiException.forbidden("You can only dispute your own records");
        if (disputes.existsByTypeAndSubjectIdAndStatusIn(type, subjectId, EnumSet.of(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW))) {
            throw ApiException.conflict("ALREADY_DISPUTED", "There is already an open dispute for this record");
        }
        Dispute d = new Dispute();
        d.setStudent(student);
        d.setType(type);
        d.setSubjectId(subjectId);
        d.setDescription(description);
        d.setCreatedAt(clock.instant());
        d = disputes.save(d);
        audit.record(student, "DISPUTE_OPENED", "Dispute", d.getId(), type + " #" + subjectId);
        return d;
    }

    @Transactional
    public Dispute startReview(User admin, Long id) {
        Dispute d = get(id);
        if (d.getStatus() != DisputeStatus.OPEN) throw ApiException.conflict("NOT_OPEN", "Dispute is " + d.getStatus());
        d.setStatus(DisputeStatus.UNDER_REVIEW);
        notifications.notify(d.getStudent(), NotificationType.DISPUTE_UPDATE, "Dispute under review",
                "An administrator is reviewing your dispute #" + d.getId() + ".");
        return d;
    }

    @Transactional
    public Dispute resolve(User admin, Long id, Resolution res) {
        Dispute d = get(id);
        if (!d.getStatus().isOpen()) throw ApiException.conflict("ALREADY_RESOLVED", "Dispute is already " + d.getStatus());
        if (res.resolution() == null || res.resolution().isBlank()) throw ApiException.badRequest("REASON_REQUIRED", "Explain the outcome");
        String why = "Dispute #" + d.getId() + ": " + res.resolution();
        if (res.upheld()) {
            switch (d.getType()) {
                case FINE -> {
                    Fine f = fines.findById(d.getSubjectId()).orElseThrow();
                    if (res.correctedAmount() == null) fineService.waive(f, why, admin);
                    else fineService.correct(f, res.correctedAmount(), why, admin);
                }
                case CONDITION_REPORT -> {
                    ConditionReport r = reports.findById(d.getSubjectId()).orElseThrow();
                    r.setVoided(true);
                    if (res.waiveRelatedCharges()) {
                        for (Fine f : fines.findByLoanId(r.getLoan().getId())) {
                            if (f.getType() != FineType.LATE_RETURN && f.getStatus().isPayable()) fineService.waive(f, why, admin);
                        }
                    }
                    events.publishEvent(new StudentActivityEvent(d.getStudent().getId(), ScoreEventType.DISPUTE_CORRECTION,
                            "Condition report corrected: " + res.resolution(), r.getLoan().getId(), null, null));
                }
                case SCORE -> {
                    CreditScoreHistory h = scoreHistory.findById(d.getSubjectId()).orElseThrow();
                    if (h.getEventType() == ScoreEventType.MANUAL_ADJUSTMENT) {
                        scores.voidAdjustment(h, admin, res.resolution());
                    } else {
                        scores.recalculate(d.getStudent(), ScoreEventType.DISPUTE_CORRECTION, why, h.getLoanId(), h.getReservationId(), h.getFineId(), admin);
                    }
                }
            }
        }
        d.setStatus(res.upheld() ? DisputeStatus.UPHELD : DisputeStatus.DISMISSED);
        d.setResolution(res.resolution());
        d.setResolvedBy(admin);
        d.setResolvedAt(clock.instant());
        audit.record(admin, res.upheld() ? "DISPUTE_UPHELD" : "DISPUTE_DISMISSED", "Dispute", d.getId(), res.resolution());
        notifications.notify(d.getStudent(), NotificationType.DISPUTE_UPDATE,
                res.upheld() ? "Dispute upheld" : "Dispute dismissed", "Dispute #" + d.getId() + ": " + res.resolution());
        return d;
    }

    public Dispute get(Long id) {
        return disputes.findById(id).orElseThrow(() -> ApiException.notFound("Dispute"));
    }

    public List<Dispute> forStudent(User s) {
        return disputes.findByStudentIdOrderByCreatedAtDesc(s.getId());
    }

    public List<Dispute> all() {
        return disputes.findAllByOrderByCreatedAtDesc();
    }
}
