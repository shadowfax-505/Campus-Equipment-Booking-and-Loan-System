package edu.campus.equipment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "credit_score_history", indexes = @Index(columnList = "student_id,createdAt"))
@Getter @Setter @NoArgsConstructor
public class CreditScoreHistory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private User student;

    @Column(nullable = false) private double previousScore;
    @Column(nullable = false) private double newScore;

    /** For manual adjustments: the points added or removed. Zero for recalculations. */
    @Column(nullable = false) private double adjustment;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private ScoreEventType eventType;

    @Column(nullable = false, length = 1000)
    private String reason;

    private Long loanId;
    private Long reservationId;
    private Long fineId;

    /** Component values after this change, for the "factors" view. */
    @Column(length = 400)
    private String componentsSnapshot;

    @ManyToOne
    private User changedBy;

    /** A dispute upheld against a manual adjustment voids it. */
    @Column(nullable = false)
    private boolean voided;

    @Column(nullable = false)
    private Instant createdAt;
}
