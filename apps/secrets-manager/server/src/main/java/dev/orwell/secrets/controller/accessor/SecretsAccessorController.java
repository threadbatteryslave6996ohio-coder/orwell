package dev.orwell.secrets.controller.accessor;

import dev.orwell.secrets.model.SecretBundleEntry;
import dev.orwell.secrets.service.AuthValidator;
import dev.orwell.secrets.service.SecretsService;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("${secrets.route-prefix:}")
public class SecretsAccessorController {
    private final AuthValidator authValidator;
    private final SecretsService secretsService;

    public SecretsAccessorController(AuthValidator authValidator, SecretsService secretsService) {
        this.authValidator = authValidator;
        this.secretsService = secretsService;
    }

    @GetMapping("/groups")
    public List<AccessorGroupResponse> listGroups(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId
    ) {
        authValidator.requireAccessor(authorization, clientId);
        return secretsService.listGroups().stream()
                .map(g -> new AccessorGroupResponse(g.getId(), g.getName(), g.getDescription(),
                        g.getCreatedAt()))
                .toList();
    }

    @GetMapping("/groups/{groupId}/envs")
    public List<AccessorEnvironmentResponse> listEnvironments(
            @PathVariable("groupId") Long groupId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId
    ) {
        authValidator.requireAccessor(authorization, clientId);
        return secretsService.listEnvironments(groupId).stream()
                .map(e -> new AccessorEnvironmentResponse(e.getId(), e.getName(), e.getValue(),
                        e.getCreatedAt(), e.getUpdatedAt()))
                .toList();
    }

    @GetMapping("/groups/{groupId}/envs/{envId}")
    public AccessorEnvironmentResponse getEnvironment(
            @PathVariable("groupId") Long groupId,
            @PathVariable("envId") Long envId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId
    ) {
        authValidator.requireAccessor(authorization, clientId);
        var env = secretsService.getEnvironment(envId);
        return new AccessorEnvironmentResponse(env.getId(), env.getName(), env.getValue(),
                env.getCreatedAt(), env.getUpdatedAt());
    }

    @GetMapping("/groups/{groupId}/envs/by-name/{envName}")
    public AccessorEnvironmentResponse getEnvironmentByName(
            @PathVariable("groupId") Long groupId,
            @PathVariable("envName") String envName,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId
    ) {
        authValidator.requireAccessor(authorization, clientId);
        var env = secretsService.getEnvironmentByGroupAndName(groupId, envName);
        return new AccessorEnvironmentResponse(env.getId(), env.getName(), env.getValue(),
                env.getCreatedAt(), env.getUpdatedAt());
    }

    @GetMapping("/bundles")
    public List<AccessorBundleResponse> listBundles(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId
    ) {
        authValidator.requireAccessor(authorization, clientId);
        return secretsService.listBundles().stream()
                .map(b -> new AccessorBundleResponse(b.getId(), b.getName(), b.getDescription(),
                        b.getCreatedAt()))
                .toList();
    }

    @GetMapping("/bundles/{id}")
    public AccessorBundleDetailResponse getBundle(
            @PathVariable("id") Long id,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId
    ) {
        authValidator.requireAccessor(authorization, clientId);
        var bundle = secretsService.getBundle(id);
        var envs = secretsService.getBundleEntries(id).stream()
                .map(SecretBundleEntry::getEnvironment)
                .map(e -> new AccessorEnvironmentResponse(e.getId(), e.getName(), e.getValue(),
                        e.getCreatedAt(), e.getUpdatedAt()))
                .toList();
        return new AccessorBundleDetailResponse(bundle.getId(), bundle.getName(), bundle.getDescription(),
                bundle.getCreatedAt(), envs);
    }
}
