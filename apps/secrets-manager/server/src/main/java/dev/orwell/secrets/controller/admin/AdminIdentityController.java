package dev.orwell.secrets.controller.admin;

import dev.orwell.auth.AuthenticationContext;
import dev.orwell.secrets.model.AdminIdentity;
import dev.orwell.secrets.service.AuthValidator;
import dev.orwell.secrets.service.SecretsService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("${secrets.route-prefix:}/admin/admins")
public class AdminIdentityController extends AbstractSecretsAdminController {

    public AdminIdentityController(
            AuthValidator authValidator,
            SecretsService secretsService,
            ObjectProvider<AuthenticationContext> authenticationContextProvider) {
        super(authValidator, secretsService, authenticationContextProvider);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminResponse addAdmin(@Valid @RequestBody CreateAdminRequest request) {
        requireAdmin();
        AdminIdentity admin = secretsService.addAdmin(request.name());
        return toResponse(admin);
    }

    @GetMapping
    public List<AdminResponse> listAdmins() {
        requireAdmin();
        return secretsService.listAdmins().stream()
                .map(this::toResponse)
                .toList();
    }

    private AdminResponse toResponse(AdminIdentity admin) {
        return new AdminResponse(admin.getId(), admin.getName(), admin.getCreatedAt());
    }
}
