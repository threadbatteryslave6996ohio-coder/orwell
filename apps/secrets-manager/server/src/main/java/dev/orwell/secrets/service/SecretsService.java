package dev.orwell.secrets.service;

import dev.orwell.secrets.model.AdminIdentity;
import dev.orwell.secrets.model.SecretBundle;
import dev.orwell.secrets.model.SecretBundleEntry;
import dev.orwell.secrets.model.SecretEnvironment;
import dev.orwell.secrets.model.SecretGroup;
import dev.orwell.secrets.repository.AdminIdentityRepository;
import dev.orwell.secrets.repository.SecretBundleEntryRepository;
import dev.orwell.secrets.repository.SecretBundleRepository;
import dev.orwell.secrets.repository.SecretEnvironmentRepository;
import dev.orwell.secrets.repository.SecretGroupRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
@Transactional
public class SecretsService {
    private final AdminIdentityRepository adminRepo;
    private final SecretGroupRepository groupRepo;
    private final SecretEnvironmentRepository envRepo;
    private final SecretBundleRepository bundleRepo;
    private final SecretBundleEntryRepository bundleEntryRepo;

    public SecretsService(
            AdminIdentityRepository adminRepo,
            SecretGroupRepository groupRepo,
            SecretEnvironmentRepository envRepo,
            SecretBundleRepository bundleRepo,
            SecretBundleEntryRepository bundleEntryRepo
    ) {
        this.adminRepo = adminRepo;
        this.groupRepo = groupRepo;
        this.envRepo = envRepo;
        this.bundleRepo = bundleRepo;
        this.bundleEntryRepo = bundleEntryRepo;
    }

    public AdminIdentity addAdmin(String name) {
        if (adminRepo.existsByName(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Admin already exists.");
        }
        try {
            return adminRepo.save(new AdminIdentity(name, Instant.now()));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Admin already exists.", e);
        }
    }

    @Transactional(readOnly = true)
    public List<AdminIdentity> listAdmins() {
        return adminRepo.findAll();
    }

    public SecretGroup createGroup(String name, String description, String createdBy) {
        if (groupRepo.existsByName(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Group already exists.");
        }
        try {
            return groupRepo.save(new SecretGroup(name, description, Instant.now(), createdBy));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Group already exists.", e);
        }
    }

    public SecretGroup updateGroup(Long id, String name, String description) {
        SecretGroup group = groupRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found."));
        if (name != null && !name.isBlank()) {
            if (!name.equals(group.getName()) && groupRepo.existsByName(name)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Group name already taken.");
            }
            group.setName(name);
        }
        if (description != null) {
            group.setDescription(description);
        }
        return groupRepo.save(group);
    }

    public void deleteGroup(Long id) {
        SecretGroup group = groupRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found."));
        groupRepo.delete(group);
    }

    @Transactional(readOnly = true)
    public List<SecretGroup> listGroups() {
        return groupRepo.findAll();
    }

    @Transactional(readOnly = true)
    public SecretGroup getGroup(Long id) {
        return groupRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found."));
    }

    public SecretEnvironment createEnvironment(Long groupId, String name, String value) {
        SecretGroup group = groupRepo.findById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found."));
        if (envRepo.existsByGroupIdAndName(groupId, name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Environment already exists in this group.");
        }
        try {
            return envRepo.save(new SecretEnvironment(group, name, value, Instant.now()));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Environment already exists in this group.", e);
        }
    }

    public SecretEnvironment updateEnvironment(Long envId, String name, String value) {
        SecretEnvironment env = envRepo.findById(envId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Environment not found."));
        if (name != null && !name.isBlank()) {
            if (!name.equals(env.getName())
                    && envRepo.existsByGroupIdAndName(env.getGroup().getId(), name)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Environment name already taken in this group.");
            }
            env.setName(name);
        }
        if (value != null) {
            env.setValue(value);
        }
        env.setUpdatedAt(Instant.now());
        return envRepo.save(env);
    }

    public void deleteEnvironment(Long envId) {
        SecretEnvironment env = envRepo.findById(envId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Environment not found."));
        envRepo.delete(env);
    }

    @Transactional(readOnly = true)
    public List<SecretEnvironment> listEnvironments(Long groupId) {
        if (!groupRepo.existsById(groupId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found.");
        }
        return envRepo.findByGroupId(groupId);
    }

    @Transactional(readOnly = true)
    public SecretEnvironment getEnvironment(Long envId) {
        return envRepo.findById(envId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Environment not found."));
    }

    @Transactional(readOnly = true)
    public SecretEnvironment getEnvironmentByGroupAndName(Long groupId, String name) {
        return envRepo.findByGroupIdAndName(groupId, name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Environment not found."));
    }

    public SecretBundle createBundle(String name, String description, List<Long> envIds, String createdBy) {
        if (bundleRepo.existsByName(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Bundle already exists.");
        }
        SecretBundle bundle;
        try {
            bundle = bundleRepo.save(new SecretBundle(name, description, Instant.now(), createdBy));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Bundle already exists.", e);
        }
        if (envIds != null && !envIds.isEmpty()) {
            setBundleEnvironmentReferences(bundle.getId(), envIds);
        }
        return bundle;
    }

    public SecretBundle updateBundle(Long id, String name, String description) {
        SecretBundle bundle = bundleRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bundle not found."));
        if (name != null && !name.isBlank()) {
            if (!name.equals(bundle.getName()) && bundleRepo.existsByName(name)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Bundle name already taken.");
            }
            bundle.setName(name);
        }
        if (description != null) {
            bundle.setDescription(description);
        }
        return bundleRepo.save(bundle);
    }

    public void setBundleEnvironmentReferences(Long bundleId, List<Long> envIds) {
        SecretBundle bundle = bundleRepo.findById(bundleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bundle not found."));
        bundleEntryRepo.deleteByBundleId(bundleId);
        for (Long envId : envIds) {
            SecretEnvironment env = envRepo.findById(envId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "Environment not found: " + envId));
            bundleEntryRepo.save(new SecretBundleEntry(bundle, env));
        }
    }

    public void deleteBundle(Long id) {
        SecretBundle bundle = bundleRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bundle not found."));
        bundleRepo.delete(bundle);
    }

    @Transactional(readOnly = true)
    public List<SecretBundle> listBundles() {
        return bundleRepo.findAll();
    }

    @Transactional(readOnly = true)
    public SecretBundle getBundle(Long id) {
        return bundleRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bundle not found."));
    }

    @Transactional(readOnly = true)
    public List<SecretBundleEntry> getBundleEntries(Long bundleId) {
        return bundleEntryRepo.findByBundleId(bundleId);
    }
}
