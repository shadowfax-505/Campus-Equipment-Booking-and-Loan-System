package edu.campus.equipment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "reservations", indexes = {
        @Index(columnList = "equipment_id,status"),
        @Index(columnList = "student_id,status")})
@Getter @Setter @NoArgsConstructor
public class Reservation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private User student;

    @ManyToOne(optional = false)
    private Equipment equipment;

    @Column(nullable = false)
    private Instant startAt;

    @Column(nullable = false)
    private Instant endAt;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private ReservationStatus status = ReservationStatus.PENDING;

    @Column(length = 500)
    private String purpose;

    @Column(nullable = false)
    private Instant createdAt;

    /** End of the decision window during which competing requests are gathered. */
    @Column(nullable = false)
    private Instant decisionWindowEndsAt;

    private Instant decidedAt;

    @ManyToOne
    private User decidedBy;

    @Column(length = 1000)
    private String decisionReason;

    /** Set on approval: the student must collect by this time or the booking expires. */
    private Instant collectionDeadline;

    private Instant cancelledAt;

    @Column(nullable = false)
    private boolean lateCancellation;

    /** Raised when the student acquires an unpaid fine after approval, so staff can review the booking. */
    @Column(nullable = false)
    private boolean flaggedForReview;

    @Column(length = 500)
    private String flagReason;

    @ManyToOne
    private AllocationDecision allocationDecision;

    private Instant collectionReminderSentAt;

    @Version
    private long version;

    public void moveTo(ReservationStatus target) {
        this.status = this.status.transitionTo(target);
    }

    public boolean overlaps(Instant from, Instant to) {
        return startAt.isBefore(to) && from.isBefore(endAt);
    }
}
