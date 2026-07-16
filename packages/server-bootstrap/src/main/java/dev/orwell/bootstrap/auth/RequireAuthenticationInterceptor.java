package dev.orwell.bootstrap.auth;

import dev.orwell.auth.AuthenticationContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces {@link RequireAuthentication}: rejects unauthenticated requests with the shared
 * {@code 401 {"error":"authentication required"}} contract before the handler method runs.
 */
public class RequireAuthenticationInterceptor implements HandlerInterceptor {
    private final ObjectProvider<AuthenticationContext> authenticationContextProvider;
    // The answer per handler method is a compile-time constant; memoize so the merged-annotation
    // reflection scan doesn't run on every request. Keyed on Method: Spring recreates
    // HandlerMethod instances per request, so caching on the HandlerMethod would always miss.
    private final Map<Method, Boolean> authenticationRequired = new ConcurrentHashMap<>();

    public RequireAuthenticationInterceptor(ObjectProvider<AuthenticationContext> authenticationContextProvider) {
        this.authenticationContextProvider = authenticationContextProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        boolean required = authenticationRequired.computeIfAbsent(handlerMethod.getMethod(),
                method -> requiresAuthentication(handlerMethod));
        if (!required) {
            return true;
        }
        AuthenticationContext authenticationContext = authenticationContextProvider.getObject();
        if (authenticationContext.authenticated()) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"authentication required\"}");
        return false;
    }

    private static boolean requiresAuthentication(HandlerMethod handlerMethod) {
        // Merged-annotation search on BOTH the method and the bean type so the guard also fires
        // for @RequireAuthentication on a controller base class or composed meta-annotation —
        // a plain isAnnotationPresent check would silently fail OPEN for those.
        return AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), RequireAuthentication.class)
                || AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), RequireAuthentication.class);
    }
}
