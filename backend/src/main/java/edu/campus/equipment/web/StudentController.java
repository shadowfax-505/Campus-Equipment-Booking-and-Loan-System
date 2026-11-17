package edu.campus.equipment.web;

import edu.campus.equipment.common.ApiException;
import edu.campus.equipment.config.AppProperties;
import edu.campus.equipment.domain.*;
import edu.campus.equipment.repo.*;
import edu.campus.equipment.security.CurrentUser;
import edu.campus.equipment.service.*;
import edu.campus.equipment.service.payment.PaymentGateway;
import edu.campus.equipment.service.payment.PaymentService;
import edu.campus.equipment.web.dto.Requests;
import edu.campus.equipment.web.dto.Views.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;

/**
 * Endpoints acting on the signed-in user's own records. Ownership is enforced here and in the services,
 * so a student can never read or change another student's bookings, loans, fines or score.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "My account (students)")
public class StudentController {
    private final CurrentUser current;
    private final ReservationService reservations;
    private final LoanService loans;
    private final ExtensionService extensions;
    private final FineService fines;
    private final PaymentService payments;
    private final CreditScoreService scores;
    private final DisputeService disputes;
    private final EligibilityService eligibility;
    private final AuthService auth;
    private final NotificationRepository notificationRepo;
    private final AllocationDecisionRepository decisionRepo;
    private final UserRepository users;
    private final ViewMapper map;
    private final AppProperties props;
    private final Clock clock;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public StudentController(CurrentUser current, ReservationService reservations, LoanService loans, ExtensionService extensions,
                             FineService fines, PaymentService payments, CreditScoreService scores, DisputeService disputes,
                             EligibilityService eligibility, AuthService auth, NotificationRepository notificationRepo,
                             AllocationDecisionRepository decisionRepo, UserRepository users, ViewMapper map, AppProperties props,
                             Clock clock, com.fasterxml.jackson.databind.ObjectMapper json) {
        this.current = current;
        this.reservations = reservations;
        this.loans = loans;
        this.extensions = extensions;
        this.fines = fines;
        this.payments = payments;
        this.scores = scores;
        this.disputes = disputes;
        this.eligibility = eligibility;
        this.auth = auth;
        this.notificationRepo = notificationRepo;
        this.decisionRepo = decisionRepo;
        this.users = users;
        this.map = map;
        this.props = props;
        this.clock = clock;
        this.json = json;
    }

    // ---- profile ----

    @PatchMapping("/me")
    @Transactional
    @Operation(summary = "Update permitted profile fields (phone, department)")
    public UserView updateProfile(@Valid @RequestBody Requests.UpdateProfile r) {
        User u = current.get();
        if (r.phone() != null) u.setPhone(r.phone());
        if (r.department() != null) u.setDepartment(r.department());
        return UserView.of(users.save(u));
    }

    @PostMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody Requests.ChangePassword r) {
        auth.changePassword(current.get(), r.currentPassword(), r.newPassword());
    }

    @GetMapping("/me/eligibility")
    public EligibilityService.Result myEligibility() {
        return eligibility.check(current.get(), EligibilityService.Stage.REQUEST, null);
    }

    // ---- reservations ----

    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationView request(@Valid @RequestBody Requests.CreateReservation r) {
        return map.reservation(reservations.request(current.get(), r.equipmentId(), r.startAt(), r.endAt(), r.purpose()));
    }

    @GetMapping("/reservations")
    public List<ReservationView> myReservations() {
        return reservations.forStudent(current.get()).stream().map(map::reservation).toList();
    }

    @GetMapping("/reservations/{id}")
    public ReservationView reservation(@PathVariable Long id) {
        User u = current.get();
        Reservation r = reservations.get(id);
        current.requireSelfOrStaff(u, r.getStudent().getId());
        return map.reservation(r);
    }

    @PostMapping("/reservations/{id}/cancel")
    public ReservationView cancel(@PathVariable Long id, @RequestBody(required = false) Requests.Reason r) {
        return map.reservation(reservations.cancel(current.get(), id, r == null ? null : r.reason()));
    }

    public record MyAllocation(Long decisionId, boolean selected, int candidates, Integer myRank, double myScoreSnapshot,
                               boolean eligible, String exclusionReason, String explanation) {}

    @GetMapping("/reservations/{id}/allocation")
    @Operation(summary = "Your own allocation outcome", description = "Shows your rank and score snapshot only; other students' details are never exposed.")
    public MyAllocation allocation(@PathVariable Long id) throws Exception {
        User u = current.get();
        Reservation r = reservations.get(id);
        if (!r.getStudent().getId().equals(u.getId())) throw ApiException.forbidden("You can only view your own allocation outcome");
        if (r.getAllocationDecision() == null) throw ApiException.notFound("Allocation decision");
        AllocationDecision d = decisionRepo.findById(r.getAllocationDecision().getId()).orElseThrow();
        List<Map<String, Object>> rows = json.readValue(d.getCandidatesJson(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        Map<String, Object> mine = rows.stream().filter(m -> ((Number) m.get("reservationId")).longValue() == r.getId()).findFirst().orElse(Map.of());
        boolean selected = d.getSelectedReservation().getId().equals(r.getId());
        return new MyAllocation(d.getId(), selected, d.getCandidateCount(),
                mine.get("rank") == null ? null : ((Number) mine.get("rank")).intValue(),
                mine.get("scoreSnapshot") == null ? 0 : ((Number) mine.get("scoreSnapshot")).doubleValue(),
                Boolean.TRUE.equals(mine.get("eligible")), (String) mine.get("exclusionReason"),
                selected ? "Your request was selected." : r.getDecisionReason());
    }

    // ---- loans & extensions ----

    @GetMapping("/loans")
    public List<LoanView> myLoans() {
        return loans.forStudent(current.get()).stream().map(map::loan).toList();
    }

    @GetMapping("/loans/{id}")
    public LoanView loan(@PathVariable Long id) {
        User u = current.get();
        Loan l = loans.get(id);
        current.requireSelfOrStaff(u, l.getStudent().getId());
        return map.loan(l);
    }

    @PostMapping("/loans/{id}/extensions")
    @ResponseStatus(HttpStatus.CREATED)
    public ExtensionView requestExtension(@PathVariable Long id, @Valid @RequestBody Requests.ExtensionReq r) {
        return ExtensionView.of(extensions.request(current.get(), id, r.requestedDueAt(), r.reason()));
    }

    @GetMapping("/extensions")
    public List<ExtensionView> myExtensions() {
        return extensions.forStudent(current.get()).stream().map(ExtensionView::of).toList();
    }

    // ---- fines & payments ----

    public record FinesSummary(BigDecimal outstanding, boolean blocked, List<FineView> fines) {}

    @GetMapping("/fines")
    public FinesSummary myFines() {
        User u = current.get();
        return new FinesSummary(eligibility.outstandingBalance(u), eligibility.blockingBalance(u).signum() > 0,
                fines.forStudent(u).stream().map(map::fine).toList());
    }

    @GetMapping("/payments")
    public List<PaymentView> myPayments() {
        return payments.forStudent(current.get()).stream().map(map::payment).toList();
    }

    public record CheckoutResponse(PaymentView payment, String redirectUrl) {}

    @PostMapping("/payments/online")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start an online payment", description = "Creates a pending payment and returns the provider checkout. Fines change only when the provider confirms.")
    public CheckoutResponse payOnline(@Valid @RequestBody Requests.OnlinePayment r) {
        PaymentService.CheckoutResult c = payments.startOnline(current.get(), r.amount(), r.fineIds());
        return new CheckoutResponse(map.payment(c.payment()), c.redirectUrl());
    }

    @PostMapping("/payments/external")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Submit an externally made payment for staff verification", description = "Does not clear any fine until staff verify it.")
    public PaymentView payExternal(@Valid @RequestBody Requests.ExternalPayment r) {
        return map.payment(payments.submitExternal(current.get(), r.amount(), r.fineIds(), r.receiptReference(), r.notes(), r.evidenceUrl()));
    }

    @PostMapping("/payments/mock-checkout/{reference}")
    @Operation(summary = "Demo only: act as the mock provider's checkout page",
            description = "Available when app.payments.provider=mock. Sends the same callback a real provider would.")
    public PaymentView mockCheckout(@PathVariable String reference, @RequestParam(defaultValue = "true") boolean succeed) {
        if (!"mock".equals(props.payments().provider())) throw ApiException.notFound("Mock provider");
        User u = current.get();
        Payment p = payments.forStudent(u).stream().filter(x -> x.getReference().equals(reference)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Payment"));
        return map.payment(payments.handleProviderNotification(new PaymentGateway.Notification(reference, succeed, p.getAmount(),
                succeed ? null : "Card declined (simulated)")));
    }

    // ---- credit score ----

    public record CreditView(double score, Map<String, Double> components, Map<String, Double> weights,
                             Map<String, Double> contributions, double manualAdjustment, Map<String, Long> counts,
                             String explanation, List<ScoreHistoryView> history) {}

    @GetMapping("/credit")
    public CreditView myCredit() {
        return creditView(current.get(), scores);
    }

    static CreditView creditView(User u, CreditScoreService scores) {
        CreditProfile p = scores.profile(u);
        var f = scores.factors(u);
        var c = f.components();
        var cfg = f.config();
        Map<String, Double> comps = new LinkedHashMap<>();
        comps.put("punctuality", c.punctuality());
        comps.put("care", c.care());
        comps.put("paymentTimeliness", c.payment());
        comps.put("reliability", c.reliability());
        comps.put("history", c.history());
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("punctuality", cfg.getWeightPunctuality());
        weights.put("care", cfg.getWeightCare());
        weights.put("paymentTimeliness", cfg.getWeightPayment());
        weights.put("reliability", cfg.getWeightReliability());
        weights.put("history", cfg.getWeightHistory());
        Map<String, Double> contrib = new LinkedHashMap<>();
        comps.forEach((k, v) -> contrib.put(k, Math.round(v * weights.get(k) * 100.0) / 100.0));
        String explanation = String.format("Score = %.0f%% punctuality + %.0f%% care + %.0f%% payment timeliness + %.0f%% reliability + %.0f%% history. "
                        + "Only the last %d days count. Components with no history use the neutral value %.0f.",
                cfg.getWeightPunctuality() * 100, cfg.getWeightCare() * 100, cfg.getWeightPayment() * 100,
                cfg.getWeightReliability() * 100, cfg.getWeightHistory() * 100, cfg.getRecentHistoryDays(), cfg.getNeutralValue());
        return new CreditView(p.getScore(), comps, weights, contrib, c.manualAdjustment(), f.counts(), explanation,
                scores.history(u).stream().map(ScoreHistoryView::of).toList());
    }

    // ---- disputes ----

    @PostMapping("/disputes")
    @ResponseStatus(HttpStatus.CREATED)
    public DisputeView openDispute(@Valid @RequestBody Requests.OpenDispute r) {
        return DisputeView.of(disputes.open(current.get(), r.type(), r.subjectId(), r.description()));
    }

    @GetMapping("/disputes")
    public List<DisputeView> myDisputes() {
        return disputes.forStudent(current.get()).stream().map(DisputeView::of).toList();
    }

    // ---- notifications ----

    public record NotificationPage(long unread, List<NotificationView> items) {}

    @GetMapping("/notifications")
    public NotificationPage notifications(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size) {
        User u = current.get();
        return new NotificationPage(notificationRepo.countByUserIdAndReadAtIsNull(u.getId()),
                notificationRepo.findByUserIdOrderByCreatedAtDesc(u.getId(), PageRequest.of(page, Math.min(size, 100)))
                        .map(NotificationView::of).getContent());
    }

    @PostMapping("/notifications/{id}/read")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable Long id) {
        User u = current.get();
        Notification n = notificationRepo.findById(id).orElseThrow(() -> ApiException.notFound("Notification"));
        if (!n.getUser().getId().equals(u.getId())) throw ApiException.forbidden("Not your notification");
        if (n.getReadAt() == null) n.setReadAt(clock.instant());
    }

    @PostMapping("/notifications/read-all")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead() {
        notificationRepo.markAllRead(current.get().getId(), clock.instant());
    }

    // ---- dashboard ----

    public record StudentDashboard(List<LoanView> currentLoans, List<ReservationView> pendingAndUpcoming, FinesSummary fines,
                                   EligibilityService.Result eligibility, double score, List<ScoreHistoryView> recentScoreChanges,
                                   NotificationPage notifications) {}

    @GetMapping("/dashboard/student")
    public StudentDashboard dashboard() {
        User u = current.get();
        List<LoanView> active = loans.forStudent(u).stream().filter(l -> l.getStatus() == LoanStatus.ACTIVE).map(map::loan).toList();
        List<ReservationView> upcoming = reservations.forStudent(u).stream()
                .filter(r -> r.getStatus().isOpenRequest() || r.getStatus() == ReservationStatus.APPROVED)
                .sorted(Comparator.comparing(Reservation::getStartAt)).map(map::reservation).toList();
        return new StudentDashboard(active, upcoming, myFines(), eligibility.check(u, EligibilityService.Stage.REQUEST, null),
                scores.profile(u).getScore(), scores.history(u).stream().limit(5).map(ScoreHistoryView::of).toList(),
                notifications(0, 5));
    }
}
