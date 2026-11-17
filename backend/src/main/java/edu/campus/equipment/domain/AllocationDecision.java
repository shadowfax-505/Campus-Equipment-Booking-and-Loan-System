package edu.campus.equipment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Record of how competing requests for one item were ranked and which one was chosen. */
@Entity
@Table(name = "allocation_decisions")
@Getter @Setter @NoArgsConstructor
public class AllocationDecision {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Equipment equipment;

    @ManyToOne(optional = false)
    private Reservation selectedReservation;

    /** JSON array of candidates: reservationId, studentId, scoreSnapshot, submittedAt, rank, eligible, exclusionReason. */
    @Column(nullable = false, columnDefinition = "text")
    private String candidatesJson;

    @Column(nullable = false)
    private int candidateCount;

    /** True when staff chose a request other than the top-ranked one, or approved early. */
    @Column(nullable = false)
    private boolean override;

    @Column(length = 1000)
    private String reason;

    @ManyToOne(optional = false)
    private User decidedBy;

    @Column(nullable = false)
    private Instant decidedAt;
}
