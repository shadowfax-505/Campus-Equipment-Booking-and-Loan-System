package edu.campus.equipment.service.payment;

import edu.campus.equipment.common.ApiException;
import edu.campus.equipment.config.AppProperties;
import edu.campus.equipment.domain.*;
import edu.campus.equipment.repo.FineRepository;
import edu.campus.equipment.repo.PaymentAllocationRepository;
import edu.campus.equipment.repo.PaymentRepository;
import edu.campus.equipment.service.AuditService;
import edu.campus.equipment.service.FineService;
import edu.campus.equipment.service.PaymentApplier;
import edu.campus.equipment.service.notify.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.*;

/**
 * Payment workflow. A fine is only reduced by a payment in status SUCCEEDED, which is reached either by an
 * authenticated provider callback or by authorised staff verifying an external receipt. Screenshots and claims
 * alone never clear a fine. Repeated provider notifications are idempotent (row lock + terminal-state check).
 */
@Service
public class PaymentService {
    private final PaymentRepository payments;
    private final PaymentAllocationRepository allocations;
    private final FineRepository fines;
    private final Map<String, PaymentGateway> gateways = new HashMap<>();
    private final PaymentApplier applier;
    private final NotificationService notifications;
    private final AuditService audit;
    private final AppProperties props;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, PaymentAllocationRepository allocations, FineRepository fines,
                          List<PaymentGateway> gateways, PaymentApplier applier, NotificationService notifications,
                          AuditService audit, AppProperties props, Clock clock) {
        this.payments = payments;
        this.allocations = allocations;
        this.fines = fines;
        gateways.forEach(g -> this.gateways.put(g.name(), g));
        this.applier = applier;
        this.notifications = notifications;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    public record CheckoutResult(Payment payment, String redirectUrl) {}

    public PaymentGateway gateway(String name) {
        PaymentGateway g = gateways.get(name);
        if (g == null) throw ApiException.notFound("Payment provider " + name);
        return g;
    }

    @Transactional
    public CheckoutResult startOnline(User student, BigDecimal amount, List<Long> fineIds) {
        BigDecimal amt = validateAmount(student, amount, fineIds);
        PaymentGateway g = gateway(props.payments().provider());
        Payment p = basePayment(student, amt, PaymentChannel.ONLINE, fineIds);
        p.setStatus(PaymentStatus.PENDING);
        p.setProvider(g.name());
        p.setReference("TMP-" + UUID.randomUUID());
        p = payments.save(p);
        PaymentGateway.Checkout c = g.createCheckout(p, props.payments().currency());
        p.setReference(c.reference());
        return new CheckoutResult(p, c.redirectUrl());
    }

    @Transactional
    public Payment submitExternal(User student, BigDecimal amount, List<Long> fineIds, String receiptReference,
                                  String notes, String evidenceUrl) {
        BigDecimal amt = validateAmount(student, amount, fineIds);
        if (receiptReference == null || receiptReference.isBlank()) {
            throw ApiException.badRequest("REFERENCE_REQUIRED", "Enter the receipt or transaction number");
        }
        String ref = "EXT-" + receiptReference.trim();
        if (payments.findByReference(ref).isPresent()) {
            throw ApiException.conflict("DUPLICATE_REFERENCE", "This receipt number has already been submitted");
        }
        Payment p = basePayment(student, amt, PaymentChannel.EXTERNAL, fineIds);
        p.setStatus(PaymentStatus.PENDING_VERIFICATION);
        p.setReference(ref);
        p.setNotes(notes);
        p.setEvidenceUrl(evidenceUrl);
        p = payments.save(p);
        audit.record(student, "PAYMENT_SUBMITTED", "Payment", p.getId(), "external " + amt + " ref " + ref);
        return p;
    }

    /** Provider callback. Safe to receive any number of times for the same reference. */
    @Transactional
    public Payment handleProviderNotification(PaymentGateway.Notification n) {
        Payment p = payments.lockByReference(n.reference()).orElseThrow(() -> ApiException.notFound("Payment " + n.reference()));
        if (p.getStatus() == PaymentStatus.SUCCEEDED || p.getStatus() == PaymentStatus.FAILED) {
            return p; // duplicate delivery: already processed
        }
        if (p.getChannel() != PaymentChannel.ONLINE) throw ApiException.conflict("WRONG_CHANNEL", "Not an online payment");
        if (n.succeeded()) {
            if (n.amount() != null && n.amount().compareTo(p.getAmount()) != 0) {
                p.setStatus(PaymentStatus.FAILED);
                p.setFailureReason("Amount mismatch: provider reported " + n.amount());
                p.setCompletedAt(clock.instant());
                audit.record(null, "PAYMENT_AMOUNT_MISMATCH", "Payment", p.getId(), p.getFailureReason());
                return p;
            }
            p.setStatus(PaymentStatus.SUCCEEDED);
            p.setCompletedAt(clock.instant());
            applier.apply(p);
            audit.record(null, "PAYMENT_CONFIRMED", "Payment", p.getId(), "provider " + p.getProvider() + " " + p.getAmount());
        } else {
            p.setStatus(PaymentStatus.FAILED);
            p.setFailureReason(n.failureReason() == null ? "Declined by provider" : n.failureReason());
            p.setCompletedAt(clock.instant());
            notifications.notify(p.getStudent(), NotificationType.PAYMENT_FAILED, "Payment failed",
                    "Your payment of " + p.getAmount() + " did not go through: " + p.getFailureReason() + ". Your fines are unchanged.");
        }
        return p;
    }

    @Transactional
    public Payment verifyExternal(Long paymentId, boolean approve, String note, User staff) {
        if (!staff.hasPermission(StaffPermission.VERIFY_PAYMENTS)) throw ApiException.forbidden("Missing permission VERIFY_PAYMENTS");
        Payment p = payments.findById(paymentId).orElseThrow(() -> ApiException.notFound("Payment"));
        if (p.getStatus() != PaymentStatus.PENDING_VERIFICATION) {
            throw ApiException.conflict("ALREADY_PROCESSED", "This payment is already " + p.getStatus());
        }
        p.setVerifiedBy(staff);
        p.setCompletedAt(clock.instant());
        if (approve) {
            p.setStatus(PaymentStatus.SUCCEEDED);
            applier.apply(p);
        } else {
            if (note == null || note.isBlank()) throw ApiException.badRequest("REASON_REQUIRED", "Give a reason for rejecting the payment");
            p.setStatus(PaymentStatus.REJECTED);
            p.setFailureReason(note);
            notifications.notify(p.getStudent(), NotificationType.PAYMENT_FAILED, "Payment not verified",
                    "Your submitted payment " + p.getReference() + " could not be verified: " + note);
        }
        audit.record(staff, approve ? "PAYMENT_VERIFIED" : "PAYMENT_REJECTED", "Payment", p.getId(),
                p.getAmount() + " ref " + p.getReference() + (note == null ? "" : " - " + note));
        return p;
    }

    public List<Payment> forStudent(User student) {
        return payments.findByStudentIdOrderBySubmittedAtDesc(student.getId());
    }

    public List<PaymentAllocation> allocationsFor(Payment p) {
        return allocations.findByPaymentId(p.getId());
    }

    private Payment basePayment(User student, BigDecimal amt, PaymentChannel ch, List<Long> fineIds) {
        Payment p = new Payment();
        p.setStudent(student);
        p.setAmount(amt);
        p.setChannel(ch);
        p.setTargetFineIds(fineIds == null ? new ArrayList<>() : new ArrayList<>(fineIds));
        p.setSubmittedAt(clock.instant());
        return p;
    }

    private BigDecimal validateAmount(User student, BigDecimal amount, List<Long> fineIds) {
        if (amount == null || amount.signum() <= 0) throw ApiException.badRequest("INVALID_AMOUNT", "Amount must be positive");
        BigDecimal amt = amount.setScale(2, RoundingMode.HALF_UP);
        List<Fine> payable = fines.findPayable(student.getId());
        if (fineIds != null && !fineIds.isEmpty()) {
            Set<Long> ids = new HashSet<>(fineIds);
            payable = payable.stream().filter(f -> ids.contains(f.getId())).toList();
            if (payable.size() != ids.size()) throw ApiException.badRequest("INVALID_FINES", "Some selected fines are not yours or not payable");
        }
        BigDecimal due = payable.stream().map(Fine::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (due.signum() == 0) throw ApiException.unprocessable("NOTHING_DUE", "There is nothing to pay");
        if (amt.compareTo(due) > 0) throw ApiException.unprocessable("OVERPAYMENT", "Amount exceeds the outstanding balance of " + due);
        return amt;
    }
}
