package edu.campus.equipment.web;

import edu.campus.equipment.common.ApiException;
import edu.campus.equipment.domain.*;
import edu.campus.equipment.repo.*;
import edu.campus.equipment.security.CurrentUser;
import edu.campus.equipment.service.*;
import edu.campus.equipment.service.payment.PaymentService;
import edu.campus.equipment.web.dto.Requests;
import edu.campus.equipment.web.dto.Views.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/staff")
@Tag(name = "Lab staff")
public class StaffController {
    private final CurrentUser current;
    private final ReservationService reservations;
    private final LoanService loans;
    private final ExtensionService extensions;
    private final PaymentService payments;
    private final MaintenanceService maintenance;
    private final EligibilityService eligibility;
    private final CreditScoreService scores;
    private final FineService fines;
    private final PaymentRepository paymentRepo;
    private final UserRepository users;
    private final EquipmentRepository equipmentRepo;
    private final StaffScope scope;
    private final ViewMapper map;
    private final Clock clock;

    public StaffController(CurrentUser current, ReservationService reservations, LoanService loans, ExtensionService extensions,
                           PaymentService payments, MaintenanceService maintenance, EligibilityService eligibility,
                           CreditScoreService scores, FineService fines, PaymentRepository paymentRepo, UserRepository users,
                           EquipmentRepository equipmentRepo, StaffScope scope, ViewMapper map, Clock clock) {
        this.current = current;
        this.reservations = reservations;
        this.loans = loans;
        this.extensions = extensions;
        this.payments = payments;
        this.maintenance = maintenance;
        this.eligibility = eligibility;
        this.scores = scores;
        this.fines = fines;
        this.paymentRepo = paymentRepo;
        this.users = users;
        this.equipmentRepo = equipmentRepo;
        this.scope = scope;
        this.map = map;
        this.clock = clock;
    }

    // ---- request queue ----

    public record RankedEntryView(ReservationView reservation, double score, Integer rank, boolean eligible, List<String> reasons,
                                  boolean recommended) {}

    public record GroupView(EquipmentRef equipment, Instant windowClosesAt, boolean decidable, List<RankedEntryView> requests) {}

    @GetMapping("/requests")
    @Operation(summary = "Pending requests grouped by competing periods, ranked by credit priority")
    public List<GroupView> queue() {
        User staff = current.requireStaff();
        return reservations.queue(staff).stream().map(g -> new GroupView(EquipmentRef.of(g.equipment()), g.windowClosesAt(), g.decidable(),
                g.entries().stream().map(e -> new RankedEntryView(map.reservation(e.reservation()), e.score(), e.rank(), e.eligible(),
                        e.reasons(), g.decidable() && e.rank() != null && e.rank() == 1)).toList())).toList();
    }

    @GetMapping("/reservations")
    public List<ReservationView> byStatus(@RequestParam ReservationStatus status) {
        return reservations.byStatus(current.requireStaff(), status).stream().map(map::reservation).toList();
    }

    @PostMapping("/reservations/{id}/approve")
    public ReservationView approve(@PathVariable Long id, @Valid @RequestBody(required = false) Requests.Approve r) {
        return map.reservation(reservations.approve(current.requireStaff(), id, r != null && r.override(), r == null ? null : r.reason()));
    }

    @PostMapping("/reservations/{id}/reject")
    public ReservationView reject(@PathVariable Long id, @Valid @RequestBody Requests.Reason r) {
        return map.reservation(reservations.reject(current.requireStaff(), id, r.reason()));
    }

    @PostMapping("/reservations/{id}/cancel")
    public ReservationView cancel(@PathVariable Long id, @Valid @RequestBody Requests.Reason r) {
        if (r.reason() == null || r.reason().isBlank()) throw ApiException.badRequest("REASON_REQUIRED", "A reason is required");
        return map.reservation(reservations.cancel(current.requireStaff(), id, r.reason()));
    }

    @PostMapping("/reservations/{id}/clear-flag")
    public ReservationView clearFlag(@PathVariable Long id, @Valid @RequestBody Requests.Reason r) {
        return map.reservation(reservations.clearFlag(current.requireStaff(), id, r.reason()));
    }

    // ---- issue & return ----

    @PostMapping("/reservations/{id}/issue")
    public LoanView issue(@PathVariable Long id, @Valid @RequestBody Requests.Issue r) {
        return map.loan(loans.issue(current.requireStaff(), id, new LoanService.IssueCommand(r.condition(), r.accessoriesPresent(), r.notes())));
    }

    @GetMapping("/loans")
    public List<LoanView> activeLoans(@RequestParam(defaultValue = "false") boolean overdueOnly) {
        return loans.activeFor(current.requireStaff(), overdueOnly).stream().map(map::loan).toList();
    }

    @GetMapping("/loans/{id}")
    public LoanView loan(@PathVariable Long id) {
        User s = current.requireStaff();
        Loan l = loans.get(id);
        scope.require(s, l.getEquipment());
        return map.loan(l);
    }

    public record ReturnResponse(LoanView loan, FineView lateFee) {}

    @PostMapping("/loans/{id}/return")
    public ReturnResponse receiveReturn(@PathVariable Long id, @Valid @RequestBody Requests.Return r) {
        var res = loans.receiveReturn(current.requireStaff(), id, new LoanService.ReturnCommand(r.returnedAt(), r.condition(),
                r.accessoriesPresent(), r.damageVerified(), r.requiresRepair(), r.notes(), r.photoUrls()));
        return new ReturnResponse(map.loan(res.loan()), res.lateFee().map(map::fine).orElse(null));
    }

    @PostMapping("/loans/{id}/charges")
    public FineView charge(@PathVariable Long id, @Valid @RequestBody Requests.Charge r) {
        return map.fine(loans.assessCharge(current.requireStaff(), id, r.type(), r.amount(), r.assessment()));
    }

    @PostMapping("/loans/{id}/lost")
    public LoanView lost(@PathVariable Long id, @Valid @RequestBody Requests.Lost r) {
        return map.loan(loans.reportLost(current.requireStaff(), id, r.charge(), r.notes()));
    }

    // ---- extensions ----

    @GetMapping("/extensions")
    public List<ExtensionView> pendingExtensions() {
        return extensions.pending(current.requireStaff()).stream().map(ExtensionView::of).toList();
    }

    @PostMapping("/extensions/{id}/approve")
    public ExtensionView approveExtension(@PathVariable Long id, @Valid @RequestBody(required = false) Requests.ExtensionDecision r) {
        return ExtensionView.of(extensions.approve(current.requireStaff(), id, r != null && r.grantException(), r == null ? null : r.reason()));
    }

    @PostMapping("/extensions/{id}/reject")
    public ExtensionView rejectExtension(@PathVariable Long id, @Valid @RequestBody Requests.Reason r) {
        return ExtensionView.of(extensions.reject(current.requireStaff(), id, r.reason()));
    }

    // ---- payments ----

    @GetMapping("/payments/pending")
    public List<PaymentView> pendingPayments() {
        current.requireStaff();
        return paymentRepo.findByStatusOrderBySubmittedAt(PaymentStatus.PENDING_VERIFICATION).stream().map(map::payment).toList();
    }

    @PostMapping("/payments/{id}/verify")
    public PaymentView verify(@PathVariable Long id, @Valid @RequestBody Requests.VerifyPayment r) {
        return map.payment(payments.verifyExternal(id, r.approve(), r.note(), current.requireStaff()));
    }

    // ---- maintenance ----

    @PostMapping("/equipment/{id}/maintenance")
    public MaintenanceView startMaintenance(@PathVariable Long id, @Valid @RequestBody Requests.Maintenance r) {
        return MaintenanceView.of(maintenance.start(current.requireStaff(), id, r.reason(), r.startAt(), r.endAt()));
    }

    @GetMapping("/maintenance")
    public List<MaintenanceView> openMaintenance() {
        return maintenance.open(current.requireStaff()).stream().map(MaintenanceView::of).toList();
    }

    @GetMapping("/equipment/{id}/maintenance")
    public List<MaintenanceView> maintenanceHistory(@PathVariable Long id) {
        current.requireStaff();
        return maintenance.forEquipment(id).stream().map(MaintenanceView::of).toList();
    }

    @PostMapping("/maintenance/{id}/complete")
    public MaintenanceView completeMaintenance(@PathVariable Long id, @Valid @RequestBody Requests.CompleteMaintenance r) {
        return MaintenanceView.of(maintenance.complete(current.requireStaff(), id, r.notes(), r.condition()));
    }

    // ---- student lookup (borrowing history needed to manage a loan) ----

    public record StudentSummary(PersonRef student, String email, double score, EligibilityService.Result eligibility,
                                 List<LoanView> loans, List<ReservationView> reservations, List<FineView> fines) {}

    @GetMapping("/students/{id}")
    public StudentSummary student(@PathVariable Long id) {
        current.requireStaff();
        User s = users.findById(id).filter(u -> u.getRole() == Role.STUDENT).orElseThrow(() -> ApiException.notFound("Student"));
        return new StudentSummary(PersonRef.of(s), s.getEmail(), scores.profile(s).getScore(),
                eligibility.check(s, EligibilityService.Stage.REQUEST, null),
                loans.forStudent(s).stream().map(map::loan).toList(),
                reservations.forStudent(s).stream().map(map::reservation).toList(),
                fines.forStudent(s).stream().map(map::fine).toList());
    }

    // ---- dashboard ----

    public record StaffDashboard(int requestsAwaiting, int decidableGroups, List<GroupView> competing, List<ReservationView> awaitingCollection,
                                 List<LoanView> dueSoon, List<LoanView> overdue, List<ExtensionView> extensions,
                                 List<ReservationView> flagged, List<MaintenanceView> maintenance, int paymentsToVerify) {}

    @GetMapping("/dashboard")
    public StaffDashboard dashboard() {
        User s = current.requireStaff();
        Instant now = clock.instant();
        List<GroupView> groups = queue();
        List<ReservationView> approved = reservations.byStatus(s, ReservationStatus.APPROVED).stream().map(map::reservation).toList();
        List<Loan> active = loans.activeFor(s, false);
        return new StaffDashboard(
                groups.stream().mapToInt(g -> g.requests().size()).sum(),
                (int) groups.stream().filter(GroupView::decidable).count(),
                groups.stream().filter(g -> g.requests().size() > 1).toList(),
                approved,
                active.stream().filter(l -> !l.isOverdue(now) && l.getDueAt().isBefore(now.plusSeconds(48 * 3600))).map(map::loan).toList(),
                active.stream().filter(l -> l.isOverdue(now)).map(map::loan).toList(),
                extensions.pending(s).stream().map(ExtensionView::of).toList(),
                approved.stream().filter(ReservationView::flaggedForReview).toList(),
                maintenance.open(s).stream().map(MaintenanceView::of).toList(),
                s.hasPermission(StaffPermission.VERIFY_PAYMENTS) ? paymentRepo.findByStatusOrderBySubmittedAt(PaymentStatus.PENDING_VERIFICATION).size() : 0);
    }
}
