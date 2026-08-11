# syntax=docker/dockerfile:1

FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /workspace

# The proxy depends on modules elsewhere in the monorepo. Build from the root
# reactor and include only the proxy plus the modules it requires.
COPY . .
RUN mvn -B -DskipTests -pl apps/object-storage-proxy -am package

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

RUN groupadd --system objectstorageproxy \
    && useradd --system --gid objectstorageproxy --no-create-home --shell /usr/sbin/nologin objectstorageproxy \
    && mkdir -p /app/logs \
    && chown -R objectstorageproxy:objectstorageproxy /app

COPY --from=build --chown=objectstorageproxy:objectstorageproxy \
    /workspace/apps/object-storage-proxy/target/*-exec.jar \
    /app/object-storage-proxy.jar

USER objectstorageproxy
EXPOSE 5000

ENTRYPOINT ["java", "-jar", "/app/object-storage-proxy.jar"]
