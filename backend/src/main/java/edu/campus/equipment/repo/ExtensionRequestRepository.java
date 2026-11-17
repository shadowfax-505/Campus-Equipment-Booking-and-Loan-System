package edu.campus.equipment.repo;

import edu.campus.equipment.domain.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public interface ExtensionRequestRepository extends JpaRepository<ExtensionRequest, Long> {
    List<ExtensionRequest> findByLoanIdOrderByCreatedAtDesc(Long loanId);
    List<ExtensionRequest> findByStatusOrderByCreatedAt(ExtensionStatus status);
    boolean existsByLoanIdAndStatus(Long loanId, ExtensionStatus status);
    List<ExtensionRequest> findByLoanStudentIdOrderByCreatedAtDesc(Long studentId);
}
