package edu.campus.equipment.security;

import edu.campus.equipment.config.AppProperties;
import edu.campus.equipment.domain.Role;
import edu.campus.equipment.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class JwtService {
    private final SecretKey key;
    private final AppProperties.Jwt cfg;
    private final Clock clock;

    public JwtService(AppProperties props, Clock clock) {
        this.cfg = props.jwt();
        this.clock = clock;
        byte[] secret = cfg.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("app.jwt.secret must be at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret);
    }

    public String createAccessToken(User user) {
        Instant now = clock.instant();
        return Jwts.builder()
                .issuer(cfg.issuer())
                .subject(String.valueOf(user.getId()))
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(cfg.accessTtl())))
                .signWith(key)
                .compact();
    }

    public long accessTtlSeconds() {
        return cfg.accessTtl().toSeconds();
    }

    public Optional<AuthUser> parse(String token) {
        try {
            Claims c = Jwts.parser().clock(() -> Date.from(clock.instant())).verifyWith(key).requireIssuer(cfg.issuer()).build()
                    .parseSignedClaims(token).getPayload();
            return Optional.of(new AuthUser(Long.valueOf(c.getSubject()), c.get("email", String.class),
                    Role.valueOf(c.get("role", String.class))));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
