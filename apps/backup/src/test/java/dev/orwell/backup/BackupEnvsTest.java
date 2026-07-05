package dev.orwell.backup;

import dev.orwell.env.Env;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BackupEnvsTest {
    @Test
    void resolvesSingleProject() {
        Env env = BackupEnvs.from(Map.of(
                "BACKUP_PROJECTS", "myapp",
                "BACKUP_MYAPP_DATABASE_URL", "postgresql://user:pass@localhost:5432/myapp"
        ));
        List<ProjectConfig> projects = BackupEnvs.resolveProjects(env, Map.of(
                "BACKUP_PROJECTS", "myapp",
                "BACKUP_MYAPP_DATABASE_URL", "postgresql://user:pass@localhost:5432/myapp"
        ));
        assertEquals(1, projects.size());
        assertEquals("myapp", projects.getFirst().name());
        assertEquals("postgresql://user:pass@localhost:5432/myapp", projects.getFirst().databaseUrl());
        assertEquals("directory", projects.getFirst().storageType());
        assertNull(projects.getFirst().storageDir());
    }

    @Test
    void resolvesMultipleProjects() {
        Env env = BackupEnvs.from(Map.of(
                "BACKUP_PROJECTS", "alpha,beta"
        ));
        List<ProjectConfig> projects = BackupEnvs.resolveProjects(env, Map.of(
                "BACKUP_PROJECTS", "alpha,beta",
                "BACKUP_ALPHA_DATABASE_URL", "postgresql://u1:p1@h1:5432/db1",
                "BACKUP_BETA_DATABASE_URL", "postgresql://u2:p2@h2:5433/db2"
        ));
        assertEquals(2, projects.size());
        assertEquals("alpha", projects.get(0).name());
        assertEquals("beta", projects.get(1).name());
    }

    @Test
    void respectsCustomStorageTypeAndDir() {
        Env env = BackupEnvs.from(Map.of(
                "BACKUP_PROJECTS", "myapp"
        ));
        List<ProjectConfig> projects = BackupEnvs.resolveProjects(env, Map.of(
                "BACKUP_PROJECTS", "myapp",
                "BACKUP_MYAPP_DATABASE_URL", "postgresql://u:p@localhost:5432/myapp",
                "BACKUP_MYAPP_STORAGE_TYPE", "directory",
                "BACKUP_MYAPP_STORAGE_DIR", "/custom/path"
        ));
        assertEquals("directory", projects.getFirst().storageType());
        assertEquals("/custom/path", projects.getFirst().storageDir());
    }

    @Test
    void rejectsProjectWithoutDatabaseUrl() {
        Env env = BackupEnvs.from(Map.of(
                "BACKUP_PROJECTS", "broken"
        ));
        assertThrows(IllegalArgumentException.class, () ->
                BackupEnvs.resolveProjects(env, Map.of("BACKUP_PROJECTS", "broken")));
    }

    @Test
    void skipsEmptyProjectNames() {
        Env env = BackupEnvs.from(Map.of(
                "BACKUP_PROJECTS", "a,,b"
        ));
        List<ProjectConfig> projects = BackupEnvs.resolveProjects(env, Map.of(
                "BACKUP_PROJECTS", "a,,b",
                "BACKUP_A_DATABASE_URL", "postgresql://u:p@h:5432/a",
                "BACKUP_B_DATABASE_URL", "postgresql://u:p@h:5432/b"
        ));
        assertEquals(2, projects.size());
    }

    @Test
    void globalConfigDefaults() {
        Env env = BackupEnvs.from(Map.of("BACKUP_PROJECTS", "x"));
        assertEquals("backups", env.get(BackupEnvs.BACKUP_OUTPUT_DIR));
        assertEquals(30, env.get(BackupEnvs.BACKUP_RETENTION_DAYS));
    }
}
