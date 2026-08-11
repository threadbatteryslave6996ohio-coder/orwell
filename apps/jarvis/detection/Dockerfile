# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build stage. `-am` pulls in jarvis-retention and the shared packages the
# detection server depends on.
# ---------------------------------------------------------------------------
FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /workspace

# The parent .dockerignore keeps target/, Git data, logs and local .env files
# out of the build context.
COPY . .

# Deliberately no `--mount=type=cache` for /root/.m2, unlike apps/klippy and apps/log-analyzer:
# that requires BuildKit with the buildx component, and without it the build fails outright
# rather than merely being slower. This one builds under either builder, like
# apps/secrets-manager/server/Dockerfile, at the cost of re-fetching dependencies per build.
RUN mvn -B -DskipTests \
        -pl apps/jarvis/detection -am \
        package

# ---------------------------------------------------------------------------
# Runtime stage.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

RUN groupadd --system detection \
    && useradd --system --gid detection --no-create-home --shell /usr/sbin/nologin detection \
    && mkdir -p /app/logs \
    && chown -R detection:detection /app

COPY --from=build --chown=detection:detection \
    /workspace/apps/jarvis/detection/target/*-exec.jar \
    /app/jarvis-detection.jar

USER detection
EXPOSE 9001

# The frame hub needs the Spring engine: it serves SSE and runs the retention
# sweep on a scheduled bean, neither of which the Undertow runtime sets up.
# Under SERVER_ENGINE=undertow, /frames answers 501 and /frames/stream is not
# served at all — so the image defaults to spring rather than leaving it to
# whoever writes the compose entry.
ENV SERVER_ENGINE=spring

ENTRYPOINT ["java", "-jar", "/app/jarvis-detection.jar"]
