package edu.campus.equipment.web;

import edu.campus.equipment.domain.*;
import edu.campus.equipment.repo.*;
import edu.campus.equipment.service.AvailabilityService;
import edu.campus.equipment.service.FineService;
import edu.campus.equipment.web.dto.Views.*;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.EnumSet;

@Component
public class ViewMapper {
    private final LoanRepository loans;
    private final ConditionReportRepository reports;
    private final PaymentAllocationRepository allocations;
    private final DisputeRepository disputes;
    private final FineService fines;
    private final AvailabilityService availability;
    private final Clock clock;

    public ViewMapper(LoanRepository loans, ConditionReportRepository reports, PaymentAllocationRepository allocations,
                      DisputeRepository disputes, FineService fines, AvailabilityService availability, Clock clock) {
        this.loans = loans;
        this.reports = reports;
        this.allocations = allocations;
        this.disputes = disputes;
        this.fines = fines;
        this.availability = availability;
        this.clock = clock;
    }

    public EquipmentView equipment(Equipment e) {
        return EquipmentView.of(e, availability.isAvailableNow(e));
    }

    public ReservationView reservation(Reservation r) {
        Long loanId = r.getStatus() == ReservationStatus.FULFILLED ? loans.findByReservationId(r.getId()).map(Loan::getId).orElse(null) : null;
        return ReservationView.of(r, loanId);
    }

    public LoanView loan(Loan l) {
        var q = fines.estimate(l);
        return LoanView.of(l, clock.instant(), q.amount(), q.explanation(), reports.findByLoanIdOrderByRecordedAt(l.getId()));
    }

    public FineView fine(Fine f) {
        return FineView.of(f, disputes.existsByTypeAndSubjectIdAndStatusIn(DisputeType.FINE, f.getId(),
                EnumSet.of(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW)));
    }

    public PaymentView payment(Payment p) {
        return PaymentView.of(p, allocations.findByPaymentId(p.getId()));
    }
}
