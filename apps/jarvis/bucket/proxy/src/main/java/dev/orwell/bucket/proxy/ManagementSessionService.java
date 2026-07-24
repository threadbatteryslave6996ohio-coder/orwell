package dev.orwell.bucket.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

@Component
public class ManagementSessionService {
    private final byte[] secret;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ManagementSessionService(ProxyProperties properties) {
        this.secret = properties.management().sessionSecret().getBytes(StandardCharsets.UTF_8);
    }

    public String createSession(String username, Instant expiresAt) {
        SessionPayload payload = new SessionPayload(username, expiresAt.getEpochSecond());
        try {
            byte[] json = objectMapper.writeValueAsBytes(payload);
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(json);
            return encoded + "." + sign(encoded);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot create session.", exception);
        }
    }

    /** Returns the session's username when the token is valid and unexpired, otherwise empty. */
    public Optional<String> validate(String token) {
        String[] parts = token.split("\\.", 2);
        if (parts.length != 2 || !SecureTokenUtils.constantTimeEquals(parts[1], sign(parts[0]))) {
            return Optional.empty();
        }

        try {
            SessionPayload payload = objectMapper.readValue(Base64.getUrlDecoder().decode(parts[0]), SessionPayload.class);
            if (Instant.now().getEpochSecond() >= payload.exp()) {
                return Optional.empty();
            }
            String username = payload.sub();
            return username != null && !username.isBlank() ? Optional.of(username) : Optional.empty();
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private String sign(String encodedPayload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot sign session.", exception);
        }
    }

    private record SessionPayload(String sub, long exp) {}
}
