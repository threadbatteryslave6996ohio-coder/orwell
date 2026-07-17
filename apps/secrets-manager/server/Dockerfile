# syntax=docker/dockerfile:1

FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /workspace

COPY . .
RUN mvn -B -DskipTests -pl apps/secrets-manager/server -am package

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

RUN groupadd --system secrets \
    && useradd --system --gid secrets --no-create-home --shell /usr/sbin/nologin secrets \
    && mkdir -p /app/logs \
    && chown -R secrets:secrets /app

COPY --from=build --chown=secrets:secrets \
    /workspace/apps/secrets-manager/server/target/*-exec.jar \
    /app/secrets-manager-server.jar

USER secrets
EXPOSE 8085

ENTRYPOINT ["java", "-jar", "/app/secrets-manager-server.jar"]
