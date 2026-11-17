package edu.campus.equipment.repo;

import edu.campus.equipment.domain.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public interface EquipmentRepository extends JpaRepository<Equipment, Long> {
    boolean existsByAssetTagIgnoreCase(String assetTag);

    /** Row lock used to serialise approvals, issues and extensions for one physical item. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Equipment e where e.id = :id")
    Optional<Equipment> lockById(@Param("id") Long id);

    @Query("""
        select e from Equipment e
        where (:categoryId is null or e.category.id = :categoryId)
          and (:locationId is null or e.location.id = :locationId)
          and (:condition is null or e.condition = :condition)
          and (:status is null or e.status = :status)
          and (:q is null or lower(e.name) like :q or lower(e.description) like :q or lower(e.assetTag) like :q)
        order by e.name, e.assetTag""")
    List<Equipment> search(@Param("categoryId") Long categoryId, @Param("locationId") Long locationId,
                           @Param("condition") EquipmentCondition condition, @Param("status") OperationalStatus status,
                           @Param("q") String q);

    List<Equipment> findByLocationIdIn(Collection<Long> locationIds);
}
