# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build stage. `-am` pulls in jarvis-frame-core and the shared packages.
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
        -pl apps/jarvis/hub -am \
        package

# ---------------------------------------------------------------------------
# Runtime stage.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

RUN groupadd --system hub \
    && useradd --system --gid hub --no-create-home --shell /usr/sbin/nologin hub \
    && mkdir -p /app/logs \
    && chown -R hub:hub /app

COPY --from=build --chown=hub:hub \
    /workspace/apps/jarvis/hub/target/*-exec.jar \
    /app/jarvis-hub.jar

USER hub
EXPOSE 9001

# No SERVER_ENGINE here, unlike person-detection and motion: the hub serves SSE, which only the
# Spring engine provides, so there is no choice to make and nothing to set wrongly. That used to
# be an env var this image had to pin defensively.
ENTRYPOINT ["java", "-jar", "/app/jarvis-hub.jar"]
