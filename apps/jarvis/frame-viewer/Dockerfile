# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build stage. `-am` pulls in the shared packages.
# ---------------------------------------------------------------------------
FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /workspace

# The parent .dockerignore keeps target/, Git data, logs and local .env files
# out of the build context.
COPY . .

# Deliberately no `--mount=type=cache` for /root/.m2, matching the other jarvis images: it requires
# BuildKit with the buildx component, and without it the build fails outright rather than merely
# being slower. This one builds under either builder, at the cost of re-fetching dependencies.
RUN mvn -B -DskipTests \
        -pl apps/jarvis/frame-viewer -am \
        package

# ---------------------------------------------------------------------------
# Runtime stage.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# ffmpeg is what POST /api/uploads cuts a video into frames with. It is the largest single thing in
# this image by a wide margin, and it is here rather than vendored as a Java library because the
# alternatives cost more: JavaCV ships hundreds of megabytes of platform natives, and a pure-Java
# decoder reads a fraction of the containers a phone or screen recorder produces. apps/jarvis's
# desktop clients already record with ffmpeg, so this is the same tool on the other end of the same
# pipeline. Drop this layer and the app still browses frames — only uploading stops working, which
# it says on /health as uploadsEnabled.
RUN apt-get update \
    && apt-get install --no-install-recommends -y ffmpeg \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system viewer \
    && useradd --system --gid viewer --no-create-home --shell /usr/sbin/nologin viewer \
    && mkdir -p /app/logs \
    && chown -R viewer:viewer /app

COPY --from=build --chown=viewer:viewer \
    /workspace/apps/jarvis/frame-viewer/target/*-exec.jar \
    /app/jarvis-frame-viewer.jar

USER viewer
# The page and its API. Still published to 127.0.0.1 by docker-compose.all-services.yml even though a
# sign-in is required there: what this serves is recorded footage, so the bind address stays as the
# backstop behind the login.
EXPOSE 9003

# A plain program, not a Spring app: the shaded jar is the whole thing.
ENTRYPOINT ["java", "-jar", "/app/jarvis-frame-viewer.jar"]
