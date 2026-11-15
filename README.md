# PerfLens

[![CI](https://github.com/mihirraj/Perflens/actions/workflows/ci.yml/badge.svg)](https://github.com/mihirraj/Perflens/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A performance advisor that runs **inside your Spring Boot app**. Add one dependency, start the app, and open `/actuator/perflens`. PerfLens tells you what is slow, where in your code, and with which real inputs, and suggests a fix.

No `-javaagent`, no APM server, no database to run.

- **Slow queries with their real bind parameters**, plus a copy-paste-ready statement for `EXPLAIN ANALYZE`
- **N+1 queries**: the statement, how many times per request, and the line that triggers them (servlet and WebFlux)
- **Hot methods** (CPU, via JDK Flight Recorder): which method and line burn the CPU, with concrete suggestions
- **Slow method calls with their arguments**, each split into SQL / HTTP / other time
- **Network**: slow or failing outbound calls, sequential call "waterfalls" that could run in parallel, slow raw socket I/O
- **JVM**: GC pauses, lock contention, virtual-thread pinning, allocation hotspots
- **"What got worse"**: compare today with any earlier snapshot

> **Status: early (0.1, not yet on Maven Central).** It targets dev and staging first and records real parameter values there. Read [Privacy and safety](#privacy-and-safety) before turning it on in production.

## Quick start

Requirements: **Java 17+** on a HotSpot JVM (for JFR), **Spring Boot 3.5**.

PerfLens is not on Maven Central yet, so install it to your local Maven repository first:

```bash
git clone https://github.com/mihirraj/Perflens.git
cd Perflens
./mvnw install -DskipTests        # Windows: mvnw.cmd install -DskipTests
```

Then add the starter to your app:

```xml
<dependency>
  <groupId>io.github.mihirraj</groupId>
  <artifactId>perflens-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```kotlin
// Gradle: add mavenLocal() to repositories
implementation("io.github.mihirraj:perflens-spring-boot-starter:0.1.0-SNAPSHOT")
```

Start the app and open `http://localhost:8080/actuator/perflens`. The startup log prints the exact URL.

### Try the demo first

The repository has a demo shop with deliberate problems: N+1 queries, a slow self-join, `LOWER(col) LIKE '%x%'`, `new ObjectMapper()` in a loop, three sequential downstream calls, and a flaky dependency. A built-in load generator drives traffic, so findings appear within a minute or two.

```bash
./mvnw install -DskipTests
java -jar perflens-demo/target/perflens-demo-0.1.0-SNAPSHOT.jar
# open http://localhost:8080/actuator/perflens
```

## What you see

The UI is a single page served by the actuator endpoint:

- **Findings**: ranked problems with the evidence behind each (real parameters, caller line, counts, timings) and suggested fixes
- **Queries, Methods, HTTP, Endpoints**: the raw evidence, sortable, with the slowest executions kept as examples
- **Flame graph**: where CPU time goes, by method
- **JVM**: GC, locks, virtual-thread pinning, top allocated types
- **Changes**: regressions, improvements, new and resolved findings since a snapshot

Everything in the UI is also available as JSON (see [API](#api)).

## How it collects evidence

| What | How | Limits |
|---|---|---|
| SQL, bind params, timing, pool wait | wraps every `DataSource` bean with [datasource-proxy](https://github.com/jdbc-observations/datasource-proxy) | |
| R2DBC SQL, bind params, timing | wraps every R2DBC `ConnectionFactory` with [r2dbc-proxy](https://github.com/r2dbc/r2dbc-proxy). **Opt-in: add `io.r2dbc:r2dbc-proxy`** to your app | For N+1 findings the caller line is unknown: reactive queries run after the method that built them has returned |
| Request context (servlet) | servlet filter per request; the first application method opens a context for jobs, listeners and async work | ThreadLocal: `@Async` / `CompletableFuture` hand-offs start a new context |
| Request context (WebFlux) | `WebFilter` puts the request context into the Reactor context; R2DBC queries and `WebClient` calls made for the request are attributed to it | |
| Method timing + arguments | Spring AOP advisor on public methods of beans in your base packages | Can't see private methods or `this.foo()` self-calls. Skips final classes, and methods returning `Mono`/`Flux`/`CompletableFuture` (their timing would only measure pipeline assembly) |
| CPU / allocation / locks / GC / sockets | in-process JFR `RecordingStream` | HotSpot JVMs; ~1 s delay |
| Outbound HTTP | Spring's built-in observations for `RestClient`, `RestTemplate` and `WebClient` | Only clients built from Boot's auto-configured builders. `new RestTemplate()` / `WebClient.create()` bypass observations; their socket waits still show under *Slow socket I/O* |

*Base packages* default to your `@SpringBootApplication` package. Set `perflens.base-packages` to change them.

**In `@SpringBootTest`:** calls the test makes through `TestRestTemplate` or `WebTestClient` are outbound HTTP too, so they appear under HTTP. Look at the endpoint and finding sections to judge the app itself, or set `perflens.enabled=false` in tests that don't need PerfLens.

Values are rendered **without calling `toString()`**, because on JPA entities that can trigger lazy loading. Entities show as `Order{id=42}`, collections by size plus their first elements, and long strings are truncated.

**About CPU sample counts:** JFR drops a sample whenever it can't walk the thread's stack at that instant. On a Windows dev box with JDK 24 we measured ~50% of the nominal rate for realistic code, and far less inside tight JIT-compiled loops. Percentages stay meaningful, but low-traffic apps need a few minutes of load before a method crosses the hot-method thresholds (`perflens.jfr.min-samples`, `perflens.jfr.hot-method-percent`). JDK 25's cooperative sampling (JEP 518) improves accuracy. The overview reports `jfrEvents` and `jfrHandlerErrors` so you can check that the collector is healthy.

## Overhead

`OverheadBenchmark` in the demo runs the same closed-loop workload (4 client threads, 15 s warm-up, 30 s measurement) with PerfLens off and on, alternating off/on/off/on. Measured on a Windows 11 laptop with JDK 24:

| Setting | Throughput | CPU per request | p50 latency |
|---|---|---|---|
| All collectors on | −5 to −6% | **+5 to +6% (+0.05 ms)** | +0.11 ms |
| Method timing off | same | same | same |
| SQL interception off | ≈0 | ≈0 | ≈0 |

How to read this:

- **This workload is a deliberate worst case.** Requests hit an in-memory H2 database, cost only ~0.8 ms of CPU each, and run ~40 SQL statements each (the demo's N+1).
- **The cost is a fixed ~1–2 µs per SQL statement**, not a percentage. About two thirds of it is datasource-proxy's own proxying; method timing and JFR sampling cost nothing measurable. Against a real database, where a statement takes 0.1 ms to several ms, that is well under 1%.

Run it yourself:

```bash
./mvnw -pl perflens-demo test -Dtest=OverheadBenchmark -Dsurefire.failIfNoSpecifiedTests=false -Dbenchmark=true
# switch collectors off for the 'on' runs (';'-separated):
#   -Dbench.on="perflens.methods.enabled=false;perflens.jfr.enabled=false"
```

## Privacy and safety

PerfLens targets **dev and staging first**, so it captures real values by default:

- Values whose parameter name or SQL column matches `perflens.capture.mask-names` are replaced with `***`. The defaults cover password, secret, token, apikey, authorization, ssn, card, cvv, iban and pin. Query-string params on outbound URLs are masked the same way.
- **When a `prod` or `production` profile is active, values are not recorded** unless you set `perflens.capture.params=true` explicitly. The startup log says when this applies. Change the profile names with `perflens.capture.off-in-profiles`. If production runs without such a profile, set `perflens.capture.params=false` yourself. Without value capture:
  - SQL examples show the statement with literals removed (`WHERE id = ?`), no bind values and no executable statement
  - method arguments show as `?`
  - outbound calls show the route (`/items/{id}`), not the URL
  - errors show the exception type, not its message
- Requests are always named by their route pattern (`GET /users/{id}`), never by the raw URL, in every mode. Requests that match no route share one `[unmapped]` entry. Outbound URLs built by string concatenation have numeric, UUID and long hex segments replaced by `{id}`.
- Argument masking matches parameter names, so the app must be compiled with `-parameters` (Spring Boot's Maven and Gradle plugins do this by default). PerfLens logs a warning if it isn't.
- Exposure: if the app has **not** configured `management.endpoints.web.exposure.include`, PerfLens exposes `health,perflens`. If you configured exposure yourself, add `perflens` to it. Turn this behaviour off with `perflens.auto-expose=false`.
- The UI and API are a normal actuator endpoint, so they follow your Spring Security rules and management port. An `/actuator/**` permit-all rule exposes PerfLens too.

### Circuit breaker

PerfLens checks the host JVM every 5 s. If old-generation occupancy after GC stays above 85%, or GC takes more than 10% of wall time, for two checks in a row, it stops JFR, frees its flame-graph memory and stops recording. The UI shows a *PAUSED* banner with the reason. Collection resumes after six consecutive calm checks (about 30 s) with heap at least 15 points under the limit.

## Snapshots and "what got worse"

Snapshots are gzipped JSON files written daily at 18:00 (`perflens.storage.snapshot-cron`; `-` turns this off), on graceful shutdown, and on demand. Each records the application name, version and git commit when `git.properties` / `build-info` are available. The **Changes** tab compares the current state with any snapshot:

- **Regressions** are ranked by `relative change × ln(1 + ms consumed now)`, so a +300% on something that barely runs ranks low.
- Only traffic-independent metrics are compared: avg/p95 time, SQL queries per request, outbound calls per request, CPU share and error rate. A busier day is not a regression.
- Also shown: new and resolved findings, improvements, and entrypoints / SQL statements that didn't exist before.

After each **scheduled** snapshot PerfLens starts a fresh window, so each daily snapshot covers exactly one day. Turn this off with `perflens.storage.reset-after-scheduled-snapshot=false`. Manual snapshots never reset; the UI warns when the compared windows overlap. Tune with `perflens.storage.compare-min-change-percent` (default 25) and `perflens.storage.compare-min-score` (default 1.0).

**Containers:** `./.perflens` lives inside the container and disappears on redeploy. Mount a volume and point `perflens.storage.path` at it to keep a baseline across deploys.

## Configuration

Everything is optional:

```properties
perflens.enabled=true
perflens.base-packages=com.acme
# default: true, but false when one of these profiles is active
perflens.capture.params=true
perflens.capture.off-in-profiles=prod,production
perflens.capture.mask-names=password,secret,token,ssn
perflens.capture.exemplars-per-item=5
perflens.sql.enabled=true
perflens.sql.slow-query-threshold=100ms
perflens.sql.nplusone-threshold=10
perflens.sql.pool-wait-threshold=20ms
perflens.methods.enabled=true
perflens.methods.slow-threshold=300ms
perflens.http.enabled=true
perflens.http.slow-threshold=500ms
perflens.http.slow-endpoint-threshold=1000ms
perflens.jfr.enabled=true
perflens.jfr.cpu-sample-interval=10ms
perflens.jfr.hot-method-percent=10
perflens.storage.path=./.perflens
perflens.storage.retention-days=14
perflens.circuit-breaker.max-heap-after-gc-percent=85
perflens.circuit-breaker.max-gc-time-percent=10
```

## API

| | |
|---|---|
| `GET /actuator/perflens` | UI |
| `GET /actuator/perflens/report` | everything (JSON) |
| `GET /actuator/perflens/findings` | ranked findings |
| `GET /actuator/perflens/flamegraph` | CPU tree (d3-flame-graph format) |
| `GET /actuator/perflens/snapshots` | stored snapshots, newest first |
| `GET /actuator/perflens/compare?baseline=<name>` | what changed since a snapshot (newest when omitted) |
| `POST /actuator/perflens/snapshot` | write a snapshot to `perflens.storage.path` |
| `POST /actuator/perflens/reset` | clear collected evidence |

## Modules

- `perflens-core`: plain-JDK engine (JFR profiler, SQL fingerprinting, registries, rules, suggestion catalog). No Spring.
- `perflens-autoconfigure`: Spring Boot wiring (DataSource and R2DBC proxies, method advisor, servlet and WebFlux filters, observation handlers, actuator endpoint, UI).
- `perflens-spring-boot-starter`: the one dependency to add.
- `perflens-demo`: sample app plus an end-to-end test that checks every planted bottleneck is reported.

## Roadmap

- Publish to Maven Central
- Spring Boot 4 support
- Lower per-statement JDBC overhead (replace datasource-proxy with direct wrappers)
- More suggestion patterns

## Contributing

Bug reports, new suggestion patterns and fixes are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for how to build, test and send a change. Suggestions come from deterministic rules in `perflens-core` (`rules/SqlAdvisor`, `rules/HotMethodAdvisor`), and adding a pattern is a good first contribution.

## License

[MIT](LICENSE)
