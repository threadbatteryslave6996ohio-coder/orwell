package dev.orwell.secrets.controller.admin;

import dev.orwell.auth.AuthenticationContext;
import dev.orwell.secrets.auth.RequireAdmin;
import dev.orwell.secrets.auth.SecretsRoleInterceptor;
import dev.orwell.secrets.model.SecretBundle;
import dev.orwell.secrets.model.SecretBundleEntry;
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
@RequestMapping("${secrets.route-prefix:}/admin/bundles")
@RequireAdmin
public class SecretBundleController {
    private final SecretsService secretsService;

    public SecretBundleController(SecretsService secretsService) {
        this.secretsService = secretsService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BundleResponse createBundle(
            @Valid @RequestBody CreateBundleRequest request,
            @RequestAttribute(SecretsRoleInterceptor.CALLER_ATTRIBUTE) AuthenticationContext caller) {
        SecretBundle bundle = secretsService.createBundle(
                request.name(), request.description(), request.envIds(), caller.clientId());
        return toResponse(bundle);
    }

    @GetMapping
    public List<BundleResponse> listBundles() {
        return secretsService.listBundles().stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{id}")
    public BundleDetailResponse getBundle(@PathVariable("id") Long id) {
        return toBundleDetail(id);
    }

    @PutMapping("/{id}")
    public BundleResponse updateBundle(@PathVariable("id") Long id, @Valid @RequestBody UpdateBundleRequest request) {
        return toResponse(secretsService.updateBundle(id, request.name(), request.description()));
    }

    @PutMapping("/{id}/envs")
    public BundleDetailResponse setBundleEnvs(@PathVariable("id") Long id, @Valid @RequestBody SetBundleEnvsRequest request) {
        secretsService.setBundleEnvironmentReferences(id, request.envIds());
        return toBundleDetail(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBundle(@PathVariable("id") Long id) {
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

    private BundleResponse toResponse(SecretBundle bundle) {
        return new BundleResponse(bundle.getId(), bundle.getName(), bundle.getDescription(),
                bundle.getCreatedAt(), bundle.getCreatedBy());
    }
}
