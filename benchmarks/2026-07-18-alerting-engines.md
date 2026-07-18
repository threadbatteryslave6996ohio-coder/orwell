# Alerting HTTP engine RAM benchmark — 2026-07-18

This compares the alerting engines' resident memory. SMTP and other external
work were disabled.

## Environment

- JDK: OpenJDK 26.0.1
- Host: Linux 7.0.14, Intel Core i7-13700H, 5 available CPUs
- JVM: `-XX:+UseSerialGC -Xms32m -Xmx192m`
- Clients: 5 persistent idle TCP connections
- Settle time: 2 seconds before each RSS sample
- Artifact: the same Spring Boot executable jar for both engines

## Results

| Metric | Undertow | Spring Boot/Tomcat |
|---|---:|---:|
| Idle RSS | 102,316 KiB | 184,304 KiB |
| RSS with 5 connected clients | 102,320 KiB | 184,308 KiB |
| Observed 5-client delta | +4 KiB | +4 KiB |

In this fresh-process sample, Undertow used about 44% less RSS than Spring at
idle and with five connected clients. The measured per-connection difference
was below the useful resolution of process RSS in this run; the framework
baseline dominates at this client count. Repeat fresh-process samples and use
the median before treating the exact KiB values as stable.

Throughput, latency, and startup timing are intentionally out of scope. The
executable jar still contains both engines so they can be selected at runtime;
Undertow mode does not initialize Spring or Tomcat.
