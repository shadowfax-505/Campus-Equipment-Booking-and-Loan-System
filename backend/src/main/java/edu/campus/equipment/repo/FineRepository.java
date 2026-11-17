package edu.campus.equipment.repo;

import edu.campus.equipment.domain.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public interface FineRepository extends JpaRepository<Fine, Long> {
    List<Fine> findByStudentIdOrderByAssessedAtDesc(Long studentId);
    boolean existsByDedupeKey(String dedupeKey);
    List<Fine> findByLoanId(Long loanId);

    @Query("select f from Fine f where f.student.id = :studentId and f.status in (edu.campus.equipment.domain.FineStatus.OUTSTANDING, edu.campus.equipment.domain.FineStatus.PARTIALLY_PAID) order by f.assessedAt")
    List<Fine> findPayable(@Param("studentId") Long studentId);

    @Query("select f from Fine f where f.status in (edu.campus.equipment.domain.FineStatus.OUTSTANDING, edu.campus.equipment.domain.FineStatus.PARTIALLY_PAID) order by f.assessedAt")
    List<Fine> findAllPayable();

    @Query("select f from Fine f where f.student.id = :studentId and f.assessedAt >= :since")
    List<Fine> findRecentForStudent(@Param("studentId") Long studentId, @Param("since") Instant since);

    @Query("select f from Fine f where f.assessedAt >= :from and f.assessedAt < :to")
    List<Fine> findAssessedBetween(@Param("from") Instant from, @Param("to") Instant to);
}
