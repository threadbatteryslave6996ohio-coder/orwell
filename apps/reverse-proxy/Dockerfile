# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build stage. `-am` pulls in server-bootstrap, logger, env-core and http-client.
# ---------------------------------------------------------------------------
FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /workspace

# The parent .dockerignore keeps target/, Git data, logs and local .env files
# out of the build context.
COPY . .

# Deliberately no `--mount=type=cache` for /root/.m2, for the same reason as apps/jarvis/hub:
# it requires BuildKit with the buildx component, and without it the build fails outright rather
# than merely being slower. This one builds under either builder, at the cost of re-fetching
# dependencies per build.
RUN mvn -B -DskipTests \
        -pl apps/reverse-proxy -am \
        package

# ---------------------------------------------------------------------------
# Runtime stage.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# `revproxy`, not `proxy`: Debian ships a system group called `proxy`, so `groupadd` fails on it
# and takes the build down with it.
RUN groupadd --system revproxy \
    && useradd --system --gid revproxy --no-create-home --shell /usr/sbin/nologin revproxy \
    && mkdir -p /app/logs \
    && chown -R revproxy:revproxy /app

COPY --from=build --chown=revproxy:revproxy \
    /workspace/apps/reverse-proxy/target/*-exec.jar \
    /app/reverse-proxy.jar

USER revproxy
EXPOSE 9300

# No SERVER_ENGINE here: this app relies on servlet streaming to relay arbitrary request bodies,
# so it is Spring/Tomcat only and there is no engine choice to set wrongly.
ENTRYPOINT ["java", "-jar", "/app/reverse-proxy.jar"]
