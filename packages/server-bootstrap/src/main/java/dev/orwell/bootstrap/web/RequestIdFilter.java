package dev.orwell.bootstrap.web;

import dev.orwell.http.RequestId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Gives every inbound request an id, and puts it where the rest of the process can find it.
 *
 * <p>Three things happen per request, and each is one of the reasons this exists:
 *
 * <ul>
 *   <li>An inbound {@code X-Request-Id} is kept — sanitized, never trusted verbatim — so a request
 *       arriving from another service keeps the id that service already logged. A request without
 *       one gets a fresh id, so there is never a record with no id at all.</li>
 *   <li>The id is bound to the serving thread, which is what lets {@code ServiceHeaderHttpClient}
 *       carry it onward and a controller record it without every method taking an id parameter.</li>
 *   <li>It is echoed on the response, so a caller — including a human with curl — can quote the
 *       id when asking what happened to their request.</li>
 * </ul>
 *
 * <p>The binding is cleared in a finally block. Tomcat threads are pooled, and a leftover id would
 * file the next request's records under the previous request, which is worse than no id at all.
 */
public final class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = RequestId.resolve(request.getHeader(RequestId.HEADER));
        RequestId.set(requestId);
        try {
            response.setHeader(RequestId.HEADER, requestId);
            chain.doFilter(request, response);
        } finally {
            RequestId.clear();
        }
    }
}
