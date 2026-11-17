package edu.campus.equipment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "loans", indexes = {@Index(columnList = "student_id,status"), @Index(columnList = "equipment_id,status")})
@Getter @Setter @NoArgsConstructor
public class Loan {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false)
    @JoinColumn(unique = true)
    private Reservation reservation;

    @ManyToOne(optional = false)
    private User student;

    @ManyToOne(optional = false)
    private Equipment equipment;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private LoanStatus status = LoanStatus.ACTIVE;

    @Column(nullable = false)
    private Instant issuedAt;

    @ManyToOne(optional = false)
    private User issuedBy;

    @Column(nullable = false)
    private Instant dueAt;

    /** Due time before any extension. */
    @Column(nullable = false)
    private Instant originalDueAt;

    private Instant returnedAt;

    @ManyToOne
    private User receivedBy;

    @Column(nullable = false)
    private int extensionCount;

    // Policy snapshot taken at issue time so later policy changes never rewrite historical charges.
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal appliedDailyRate;

    @Column(nullable = false)
    private int appliedGraceMinutes;

    @Column(precision = 10, scale = 2)
    private BigDecimal appliedFineCap;

    @Column(nullable = false, length = 80)
    private String appliedPolicyName;

    private Instant returnReminderSentAt;
    private Instant overdueNotifiedAt;

    @Version
    private long version;

    public void moveTo(LoanStatus target) {
        this.status = this.status.transitionTo(target);
    }

    public boolean isOverdue(Instant now) {
        return status == LoanStatus.ACTIVE && now.isAfter(dueAt);
    }
}
