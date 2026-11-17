package edu.campus.equipment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String timezone,
        Jwt jwt,
        Mail mail,
        Payments payments,
        Jobs jobs,
        boolean seedDemoData,
        String corsOrigins) {

    public record Jwt(String secret, Duration accessTtl, Duration refreshTtl, String issuer) {}

    /** provider: "log" (write emails to the log) or "smtp" (use spring.mail.*). */
    public record Mail(String provider, String from) {}

    /** provider: "mock" (built-in simulator) — other providers plug in through PaymentGateway. */
    public record Payments(String provider, String webhookSecret, String currency) {}

    public record Jobs(Duration reminderLead) {}
}
