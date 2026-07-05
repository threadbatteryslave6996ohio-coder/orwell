package dev.orwell.backup;

import dev.orwell.env.Env;
import dev.orwell.env.EnvFiles;

import java.nio.file.Path;
import java.util.List;

public final class BackupLauncher {
    private BackupLauncher() {
    }

    public static void main(String[] args) throws Exception {
        var rawEnv = EnvFiles.load();
        Env env = BackupEnvs.from(rawEnv);
        List<ProjectConfig> projects = BackupEnvs.resolveProjects(env, rawEnv);

        if (projects.isEmpty()) {
            System.out.println("No projects configured.");
            return;
        }

        Path outputDir = Path.of(env.get(BackupEnvs.BACKUP_OUTPUT_DIR));
        int retentionDays = env.get(BackupEnvs.BACKUP_RETENTION_DAYS);

        BackupRunner runner = new BackupRunner(outputDir, retentionDays);

        System.out.println("Backing up " + projects.size() + " project(s) to " + outputDir.toAbsolutePath() + " ...");
        runner.run(projects);

        System.out.println("Cleaning up backups older than " + retentionDays + " days ...");
        runner.cleanUp();
        runner.cleanUpEmptyDirs();

        System.out.println("Backup complete.");
    }
}
