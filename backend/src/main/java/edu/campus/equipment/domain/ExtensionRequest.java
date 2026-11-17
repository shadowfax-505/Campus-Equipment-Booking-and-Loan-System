package edu.campus.equipment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "extension_requests")
@Getter @Setter @NoArgsConstructor
public class ExtensionRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Loan loan;

    @Column(nullable = false)
    private Instant currentDueAt;

    @Column(nullable = false)
    private Instant requestedDueAt;

    @Column(length = 500)
    private String studentReason;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private ExtensionStatus status = ExtensionStatus.PENDING;

    @Column(length = 1000)
    private String decisionReason;

    @Column(nullable = false)
    private boolean exceptionGranted;

    @ManyToOne
    private User decidedBy;

    private Instant decidedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Version
    private long version;
}
