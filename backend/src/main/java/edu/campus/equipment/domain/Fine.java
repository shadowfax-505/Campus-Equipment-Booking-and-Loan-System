package edu.campus.equipment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "fines", indexes = @Index(columnList = "student_id,status"))
@Getter @Setter @NoArgsConstructor
public class Fine {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private User student;

    @ManyToOne
    private Loan loan;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private FineType type;

    /** Deterministic key (e.g. LATE_RETURN:loan:42) with a unique index, so a retried job can never charge twice. */
    @Column(nullable = false, unique = true, length = 120)
    private String dedupeKey;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private FineStatus status = FineStatus.OUTSTANDING;

    /** How the amount was derived, e.g. "2 day(s) x 50.00 (grace 60 min, cap none)". */
    @Column(nullable = false, length = 1000)
    private String description;

    @Column(nullable = false)
    private Instant assessedAt;

    @ManyToOne
    private User assessedBy;

    private Instant settledAt;

    @Column(length = 1000)
    private String adjustmentReason;

    @ManyToOne
    private User adjustedBy;

    @Version
    private long version;

    public BigDecimal balance() {
        if (!status.isPayable()) return BigDecimal.ZERO;
        return amount.subtract(paidAmount).max(BigDecimal.ZERO);
    }
}
