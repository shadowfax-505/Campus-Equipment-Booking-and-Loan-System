package edu.campus.equipment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "users")
@Getter @Setter @NoArgsConstructor
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 160)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false, length = 120)
    private String fullName;

    /** Institutional student or staff number. */
    @Column(unique = true, length = 40)
    private String institutionId;

    @Column(length = 40)
    private String phone;

    @Column(length = 120)
    private String department;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private AccountStatus status = AccountStatus.ACTIVE;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "user_permissions", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING) @Column(name = "permission", length = 40)
    private Set<StaffPermission> permissions = new HashSet<>();

    /** Locations whose equipment this staff member is responsible for. */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "staff_locations", joinColumns = @JoinColumn(name = "user_id"), inverseJoinColumns = @JoinColumn(name = "location_id"))
    private Set<Location> managedLocations = new HashSet<>();

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public boolean hasPermission(StaffPermission p) {
        return role == Role.ADMIN || (role == Role.STAFF && permissions.contains(p));
    }
}
