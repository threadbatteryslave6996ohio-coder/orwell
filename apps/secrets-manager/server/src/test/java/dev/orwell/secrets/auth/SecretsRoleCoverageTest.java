package dev.orwell.secrets.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every secrets endpoint must declare a role.
 *
 * <p>{@link SecretsRoleInterceptor} guards a handler only when {@link RequireAdmin} or
 * {@link RequireAccessor} is present; an unannotated handler is served without a check. That is the
 * same fail-open shape the per-method {@code requireAdmin()} calls had, just moved — so the
 * guarantee lives here instead: a new controller or a handler outside an annotated class fails the
 * build rather than quietly publishing a secret.
 *
 * <p>Scans by package rather than listing the controllers, so a file added later is covered without
 * anyone remembering to extend this test.
 */
class SecretsRoleCoverageTest {
    private static final String CONTROLLER_PACKAGE = "dev.orwell.secrets.controller";

    @Test
    void everyHandlerDeclaresARole() {
        List<String> unguarded = controllers().stream()
                // getMethods(), not getDeclaredMethods(): handlers inherited from a base class are
                // mapped by Spring too, and declared-only would skip exactly the ones a reviewer is
                // least likely to notice.
                .flatMap(controller -> List.of(controller.getMethods()).stream())
                .filter(SecretsRoleCoverageTest::isHandler)
                .filter(method -> !hasRole(method))
                .map(method -> method.getDeclaringClass().getSimpleName() + "." + method.getName())
                .sorted()
                .toList();

        assertThat(unguarded)
                .as("handlers with neither @RequireAdmin nor @RequireAccessor, on the method or its class")
                .isEmpty();
    }

    @Test
    void theScanFindsTheControllersItIsMeantToCover() {
        // Guards the guard: a renamed package would leave everyHandlerDeclaresARole() passing
        // vacuously over an empty scan.
        assertThat(controllers()).hasSizeGreaterThanOrEqualTo(3);
    }

    private static List<Class<?>> controllers() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        return scanner.findCandidateComponents(CONTROLLER_PACKAGE).stream()
                .map(BeanDefinition::getBeanClassName)
                .<Class<?>>map(name -> ClassUtils.resolveClassName(name, null))
                .toList();
    }

    /** Mirrors what Spring maps: {@code @GetMapping} and friends are meta-annotated {@code @RequestMapping}. */
    private static boolean isHandler(Method method) {
        return AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class);
    }

    /** Mirrors {@code SecretsRoleInterceptor.roleOf}: method first, then the declaring class. */
    private static boolean hasRole(Method method) {
        return AnnotatedElementUtils.hasAnnotation(method, RequireAdmin.class)
                || AnnotatedElementUtils.hasAnnotation(method, RequireAccessor.class)
                || AnnotatedElementUtils.hasAnnotation(method.getDeclaringClass(), RequireAdmin.class)
                || AnnotatedElementUtils.hasAnnotation(method.getDeclaringClass(), RequireAccessor.class);
    }
}
