package edu.campus.equipment.service;

import edu.campus.equipment.domain.*;
import edu.campus.equipment.repo.DisputeRepository;
import edu.campus.equipment.repo.FineRepository;
import edu.campus.equipment.repo.LoanRepository;
import edu.campus.equipment.repo.ReservationRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Borrowing-eligibility rules, enforced on the server at request, approval, issue and extension.
 * A high credit score never overrides an unpaid-fine restriction.
 */
@Service
public class EligibilityService {
    public enum Stage { REQUEST, APPROVAL, COLLECTION, EXTENSION }

    public record Result(boolean eligible, List<String> reasons, BigDecimal blockingBalance, long activeCount, int limit,
                         boolean hasBlockingFines) {}

    private final FineRepository fines;
    private final LoanRepository loans;
    private final ReservationRepository reservations;
    private final DisputeRepository disputes;
    private final PolicyService policies;
    private final Clock clock;

    public EligibilityService(FineRepository fines, LoanRepository loans, ReservationRepository reservations,
                              DisputeRepository disputes, PolicyService policies, Clock clock) {
        this.fines = fines;
        this.loans = loans;
        this.reservations = reservations;
        this.disputes = disputes;
        this.policies = policies;
        this.clock = clock;
    }

    public BigDecimal blockingBalance(User student) {
        BorrowingPolicy def = policies.defaultPolicy();
        return fines.findPayable(student.getId()).stream()
                .filter(f -> def.isDisputedFinesBlock() || !disputes.existsByTypeAndSubjectIdAndStatusIn(
                        DisputeType.FINE, f.getId(), EnumSet.of(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW)))
                .map(Fine::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal outstandingBalance(User student) {
        return fines.findPayable(student.getId()).stream().map(Fine::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * @param excludeReservationId the reservation being decided, so it does not count against its own limit
     */
    public Result check(User student, Stage stage, Long excludeReservationId) {
        BorrowingPolicy def = policies.defaultPolicy();
        Instant now = clock.instant();
        List<String> reasons = new ArrayList<>();

        if (student.getStatus() != AccountStatus.ACTIVE) {
            reasons.add("Account is " + student.getStatus().name().toLowerCase());
        }
        BigDecimal blocking = blockingBalance(student);
        boolean finesBlock = blocking.signum() > 0;
        if (finesBlock) {
            reasons.add("Unpaid fines of " + blocking.setScale(2) + " must be cleared first");
        }
        if (def.isOverdueLoanBlocks() && stage != Stage.EXTENSION) {
            long overdue = loans.findByStudentIdAndStatus(student.getId(), LoanStatus.ACTIVE).stream()
                    .filter(l -> l.isOverdue(now)).count();
            if (overdue > 0) reasons.add(overdue + " overdue item(s) must be returned first");
        }

        long active;
        if (stage == Stage.REQUEST) {
            active = reservations.countByStudentIdAndStatusIn(student.getId(),
                    EnumSet.of(ReservationStatus.PENDING, ReservationStatus.WAITLISTED, ReservationStatus.APPROVED))
                    + loans.countByStudentIdAndStatus(student.getId(), LoanStatus.ACTIVE);
            if (active >= def.getMaxActiveBookings()) {
                reasons.add("You already have " + active + " open request(s), booking(s) or loan(s); the limit is " + def.getMaxActiveBookings());
            }
        } else if (stage == Stage.APPROVAL) {
            active = reservations.findByStudentIdOrderByCreatedAtDesc(student.getId()).stream()
                    .filter(r -> r.getStatus() == ReservationStatus.APPROVED && !r.getId().equals(excludeReservationId)).count()
                    + loans.countByStudentIdAndStatus(student.getId(), LoanStatus.ACTIVE);
            if (active >= def.getMaxActiveBookings()) {
                reasons.add("Student already holds " + active + " approved booking(s) or loan(s); the limit is " + def.getMaxActiveBookings());
            }
        } else {
            active = loans.countByStudentIdAndStatus(student.getId(), LoanStatus.ACTIVE);
        }
        return new Result(reasons.isEmpty(), reasons, blocking, active, def.getMaxActiveBookings(), finesBlock);
    }
}
