package edu.campus.equipment.service.payment;

import edu.campus.equipment.domain.Payment;

import java.math.BigDecimal;

/** Adapter boundary for an online payment provider. */
public interface PaymentGateway {

    record Checkout(String reference, String redirectUrl) {}

    /** Outcome reported by the provider for a reference. */
    record Notification(String reference, boolean succeeded, BigDecimal amount, String failureReason) {}

    String name();

    Checkout createCheckout(Payment payment, String currency);

    /** Verifies a provider callback is authentic; implementations check signatures or shared secrets. */
    boolean verifyCallback(String signatureHeader, String rawBody);
}
