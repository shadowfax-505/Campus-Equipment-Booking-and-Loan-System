package edu.campus.equipment.repo;

import edu.campus.equipment.domain.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public interface ConditionReportRepository extends JpaRepository<ConditionReport, Long> {
    List<ConditionReport> findByLoanIdOrderByRecordedAt(Long loanId);
    Optional<ConditionReport> findFirstByLoanIdAndType(Long loanId, ConditionReportType type);

    @Query("select c from ConditionReport c where c.type = edu.campus.equipment.domain.ConditionReportType.RETURN and (c.damageVerified = true or size(c.missingAccessories) > 0) and c.recordedAt >= :from and c.recordedAt < :to")
    List<ConditionReport> findIncidents(@Param("from") Instant from, @Param("to") Instant to);
}
