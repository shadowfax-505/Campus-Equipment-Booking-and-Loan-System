package edu.campus.equipment.repo;

import edu.campus.equipment.domain.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public interface AllocationDecisionRepository extends JpaRepository<AllocationDecision, Long> {
    @Query("select a from AllocationDecision a where a.decidedAt >= :from and a.decidedAt < :to order by a.decidedAt desc")
    List<AllocationDecision> findBetween(@Param("from") Instant from, @Param("to") Instant to);
}
