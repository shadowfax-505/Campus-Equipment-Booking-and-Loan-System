package edu.campus.equipment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "condition_reports")
@Getter @Setter @NoArgsConstructor
public class ConditionReport {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Loan loan;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10)
    private ConditionReportType type;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private EquipmentCondition condition;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "condition_report_accessories_present", joinColumns = @JoinColumn(name = "report_id"))
    @Column(name = "accessory", length = 120)
    private Set<String> accessoriesPresent = new HashSet<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "condition_report_accessories_missing", joinColumns = @JoinColumn(name = "report_id"))
    @Column(name = "accessory", length = 120)
    private Set<String> missingAccessories = new HashSet<>();

    /** Staff verified the damage was caused during this loan (beyond normal wear and tear). */
    @Column(nullable = false)
    private boolean damageVerified;

    @Column(nullable = false)
    private boolean requiresRepair;

    @Column(length = 2000)
    private String notes;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "condition_report_photos", joinColumns = @JoinColumn(name = "report_id"))
    @Column(name = "url", length = 500)
    private List<String> photoUrls = new ArrayList<>();

    /** Set when a dispute about this report is upheld: the report no longer counts against the student. */
    @Column(nullable = false)
    private boolean voided;

    @ManyToOne(optional = false)
    private User recordedBy;

    @Column(nullable = false)
    private Instant recordedAt;
}
