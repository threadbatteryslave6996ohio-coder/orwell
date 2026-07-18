# Server RAM benchmark

This benchmark measures Linux resident memory (RSS) without generating request
load. It samples the server at idle, with up to five persistent TCP clients,
and after those clients disconnect.

Start the server, note its process ID, then run:

```bash
java benchmarks/RamBenchmark.java <server-pid> 127.0.0.1 9000 5
```

The client count is deliberately limited to five. Use the same JDK, JVM
options, artifact, environment, settle time, and endpoint configuration for
each engine. Run each engine several times from a fresh process and compare the
median `idle_rss_kib` and `connected_rss_kib` values.

The probe reads `/proc/<pid>/status`, so it currently supports Linux only. Its
client sockets stay idle after connecting; it measures framework and
connection memory rather than throughput, latency, or application workload.
