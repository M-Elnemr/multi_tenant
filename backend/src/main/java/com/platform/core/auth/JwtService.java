package com.platform.core.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final SecretKey key;
    private final JwtProperties props;

    public JwtService(JwtProperties props) {
        this.props = props;
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(props.secret()));
    }

    /** Who a token belongs to: staff (core.users), a patient account or a shop client account - never interchangeable. */
    public record Subject(UUID id, String type) {}

    public String accessToken(UUID userId) { return accessToken(userId, "STAFF"); }

    public String accessToken(UUID id, String type) {
        Instant now = Instant.now();
        return Jwts.builder().subject(id.toString()).claim("typ", type).issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(props.accessTokenTtl()))).signWith(key).compact();
    }

    public long accessTtlSeconds() { return props.accessTokenTtl().toSeconds(); }

    public Optional<UUID> parse(String token) { return parseSubject(token).map(Subject::id); }

    public Optional<Subject> parseSubject(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            String type = c.get("typ", String.class);
            return Optional.of(new Subject(UUID.fromString(c.getSubject()), type == null ? "STAFF" : type));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
