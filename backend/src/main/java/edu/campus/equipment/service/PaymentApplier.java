package edu.campus.equipment.service;

import edu.campus.equipment.domain.*;
import edu.campus.equipment.repo.FineRepository;
import edu.campus.equipment.repo.PaymentAllocationRepository;
import edu.campus.equipment.service.notify.NotificationService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;

/** Applies a verified payment to fine balances: chosen fines first, then the oldest remaining ones. */
@Component
public class PaymentApplier {
    private final FineRepository fines;
    private final PaymentAllocationRepository allocations;
    private final FineService fineService;
    private final NotificationService notifications;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public PaymentApplier(FineRepository fines, PaymentAllocationRepository allocations, FineService fineService,
                          NotificationService notifications, ApplicationEventPublisher events, Clock clock) {
        this.fines = fines;
        this.allocations = allocations;
        this.fineService = fineService;
        this.notifications = notifications;
        this.events = events;
        this.clock = clock;
    }

    public void apply(Payment p) {
        if (p.getStatus() != PaymentStatus.SUCCEEDED) throw new IllegalStateException("Only verified payments are applied");
        if (!allocations.findByPaymentId(p.getId()).isEmpty()) return; // already applied
        List<Fine> payable = new ArrayList<>(fines.findPayable(p.getStudent().getId()));
        Set<Long> targets = new HashSet<>(p.getTargetFineIds());
        payable.sort(Comparator.comparing((Fine f) -> !targets.contains(f.getId())).thenComparing(Fine::getAssessedAt));
        BigDecimal remaining = p.getAmount();
        for (Fine f : payable) {
            if (remaining.signum() <= 0) break;
            BigDecimal portion = remaining.min(f.balance());
            if (portion.signum() <= 0) continue;
            f.setPaidAmount(f.getPaidAmount().add(portion));
            fineService.recomputeStatus(f);
            PaymentAllocation a = new PaymentAllocation();
            a.setPayment(p);
            a.setFine(f);
            a.setAmount(portion);
            a.setCreatedAt(clock.instant());
            allocations.save(a);
            remaining = remaining.subtract(portion);
        }
        if (remaining.signum() > 0) {
            p.setNotes((p.getNotes() == null ? "" : p.getNotes() + " | ") + "Unapplied credit " + remaining + " (refund or credit required)");
        }
        BigDecimal left = fines.findPayable(p.getStudent().getId()).stream().map(Fine::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        notifications.notify(p.getStudent(), NotificationType.PAYMENT_CONFIRMED, "Payment received",
                "We received " + p.getAmount() + " (ref " + p.getReference() + "). "
                        + (left.signum() == 0 ? "All fines are cleared and you can request equipment again."
                                              : "Remaining balance: " + left + "."));
        fineService.clearFlagsIfSettled(p.getStudent());
        events.publishEvent(StudentActivityEvent.of(p.getStudent().getId(), ScoreEventType.PAYMENT_CONFIRMED,
                "Payment of " + p.getAmount() + " confirmed"));
    }
}
