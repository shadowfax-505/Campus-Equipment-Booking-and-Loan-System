package edu.campus.equipment.repo;

import edu.campus.equipment.domain.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public interface DisputeRepository extends JpaRepository<Dispute, Long> {
    List<Dispute> findByStudentIdOrderByCreatedAtDesc(Long studentId);
    List<Dispute> findAllByOrderByCreatedAtDesc();
    boolean existsByTypeAndSubjectIdAndStatusIn(DisputeType type, Long subjectId, Collection<DisputeStatus> statuses);
}
