package edu.campus.equipment.service;

import edu.campus.equipment.common.ApiException;
import edu.campus.equipment.domain.*;
import edu.campus.equipment.repo.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;

/** Administrator management of users, inventory, policies and scoring configuration. */
@Service
public class AdminService {

    public record UserCommand(String email, String password, String fullName, String institutionId, String department,
                              String phone, Role role, AccountStatus status, Set<StaffPermission> permissions, Set<Long> locationIds) {}

    public record EquipmentCommand(String assetTag, String name, String description, Long categoryId, Long locationId,
                                   String serialNumber, EquipmentCondition condition, Long policyId, BigDecimal overdueRateOverride,
                                   String borrowingNotes, List<String> accessories, String imageUrl) {}

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final EquipmentRepository equipment;
    private final CategoryRepository categories;
    private final LocationRepository locations;
    private final BorrowingPolicyRepository policies;
    private final CreditScoreConfigRepository scoreConfigs;
    private final CreditScoreService scores;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final Clock clock;

    public AdminService(UserRepository users, RefreshTokenRepository refreshTokens, EquipmentRepository equipment,
                        CategoryRepository categories, LocationRepository locations, BorrowingPolicyRepository policies,
                        CreditScoreConfigRepository scoreConfigs, CreditScoreService scores, PasswordEncoder encoder,
                        AuditService audit, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.equipment = equipment;
        this.categories = categories;
        this.locations = locations;
        this.policies = policies;
        this.scoreConfigs = scoreConfigs;
        this.scores = scores;
        this.encoder = encoder;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- users ----

    @Transactional
    public User createUser(User admin, UserCommand c) {
        if (c.email() == null || c.fullName() == null || c.role() == null) throw ApiException.badRequest("MISSING_FIELDS", "Email, name and role are required");
        if (users.existsByEmailIgnoreCase(c.email())) throw ApiException.conflict("EMAIL_TAKEN", "Email already in use");
        AuthService.validatePassword(c.password());
        User u = new User();
        u.setEmail(c.email().trim().toLowerCase());
        u.setPasswordHash(encoder.encode(c.password()));
        u.setCreatedAt(clock.instant());
        applyUser(u, c);
        u = users.save(u);
        if (u.getRole() == Role.STUDENT) scores.profile(u);
        audit.record(admin, "USER_CREATED", "User", u.getId(), u.getEmail() + " as " + u.getRole());
        return u;
    }

    @Transactional
    public User updateUser(User admin, Long id, UserCommand c) {
        User u = users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
        if (u.getId().equals(admin.getId()) && (c.role() != null && c.role() != Role.ADMIN || c.status() != null && c.status() != AccountStatus.ACTIVE)) {
            throw ApiException.badRequest("SELF_LOCKOUT", "You cannot remove your own administrator access");
        }
        String before = u.getRole() + "/" + u.getStatus() + "/" + u.getPermissions();
        applyUser(u, c);
        if (c.password() != null && !c.password().isBlank()) {
            AuthService.validatePassword(c.password());
            u.setPasswordHash(encoder.encode(c.password()));
        }
        if (u.getStatus() != AccountStatus.ACTIVE || c.password() != null) refreshTokens.revokeAllForUser(u.getId(), clock.instant());
        if (u.getRole() == Role.STUDENT) scores.profile(u);
        audit.record(admin, "USER_UPDATED", "User", u.getId(), before + " -> " + u.getRole() + "/" + u.getStatus() + "/" + u.getPermissions());
        return u;
    }

    private void applyUser(User u, UserCommand c) {
        if (c.fullName() != null) u.setFullName(c.fullName().trim());
        if (c.institutionId() != null) u.setInstitutionId(c.institutionId().isBlank() ? null : c.institutionId().trim());
        if (c.department() != null) u.setDepartment(c.department());
        if (c.phone() != null) u.setPhone(c.phone());
        if (c.role() != null) u.setRole(c.role());
        if (c.status() != null) u.setStatus(c.status());
        if (c.permissions() != null) u.setPermissions(new HashSet<>(c.permissions()));
        if (c.locationIds() != null) u.setManagedLocations(new HashSet<>(locations.findAllById(c.locationIds())));
    }

    // ---- inventory ----

    @Transactional
    public Equipment saveEquipment(User admin, Long id, EquipmentCommand c) {
        Equipment e = id == null ? new Equipment() : equipment.findById(id).orElseThrow(() -> ApiException.notFound("Equipment"));
        if (id == null || (c.assetTag() != null && !c.assetTag().equalsIgnoreCase(e.getAssetTag()))) {
            if (c.assetTag() == null || c.assetTag().isBlank()) throw ApiException.badRequest("ASSET_TAG_REQUIRED", "Asset ID is required");
            if (equipment.existsByAssetTagIgnoreCase(c.assetTag())) throw ApiException.conflict("ASSET_TAG_TAKEN", "Asset ID already exists");
            e.setAssetTag(c.assetTag().trim().toUpperCase());
        }
        if (c.name() != null) e.setName(c.name());
        if (c.description() != null) e.setDescription(c.description());
        if (c.categoryId() != null) e.setCategory(categories.findById(c.categoryId()).orElseThrow(() -> ApiException.notFound("Category")));
        if (c.locationId() != null) e.setLocation(locations.findById(c.locationId()).orElseThrow(() -> ApiException.notFound("Location")));
        if (c.serialNumber() != null) e.setSerialNumber(c.serialNumber());
        if (c.condition() != null) e.setCondition(c.condition());
        if (c.policyId() != null) e.setPolicy(policies.findById(c.policyId()).orElseThrow(() -> ApiException.notFound("Policy")));
        if (e.getPolicy() == null) e.setPolicy(policies.findFirstByDefaultPolicyTrue().orElseThrow());
        e.setOverdueRateOverride(c.overdueRateOverride());
        if (c.borrowingNotes() != null) e.setBorrowingNotes(c.borrowingNotes());
        if (c.accessories() != null) e.setAccessories(new ArrayList<>(c.accessories()));
        if (c.imageUrl() != null) e.setImageUrl(c.imageUrl());
        if (e.getName() == null || e.getCategory() == null || e.getLocation() == null) {
            throw ApiException.badRequest("MISSING_FIELDS", "Name, category and location are required");
        }
        if (id == null) e.setCreatedAt(clock.instant());
        e = equipment.save(e);
        audit.record(admin, id == null ? "EQUIPMENT_CREATED" : "EQUIPMENT_UPDATED", "Equipment", e.getId(), e.getAssetTag());
        return e;
    }

    @Transactional
    public Category saveCategory(User admin, Long id, String name, String description) {
        Category c = id == null ? new Category() : categories.findById(id).orElseThrow(() -> ApiException.notFound("Category"));
        c.setName(name);
        c.setDescription(description);
        c = categories.save(c);
        audit.record(admin, "CATEGORY_SAVED", "Category", c.getId(), name);
        return c;
    }

    @Transactional
    public Location saveLocation(User admin, Long id, String name, String department, String room) {
        Location l = id == null ? new Location() : locations.findById(id).orElseThrow(() -> ApiException.notFound("Location"));
        l.setName(name);
        l.setDepartment(department);
        l.setRoom(room);
        l = locations.save(l);
        audit.record(admin, "LOCATION_SAVED", "Location", l.getId(), name);
        return l;
    }

    @Transactional
    public BorrowingPolicy savePolicy(User admin, Long id, BorrowingPolicy in) {
        BorrowingPolicy p = id == null ? new BorrowingPolicy() : policies.findById(id).orElseThrow(() -> ApiException.notFound("Policy"));
        if (in.getMaxLoanHours() <= 0 || in.getMaxActiveBookings() <= 0 || in.getDailyOverdueRate() == null
                || in.getDailyOverdueRate().signum() < 0 || in.getGracePeriodMinutes() < 0 || in.getDecisionWindowMinutes() < 0
                || (in.getFineCap() != null && in.getFineCap().signum() < 0)) {
            throw ApiException.badRequest("INVALID_POLICY", "Policy values must be positive");
        }
        String before = id == null ? "new" : describe(p);
        p.setName(in.getName());
        p.setMaxLoanHours(in.getMaxLoanHours());
        p.setMinLoanMinutes(in.getMinLoanMinutes());
        p.setMaxAdvanceDays(in.getMaxAdvanceDays());
        p.setMaxActiveBookings(in.getMaxActiveBookings());
        p.setDailyOverdueRate(in.getDailyOverdueRate());
        p.setGracePeriodMinutes(in.getGracePeriodMinutes());
        p.setFineCap(in.getFineCap());
        p.setDecisionWindowMinutes(in.getDecisionWindowMinutes());
        p.setCollectionWindowHours(in.getCollectionWindowHours());
        p.setEarlyCollectionMinutes(in.getEarlyCollectionMinutes());
        p.setCancellationCutoffHours(in.getCancellationCutoffHours());
        p.setMaxExtensions(in.getMaxExtensions());
        p.setWaitlistUnsuccessful(in.isWaitlistUnsuccessful());
        p.setDisputedFinesBlock(in.isDisputedFinesBlock());
        p.setOverdueLoanBlocks(in.isOverdueLoanBlocks());
        if (in.isDefaultPolicy() && !p.isDefaultPolicy()) {
            policies.findFirstByDefaultPolicyTrue().ifPresent(old -> old.setDefaultPolicy(false));
            p.setDefaultPolicy(true);
        }
        p = policies.save(p);
        audit.record(admin, "POLICY_SAVED", "BorrowingPolicy", p.getId(), before + " -> " + describe(p));
        return p;
    }

    private static String describe(BorrowingPolicy p) {
        return String.format("%s: maxHours=%d limit=%d rate=%s grace=%d cap=%s window=%d", p.getName(), p.getMaxLoanHours(),
                p.getMaxActiveBookings(), p.getDailyOverdueRate(), p.getGracePeriodMinutes(), p.getFineCap(), p.getDecisionWindowMinutes());
    }

    @Transactional
    public CreditScoreConfig saveScoreConfig(User admin, CreditScoreConfig in) {
        double sum = in.getWeightPunctuality() + in.getWeightCare() + in.getWeightPayment() + in.getWeightReliability() + in.getWeightHistory();
        if (Math.abs(sum - 1.0) > 0.001) throw ApiException.badRequest("INVALID_WEIGHTS", "Weights must add up to 1.0 (currently " + sum + ")");
        if (in.getNeutralValue() < 0 || in.getNeutralValue() > 100 || in.getRecentHistoryDays() < 1) {
            throw ApiException.badRequest("INVALID_CONFIG", "Neutral value must be 0-100 and the history period at least one day");
        }
        CreditScoreConfig c = scoreConfigs.findById(1L).orElseGet(CreditScoreConfig::new);
        String before = String.format("w=%.2f/%.2f/%.2f/%.2f/%.2f neutral=%.1f days=%d", c.getWeightPunctuality(), c.getWeightCare(),
                c.getWeightPayment(), c.getWeightReliability(), c.getWeightHistory(), c.getNeutralValue(), c.getRecentHistoryDays());
        c.setWeightPunctuality(in.getWeightPunctuality());
        c.setWeightCare(in.getWeightCare());
        c.setWeightPayment(in.getWeightPayment());
        c.setWeightReliability(in.getWeightReliability());
        c.setWeightHistory(in.getWeightHistory());
        c.setNeutralValue(in.getNeutralValue());
        c.setRecentHistoryDays(in.getRecentHistoryDays());
        c.setLatePenaltyPerDay(in.getLatePenaltyPerDay());
        c.setPromptPaymentDays(in.getPromptPaymentDays());
        c.setHistoryTarget(in.getHistoryTarget());
        c.setSignificantChange(in.getSignificantChange());
        c.setUpdatedAt(clock.instant());
        c = scoreConfigs.save(c);
        audit.record(admin, "SCORE_CONFIG_CHANGED", "CreditScoreConfig", 1L, before + " -> " + String.format(
                "w=%.2f/%.2f/%.2f/%.2f/%.2f neutral=%.1f days=%d", c.getWeightPunctuality(), c.getWeightCare(), c.getWeightPayment(),
                c.getWeightReliability(), c.getWeightHistory(), c.getNeutralValue(), c.getRecentHistoryDays()));
        scores.recalculateAll(ScoreEventType.CONFIG_CHANGED, "Scoring parameters were updated");
        return c;
    }
}
