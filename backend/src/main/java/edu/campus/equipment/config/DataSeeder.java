package edu.campus.equipment.config;

import edu.campus.equipment.domain.*;
import edu.campus.equipment.repo.*;
import edu.campus.equipment.service.CreditScoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Ensures the reference rows the system cannot run without (default policy, scoring config, first administrator)
 * and, when app.seed-demo-data=true on an empty database, loads a small demo campus.
 */
@Component
public class DataSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final UserRepository users;
    private final CategoryRepository categories;
    private final LocationRepository locations;
    private final BorrowingPolicyRepository policies;
    private final CreditScoreConfigRepository scoreConfigs;
    private final EquipmentRepository equipment;
    private final ReservationRepository reservations;
    private final LoanRepository loans;
    private final ConditionReportRepository reports;
    private final FineRepository fines;
    private final CreditScoreService scores;
    private final PasswordEncoder encoder;
    private final AppProperties props;
    private final Clock clock;
    private final String adminEmail;
    private final String adminPassword;

    public DataSeeder(UserRepository users, CategoryRepository categories, LocationRepository locations,
                      BorrowingPolicyRepository policies, CreditScoreConfigRepository scoreConfigs, EquipmentRepository equipment,
                      ReservationRepository reservations, LoanRepository loans, ConditionReportRepository reports,
                      FineRepository fines, CreditScoreService scores, PasswordEncoder encoder, AppProperties props, Clock clock,
                      @Value("${app.bootstrap-admin.email:admin@campus.edu}") String adminEmail,
                      @Value("${app.bootstrap-admin.password:Admin12345}") String adminPassword) {
        this.users = users;
        this.categories = categories;
        this.locations = locations;
        this.policies = policies;
        this.scoreConfigs = scoreConfigs;
        this.equipment = equipment;
        this.reservations = reservations;
        this.loans = loans;
        this.reports = reports;
        this.fines = fines;
        this.scores = scores;
        this.encoder = encoder;
        this.props = props;
        this.clock = clock;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (scoreConfigs.findById(1L).isEmpty()) scoreConfigs.save(new CreditScoreConfig());
        BorrowingPolicy standard = policies.findFirstByDefaultPolicyTrue().orElseGet(() -> {
            BorrowingPolicy p = new BorrowingPolicy();
            p.setName("Standard");
            p.setDefaultPolicy(true);
            return policies.save(p);
        });
        if (users.findByRole(Role.ADMIN).isEmpty()) {
            User a = user(adminEmail, adminPassword, "System Administrator", null, Role.ADMIN);
            log.warn("Created bootstrap administrator {} - change its password after first sign-in", adminEmail);
            users.save(a);
        }
        if (props.seedDemoData() && equipment.count() == 0) seedDemo(standard);
    }

    private User user(String email, String password, String name, String instId, Role role) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(encoder.encode(password));
        u.setFullName(name);
        u.setInstitutionId(instId);
        u.setRole(role);
        u.setCreatedAt(clock.instant());
        return u;
    }

    private void seedDemo(BorrowingPolicy standard) {
        log.info("Seeding demo data");
        Instant now = clock.instant();

        BorrowingPolicy premium = new BorrowingPolicy();
        premium.setName("High-value instruments");
        premium.setMaxLoanHours(48);
        premium.setDailyOverdueRate(new BigDecimal("200.00"));
        premium.setFineCap(new BigDecimal("2000.00"));
        premium.setGracePeriodMinutes(30);
        premium.setMaxExtensions(1);
        premium = policies.save(premium);

        Category cameras = cat("Cameras", "DSLR and mirrorless cameras, lenses and video gear");
        Category laptops = cat("Laptops", "Portable computers for coursework and fieldwork");
        Category projectors = cat("Projectors", "Portable projectors and presentation kits");
        Category instruments = cat("Lab instruments", "Oscilloscopes, multimeters, signal generators");

        Location media = loc("Media Lab", "Journalism & Media", "B-104");
        Location cse = loc("CSE Hardware Lab", "Computer Science & Engineering", "C-310");
        Location eee = loc("EEE Instrument Store", "Electrical & Electronic Engineering", "D-021");

        User staffMedia = user("staff.media@campus.edu", "Staff12345", "Grace Kim", "ST-1001", Role.STAFF);
        staffMedia.setManagedLocations(new HashSet<>(List.of(media)));
        staffMedia.setPermissions(new HashSet<>(List.of(StaffPermission.VERIFY_PAYMENTS, StaffPermission.ASSESS_CHARGES)));
        User staffLab = user("staff.lab@campus.edu", "Staff12345", "Omar Haddad", "ST-1002", Role.STAFF);
        staffLab.setManagedLocations(new HashSet<>(List.of(cse, eee)));
        staffLab.setPermissions(EnumSet.allOf(StaffPermission.class));
        users.saveAll(List.of(staffMedia, staffLab));

        List<User> students = new ArrayList<>();
        String[][] people = {
                {"alice@student.campus.edu", "Alice Moreau", "2021-1-60-001"},
                {"bilal@student.campus.edu", "Bilal Chowdhury", "2021-1-60-002"},
                {"chen@student.campus.edu", "Chen Wei", "2022-2-60-014"},
                {"daniela@student.campus.edu", "Daniela Souza", "2023-1-60-027"}};
        for (String[] p : people) {
            User s = user(p[0], "Student123", p[1], p[2], Role.STUDENT);
            s.setDepartment("Computer Science & Engineering");
            students.add(users.save(s));
        }

        Equipment cam1 = eq("CAM-001", "Canon EOS R6", "Full-frame mirrorless camera, 24-105mm kit lens", cameras, media, standard, "SN-R6-88412",
                List.of("Battery x2", "Charger", "24-105mm lens", "Camera bag"));
        Equipment cam2 = eq("CAM-002", "Sony A7 III", "Full-frame mirrorless camera for photo and 4K video", cameras, media, standard, "SN-A7-12093",
                List.of("Battery", "Charger", "28-70mm lens"));
        eq("CAM-003", "GoPro HERO12", "Action camera with mounts", cameras, media, standard, "SN-GP-55120", List.of("Chest mount", "Battery x2"));
        Equipment lap1 = eq("LAP-001", "Dell XPS 15", "i7, 32 GB RAM, RTX 4050 - for ML coursework", laptops, cse, standard, "SN-XPS-4411",
                List.of("Charger", "Sleeve"));
        eq("LAP-002", "MacBook Air M3", "13-inch, 16 GB RAM", laptops, cse, standard, "SN-MBA-7702", List.of("USB-C charger"));
        eq("LAP-003", "Lenovo ThinkPad T14", "Linux-ready laptop", laptops, cse, standard, "SN-TP-3301", List.of("Charger"));
        eq("PRJ-001", "Epson EB-W51 Projector", "Portable 4000-lumen projector", projectors, media, standard, "SN-EP-1120",
                List.of("HDMI cable", "Power cable", "Remote", "Carry case"));
        Equipment osc = eq("OSC-001", "Rigol DS1104Z Oscilloscope", "4-channel 100 MHz digital oscilloscope", instruments, eee, premium, "SN-RG-9001",
                List.of("Probe x4", "Power cable"));
        eq("MUL-001", "Fluke 87V Multimeter", "True-RMS industrial multimeter", instruments, eee, standard, "SN-FL-4410", List.of("Test leads", "Holster"));
        Equipment maint = eq("PRJ-002", "BenQ MW560 Projector", "Projector awaiting lamp replacement", projectors, media, standard, "SN-BQ-2201",
                List.of("HDMI cable", "Power cable"));
        maint.setStatus(OperationalStatus.UNDER_MAINTENANCE);

        // History so the scores differ: Alice has a clean record, Bilal returned late once.
        pastLoan(students.get(0), lap1, now.minus(Duration.ofDays(40)), Duration.ofHours(48), Duration.ZERO, staffLab);
        pastLoan(students.get(0), cam2, now.minus(Duration.ofDays(25)), Duration.ofHours(24), Duration.ZERO, staffMedia);
        pastLoan(students.get(0), osc, now.minus(Duration.ofDays(12)), Duration.ofHours(24), Duration.ZERO, staffLab);
        Loan late = pastLoan(students.get(1), cam1, now.minus(Duration.ofDays(20)), Duration.ofHours(24), Duration.ofHours(30), staffMedia);
        Fine f = new Fine();
        f.setStudent(students.get(1));
        f.setLoan(late);
        f.setType(FineType.LATE_RETURN);
        f.setDedupeKey("LATE_RETURN:loan:" + late.getId());
        f.setAmount(new BigDecimal("100.00"));
        f.setPaidAmount(new BigDecimal("100.00"));
        f.setStatus(FineStatus.PAID);
        f.setDescription("Late return of Canon EOS R6 (CAM-001): 2 started day(s) late x 50.00 (grace 60 min, no cap)");
        f.setAssessedAt(late.getReturnedAt());
        f.setSettledAt(late.getReturnedAt().plus(Duration.ofDays(2)));
        fines.save(f);

        for (User s : students) scores.recalculate(s, ScoreEventType.RECALCULATION, "Demo history loaded", null, null, null, null);
    }

    private Loan pastLoan(User s, Equipment e, Instant start, Duration length, Duration late, User staff) {
        Reservation r = new Reservation();
        r.setStudent(s);
        r.setEquipment(e);
        r.setStartAt(start);
        r.setEndAt(start.plus(length));
        r.setStatus(ReservationStatus.FULFILLED);
        r.setCreatedAt(start.minus(Duration.ofDays(2)));
        r.setDecisionWindowEndsAt(start.minus(Duration.ofDays(2)).plus(Duration.ofHours(1)));
        r.setDecidedAt(start.minus(Duration.ofDays(1)));
        r.setDecidedBy(staff);
        r.setCollectionDeadline(start.plus(Duration.ofHours(4)));
        r = reservations.save(r);
        Loan l = new Loan();
        l.setReservation(r);
        l.setStudent(s);
        l.setEquipment(e);
        l.setStatus(LoanStatus.RETURNED);
        l.setIssuedAt(start);
        l.setIssuedBy(staff);
        l.setDueAt(r.getEndAt());
        l.setOriginalDueAt(r.getEndAt());
        l.setReturnedAt(r.getEndAt().plus(late).minus(late.isZero() ? Duration.ofHours(1) : Duration.ZERO));
        l.setReceivedBy(staff);
        l.setAppliedDailyRate(e.effectiveDailyRate());
        l.setAppliedGraceMinutes(e.getPolicy().getGracePeriodMinutes());
        l.setAppliedFineCap(e.getPolicy().getFineCap());
        l.setAppliedPolicyName(e.getPolicy().getName());
        l = loans.save(l);
        for (ConditionReportType t : ConditionReportType.values()) {
            ConditionReport c = new ConditionReport();
            c.setLoan(l);
            c.setType(t);
            c.setCondition(EquipmentCondition.GOOD);
            c.setAccessoriesPresent(new HashSet<>(e.getAccessories()));
            c.setRecordedBy(staff);
            c.setRecordedAt(t == ConditionReportType.ISSUE ? l.getIssuedAt() : l.getReturnedAt());
            reports.save(c);
        }
        return l;
    }

    private Category cat(String name, String desc) {
        Category c = new Category();
        c.setName(name);
        c.setDescription(desc);
        return categories.save(c);
    }

    private Location loc(String name, String dept, String room) {
        Location l = new Location();
        l.setName(name);
        l.setDepartment(dept);
        l.setRoom(room);
        return locations.save(l);
    }

    private Equipment eq(String tag, String name, String desc, Category c, Location l, BorrowingPolicy p, String serial, List<String> acc) {
        Equipment e = new Equipment();
        e.setAssetTag(tag);
        e.setName(name);
        e.setDescription(desc);
        e.setCategory(c);
        e.setLocation(l);
        e.setPolicy(p);
        e.setSerialNumber(serial);
        e.setAccessories(new ArrayList<>(acc));
        e.setCondition(EquipmentCondition.GOOD);
        e.setCreatedAt(clock.instant());
        return equipment.save(e);
    }
}
