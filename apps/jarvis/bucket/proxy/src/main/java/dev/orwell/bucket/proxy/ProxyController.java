package dev.orwell.bucket.proxy;

import dev.orwell.auth.AuthenticationStrategy;
import dev.orwell.auth.BearerToken;
import dev.orwell.bucket.proxy.storage.BucketStorage;
import dev.orwell.bucket.proxy.storage.ObjectKeys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("${jarvis.server.route-prefix:}")
public class ProxyController {
    private final ProxyProperties properties;
    private final AuthServerClient authServerClient;
    private final AuthenticationStrategy authenticationStrategy;
    private final BucketStorage storage;
    private final FileAuditLogger audit;
    private final ManagementSessionService sessions;

    public ProxyController(
            ProxyProperties properties,
            AuthServerClient authServerClient,
            AuthenticationStrategy authenticationStrategy,
            BucketStorage storage,
            FileAuditLogger audit,
            ManagementSessionService sessions
    ) {
        this.properties = properties;
        this.authServerClient = authServerClient;
        this.authenticationStrategy = authenticationStrategy;
        this.storage = storage;
        this.audit = audit;
        this.sessions = sessions;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "healthy",
                "storageProvider", storage.provider(),
                "bucket", storage.containerName(),
                "region", storage.location(),
                "auth", "external-auth-server",
                "authServer", properties.authServer().baseUrl()
        );
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody ProxyLoginRequest login) {
        var result = authServerClient.login(login.username(), login.password());
        if (!result.success() || !StringUtils.hasText(result.token())) {
            int status = result.statusCode() >= 400 ? result.statusCode() : HttpStatus.UNAUTHORIZED.value();
            String error = status == HttpStatus.UNAUTHORIZED.value()
                    ? "Invalid username or password"
                    : "Auth server rejected login with HTTP " + status + ".";
            return ResponseEntity.status(status)
                    .body(Map.of("success", false, "error", error));
        }
        return ResponseEntity.ok(Map.of(
                "success", true,
                "clientId", result.clientId(),
                "token", result.token(),
                "tokenType", "Bearer"
        ));
    }

    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam(value = "folder", defaultValue = "uploads") String folder,
                                    @RequestParam(value = "fileName", required = false) String fileName,
                                    @RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestHeader(value = "X-Client-Id", required = false) String clientId) throws IOException {
        String user = authenticate(authorization, clientId);
        if (user == null) {
            return unauthorized();
        }
        Path temp = Files.createTempFile("bucket-upload-", ".bin");
        try {
            file.transferTo(temp);
            var result = storage.upload(temp, file.getContentType(), folder, StringUtils.hasText(fileName) ? fileName : file.getOriginalFilename());
            return ResponseEntity.ok(Map.of("success", true, "message", "File uploaded successfully", "key", result.key(), "etag", result.etag()));
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @PostMapping("/batch-upload")
    public ResponseEntity<?> batchUpload(@RequestParam("files") List<MultipartFile> files,
                                         @RequestParam(value = "folder", defaultValue = "uploads") String folder,
                                         @RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestHeader(value = "X-Client-Id", required = false) String clientId) throws IOException {
        String user = authenticate(authorization, clientId);
        if (user == null) {
            return unauthorized();
        }
        List<Map<String, Object>> uploaded = new ArrayList<>();
        for (MultipartFile file : files) {
            Path temp = Files.createTempFile("bucket-upload-", ".bin");
            try {
                file.transferTo(temp);
                var result = storage.upload(temp, file.getContentType(), folder, file.getOriginalFilename());
                uploaded.add(Map.of("key", result.key(), "etag", result.etag()));
            } finally {
                Files.deleteIfExists(temp);
            }
        }
        return ResponseEntity.ok(Map.of("success", true, "message", uploaded.size() + " files uploaded successfully", "files", uploaded));
    }

    @GetMapping("/list/{folder}")
    public ResponseEntity<?> list(@PathVariable("folder") String folder,
                                  @RequestHeader(value = "Authorization", required = false) String authorization,
                                  @RequestHeader(value = "X-Client-Id", required = false) String clientId) {
        String user = authenticate(authorization, clientId);
        if (user == null) {
            return unauthorized();
        }
        var files = storage.list(folder).stream().map(item -> Map.<String, Object>of(
                "key", item.key(),
                "size", item.size(),
                "lastModified", item.lastModified()
        )).toList();
        return ResponseEntity.ok(Map.of("success", true, "folder", folder.replaceAll("^/+|/+$", ""), "files", files));
    }

    @GetMapping("/metadata/{*key}")
    public ResponseEntity<?> metadata(@PathVariable("key") String key,
                                      @RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestHeader(value = "X-Client-Id", required = false) String clientId) {
        String user = authenticate(authorization, clientId);
        if (user == null) {
            return unauthorized();
        }
        String normalizedKey = ObjectKeys.normalizeKey(key);
        var metadata = storage.metadata(normalizedKey);
        if (!metadata.exists()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("success", true, "exists", false, "key", normalizedKey));
        }
        return ResponseEntity.ok(Map.of("success", true, "exists", true, "key", normalizedKey, "size", metadata.size(), "etag", metadata.etag(), "lastModified", metadata.lastModified()));
    }

    @DeleteMapping("/delete/{*key}")
    public ResponseEntity<?> delete(@PathVariable("key") String key,
                                    @RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestHeader(value = "X-Client-Id", required = false) String clientId) {
        String user = authenticate(authorization, clientId);
        if (user == null) {
            return unauthorized();
        }
        String normalizedKey = ObjectKeys.normalizeKey(key);
        storage.delete(normalizedKey);
        return ResponseEntity.ok(Map.of("success", true, "message", "File deleted successfully", "key", normalizedKey));
    }

    @GetMapping(value = "/admin", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> admin(@CookieValue(value = "s3proxy_admin", required = false) String token) {
        String[] username = new String[1];
        if (token == null || !sessions.tryValidate(token, username)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(adminLoginPage(""));
        }
        return ResponseEntity.ok(adminDashboard(username[0], ""));
    }

    @PostMapping(value = "/admin/login", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> adminLogin(@RequestParam("username") String username,
                                             @RequestParam("password") String password,
                                             HttpServletResponse response) {
        if (!SecureTokenUtils.constantTimeEquals(username, properties.management().username()) || !SecureTokenUtils.constantTimeEquals(password, properties.management().password())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(adminLoginPage("Invalid username or password."));
        }
        String token = sessions.createSession(username, Instant.now().plusSeconds(8 * 3600));
        ResponseCookie cookie = ResponseCookie.from("s3proxy_admin", token).httpOnly(true).secure(useSecureCookies()).path("/").sameSite("Strict").maxAge(8 * 3600).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "/admin").body(adminDashboard(username, ""));
    }

    @PostMapping(value = "/admin/logout", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> adminLogout(@CookieValue(value = "s3proxy_admin", required = false) String token, HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from("s3proxy_admin", "").httpOnly(true).secure(useSecureCookies()).path("/").sameSite("Strict").maxAge(0).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "/admin").body(adminLoginPage(""));
    }

    @PostMapping(value = "/admin/users", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> createIdentity(@CookieValue(value = "s3proxy_admin", required = false) String token,
                                                 @RequestParam("clientId") String clientId,
                                                 @RequestParam("secret") String secret) {
        String[] username = new String[1];
        if (token == null || !sessions.tryValidate(token, username)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(adminLoginPage("Please sign in."));
        }
        var result = authServerClient.createIdentity(clientId, secret);
        int status = result.statusCode() >= 400 ? result.statusCode() : HttpStatus.BAD_REQUEST.value();
        String message = result.success()
                ? "Created auth-server identity '" + escapeHtml(clientId) + "'."
                : "Auth server rejected identity creation with HTTP " + status + ".";
        int responseStatus = result.success() ? HttpStatus.OK.value() : status;
        return ResponseEntity.status(responseStatus).body(adminDashboard(username[0], message));
    }

    private String authenticate(String authorization, String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return null;
        }
        String token = BearerToken.extract(authorization);
        if (token == null) {
            return null;
        }
        return authenticationStrategy.isTokenValidForClient(clientId, token) ? clientId : null;
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("success", false, "error", "Unauthorized"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("success", false, "error", exception.getMessage()));
    }

    private static String escapeHtml(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String adminLoginPage(String message) {
        return "<!doctype html><html><body><h1>S3 Proxy Admin</h1><p>" + escapeHtml(message) + "</p>" +
                "<form method='post' action='/admin/login'><input name='username'><input name='password' type='password'><button>Sign in</button></form></body></html>";
    }

    private String adminDashboard(String user, String message) {
        return "<!doctype html><html><body><h1>S3 Proxy Admin</h1><p>Signed in as " + escapeHtml(user) + "</p><p>" + escapeHtml(message) + "</p>" +
                "<form method='post' action='/admin/logout'><button>Sign out</button></form>" +
                "<form method='post' action='/admin/users'><input name='clientId'><input name='secret' type='password'><button>Create identity</button></form></body></html>";
    }

    private boolean useSecureCookies() {
        String url = properties.server() == null ? null : properties.server().url();
        return StringUtils.hasText(url) && url.trim().toLowerCase(java.util.Locale.ROOT).startsWith("https://");
    }

    public record ProxyLoginRequest(@NotBlank String username, @NotBlank String password) {}
}
