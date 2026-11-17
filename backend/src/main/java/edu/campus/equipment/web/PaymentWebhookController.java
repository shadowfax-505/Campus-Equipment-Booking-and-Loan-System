package edu.campus.equipment.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.campus.equipment.common.ApiException;
import edu.campus.equipment.service.payment.PaymentGateway;
import edu.campus.equipment.service.payment.PaymentService;
import edu.campus.equipment.web.dto.Requests;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Server-to-server callback from a payment provider. Authenticated by the provider adapter, not by user JWT. */
@RestController
@RequestMapping("/api/payments/webhook")
@Tag(name = "Payment provider callbacks")
public class PaymentWebhookController {
    private final PaymentService payments;
    private final ObjectMapper json;

    public PaymentWebhookController(PaymentService payments, ObjectMapper json) {
        this.payments = payments;
        this.json = json;
    }

    @PostMapping("/{provider}")
    public Map<String, Object> callback(@PathVariable String provider, @RequestHeader(value = "X-Webhook-Secret", required = false) String signature,
                                        @RequestBody String body) throws Exception {
        PaymentGateway g = payments.gateway(provider);
        if (!g.verifyCallback(signature, body)) throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Callback signature rejected");
        Requests.ProviderCallback cb = json.readValue(body, Requests.ProviderCallback.class);
        var p = payments.handleProviderNotification(new PaymentGateway.Notification(cb.reference(),
                "SUCCEEDED".equalsIgnoreCase(cb.status()), cb.amount(), cb.failureReason()));
        return Map.of("reference", p.getReference(), "status", p.getStatus().name());
    }
}
