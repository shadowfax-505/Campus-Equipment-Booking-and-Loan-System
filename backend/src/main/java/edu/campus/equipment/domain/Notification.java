package edu.campus.equipment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "notifications", indexes = @Index(columnList = "user_id,readAt"))
@Getter @Setter @NoArgsConstructor
public class Notification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private User user;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private NotificationType type;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 2000)
    private String message;

    private Instant readAt;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private DeliveryStatus emailStatus = DeliveryStatus.PENDING;

    @Column(nullable = false)
    private int emailAttempts;

    @Column(length = 500)
    private String emailError;

    @Column(nullable = false)
    private Instant createdAt;
}
