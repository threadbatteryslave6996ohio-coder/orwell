package dev.orwell.backup;

import dev.orwell.env.Env;
import dev.orwell.env.http.EnvLoader;
import dev.orwell.logging.ConsoleLogger;
import dev.orwell.logging.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class BackupLauncher {
    private BackupLauncher() {
    }

    public static void main(String[] args) throws Exception {
        Logger logger = new ConsoleLogger("backup");
        String source = args.length > 0 ? args[0] : "file";
        var rawEnv = EnvLoader.load(source);
        Env env = BackupEnvs.from(rawEnv);
        List<ProjectConfig> projects = BackupEnvs.resolveProjects(env, rawEnv);

        if (projects.isEmpty()) {
            logger.warn("No projects configured; nothing to back up.");
            return;
        }

        Path outputDir = Path.of(env.get(BackupEnvs.BACKUP_OUTPUT_DIR));
        int retentionDays = env.get(BackupEnvs.BACKUP_RETENTION_DAYS);

        BackupRunner runner = new BackupRunner(outputDir, retentionDays, logger);

        logger.info("Starting backup.", Map.of(
                "projectCount", projects.size(),
                "outputDir", outputDir.toAbsolutePath().toString()));
        runner.run(projects);

        logger.info("Cleaning up expired backups.", Map.of("retentionDays", retentionDays));
        runner.cleanUp();
        runner.cleanUpEmptyDirs();

        logger.info("Backup complete.");
    }
}
