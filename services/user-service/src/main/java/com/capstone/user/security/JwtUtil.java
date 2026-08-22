package com.capstone.user.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expirationMs;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                    @Value("${jwt.expiration-ms:3600000}") long expirationMs) {
        // Secret must be >= 256 bits for HS256; pad/derive if a short dev value is supplied.
        this.key = Keys.hmacShaKeyFor(pad(secret).getBytes());
        this.expirationMs = expirationMs;
    }

    private String pad(String secret) {
        StringBuilder sb = new StringBuilder(secret);
        while (sb.length() < 32) sb.append(secret);
        return sb.substring(0, 32);
    }

    public String generateToken(String email) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
                .subject(email)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    public String extractEmail(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload().getSubject();
    }
}
