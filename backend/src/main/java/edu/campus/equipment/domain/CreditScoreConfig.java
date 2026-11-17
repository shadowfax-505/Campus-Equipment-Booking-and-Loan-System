package edu.campus.equipment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Single-row, administrator-editable scoring parameters (weights must sum to 1). */
@Entity
@Table(name = "credit_score_config")
@Getter @Setter @NoArgsConstructor
public class CreditScoreConfig {
    @Id
    private Long id = 1L;

    @Column(nullable = false) private double weightPunctuality = 0.40;
    @Column(nullable = false) private double weightCare = 0.25;
    @Column(nullable = false) private double weightPayment = 0.15;
    @Column(nullable = false) private double weightReliability = 0.10;
    @Column(nullable = false) private double weightHistory = 0.10;

    /** Value used for a component with no history, and the starting score for new students. */
    @Column(nullable = false) private double neutralValue = 70.0;

    /** Only events within this many days count, so students can recover from past mistakes. */
    @Column(nullable = false) private int recentHistoryDays = 180;

    /** Points removed from punctuality per started day late on a single return. */
    @Column(nullable = false) private double latePenaltyPerDay = 15.0;

    /** Fines settled within this many days of assessment count as prompt. */
    @Column(nullable = false) private int promptPaymentDays = 7;

    /** Completed loans needed for the history component to reach 100. */
    @Column(nullable = false) private int historyTarget = 10;

    /** Changes of at least this many points notify the student. */
    @Column(nullable = false) private double significantChange = 5.0;

    @Column(nullable = false) private Instant updatedAt = Instant.now();
}
