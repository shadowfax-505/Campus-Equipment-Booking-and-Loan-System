package edu.campus.equipment.service;

import edu.campus.equipment.common.ApiException;
import edu.campus.equipment.config.AppProperties;
import edu.campus.equipment.domain.AccountStatus;
import edu.campus.equipment.domain.RefreshToken;
import edu.campus.equipment.domain.Role;
import edu.campus.equipment.domain.User;
import edu.campus.equipment.repo.RefreshTokenRepository;
import edu.campus.equipment.repo.UserRepository;
import edu.campus.equipment.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/**
 * Short-lived JWT access tokens plus opaque, rotating refresh tokens stored only as SHA-256 hashes.
 * Reusing an already-rotated refresh token revokes every session of that user (theft detection).
 */
@Service
public class AuthService {

    public record Tokens(String accessToken, String refreshToken, long expiresIn, User user) {}

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AppProperties props;
    private final CreditScoreService scores;
    private final AuditService audit;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder encoder, JwtService jwt,
                       AppProperties props, CreditScoreService scores, AuditService audit, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.encoder = encoder;
        this.jwt = jwt;
        this.props = props;
        this.scores = scores;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Tokens register(String email, String password, String fullName, String institutionId, String department, String phone) {
        String e = email.trim().toLowerCase();
        if (users.existsByEmailIgnoreCase(e)) throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists");
        if (institutionId != null && !institutionId.isBlank() && users.existsByInstitutionId(institutionId.trim())) {
            throw ApiException.conflict("ID_TAKEN", "This student ID is already registered");
        }
        validatePassword(password);
        User u = new User();
        u.setEmail(e);
        u.setPasswordHash(encoder.encode(password));
        u.setFullName(fullName.trim());
        u.setInstitutionId(institutionId == null || institutionId.isBlank() ? null : institutionId.trim());
        u.setDepartment(department);
        u.setPhone(phone);
        u.setRole(Role.STUDENT);
        u.setCreatedAt(clock.instant());
        u = users.save(u);
        scores.profile(u);
        return issue(u);
    }

    @Transactional
    public Tokens login(String email, String password) {
        User u = users.findByEmailIgnoreCase(email.trim()).orElse(null);
        if (u == null || !encoder.matches(password, u.getPasswordHash())) {
            throw new BadCredentialsException("Incorrect email or password");
        }
        if (u.getStatus() != AccountStatus.ACTIVE) throw ApiException.forbidden("This account is " + u.getStatus().name().toLowerCase());
        return issue(u);
    }

    /** noRollbackFor: the theft-detection revocation must commit even though the call itself is refused. */
    @Transactional(noRollbackFor = ApiException.class)
    public Tokens refresh(String rawToken) {
        RefreshToken t = refreshTokens.findByTokenHash(JwtService.sha256(rawToken))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH", "Session expired; please sign in again"));
        Instant now = clock.instant();
        if (t.getRevokedAt() != null) {
            refreshTokens.revokeAllForUser(t.getUser().getId(), now);
            audit.record(t.getUser(), "REFRESH_TOKEN_REUSE", "User", t.getUser().getId(), "All sessions revoked");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH", "Session expired; please sign in again");
        }
        if (t.getExpiresAt().isBefore(now)) throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH", "Session expired; please sign in again");
        User u = t.getUser();
        if (u.getStatus() != AccountStatus.ACTIVE) throw ApiException.forbidden("This account is " + u.getStatus().name().toLowerCase());
        t.setRevokedAt(now);
        return issue(u);
    }

    @Transactional
    public void logout(String rawToken) {
        refreshTokens.findByTokenHash(JwtService.sha256(rawToken)).ifPresent(t -> {
            if (t.getRevokedAt() == null) t.setRevokedAt(clock.instant());
        });
    }

    @Transactional
    public void changePassword(User u, String current, String next) {
        if (!encoder.matches(current, u.getPasswordHash())) throw new BadCredentialsException("Current password is incorrect");
        validatePassword(next);
        u.setPasswordHash(encoder.encode(next));
        users.save(u);
        refreshTokens.revokeAllForUser(u.getId(), clock.instant());
    }

    public static void validatePassword(String p) {
        if (p == null || p.length() < 8 || !p.matches(".*[A-Za-z].*") || !p.matches(".*\\d.*")) {
            throw ApiException.badRequest("WEAK_PASSWORD", "Passwords need at least 8 characters, including a letter and a digit");
        }
    }

    private Tokens issue(User u) {
        byte[] bytes = new byte[48];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        RefreshToken t = new RefreshToken();
        t.setUser(u);
        t.setTokenHash(JwtService.sha256(raw));
        t.setCreatedAt(clock.instant());
        t.setExpiresAt(clock.instant().plus(props.jwt().refreshTtl()));
        refreshTokens.save(t);
        return new Tokens(jwt.createAccessToken(u), raw, jwt.accessTtlSeconds(), u);
    }
}
