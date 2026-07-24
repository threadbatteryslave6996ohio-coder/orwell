package dev.orwell.secrets.controller.admin;

import dev.orwell.auth.AuthenticationContext;
import dev.orwell.secrets.model.SecretEnvironment;
import dev.orwell.secrets.model.SecretGroup;
import dev.orwell.secrets.service.AuthValidator;
import dev.orwell.secrets.service.SecretsService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("${secrets.route-prefix:}/admin/groups")
public class SecretGroupController extends AbstractSecretsAdminController {

    public SecretGroupController(
            AuthValidator authValidator,
            SecretsService secretsService,
            ObjectProvider<AuthenticationContext> authenticationContextProvider) {
        super(authValidator, secretsService, authenticationContextProvider);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GroupResponse createGroup(@Valid @RequestBody CreateGroupRequest request) {
        AuthenticationContext authenticationContext = requireAdmin();
        SecretGroup group = secretsService.createGroup(request.name(), request.description(), authenticationContext.clientId());
        return toResponse(group);
    }

    @GetMapping
    public List<GroupResponse> listGroups() {
        requireAdmin();
        return secretsService.listGroups().stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{id}")
    public GroupResponse getGroup(@PathVariable("id") Long id) {
        requireAdmin();
        return toResponse(secretsService.getGroup(id));
    }

    @PutMapping("/{id}")
    public GroupResponse updateGroup(@PathVariable("id") Long id, @Valid @RequestBody UpdateGroupRequest request) {
        requireAdmin();
        return toResponse(secretsService.updateGroup(id, request.name(), request.description()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGroup(@PathVariable("id") Long id) {
        requireAdmin();
        secretsService.deleteGroup(id);
    }

    @PostMapping("/{groupId}/envs")
    @ResponseStatus(HttpStatus.CREATED)
    public EnvironmentResponse createEnvironment(@PathVariable("groupId") Long groupId, @Valid @RequestBody CreateEnvironmentRequest request) {
        requireAdmin();
        return toResponse(secretsService.createEnvironment(groupId, request.name(), request.value()));
    }

    @GetMapping("/{groupId}/envs")
    public List<EnvironmentResponse> listEnvironments(@PathVariable("groupId") Long groupId) {
        requireAdmin();
        return secretsService.listEnvironments(groupId).stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{groupId}/envs/{envId}")
    public EnvironmentResponse getEnvironment(@PathVariable("groupId") Long groupId, @PathVariable("envId") Long envId) {
        requireAdmin();
        return toResponse(secretsService.getEnvironment(envId));
    }

    @PutMapping("/{groupId}/envs/{envId}")
    public EnvironmentResponse updateEnvironment(
            @PathVariable("groupId") Long groupId, @PathVariable("envId") Long envId, @Valid @RequestBody UpdateEnvironmentRequest request) {
        requireAdmin();
        return toResponse(secretsService.updateEnvironment(envId, request.name(), request.value()));
    }

    @DeleteMapping("/{groupId}/envs/{envId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEnvironment(@PathVariable("groupId") Long groupId, @PathVariable("envId") Long envId) {
        requireAdmin();
        secretsService.deleteEnvironment(envId);
    }

    private GroupResponse toResponse(SecretGroup group) {
        return new GroupResponse(group.getId(), group.getName(), group.getDescription(),
                group.getCreatedAt(), group.getCreatedBy());
    }

    private EnvironmentResponse toResponse(SecretEnvironment env) {
        return new EnvironmentResponse(env.getId(), env.getName(), env.getValue(),
                env.getCreatedAt(), env.getUpdatedAt());
    }
}
