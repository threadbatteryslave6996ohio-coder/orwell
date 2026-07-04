package dev.orwell.bucket.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

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

    public boolean tryValidate(String token, String[] usernameOut) {
        String[] parts = token.split("\\.", 2);
        if (parts.length != 2 || !constantTimeEquals(parts[1], sign(parts[0]))) {
            return false;
        }

        try {
            SessionPayload payload = objectMapper.readValue(Base64.getUrlDecoder().decode(parts[0]), SessionPayload.class);
            if (Instant.now().getEpochSecond() >= payload.exp()) {
                return false;
            }
            usernameOut[0] = payload.sub();
            return usernameOut[0] != null && !usernameOut[0].isBlank();
        } catch (Exception exception) {
            return false;
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

    private boolean constantTimeEquals(String left, String right) {
        byte[] l = left.getBytes(StandardCharsets.UTF_8);
        byte[] r = right.getBytes(StandardCharsets.UTF_8);
        if (l.length != r.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < l.length; i++) {
            diff |= l[i] ^ r[i];
        }
        return diff == 0;
    }

    private record SessionPayload(String sub, long exp) {}
}
