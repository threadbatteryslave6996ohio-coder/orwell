# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build stage. `-am` pulls in jarvis-retention and the shared packages.
# ---------------------------------------------------------------------------
FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /workspace

# The parent .dockerignore keeps target/, Git data, logs and local .env files
# out of the build context.
COPY . .

# Deliberately no `--mount=type=cache` for /root/.m2, unlike apps/klippy and apps/log-analyzer:
# that requires BuildKit with the buildx component, and without it the build fails outright
# rather than merely being slower. This one builds under either builder, at the cost of
# re-fetching dependencies per build.
RUN mvn -B -DskipTests \
        -pl apps/jarvis/retention-worker -am \
        package

# ---------------------------------------------------------------------------
# Runtime stage.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

RUN groupadd --system retention \
    && useradd --system --gid retention --no-create-home --shell /usr/sbin/nologin retention \
    && mkdir -p /app/logs \
    && chown -R retention:retention /app

# The shaded jar, not a Spring Boot repackage: this is a plain program with no Spring context.
COPY --from=build --chown=retention:retention \
    /workspace/apps/jarvis/retention-worker/target/*-exec.jar \
    /app/jarvis-retention-worker.jar

USER retention

# No EXPOSE, and no SERVER_ENGINE. This process serves nothing — it opens a JDBC connection every
# sweep interval and closes it again. That is also why the compose entry cannot health-check it
# over HTTP: it checks the process is alive, and the sweep result goes to the log.
ENTRYPOINT ["java", "-jar", "/app/jarvis-retention-worker.jar"]
