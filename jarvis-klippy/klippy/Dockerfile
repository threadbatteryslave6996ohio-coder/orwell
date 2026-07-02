# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build stage: compile the three Spring Boot deployment targets in one pass.
# Only the server modules (and, via -am, their upstream deps clippy-utils and
# auth-client) are built. The desktop/mobile clients are skipped.
# ---------------------------------------------------------------------------
FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /workspace

# Copy the parent reactor. The parent .dockerignore keeps target/, Git data,
# logs, Terraform state, and local secrets out of the build context.
COPY . .

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests \
        -pl :clippy-server,:auth-server,:clippy-combined-server -am \
        package

# ---------------------------------------------------------------------------
# Runtime stage: a single image that carries all three executable jars. The
# compose file picks which one to run per service.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS runtime
WORKDIR /app

COPY --from=build /workspace/klippy/server/target/clippy-server-0.1.0-SNAPSHOT-exec.jar              /app/clippy-server.jar
COPY --from=build /workspace/auth/server/target/auth-server-0.1.0-SNAPSHOT-exec.jar           /app/auth-server.jar
COPY --from=build /workspace/klippy/combined-server/target/clippy-combined-server-0.1.0-SNAPSHOT.jar /app/clippy-combined-server.jar

# Spring Boot writes to the LOGGING_FILE_NAME path (relative to /app).
RUN mkdir -p /app/logs /app/config

# No default command — every compose service supplies its own `command`.
