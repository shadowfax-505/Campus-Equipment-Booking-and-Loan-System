package edu.campus.equipment.repo;

import edu.campus.equipment.domain.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public interface LoanRepository extends JpaRepository<Loan, Long> {
    List<Loan> findByStudentIdOrderByIssuedAtDesc(Long studentId);
    List<Loan> findByStatus(LoanStatus status);
    List<Loan> findByEquipmentIdAndStatus(Long equipmentId, LoanStatus status);
    Optional<Loan> findByReservationId(Long reservationId);
    long countByStudentIdAndStatus(Long studentId, LoanStatus status);
    List<Loan> findByStudentIdAndStatus(Long studentId, LoanStatus status);

    @Query("select l from Loan l where l.status = edu.campus.equipment.domain.LoanStatus.ACTIVE and l.dueAt < :now and l.overdueNotifiedAt is null")
    List<Loan> findNewlyOverdue(@Param("now") Instant now);

    @Query("select l from Loan l where l.status = edu.campus.equipment.domain.LoanStatus.ACTIVE and l.returnReminderSentAt is null and l.dueAt > :now and l.dueAt <= :horizon")
    List<Loan> findNeedingReturnReminder(@Param("now") Instant now, @Param("horizon") Instant horizon);

    @Query("select l from Loan l where l.student.id = :studentId and l.status <> edu.campus.equipment.domain.LoanStatus.ACTIVE and l.returnedAt >= :since")
    List<Loan> findClosedSince(@Param("studentId") Long studentId, @Param("since") Instant since);

    @Query("select l from Loan l where l.student.id = :studentId and l.status = edu.campus.equipment.domain.LoanStatus.LOST and l.issuedAt >= :since")
    List<Loan> findLostSince(@Param("studentId") Long studentId, @Param("since") Instant since);

    @Query("select l from Loan l where l.issuedAt < :to and (l.returnedAt is null or l.returnedAt > :from)")
    List<Loan> findActiveDuring(@Param("from") Instant from, @Param("to") Instant to);
}
