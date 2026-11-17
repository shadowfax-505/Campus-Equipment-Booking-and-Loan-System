package edu.campus.equipment.repo;

import edu.campus.equipment.domain.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    List<Reservation> findByStudentIdOrderByCreatedAtDesc(Long studentId);

    List<Reservation> findByEquipmentIdAndStatusIn(Long equipmentId, Collection<ReservationStatus> statuses);

    @Query("""
        select r from Reservation r where r.equipment.id = :equipmentId and r.status in :statuses
          and r.startAt < :to and r.endAt > :from""")
    List<Reservation> findOverlapping(@Param("equipmentId") Long equipmentId, @Param("statuses") Collection<ReservationStatus> statuses,
                                      @Param("from") Instant from, @Param("to") Instant to);

    long countByStudentIdAndStatusIn(Long studentId, Collection<ReservationStatus> statuses);

    List<Reservation> findByStatusIn(Collection<ReservationStatus> statuses);

    List<Reservation> findByStatusAndCollectionDeadlineBefore(ReservationStatus status, Instant now);

    @Query("""
        select r from Reservation r where r.status = edu.campus.equipment.domain.ReservationStatus.APPROVED
          and r.collectionReminderSentAt is null and r.startAt <= :horizon and r.collectionDeadline > :now""")
    List<Reservation> findNeedingCollectionReminder(@Param("now") Instant now, @Param("horizon") Instant horizon);

    List<Reservation> findByStudentIdAndStatusAndStartAtAfter(Long studentId, ReservationStatus status, Instant after);

    @Query("select r from Reservation r where r.student.id = :studentId and r.createdAt >= :since")
    List<Reservation> findRecentForStudent(@Param("studentId") Long studentId, @Param("since") Instant since);

    @Query("select r.equipment.id, count(r) from Reservation r where r.createdAt >= :from and r.createdAt < :to group by r.equipment.id order by count(r) desc")
    List<Object[]> countRequestsByEquipment(@Param("from") Instant from, @Param("to") Instant to);

    @Query("select r.status, count(r) from Reservation r where r.createdAt >= :from and r.createdAt < :to group by r.status")
    List<Object[]> countByStatus(@Param("from") Instant from, @Param("to") Instant to);
}
