# syntax=docker/dockerfile:1

# Build the log analyzer and the local alert service in one pass.
FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /workspace

COPY . .

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests \
        -pl apps/alerting,apps/log-analyzer -am \
        package

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

COPY --from=build /workspace/apps/alerting/target/alerting-0.1.0-SNAPSHOT-exec.jar /app/alerting.jar
COPY --from=build /workspace/apps/log-analyzer/target/log-analyzer-0.1.0-SNAPSHOT-exec.jar /app/log-analyzer.jar

