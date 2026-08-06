package dev.orwell.secrets.controller.admin;

import dev.orwell.auth.AuthenticationContext;
import dev.orwell.secrets.service.AuthValidator;
import dev.orwell.secrets.service.SecretsService;
import jakarta.servlet.http.HttpServletRequest;

abstract class AbstractSecretsAdminController {
    protected final SecretsService secretsService;
    private final AuthValidator authValidator;
    private final HttpServletRequest request;

    protected AbstractSecretsAdminController(
            AuthValidator authValidator,
            SecretsService secretsService,
            HttpServletRequest request) {
        this.authValidator = authValidator;
        this.secretsService = secretsService;
        this.request = request;
    }

    /**
     * Reads the credentials off the request rather than the shared {@code AuthenticationContext}
     * bean: that bean is built from the client auth deployment, so an admin token would never
     * authenticate against it.
     */
    protected AuthenticationContext requireAdmin() {
        return authValidator.requireAdmin(
                request.getHeader("Authorization"), request.getHeader("X-Client-Id"));
    }
}
