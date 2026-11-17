package edu.campus.equipment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "locations")
@Getter @Setter @NoArgsConstructor
public class Location {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 120)
    private String name;

    @Column(length = 120)
    private String department;

    @Column(length = 60)
    private String room;

    @Override public boolean equals(Object o) { return o instanceof Location l && id != null && id.equals(l.id); }
    @Override public int hashCode() { return Objects.hashCode(id); }
}
