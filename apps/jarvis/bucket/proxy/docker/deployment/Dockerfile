# syntax=docker/dockerfile:1

FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /workspace

# The proxy depends on modules elsewhere in the monorepo. Build from the root
# reactor and include only the proxy plus the modules it requires.
COPY . .
RUN mvn -B -DskipTests -pl apps/jarvis/bucket/proxy -am package

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

RUN groupadd --system bucketproxy \
    && useradd --system --gid bucketproxy --no-create-home --shell /usr/sbin/nologin bucketproxy \
    && mkdir -p /app/logs \
    && chown -R bucketproxy:bucketproxy /app

COPY --from=build --chown=bucketproxy:bucketproxy \
    /workspace/apps/jarvis/bucket/proxy/target/*-exec.jar \
    /app/bucket-proxy.jar

USER bucketproxy
EXPOSE 5000

ENTRYPOINT ["java", "-jar", "/app/bucket-proxy.jar"]
