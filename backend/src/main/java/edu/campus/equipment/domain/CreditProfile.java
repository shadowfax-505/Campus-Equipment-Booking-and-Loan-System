package edu.campus.equipment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "credit_profiles")
@Getter @Setter @NoArgsConstructor
public class CreditProfile {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false)
    @JoinColumn(unique = true)
    private User student;

    @Column(nullable = false) private double score;
    @Column(nullable = false) private double punctuality;
    @Column(nullable = false) private double care;
    @Column(nullable = false) private double paymentTimeliness;
    @Column(nullable = false) private double reliability;
    @Column(nullable = false) private double history;

    /** Net of administrator adjustments still inside the recent-history period. */
    @Column(nullable = false) private double manualAdjustment;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;
}
