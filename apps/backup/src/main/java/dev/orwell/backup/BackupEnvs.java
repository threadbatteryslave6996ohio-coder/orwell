package dev.orwell.backup;

import dev.orwell.env.Env;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvSchema;
import dev.orwell.env.EnvType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class BackupEnvs {
    public static final EnvOption<String> BACKUP_OUTPUT_DIR;
    public static final EnvOption<Integer> BACKUP_RETENTION_DAYS;
    public static final EnvOption<String> BACKUP_PROJECTS;

    public static final EnvSchema ENV;

    static {
        var builder = EnvSchema.builder();
        BACKUP_OUTPUT_DIR = builder.optional("BACKUP_OUTPUT_DIR", EnvType.string(), "backups");
        BACKUP_RETENTION_DAYS = builder.optional("BACKUP_RETENTION_DAYS", EnvType.integer(), 30);
        BACKUP_PROJECTS = builder.required("BACKUP_PROJECTS", EnvType.string());
        ENV = builder.build();
    }

    private static final String URL_SUFFIX = "_DATABASE_URL";
    private static final String STORAGE_TYPE_SUFFIX = "_STORAGE_TYPE";
    private static final String STORAGE_DIR_SUFFIX = "_STORAGE_DIR";

    private BackupEnvs() {
    }

    public static Env from(Map<String, String> source) {
        return ENV.from(source);
    }

    public static List<ProjectConfig> resolveProjects(Env env, Map<String, String> raw) {
        String projectsRaw = env.get(BACKUP_PROJECTS);
        String[] names = projectsRaw.split(",");
        List<ProjectConfig> projects = new ArrayList<>();

        for (String name : names) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            String prefix = "BACKUP_" + trimmed.toUpperCase();
            String databaseUrl = raw.get(prefix + URL_SUFFIX);
            if (databaseUrl == null || databaseUrl.isBlank()) {
                throw new IllegalArgumentException("Missing " + prefix + URL_SUFFIX + " for project: " + trimmed);
            }

            String storageType = raw.getOrDefault(prefix + STORAGE_TYPE_SUFFIX, "directory");
            String storageDir = raw.get(prefix + STORAGE_DIR_SUFFIX);

            projects.add(new ProjectConfig(trimmed, databaseUrl, storageType, storageDir));
        }

        return projects;
    }
}
