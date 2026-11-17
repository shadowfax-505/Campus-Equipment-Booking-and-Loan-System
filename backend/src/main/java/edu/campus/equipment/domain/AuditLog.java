package edu.campus.equipment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "audit_logs", indexes = {@Index(columnList = "createdAt"), @Index(columnList = "entityType,entityId")})
@Getter @Setter @NoArgsConstructor
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private User actor;

    @Column(nullable = false, length = 60)
    private String action;

    @Column(nullable = false, length = 40)
    private String entityType;

    private Long entityId;

    @Column(length = 4000)
    private String details;

    @Column(nullable = false)
    private Instant createdAt;
}
