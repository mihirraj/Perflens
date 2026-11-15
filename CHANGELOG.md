# Changelog

## 0.1.0 (unreleased)

First public version.

- Slow-query, N+1 and connection-pool findings from JDBC (datasource-proxy), with real bind parameters and an executable statement
- R2DBC support (opt-in via `io.r2dbc:r2dbc-proxy`), including per-request N+1 detection in WebFlux apps
- Method timing with arguments and a SQL / HTTP / other breakdown, via Spring AOP on your base packages
- CPU flame graph, hot methods, allocation, GC, lock and virtual-thread-pinning evidence from an in-process JFR stream
- Outbound HTTP for `RestClient`, `RestTemplate` and `WebClient`: slow calls, errors, sequential waterfalls
- Deterministic suggestions for SQL and hot-method patterns
- Daily snapshots and a "what got worse" comparison
- Circuit breaker that pauses collection under JVM memory or GC pressure
- Real parameter values are not recorded when a `prod` / `production` profile is active, unless enabled explicitly. That also removes raw SQL text, outbound URL paths and exception messages from examples
- Requests are named by route pattern only, never by raw URL; unmatched requests share one `[unmapped]` entry; outbound URLs built by concatenation collapse id segments into `{id}`; endpoint and route tables keep counting in an overflow bucket once full
- Warning at startup when the app is compiled without `-parameters` (argument masking needs parameter names)
- Embedded UI at `/actuator/perflens`
