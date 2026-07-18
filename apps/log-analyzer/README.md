# Log Analyzer

Polls logs through Grafana's datasource proxy, runs recent error logs through an AI model, and forwards important findings to the alert service.

Configure `GRAFANA_URL`, `GRAFANA_API_TOKEN`, and `GRAFANA_LOKI_DATASOURCE_UID` to point at a Grafana instance with a Loki datasource.

## Run

```bash
mvn -pl apps/log-analyzer -am package
SERVER_ENGINE=undertow java -jar apps/log-analyzer/target/log-analyzer-0.1.0-SNAPSHOT-exec.jar
```

Set `SERVER_ENGINE` to `undertow` for the lightweight runtime or `spring` for
the existing Spring Boot/Tomcat runtime. Both engines expose the same
`GET /health` and `POST /run-once` endpoints and share the analyzer service.

See [README.docker.md](./README.docker.md) for the compose-based setup.

## Example env

See [`./.env.example`](./.env.example).
