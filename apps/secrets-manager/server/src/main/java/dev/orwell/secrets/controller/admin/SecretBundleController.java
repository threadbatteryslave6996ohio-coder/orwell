package dev.orwell.secrets.controller.admin;

import dev.orwell.auth.AuthenticationContext;
import dev.orwell.secrets.model.SecretBundle;
import dev.orwell.secrets.model.SecretBundleEntry;
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
@RequestMapping("${secrets.route-prefix:}/admin/bundles")
public class SecretBundleController extends AbstractSecretsAdminController {

    public SecretBundleController(
            AuthValidator authValidator,
            SecretsService secretsService,
            ObjectProvider<AuthenticationContext> authenticationContextProvider) {
        super(authValidator, secretsService, authenticationContextProvider);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BundleResponse createBundle(@Valid @RequestBody CreateBundleRequest request) {
        AuthenticationContext authenticationContext = requireAdmin();
        SecretBundle bundle = secretsService.createBundle(
                request.name(), request.description(), request.envIds(), authenticationContext.clientId());
        return toResponse(bundle);
    }

    @GetMapping
    public List<BundleResponse> listBundles() {
        requireAdmin();
        return secretsService.listBundles().stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{id}")
    public BundleDetailResponse getBundle(@PathVariable("id") Long id) {
        requireAdmin();
        return toBundleDetail(id);
    }

    @PutMapping("/{id}")
    public BundleResponse updateBundle(@PathVariable("id") Long id, @Valid @RequestBody UpdateBundleRequest request) {
        requireAdmin();
        return toResponse(secretsService.updateBundle(id, request.name(), request.description()));
    }

    @PutMapping("/{id}/envs")
    public BundleDetailResponse setBundleEnvs(@PathVariable("id") Long id, @Valid @RequestBody SetBundleEnvsRequest request) {
        requireAdmin();
        secretsService.setBundleEnvironmentReferences(id, request.envIds());
        return toBundleDetail(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBundle(@PathVariable("id") Long id) {
        requireAdmin();
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
