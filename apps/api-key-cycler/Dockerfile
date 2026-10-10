# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build stage. `-am` pulls in server-bootstrap, logger, env-core and http-client.
# ---------------------------------------------------------------------------
FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /workspace

# The parent .dockerignore keeps target/, Git data, logs and local .env files
# out of the build context.
COPY . .

# Deliberately no `--mount=type=cache` for /root/.m2, for the same reason as apps/reverse-proxy:
# it requires BuildKit with the buildx component, and without it the build fails outright rather
# than merely being slower. This one builds under either builder, at the cost of re-fetching
# dependencies per build.
RUN mvn -B -DskipTests \
        -pl apps/api-key-cycler -am \
        package

# ---------------------------------------------------------------------------
# Runtime stage.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

RUN groupadd --system cycler \
    && useradd --system --gid cycler --no-create-home --shell /usr/sbin/nologin cycler \
    && mkdir -p /app/logs \
    && chown -R cycler:cycler /app

COPY --from=build --chown=cycler:cycler \
    /workspace/apps/api-key-cycler/target/*-exec.jar \
    /app/api-key-cycler.jar

USER cycler
EXPOSE 9310

# No SERVER_ENGINE here: like the reverse proxy, this app relies on servlet streaming to relay
# arbitrary request bodies, so it is Spring/Tomcat only and there is no engine choice to set wrongly.
ENTRYPOINT ["java", "-jar", "/app/api-key-cycler.jar"]
