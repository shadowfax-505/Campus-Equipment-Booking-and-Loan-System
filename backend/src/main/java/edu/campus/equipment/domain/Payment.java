package edu.campus.equipment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "payments")
@Getter @Setter @NoArgsConstructor
public class Payment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private User student;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private PaymentChannel channel;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private PaymentStatus status;

    /** Provider transaction id or external receipt number; unique so a repeated notification is ignored. */
    @Column(nullable = false, unique = true, length = 120)
    private String reference;

    @Column(length = 40)
    private String provider;

    /** Fines the student intends this payment to settle (applied oldest first when empty). */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "payment_target_fines", joinColumns = @JoinColumn(name = "payment_id"))
    @Column(name = "fine_id")
    private List<Long> targetFineIds = new ArrayList<>();

    @Column(length = 1000)
    private String notes;

    @Column(length = 500)
    private String evidenceUrl;

    @Column(nullable = false)
    private Instant submittedAt;

    private Instant completedAt;

    @ManyToOne
    private User verifiedBy;

    @Column(length = 500)
    private String failureReason;

    @Version
    private long version;
}
