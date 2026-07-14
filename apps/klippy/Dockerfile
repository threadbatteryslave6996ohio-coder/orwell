# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build stage: compile the standalone servers in one pass.
# ---------------------------------------------------------------------------
FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /workspace

# Copy the parent reactor. The parent .dockerignore keeps target/, Git data,
# logs, Terraform state, and local secrets out of the build context.
COPY . .

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests \
        -pl :clippy-server,:auth-http-server -am \
        package

# ---------------------------------------------------------------------------
# Runtime stage: a single image that carries both executable jars. The
# compose file picks which one to run per service.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS runtime
WORKDIR /app

COPY --from=build /workspace/apps/klippy/server/target/clippy-server-0.1.0-SNAPSHOT-exec.jar /app/clippy-server.jar
COPY --from=build /workspace/apps/auth/http-based/server/target/auth-http-server-0.1.0-SNAPSHOT-exec.jar /app/auth-server.jar

# Spring Boot writes to the LOGGING_FILE_NAME path (relative to /app).
RUN mkdir -p /app/logs /app/config

# No default command — every compose service supplies its own `command`.
