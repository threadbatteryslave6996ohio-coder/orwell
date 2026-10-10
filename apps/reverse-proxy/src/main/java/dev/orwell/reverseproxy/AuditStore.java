package dev.orwell.reverseproxy;

import java.util.Map;

/**
 * Where the audit trail is kept. {@link #submit(ProxyAuditRecord)} is called on the request path,
 * so an implementation must return promptly and must never throw — a store that cannot write is a
 * gap in the audit, not a failed request.
 */
public interface AuditStore extends AutoCloseable {
    /** Takes one record. Called once per request, from the serving thread. */
    void submit(ProxyAuditRecord record);

    /** What {@code /health} reports about the store. */
    Map<String, Object> healthDetails();

    @Override
    void close();

    /**
     * Keeps no rows. This is what runs when {@code PROXY_AUDIT_DATASOURCE_URL} is unset — the
     * proxy still logs every request, it just has nothing to query. Also the default in tests,
     * which is why the three-argument {@link ProxyEndpoint} constructor exists.
     */
    AuditStore DISABLED = new AuditStore() {
        @Override
        public void submit(ProxyAuditRecord record) {
        }

        @Override
        public Map<String, Object> healthDetails() {
            return Map.of("enabled", false);
        }

        @Override
        public void close() {
        }
    };
}
