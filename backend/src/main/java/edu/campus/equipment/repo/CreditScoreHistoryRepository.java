package edu.campus.equipment.repo;

import edu.campus.equipment.domain.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public interface CreditScoreHistoryRepository extends JpaRepository<CreditScoreHistory, Long> {
    List<CreditScoreHistory> findByStudentIdOrderByCreatedAtDesc(Long studentId);

    @Query("select h from CreditScoreHistory h where h.student.id = :studentId and h.eventType in (edu.campus.equipment.domain.ScoreEventType.MANUAL_ADJUSTMENT) and h.voided = false and h.createdAt >= :since")
    List<CreditScoreHistory> findActiveAdjustments(@Param("studentId") Long studentId, @Param("since") Instant since);

    @Query("select h from CreditScoreHistory h where h.eventType = edu.campus.equipment.domain.ScoreEventType.MANUAL_ADJUSTMENT and h.createdAt >= :from and h.createdAt < :to order by h.createdAt desc")
    List<CreditScoreHistory> findAdjustmentsBetween(@Param("from") Instant from, @Param("to") Instant to);
}
