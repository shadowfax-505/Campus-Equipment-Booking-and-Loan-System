package edu.campus.equipment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Limits, durations, fine rates and eligibility rules. Equipment points at one policy; the default policy
 * supplies global limits such as the maximum number of active bookings per student.
 */
@Entity
@Table(name = "borrowing_policies")
@Getter @Setter @NoArgsConstructor
public class BorrowingPolicy {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String name;

    @Column(nullable = false)
    private boolean defaultPolicy;

    /** Longest allowed booking / loan, in hours, including extensions. */
    @Column(nullable = false)
    private int maxLoanHours = 72;

    /** Minimum booking length in minutes. */
    @Column(nullable = false)
    private int minLoanMinutes = 30;

    /** How far ahead a student may book, in days. */
    @Column(nullable = false)
    private int maxAdvanceDays = 30;

    /** Max open requests + approved bookings + active loans per student (taken from the default policy). */
    @Column(nullable = false)
    private int maxActiveBookings = 3;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal dailyOverdueRate = new BigDecimal("50.00");

    @Column(nullable = false)
    private int gracePeriodMinutes = 60;

    /** Optional ceiling for the late-return fee; null means no cap. */
    @Column(precision = 10, scale = 2)
    private BigDecimal fineCap;

    /** Competing requests submitted within this window are compared before any is approved. */
    @Column(nullable = false)
    private int decisionWindowMinutes = 60;

    /** Hours after the booking start by which the student must collect. */
    @Column(nullable = false)
    private int collectionWindowHours = 4;

    /** Minutes before the booking start during which staff may already issue the item. */
    @Column(nullable = false)
    private int earlyCollectionMinutes = 60;

    /** Cancelling an approved booking later than this many hours before its start is a late cancellation. */
    @Column(nullable = false)
    private int cancellationCutoffHours = 12;

    @Column(nullable = false)
    private int maxExtensions = 2;

    /** true: losing competitors are waitlisted; false: they are rejected with an explanation. */
    @Column(nullable = false)
    private boolean waitlistUnsuccessful = true;

    /** Whether a fine under an open dispute still blocks borrowing. */
    @Column(nullable = false)
    private boolean disputedFinesBlock = true;

    /** Whether having an overdue item blocks new requests (in addition to unpaid fines). */
    @Column(nullable = false)
    private boolean overdueLoanBlocks = true;

    @Version
    private long version;
}
