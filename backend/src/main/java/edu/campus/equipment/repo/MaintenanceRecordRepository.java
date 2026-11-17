package edu.campus.equipment.repo;

import edu.campus.equipment.domain.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public interface MaintenanceRecordRepository extends JpaRepository<MaintenanceRecord, Long> {
    List<MaintenanceRecord> findByEquipmentIdOrderByStartAtDesc(Long equipmentId);
    List<MaintenanceRecord> findByCompletedFalseOrderByStartAt();
    List<MaintenanceRecord> findByEquipmentIdAndCompletedFalse(Long equipmentId);

    @Query("select m from MaintenanceRecord m where m.equipment.id = :equipmentId and m.startAt < :to and (m.endAt is null or m.endAt > :from)")
    List<MaintenanceRecord> findBlocking(@Param("equipmentId") Long equipmentId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("select m from MaintenanceRecord m where m.startAt < :to and (m.endAt is null or m.endAt > :from) order by m.startAt desc")
    List<MaintenanceRecord> findBetween(@Param("from") Instant from, @Param("to") Instant to);
}
