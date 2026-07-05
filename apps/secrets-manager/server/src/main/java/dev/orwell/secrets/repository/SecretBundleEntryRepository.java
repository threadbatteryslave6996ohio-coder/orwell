package dev.orwell.secrets.repository;

import dev.orwell.secrets.model.SecretBundleEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SecretBundleEntryRepository extends JpaRepository<SecretBundleEntry, Long> {
    List<SecretBundleEntry> findByBundleId(Long bundleId);

    void deleteByBundleId(Long bundleId);
}
