package edu.campus.equipment.service.payment;

import edu.campus.equipment.config.AppProperties;
import edu.campus.equipment.domain.Payment;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Built-in provider simulator for development and demos. It hands back a reference and expects the provider
 * callback on /api/payments/webhook/mock carrying the shared secret in X-Webhook-Secret. A real provider
 * (Stripe, SSLCommerz, bKash...) is added as another PaymentGateway implementation.
 */
@Component
public class MockPaymentGateway implements PaymentGateway {
    private final String secret;

    public MockPaymentGateway(AppProperties props) {
        this.secret = props.payments().webhookSecret();
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public Checkout createCheckout(Payment payment, String currency) {
        String ref = "MOCK-" + UUID.randomUUID();
        return new Checkout(ref, "/payments/mock-checkout?reference=" + ref);
    }

    @Override
    public boolean verifyCallback(String signatureHeader, String rawBody) {
        if (signatureHeader == null || secret == null) return false;
        return MessageDigest.isEqual(signatureHeader.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8));
    }
}
