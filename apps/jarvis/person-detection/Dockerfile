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
        -pl apps/jarvis/person-detection -am \
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
    /workspace/apps/jarvis/person-detection/target/*-exec.jar \
    /app/jarvis-person-detection.jar

USER detection
# The port serves GET /health only. Frames arrive over the hub's SSE stream, outbound, so
# nothing needs to reach this container to feed it — the port is purely for observability.
EXPOSE 9002

# No SERVER_ENGINE: with the intake endpoints gone there are no request routes to serve on two
# engines, so there is no engine to choose. The shaded jar is a plain program, not a Spring app.
ENTRYPOINT ["java", "-jar", "/app/jarvis-person-detection.jar"]
