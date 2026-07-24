package dev.orwell.secrets.controller.admin;

import dev.orwell.auth.AuthenticationContext;
import dev.orwell.secrets.service.AuthValidator;
import dev.orwell.secrets.service.SecretsService;
import org.springframework.beans.factory.ObjectProvider;

abstract class AbstractSecretsAdminController {
    protected final SecretsService secretsService;
    private final AuthValidator authValidator;
    private final ObjectProvider<AuthenticationContext> authenticationContextProvider;

    protected AbstractSecretsAdminController(
            AuthValidator authValidator,
            SecretsService secretsService,
            ObjectProvider<AuthenticationContext> authenticationContextProvider) {
        this.authValidator = authValidator;
        this.secretsService = secretsService;
        this.authenticationContextProvider = authenticationContextProvider;
    }

    protected AuthenticationContext requireAdmin() {
        AuthenticationContext authenticationContext = authenticationContextProvider.getObject();
        authValidator.requireAdmin(authenticationContext);
        return authenticationContext;
    }
}
