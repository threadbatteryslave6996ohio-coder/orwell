package dev.orwell.secrets.auth;

import dev.orwell.auth.AuthenticationContext;
import dev.orwell.secrets.service.AuthValidator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces {@link RequireAdmin} and {@link RequireAccessor} before the handler method runs, and
 * publishes the resolved caller as a request attribute for handlers that need the identity.
 *
 * <p>Every admin and accessor endpoint used to open with its own {@code requireAdmin()} /
 * {@code requireAccessor()} call — 23 copies of the guard, one per handler, where forgetting a
 * single line published that endpoint. One annotation on the class now covers every handler in it,
 * so adding a handler cannot leave it unguarded and the surface a reviewer has to check is three
 * class declarations rather than 23 method bodies.
 *
 * <p>An unannotated handler is still served without a check, so this is not fail-closed on its own:
 * {@code SecretsRoleCoverageTest} supplies that half by failing the build if any handler under
 * {@code dev.orwell.secrets.controller} declares no role.
 *
 * <p>{@link AuthValidator} answers by throwing, so a rejected caller never reaches the handler and
 * the 401/403 bodies stay exactly what they were when the controllers raised them.
 */
public class SecretsRoleInterceptor implements HandlerInterceptor {
    /** Request attribute holding the {@link AuthenticationContext} the guard resolved. */
    public static final String CALLER_ATTRIBUTE = "secretsCaller";

    private enum Role { ADMIN, ACCESSOR, NONE }

    private final AuthValidator authValidator;
    // The role of a handler method is a compile-time constant; memoize so the merged-annotation
    // scan does not run per request. Keyed on Method rather than HandlerMethod, which Spring
    // recreates per request — the same reason RequireAuthenticationInterceptor keys on Method.
    private final Map<Method, Role> roles = new ConcurrentHashMap<>();

    public SecretsRoleInterceptor(AuthValidator authValidator) {
        this.authValidator = authValidator;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        Role role = roles.computeIfAbsent(handlerMethod.getMethod(), method -> roleOf(handlerMethod));
        if (role == Role.NONE) {
            return true;
        }

        String authorization = request.getHeader("Authorization");
        String clientId = request.getHeader("X-Client-Id");
        AuthenticationContext caller = role == Role.ADMIN
                ? authValidator.requireAdmin(authorization, clientId)
                : authValidator.requireAccessor(authorization, clientId);

        request.setAttribute(CALLER_ATTRIBUTE, caller);
        return true;
    }

    /**
     * Merged-annotation search on both the method and the bean type, so the guard also fires for an
     * annotation on a base class or a composed meta-annotation — a plain {@code isAnnotationPresent}
     * check would silently fail open for those. Admin is checked first: if a handler somehow carries
     * both, the stricter role wins.
     */
    private static Role roleOf(HandlerMethod handlerMethod) {
        if (annotated(handlerMethod, RequireAdmin.class)) {
            return Role.ADMIN;
        }
        if (annotated(handlerMethod, RequireAccessor.class)) {
            return Role.ACCESSOR;
        }
        return Role.NONE;
    }

    private static boolean annotated(HandlerMethod handlerMethod, Class<? extends java.lang.annotation.Annotation> annotation) {
        return AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), annotation)
                || AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), annotation);
    }
}
