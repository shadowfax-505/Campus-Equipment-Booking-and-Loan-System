package edu.campus.equipment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "equipment")
@Getter @Setter @NoArgsConstructor
public class Equipment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String assetTag;

    @Column(nullable = false, length = 140)
    private String name;

    @Column(length = 2000)
    private String description;

    @ManyToOne(optional = false)
    private Category category;

    @ManyToOne(optional = false)
    private Location location;

    @Column(length = 120)
    private String serialNumber;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private EquipmentCondition condition = EquipmentCondition.GOOD;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private OperationalStatus status = OperationalStatus.ACTIVE;

    @ManyToOne(optional = false)
    private BorrowingPolicy policy;

    /** Overrides the policy daily overdue rate for this item (e.g. expensive instruments). */
    @Column(precision = 10, scale = 2)
    private BigDecimal overdueRateOverride;

    @Column(length = 1000)
    private String borrowingNotes;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "equipment_accessories", joinColumns = @JoinColumn(name = "equipment_id"))
    @Column(name = "accessory", length = 120)
    @OrderColumn(name = "position")
    private List<String> accessories = new ArrayList<>();

    @Column(length = 500)
    private String imageUrl;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Version
    private long version;

    public BigDecimal effectiveDailyRate() {
        return overdueRateOverride != null ? overdueRateOverride : policy.getDailyOverdueRate();
    }
}
