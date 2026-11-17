package com.example.service.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * 无状态令牌：payload 为 {@code userId.expiryEpochSecond}，使用 HMAC-SHA256 签名。
 * 令牌只承载身份，角色与账户状态每次请求从数据库重新读取，确保禁用账户立即失效。
 */
@Component
public class TokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secret;
    private final Duration ttl;

    public TokenService(@Value("${app.auth.token-secret}") String secret,
                        @Value("${app.auth.token-ttl-minutes:480}") long ttlMinutes) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalStateException("app.auth.token-secret 长度不足，必须至少 16 个字符");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    public String issue(Long userId, Instant now) {
        String payload = userId + "." + now.plus(ttl).getEpochSecond();
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + sign(encoded);
    }

    /** 校验签名与有效期；失败返回 empty。 */
    public Optional<Long> verify(String token, Instant now) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        int separator = token.lastIndexOf('.');
        if (separator <= 0 || separator == token.length() - 1) {
            return Optional.empty();
        }
        String encoded = token.substring(0, separator);
        String signature = token.substring(separator + 1);
        if (!MessageDigest.isEqual(sign(encoded).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = payload.split("\\.");
            if (parts.length != 2) {
                return Optional.empty();
            }
            Long userId = Long.valueOf(parts[0]);
            long expiresAt = Long.parseLong(parts[1]);
            if (expiresAt <= now.getEpochSecond()) {
                return Optional.empty();
            }
            return Optional.of(userId);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("令牌签名失败", ex);
        }
    }
}
