# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build stage.
# ---------------------------------------------------------------------------
FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /workspace

# The parent .dockerignore keeps target/, Git data, logs and local .env files
# out of the build context.
COPY . .

RUN mvn -B -DskipTests \
        -pl apps/health-monitor -am \
        package

# ---------------------------------------------------------------------------
# Runtime stage.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

RUN groupadd --system monitor \
    && useradd --system --gid monitor --no-create-home --shell /usr/sbin/nologin monitor \
    && mkdir -p /app/logs \
    && chown -R monitor:monitor /app

COPY --from=build --chown=monitor:monitor \
    /workspace/apps/health-monitor/target/*-exec.jar \
    /app/health-monitor.jar

USER monitor
# /health and /targets. Nothing needs to reach this container for it to do its job — it makes the
# requests. The port is for looking at the answer.
EXPOSE 9005

ENTRYPOINT ["java", "-jar", "/app/health-monitor.jar"]
