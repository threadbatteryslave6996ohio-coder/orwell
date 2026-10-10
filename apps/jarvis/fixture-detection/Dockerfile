# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build stage. `-am` pulls in jarvis-frame-client and the shared packages.
# ---------------------------------------------------------------------------
FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /workspace

# The parent .dockerignore keeps target/, Git data, logs and local .env files
# out of the build context.
COPY . .

# Deliberately no `--mount=type=cache` for /root/.m2, matching the other jarvis images: it requires
# BuildKit with the buildx component, and without it the build fails outright rather than merely
# being slower.
RUN mvn -B -DskipTests \
        -pl apps/jarvis/fixture-detection -am \
        package

# ---------------------------------------------------------------------------
# Runtime stage.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

RUN groupadd --system fixture \
    && useradd --system --gid fixture --no-create-home --shell /usr/sbin/nologin fixture \
    && mkdir -p /app/logs \
    && chown -R fixture:fixture /app

COPY --from=build --chown=fixture:fixture \
    /workspace/apps/jarvis/fixture-detection/target/*-exec.jar \
    /app/jarvis-fixture-detection.jar

USER fixture
# Serves /health, /fixtures and the calibration preview. Frames arrive over the hub's SSE stream,
# outbound, so nothing needs to reach this container to feed it.
#
# No ffmpeg and no model runtime here, unlike jarvis-frame-viewer: measuring a region is arithmetic
# on a JPEG the JDK already decodes, which is what keeps this image the size of a bare JRE.
EXPOSE 9004

ENTRYPOINT ["java", "-jar", "/app/jarvis-fixture-detection.jar"]
