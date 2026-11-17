package edu.campus.equipment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "disputes")
@Getter @Setter @NoArgsConstructor
public class Dispute {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private User student;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private DisputeType type;

    /** Id of the fine, condition report or score-history entry being disputed. */
    @Column(nullable = false)
    private Long subjectId;

    @Column(nullable = false, length = 2000)
    private String description;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private DisputeStatus status = DisputeStatus.OPEN;

    @Column(length = 2000)
    private String resolution;

    @ManyToOne
    private User resolvedBy;

    private Instant resolvedAt;

    @Column(nullable = false)
    private Instant createdAt;
}
