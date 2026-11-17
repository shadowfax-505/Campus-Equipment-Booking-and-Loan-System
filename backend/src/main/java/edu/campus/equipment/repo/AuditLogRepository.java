package edu.campus.equipment.repo;

import edu.campus.equipment.domain.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    @Query("select a from AuditLog a where (:entityType is null or a.entityType = :entityType) and (:action is null or a.action = :action) order by a.createdAt desc")
    Page<AuditLog> search(@Param("entityType") String entityType, @Param("action") String action, Pageable pageable);
}
