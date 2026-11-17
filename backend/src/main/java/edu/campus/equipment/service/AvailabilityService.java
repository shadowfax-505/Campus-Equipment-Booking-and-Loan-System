package edu.campus.equipment.service;

import edu.campus.equipment.domain.*;
import edu.campus.equipment.repo.LoanRepository;
import edu.campus.equipment.repo.MaintenanceRecordRepository;
import edu.campus.equipment.repo.ReservationRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * Availability for a period is derived from approved reservations, active loans and maintenance blocks.
 * Pending, waitlisted, cancelled, expired and rejected requests never block.
 */
@Service
public class AvailabilityService {

    public enum BlockType { RESERVED, ON_LOAN, MAINTENANCE, OUT_OF_SERVICE }

    /** reservationId/loanId are only exposed to staff by the web layer. */
    public record Block(BlockType type, Instant start, Instant end, Long reservationId, Long loanId, String note) {}

    private final ReservationRepository reservations;
    private final LoanRepository loans;
    private final MaintenanceRecordRepository maintenance;
    private final Clock clock;

    public AvailabilityService(ReservationRepository reservations, LoanRepository loans,
                               MaintenanceRecordRepository maintenance, Clock clock) {
        this.reservations = reservations;
        this.loans = loans;
        this.maintenance = maintenance;
        this.clock = clock;
    }

    public List<Block> blocks(Equipment e, Instant from, Instant to, Long excludeReservationId, Long excludeLoanId) {
        List<Block> out = new ArrayList<>();
        if (e.getStatus() == OperationalStatus.LOST || e.getStatus() == OperationalStatus.RETIRED) {
            out.add(new Block(BlockType.OUT_OF_SERVICE, from, to, null, null, e.getStatus().name()));
            return out;
        }
        Instant now = clock.instant();
        for (Reservation r : reservations.findOverlapping(e.getId(), EnumSet.of(ReservationStatus.APPROVED), from, to)) {
            if (!r.getId().equals(excludeReservationId)) {
                out.add(new Block(BlockType.RESERVED, r.getStartAt(), r.getEndAt(), r.getId(), null, null));
            }
        }
        for (Loan l : loans.findByEquipmentIdAndStatus(e.getId(), LoanStatus.ACTIVE)) {
            if (l.getId().equals(excludeLoanId)) continue;
            // An overdue item stays blocked until it is actually returned.
            Instant end = l.isOverdue(now) ? Instant.MAX : l.getDueAt();
            if (l.getIssuedAt().isBefore(to) && from.isBefore(end)) {
                out.add(new Block(BlockType.ON_LOAN, l.getIssuedAt(), l.isOverdue(now) ? null : l.getDueAt(), null, l.getId(),
                        l.isOverdue(now) ? "Overdue - return pending" : null));
            }
        }
        for (MaintenanceRecord m : maintenance.findBlocking(e.getId(), from, to)) {
            out.add(new Block(BlockType.MAINTENANCE, m.getStartAt(), m.getEndAt(), null, null, m.getReason()));
        }
        if (e.getStatus() == OperationalStatus.UNDER_MAINTENANCE && out.stream().noneMatch(b -> b.type() == BlockType.MAINTENANCE)) {
            out.add(new Block(BlockType.MAINTENANCE, now, null, null, null, "Under maintenance"));
        }
        out.sort(Comparator.comparing(Block::start));
        return out;
    }

    public boolean isAvailable(Equipment e, Instant from, Instant to, Long excludeReservationId, Long excludeLoanId) {
        return blocks(e, from, to, excludeReservationId, excludeLoanId).isEmpty();
    }

    public boolean isAvailableNow(Equipment e) {
        Instant now = clock.instant();
        return isAvailable(e, now, now.plusSeconds(60), null, null);
    }
}
