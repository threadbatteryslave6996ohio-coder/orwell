package dev.orwell.secrets.controller.admin;

import dev.orwell.auth.AuthenticationContext;
import dev.orwell.secrets.auth.RequireAdmin;
import dev.orwell.secrets.auth.SecretsRoleInterceptor;
import dev.orwell.secrets.model.SecretEnvironment;
import dev.orwell.secrets.model.SecretGroup;
import dev.orwell.secrets.service.SecretsService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** {@link RequireAdmin} on the type guards every handler below. */
@RestController
@RequestMapping("${secrets.route-prefix:}/admin/groups")
@RequireAdmin
public class SecretGroupController {
    private final SecretsService secretsService;

    public SecretGroupController(SecretsService secretsService) {
        this.secretsService = secretsService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GroupResponse createGroup(
            @Valid @RequestBody CreateGroupRequest request,
            @RequestAttribute(SecretsRoleInterceptor.CALLER_ATTRIBUTE) AuthenticationContext caller) {
        SecretGroup group = secretsService.createGroup(request.name(), request.description(), caller.clientId());
        return toResponse(group);
    }

    @GetMapping
    public List<GroupResponse> listGroups() {
        return secretsService.listGroups().stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{id}")
    public GroupResponse getGroup(@PathVariable("id") Long id) {
        return toResponse(secretsService.getGroup(id));
    }

    @PutMapping("/{id}")
    public GroupResponse updateGroup(@PathVariable("id") Long id, @Valid @RequestBody UpdateGroupRequest request) {
        return toResponse(secretsService.updateGroup(id, request.name(), request.description()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGroup(@PathVariable("id") Long id) {
        secretsService.deleteGroup(id);
    }

    @PostMapping("/{groupId}/envs")
    @ResponseStatus(HttpStatus.CREATED)
    public EnvironmentResponse createEnvironment(@PathVariable("groupId") Long groupId, @Valid @RequestBody CreateEnvironmentRequest request) {
        return toResponse(secretsService.createEnvironment(groupId, request.name(), request.value()));
    }

    @GetMapping("/{groupId}/envs")
    public List<EnvironmentResponse> listEnvironments(@PathVariable("groupId") Long groupId) {
        return secretsService.listEnvironments(groupId).stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{groupId}/envs/{envId}")
    public EnvironmentResponse getEnvironment(@PathVariable("groupId") Long groupId, @PathVariable("envId") Long envId) {
        return toResponse(secretsService.getEnvironment(envId));
    }

    @PutMapping("/{groupId}/envs/{envId}")
    public EnvironmentResponse updateEnvironment(
            @PathVariable("groupId") Long groupId, @PathVariable("envId") Long envId, @Valid @RequestBody UpdateEnvironmentRequest request) {
        return toResponse(secretsService.updateEnvironment(envId, request.name(), request.value()));
    }

    @DeleteMapping("/{groupId}/envs/{envId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEnvironment(@PathVariable("groupId") Long groupId, @PathVariable("envId") Long envId) {
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
