package edu.campus.equipment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "maintenance_records", indexes = @Index(columnList = "equipment_id"))
@Getter @Setter @NoArgsConstructor
public class MaintenanceRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Equipment equipment;

    @Column(nullable = false, length = 1000)
    private String reason;

    /** Unavailability starts here (may be in the future for a planned block). */
    @Column(nullable = false)
    private Instant startAt;

    /** Planned or actual end; null while open-ended. */
    private Instant endAt;

    @Column(nullable = false)
    private boolean completed;

    @Column(length = 2000)
    private String resolutionNotes;

    private Long relatedLoanId;

    @ManyToOne(optional = false)
    private User createdBy;

    @ManyToOne
    private User completedBy;

    @Column(nullable = false)
    private Instant createdAt;

    public boolean blocks(Instant from, Instant to) {
        Instant end = endAt == null ? Instant.MAX : endAt;
        if (completed && endAt != null) end = endAt;
        return startAt.isBefore(to) && from.isBefore(end);
    }
}
