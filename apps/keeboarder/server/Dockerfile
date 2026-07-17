# syntax=docker/dockerfile:1

FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /workspace

COPY . .
RUN mvn -B -DskipTests -pl apps/keeboarder/server -am package

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

RUN groupadd --system keeboarder \
    && useradd --system --gid keeboarder --no-create-home --shell /usr/sbin/nologin keeboarder

COPY --from=build --chown=keeboarder:keeboarder \
    /workspace/apps/keeboarder/server/target/*-exec.jar \
    /app/keeboarder-server.jar

USER keeboarder
EXPOSE 8025

ENTRYPOINT ["java", "-jar", "/app/keeboarder-server.jar"]
