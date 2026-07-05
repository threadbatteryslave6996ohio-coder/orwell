package dev.orwell.secrets.controller.admin;

import dev.orwell.secrets.model.AdminIdentity;
import dev.orwell.secrets.model.SecretBundle;
import dev.orwell.secrets.model.SecretBundleEntry;
import dev.orwell.secrets.model.SecretEnvironment;
import dev.orwell.secrets.model.SecretGroup;
import dev.orwell.secrets.service.AuthValidator;
import dev.orwell.secrets.service.SecretsService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("${secrets.route-prefix:}/admin")
public class SecretsAdminController {
    private final AuthValidator authValidator;
    private final SecretsService secretsService;

    public SecretsAdminController(AuthValidator authValidator, SecretsService secretsService) {
        this.authValidator = authValidator;
        this.secretsService = secretsService;
    }

    @PostMapping("/admins")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminResponse addAdmin(
            @Valid @RequestBody CreateAdminRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId
    ) {
        authValidator.requireAdmin(authorization, clientId);
        AdminIdentity admin = secretsService.addAdmin(request.name());
        return new AdminResponse(admin.getId(), admin.getName(), admin.getCreatedAt());
    }

    @GetMapping("/admins")
    public List<AdminResponse> listAdmins(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId
    ) {
        authValidator.requireAdmin(authorization, clientId);
        return secretsService.listAdmins().stream()
                .map(a -> new AdminResponse(a.getId(), a.getName(), a.getCreatedAt()))
                .toList();
    }

    @PostMapping("/groups")
    @ResponseStatus(HttpStatus.CREATED)
    public GroupResponse createGroup(
            @Valid @RequestBody CreateGroupRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId
    ) {
        authValidator.requireAdmin(authorization, clientId);
        SecretGroup group = secretsService.createGroup(request.name(), request.description(), clientId);
        return new GroupResponse(group.getId(), group.getName(), group.getDescription(),
                group.getCreatedAt(), group.getCreatedBy());
    }

    @GetMapping("/groups")
    public List<GroupResponse> listGroups(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId
    ) {
        authValidator.requireAdmin(authorization, clientId);
        return secretsService.listGroups().stream()
                .map(g -> new GroupResponse(g.getId(), g.getName(), g.getDescription(),
                        g.getCreatedAt(), g.getCreatedBy()))
                .toList();
    }

    @GetMapping("/groups/{id}")
    public GroupResponse getGroup(
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId
    ) {
        authValidator.requireAdmin(authorization, clientId);
        SecretGroup group = secretsService.getGroup(id);
        return new GroupResponse(group.getId(), group.getName(), group.getDescription(),
                group.getCreatedAt(), group.getCreatedBy());
    }

    @PutMapping("/groups/{id}")
    public GroupResponse updateGroup(
            @PathVariable Long id,
            @Valid @RequestBody UpdateGroupRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId
    ) {
        authValidator.requireAdmin(authorization, clientId);
        SecretGroup group = secretsService.updateGroup(id, request.name(), request.description());
        return new GroupResponse(group.getId(), group.getName(), group.getDescription(),
                group.getCreatedAt(), group.getCreatedBy());
    }

    @DeleteMapping("/groups/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGroup(
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId
    ) {
        authValidator.requireAdmin(authorization, clientId);
        secretsService.deleteGroup(id);
    }

    @PostMapping("/groups/{groupId}/envs")
    @ResponseStatus(HttpStatus.CREATED)
    public EnvironmentResponse createEnvironment(
            @PathVariable Long groupId,
            @Valid @RequestBody CreateEnvironmentRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId
    ) {
        authValidator.requireAdmin(authorization, clientId);
        SecretEnvironment env = secretsService.createEnvironment(groupId, request.name(), request.value());
        return new EnvironmentResponse(env.getId(), env.getName(), env.getValue(),
                env.getCreatedAt(), env.getUpdatedAt());
    }

    @GetMapping("/groups/{groupId}/envs")
    public List<EnvironmentResponse> listEnvironments(
            @PathVariable Long groupId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId
    ) {
        authValidator.requireAdmin(authorization, clientId);
        return secretsService.listEnvironments(groupId).stream()
                .map(e -> new EnvironmentResponse(e.getId(), e.getName(), e.getValue(),
                        e.getCreatedAt(), e.getUpdatedAt()))
                .toList();
    }

    @GetMapping("/groups/{groupId}/envs/{envId}")
    public EnvironmentResponse getEnvironment(
            @PathVariable Long groupId,
            @PathVariable Long envId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId
    ) {
        authValidator.requireAdmin(authorization, clientId);
        SecretEnvironment env = secretsService.getEnvironment(envId);
        return new EnvironmentResponse(env.getId(), env.getName(), env.getValue(),
                env.getCreatedAt(), env.getUpdatedAt());
    }

    @PutMapping("/groups/{groupId}/envs/{envId}")
    public EnvironmentResponse updateEnvironment(
            @PathVariable Long groupId,
            @PathVariable Long envId,
            @Valid @RequestBody UpdateEnvironmentRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId
    ) {
        authValidator.requireAdmin(authorization, clientId);
        SecretEnvironment env = secretsService.updateEnvironment(envId, request.name(), request.value());
        return new EnvironmentResponse(env.getId(), env.getName(), env.getValue(),
                env.getCreatedAt(), env.getUpdatedAt());
    }

    @DeleteMapping("/groups/{groupId}/envs/{envId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEnvironment(
            @PathVariable Long groupId,
            @PathVariable Long envId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId
    ) {
        authValidator.requireAdmin(authorization, clientId);
        secretsService.deleteEnvironment(envId);
    }

    @PostMapping("/bundles")
    @ResponseStatus(HttpStatus.CREATED)
    public BundleResponse createBundle(
            @Valid @RequestBody CreateBundleRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId
    ) {
        authValidator.requireAdmin(authorization, clientId);
        SecretBundle bundle = secretsService.createBundle(
                request.name(), request.description(), request.envIds(), clientId);
        return new BundleResponse(bundle.getId(), bundle.getName(), bundle.getDescription(),
                bundle.getCreatedAt(), bundle.getCreatedBy());
    }

    @GetMapping("/bundles")
    public List<BundleResponse> listBundles(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId
    ) {
        authValidator.requireAdmin(authorization, clientId);
        return secretsService.listBundles().stream()
                .map(b -> new BundleResponse(b.getId(), b.getName(), b.getDescription(),
                        b.getCreatedAt(), b.getCreatedBy()))
                .toList();
    }

    @GetMapping("/bundles/{id}")
    public BundleDetailResponse getBundle(
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId
    ) {
        authValidator.requireAdmin(authorization, clientId);
        return toBundleDetail(id);
    }

    @PutMapping("/bundles/{id}")
    public BundleResponse updateBundle(
            @PathVariable Long id,
            @Valid @RequestBody UpdateBundleRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId
    ) {
        authValidator.requireAdmin(authorization, clientId);
        SecretBundle bundle = secretsService.updateBundle(id, request.name(), request.description());
        return new BundleResponse(bundle.getId(), bundle.getName(), bundle.getDescription(),
                bundle.getCreatedAt(), bundle.getCreatedBy());
    }

    @PutMapping("/bundles/{id}/envs")
    public BundleDetailResponse setBundleEnvs(
            @PathVariable Long id,
            @Valid @RequestBody SetBundleEnvsRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId
    ) {
        authValidator.requireAdmin(authorization, clientId);
        secretsService.setBundleEnvironmentReferences(id, request.envIds());
        return toBundleDetail(id);
    }

    @DeleteMapping("/bundles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBundle(
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId
    ) {
        authValidator.requireAdmin(authorization, clientId);
        secretsService.deleteBundle(id);
    }

    private BundleDetailResponse toBundleDetail(Long bundleId) {
        SecretBundle bundle = secretsService.getBundle(bundleId);
        List<EnvironmentResponse> envs = secretsService.getBundleEntries(bundleId).stream()
                .map(SecretBundleEntry::getEnvironment)
                .map(e -> new EnvironmentResponse(e.getId(), e.getName(), e.getValue(),
                        e.getCreatedAt(), e.getUpdatedAt()))
                .toList();
        return new BundleDetailResponse(bundle.getId(), bundle.getName(), bundle.getDescription(),
                bundle.getCreatedAt(), bundle.getCreatedBy(), envs);
    }
}
