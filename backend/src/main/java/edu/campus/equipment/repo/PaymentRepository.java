package edu.campus.equipment.repo;

import edu.campus.equipment.domain.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByReference(String reference);
    List<Payment> findByStudentIdOrderBySubmittedAtDesc(Long studentId);
    List<Payment> findByStatusOrderBySubmittedAt(PaymentStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.reference = :reference")
    Optional<Payment> lockByReference(@Param("reference") String reference);

    @Query("select p from Payment p where p.status = edu.campus.equipment.domain.PaymentStatus.SUCCEEDED and p.completedAt >= :from and p.completedAt < :to")
    List<Payment> findSucceededBetween(@Param("from") Instant from, @Param("to") Instant to);
}
