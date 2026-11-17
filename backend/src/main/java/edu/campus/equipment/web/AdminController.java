package edu.campus.equipment.web;

import edu.campus.equipment.common.ApiException;
import edu.campus.equipment.domain.*;
import edu.campus.equipment.repo.*;
import edu.campus.equipment.security.CurrentUser;
import edu.campus.equipment.service.*;
import edu.campus.equipment.web.dto.Requests;
import edu.campus.equipment.web.dto.Views.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
@Tag(name = "Administration")
public class AdminController {
    private final CurrentUser current;
    private final AdminService admin;
    private final UserRepository users;
    private final BorrowingPolicyRepository policies;
    private final FineRepository fineRepo;
    private final FineService fines;
    private final DisputeService disputes;
    private final CreditScoreService scores;
    private final MaintenanceService maintenance;
    private final PolicyService policyService;
    private final ReportService reports;
    private final AuditLogRepository auditRepo;
    private final ViewMapper map;
    private final Clock clock;

    public AdminController(CurrentUser current, AdminService admin, UserRepository users, BorrowingPolicyRepository policies,
                           FineRepository fineRepo, FineService fines, DisputeService disputes, CreditScoreService scores,
                           MaintenanceService maintenance, PolicyService policyService, ReportService reports,
                           AuditLogRepository auditRepo, ViewMapper map, Clock clock) {
        this.current = current;
        this.admin = admin;
        this.users = users;
        this.policies = policies;
        this.fineRepo = fineRepo;
        this.fines = fines;
        this.disputes = disputes;
        this.scores = scores;
        this.maintenance = maintenance;
        this.policyService = policyService;
        this.reports = reports;
        this.auditRepo = auditRepo;
        this.map = map;
        this.clock = clock;
    }

    // ---- users ----

    @GetMapping("/users")
    public List<UserView> users(@RequestParam(required = false) Role role, @RequestParam(required = false) String q) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        return users.search(role, like, PageRequest.of(0, 500)).map(UserView::of).getContent();
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserView createUser(@RequestBody AdminService.UserCommand c) {
        return UserView.of(admin.createUser(current.get(), c));
    }

    @PutMapping("/users/{id}")
    public UserView updateUser(@PathVariable Long id, @RequestBody AdminService.UserCommand c) {
        return UserView.of(admin.updateUser(current.get(), id, c));
    }

    @GetMapping("/users/{id}/credit")
    public StudentController.CreditView studentCredit(@PathVariable Long id) {
        User s = users.findById(id).filter(u -> u.getRole() == Role.STUDENT).orElseThrow(() -> ApiException.notFound("Student"));
        return StudentController.creditView(s, scores);
    }

    @PostMapping("/users/{id}/credit/adjust")
    public StudentController.CreditView adjustScore(@PathVariable Long id, @Valid @RequestBody Requests.ScoreAdjust r) {
        User s = users.findById(id).orElseThrow(() -> ApiException.notFound("Student"));
        scores.adjust(s, r.points(), r.reason(), current.get());
        return StudentController.creditView(s, scores);
    }

    // ---- inventory ----

    @PostMapping("/equipment")
    @ResponseStatus(HttpStatus.CREATED)
    public EquipmentView createEquipment(@RequestBody AdminService.EquipmentCommand c) {
        return map.equipment(admin.saveEquipment(current.get(), null, c));
    }

    @PutMapping("/equipment/{id}")
    public EquipmentView updateEquipment(@PathVariable Long id, @RequestBody AdminService.EquipmentCommand c) {
        return map.equipment(admin.saveEquipment(current.get(), id, c));
    }

    @PostMapping("/equipment/{id}/status")
    public EquipmentView changeStatus(@PathVariable Long id, @Valid @RequestBody Requests.StatusChange r) {
        return map.equipment(maintenance.changeStatus(current.get(), id, r.status(), r.reason()));
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryView createCategory(@Valid @RequestBody Requests.CategoryReq r) {
        return CategoryView.of(admin.saveCategory(current.get(), null, r.name(), r.description()));
    }

    @PutMapping("/categories/{id}")
    public CategoryView updateCategory(@PathVariable Long id, @Valid @RequestBody Requests.CategoryReq r) {
        return CategoryView.of(admin.saveCategory(current.get(), id, r.name(), r.description()));
    }

    @PostMapping("/locations")
    @ResponseStatus(HttpStatus.CREATED)
    public LocationView createLocation(@Valid @RequestBody Requests.LocationReq r) {
        return LocationView.of(admin.saveLocation(current.get(), null, r.name(), r.department(), r.room()));
    }

    @PutMapping("/locations/{id}")
    public LocationView updateLocation(@PathVariable Long id, @Valid @RequestBody Requests.LocationReq r) {
        return LocationView.of(admin.saveLocation(current.get(), id, r.name(), r.department(), r.room()));
    }

    // ---- policies & scoring ----

    @GetMapping("/policies")
    public List<PolicyView> policies() {
        return policies.findAll().stream().map(PolicyView::of).toList();
    }

    @PostMapping("/policies")
    @ResponseStatus(HttpStatus.CREATED)
    public PolicyView createPolicy(@RequestBody BorrowingPolicy p) {
        return PolicyView.of(admin.savePolicy(current.get(), null, p));
    }

    @PutMapping("/policies/{id}")
    public PolicyView updatePolicy(@PathVariable Long id, @RequestBody BorrowingPolicy p) {
        return PolicyView.of(admin.savePolicy(current.get(), id, p));
    }

    @GetMapping("/credit-config")
    public CreditScoreConfig scoreConfig() {
        return policyService.scoreConfig();
    }

    @PutMapping("/credit-config")
    public CreditScoreConfig updateScoreConfig(@RequestBody CreditScoreConfig c) {
        return admin.saveScoreConfig(current.get(), c);
    }

    // ---- fines ----

    @GetMapping("/fines")
    public List<FineView> allFines(@RequestParam(defaultValue = "false") boolean outstandingOnly) {
        List<Fine> list = outstandingOnly ? fineRepo.findAllPayable() : fineRepo.findAll();
        return list.stream().sorted(Comparator.comparing(Fine::getAssessedAt).reversed()).map(map::fine).toList();
    }

    @PostMapping("/fines/{id}/waive")
    public FineView waive(@PathVariable Long id, @Valid @RequestBody Requests.Reason r) {
        Fine f = fineRepo.findById(id).orElseThrow(() -> ApiException.notFound("Fine"));
        return map.fine(fines.waive(f, r.reason(), current.get()));
    }

    @PostMapping("/fines/{id}/correct")
    public FineView correct(@PathVariable Long id, @Valid @RequestBody Requests.FineCorrection r) {
        Fine f = fineRepo.findById(id).orElseThrow(() -> ApiException.notFound("Fine"));
        return map.fine(fines.correct(f, r.amount(), r.reason(), current.get()));
    }

    // ---- disputes ----

    @GetMapping("/disputes")
    public List<DisputeView> disputes() {
        return disputes.all().stream().map(DisputeView::of).toList();
    }

    @PostMapping("/disputes/{id}/review")
    public DisputeView review(@PathVariable Long id) {
        return DisputeView.of(disputes.startReview(current.get(), id));
    }

    @PostMapping("/disputes/{id}/resolve")
    public DisputeView resolve(@PathVariable Long id, @Valid @RequestBody Requests.ResolveDispute r) {
        return DisputeView.of(disputes.resolve(current.get(), id,
                new DisputeService.Resolution(r.upheld(), r.resolution(), r.correctedAmount(), r.waiveRelatedCharges())));
    }

    // ---- reports & audit ----

    @GetMapping("/reports/{name}")
    public Object report(@PathVariable String name, @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        Instant t = to == null ? clock.instant() : to;
        Instant f = from == null ? t.minus(Duration.ofDays(30)) : from;
        return switch (name) {
            case "utilization" -> reports.utilization(f, t);
            case "activity" -> reports.activity(f, t);
            case "most-requested" -> reports.mostRequested(f, t, 10);
            case "overdue" -> reports.overdue(f, t);
            case "finances" -> reports.finances(f, t);
            case "incidents" -> reports.incidents(f, t);
            case "maintenance" -> reports.maintenanceHistory(f, t);
            case "overrides" -> reports.overridesAndAdjustments(f, t);
            default -> throw ApiException.notFound("Report " + name);
        };
    }

    @GetMapping("/audit")
    public List<AuditView> audit(@RequestParam(required = false) String entityType, @RequestParam(required = false) String action,
                                 @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "100") int size) {
        return auditRepo.search(blankToNull(entityType), blankToNull(action), PageRequest.of(page, Math.min(size, 500)))
                .map(AuditView::of).getContent();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
