# Log Analyzer Docker Compose

This stack runs the local alerting service and the log analyzer together.
The analyzer queries Grafana's Loki datasource proxy, runs the results through
an AI model, and forwards important findings to the alert service.

## Start

From this directory:

```bash
docker compose up --build
```

## Required config

Edit `.env.example` before starting and set:

- `GRAFANA_URL`
- `GRAFANA_API_TOKEN`
- `GRAFANA_LOKI_DATASOURCE_UID`
- `AI_API_KEY`

The analyzer already points its alert target at the compose service name:

- `ALERT_URL=http://alerting/alerts`

## Included services

- `alerting` through `http://localhost:9010/alerting`
- `log-analyzer` through `http://localhost:9010/log-analyzer`

## Notes

- The alerting service is local to the compose stack.
- The analyzer does not query Loki directly; it calls Grafana's datasource proxy.
- If you want to connect to a remote Grafana instance, set `GRAFANA_URL` to that
  instance and make sure the selected Loki datasource UID is valid in that Grafana.
