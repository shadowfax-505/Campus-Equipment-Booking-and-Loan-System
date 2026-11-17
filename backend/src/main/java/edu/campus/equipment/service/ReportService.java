package edu.campus.equipment.service;

import edu.campus.equipment.domain.*;
import edu.campus.equipment.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Administrator reports over a period [from, to). */
@Service
@Transactional(readOnly = true)
public class ReportService {
    private final EquipmentRepository equipment;
    private final ReservationRepository reservations;
    private final LoanRepository loans;
    private final FineRepository fines;
    private final PaymentRepository payments;
    private final ConditionReportRepository reports;
    private final MaintenanceRecordRepository maintenance;
    private final AllocationDecisionRepository decisions;
    private final CreditScoreHistoryRepository scoreHistory;
    private final Clock clock;

    public ReportService(EquipmentRepository equipment, ReservationRepository reservations, LoanRepository loans,
                         FineRepository fines, PaymentRepository payments, ConditionReportRepository reports,
                         MaintenanceRecordRepository maintenance, AllocationDecisionRepository decisions,
                         CreditScoreHistoryRepository scoreHistory, Clock clock) {
        this.equipment = equipment;
        this.reservations = reservations;
        this.loans = loans;
        this.fines = fines;
        this.payments = payments;
        this.reports = reports;
        this.maintenance = maintenance;
        this.decisions = decisions;
        this.scoreHistory = scoreHistory;
        this.clock = clock;
    }

    public List<Map<String, Object>> utilization(Instant from, Instant to) {
        Instant now = clock.instant();
        double periodHours = Duration.between(from, to).toMinutes() / 60.0;
        Map<Long, Double> hours = new HashMap<>();
        Map<Long, Integer> count = new HashMap<>();
        for (Loan l : loans.findActiveDuring(from, to)) {
            Instant s = l.getIssuedAt().isBefore(from) ? from : l.getIssuedAt();
            Instant end = l.getReturnedAt() != null ? l.getReturnedAt() : now;
            Instant e = end.isAfter(to) ? to : end;
            if (e.isAfter(s)) {
                hours.merge(l.getEquipment().getId(), Duration.between(s, e).toMinutes() / 60.0, Double::sum);
                count.merge(l.getEquipment().getId(), 1, Integer::sum);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Equipment e : equipment.findAll()) {
            double h = hours.getOrDefault(e.getId(), 0.0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("equipmentId", e.getId());
            m.put("assetTag", e.getAssetTag());
            m.put("name", e.getName());
            m.put("category", e.getCategory().getName());
            m.put("loans", count.getOrDefault(e.getId(), 0));
            m.put("hoursOnLoan", round(h));
            m.put("utilizationPct", periodHours <= 0 ? 0 : round(100 * h / periodHours));
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> -((Double) m.get("utilizationPct"))));
        return out;
    }

    public Map<String, Object> activity(Instant from, Instant to) {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Long> byStatus = new TreeMap<>();
        for (Object[] row : reservations.countByStatus(from, to)) byStatus.put(row[0].toString(), (Long) row[1]);
        m.put("reservationsByStatus", byStatus);
        List<Loan> all = loans.findAll();
        m.put("loansIssued", all.stream().filter(l -> in(l.getIssuedAt(), from, to)).count());
        m.put("loansReturned", all.stream().filter(l -> l.getReturnedAt() != null && in(l.getReturnedAt(), from, to)).count());
        m.put("activeLoansNow", all.stream().filter(l -> l.getStatus() == LoanStatus.ACTIVE).count());
        return m;
    }

    public List<Map<String, Object>> mostRequested(Instant from, Instant to, int limit) {
        Map<Long, Equipment> byId = equipment.findAll().stream().collect(Collectors.toMap(Equipment::getId, e -> e));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] row : reservations.countRequestsByEquipment(from, to)) {
            if (out.size() >= limit) break;
            Equipment e = byId.get((Long) row[0]);
            out.add(Map.of("equipmentId", e.getId(), "assetTag", e.getAssetTag(), "name", e.getName(), "requests", row[1]));
        }
        return out;
    }

    public Map<String, Object> overdue(Instant from, Instant to) {
        Instant now = clock.instant();
        List<Loan> returnedLate = loans.findAll().stream()
                .filter(l -> l.getReturnedAt() != null && in(l.getReturnedAt(), from, to) && l.getReturnedAt().isAfter(l.getDueAt()))
                .toList();
        long returnedInPeriod = loans.findAll().stream().filter(l -> l.getReturnedAt() != null && in(l.getReturnedAt(), from, to)).count();
        DoubleSummaryStatistics hrs = returnedLate.stream()
                .mapToDouble(l -> Duration.between(l.getDueAt(), l.getReturnedAt()).toMinutes() / 60.0).summaryStatistics();
        List<Map<String, Object>> current = loans.findByStatus(LoanStatus.ACTIVE).stream().filter(l -> l.isOverdue(now))
                .map(l -> Map.<String, Object>of("loanId", l.getId(), "assetTag", l.getEquipment().getAssetTag(),
                        "student", l.getStudent().getFullName(), "dueAt", l.getDueAt().toString(),
                        "hoursOverdue", round(Duration.between(l.getDueAt(), now).toMinutes() / 60.0)))
                .toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("returnsInPeriod", returnedInPeriod);
        m.put("lateReturns", returnedLate.size());
        m.put("lateReturnRatePct", returnedInPeriod == 0 ? 0 : round(100.0 * returnedLate.size() / returnedInPeriod));
        m.put("averageHoursLate", returnedLate.isEmpty() ? 0 : round(hrs.getAverage()));
        m.put("maxHoursLate", returnedLate.isEmpty() ? 0 : round(hrs.getMax()));
        m.put("currentlyOverdue", current);
        return m;
    }

    public Map<String, Object> finances(Instant from, Instant to) {
        List<Fine> assessed = fines.findAssessedBetween(from, to);
        BigDecimal outstanding = fines.findAllPayable().stream().map(Fine::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal collected = payments.findSucceededBetween(from, to).stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, BigDecimal> byType = new TreeMap<>();
        assessed.forEach(f -> byType.merge(f.getType().name(), f.getAmount(), BigDecimal::add));
        BigDecimal waived = assessed.stream().filter(f -> f.getStatus() == FineStatus.WAIVED)
                .map(f -> f.getAmount().subtract(f.getPaidAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("assessedInPeriod", assessed.stream().map(Fine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        m.put("assessedByType", byType);
        m.put("collectedInPeriod", collected);
        m.put("waivedInPeriod", waived);
        m.put("outstandingNow", outstanding);
        m.put("studentsWithOutstanding", fines.findAllPayable().stream().map(f -> f.getStudent().getId()).distinct().count());
        return m;
    }

    public List<Map<String, Object>> incidents(Instant from, Instant to) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConditionReport r : reports.findIncidents(from, to)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", r.isDamageVerified() ? "DAMAGE" : "MISSING_ACCESSORIES");
            m.put("loanId", r.getLoan().getId());
            m.put("assetTag", r.getLoan().getEquipment().getAssetTag());
            m.put("student", r.getLoan().getStudent().getFullName());
            m.put("missing", r.getMissingAccessories());
            m.put("notes", r.getNotes());
            m.put("voided", r.isVoided());
            m.put("at", r.getRecordedAt().toString());
            out.add(m);
        }
        for (Loan l : loans.findByStatus(LoanStatus.LOST)) {
            if (in(l.getIssuedAt(), from, to)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", "LOST");
                m.put("loanId", l.getId());
                m.put("assetTag", l.getEquipment().getAssetTag());
                m.put("student", l.getStudent().getFullName());
                m.put("at", l.getIssuedAt().toString());
                out.add(m);
            }
        }
        return out;
    }

    public List<Map<String, Object>> maintenanceHistory(Instant from, Instant to) {
        return maintenance.findBetween(from, to).stream().map(m -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", m.getId());
            r.put("assetTag", m.getEquipment().getAssetTag());
            r.put("name", m.getEquipment().getName());
            r.put("reason", m.getReason());
            r.put("startAt", m.getStartAt().toString());
            r.put("endAt", m.getEndAt() == null ? null : m.getEndAt().toString());
            r.put("completed", m.isCompleted());
            r.put("resolution", m.getResolutionNotes());
            return r;
        }).toList();
    }

    public Map<String, Object> overridesAndAdjustments(Instant from, Instant to) {
        List<Map<String, Object>> overrides = decisions.findBetween(from, to).stream().filter(AllocationDecision::isOverride).map(d -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("decisionId", d.getId());
            r.put("reservationId", d.getSelectedReservation().getId());
            r.put("assetTag", d.getEquipment().getAssetTag());
            r.put("decidedBy", d.getDecidedBy().getFullName());
            r.put("reason", d.getReason());
            r.put("candidates", d.getCandidateCount());
            r.put("at", d.getDecidedAt().toString());
            return r;
        }).toList();
        List<Map<String, Object>> adjustments = scoreHistory.findAdjustmentsBetween(from, to).stream().map(h -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", h.getId());
            r.put("student", h.getStudent().getFullName());
            r.put("points", h.getAdjustment());
            r.put("reason", h.getReason());
            r.put("by", h.getChangedBy() == null ? null : h.getChangedBy().getFullName());
            r.put("voided", h.isVoided());
            r.put("at", h.getCreatedAt().toString());
            return r;
        }).toList();
        long decisionsTotal = decisions.findBetween(from, to).size();
        return Map.of("allocationDecisions", decisionsTotal, "overrides", overrides, "scoreAdjustments", adjustments);
    }

    private static boolean in(Instant t, Instant from, Instant to) {
        return !t.isBefore(from) && t.isBefore(to);
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
