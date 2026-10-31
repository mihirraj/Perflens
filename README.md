# PerfLens

A performance advisor that runs **inside your Spring Boot app**: add one dependency and it starts collecting evidence, then tells you what is slow, where in your code, and with which real inputs.

- **Slow queries with their real bind parameters**, including a copy-paste-ready statement for `EXPLAIN ANALYZE`
- **N+1 queries**: the statement, how many times per request, and the exact line that triggers them
- **Hot methods (CPU, via JDK Flight Recorder)**: which library call and which line burn the CPU, with concrete fix suggestions
- **Slow method calls with their arguments**, with each call split into SQL / HTTP / other time
- **Network**: slow or failing outbound calls, sequential call waterfalls, slow raw socket I/O
- **JVM**: GC pauses, lock contention, virtual-thread pinning, allocation hotspots
- **Embedded UI** at `/actuator/perflens`

No `-javaagent`, no external server, no database.

## Quick start

```xml
<dependency>
  <groupId>io.github.mihirraj</groupId>
  <artifactId>perflens-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Start the app and open `http://localhost:8080/actuator/perflens`. The startup log prints the exact URL.

Requirements: Java 17+ on a HotSpot JVM (JFR), Spring Boot 3.5.

### Try the demo

Needs only a JDK 17+ (`JAVA_HOME`). The Maven wrapper downloads Maven itself.

```bash
./mvnw install            # Windows: mvnw.cmd install   (add -DskipTests for a faster build)
java -jar perflens-demo/target/perflens-demo-0.1.0-SNAPSHOT.jar
# open http://localhost:8080/actuator/perflens
```

The demo shop has deliberate problems (N+1, slow self-join, `LOWER(col) LIKE '%x%'`, `new ObjectMapper()` in a loop, three sequential downstream calls, a flaky dependency). A built-in load generator drives traffic.

## How it collects evidence

| What | How | Limits |
|---|---|---|
| SQL, bind params, timing, pool wait | wraps every `DataSource` bean with [datasource-proxy](https://github.com/jdbc-observations/datasource-proxy) | JDBC only (no R2DBC yet) |
| Method timing + arguments | Spring AOP advisor on public methods of beans in your base packages | Spring AOP can't see private methods or `this.foo()` self-calls. Final classes are skipped. |
| CPU / allocation / locks / GC / sockets | in-process JFR `RecordingStream` | HotSpot JVMs; ~1 s delay |
| Outbound HTTP | Spring's built-in observations for `RestClient`, `RestTemplate` and `WebClient` built from Boot's builders | Clients created with `new RestTemplate()` / `WebClient.create()` bypass observations; their socket waits still show under *Slow socket I/O* |
| Request context | servlet filter per request; the first application method opens a context for jobs, listeners and async work | ThreadLocal: async hand-offs start a new context |

*Base packages* default to your `@SpringBootApplication` package.

**About CPU sample counts:** JFR drops a sample whenever it can't walk the thread's stack at that instant. On a Windows dev box with JDK 24 we measured ~50% of the nominal rate for realistic code, and far less for tight JIT-compiled loops (for example inside an in-memory database). Percentages remain meaningful, but low-traffic apps need a few minutes of load before a method crosses the hot-method thresholds (`perflens.jfr.min-samples`, `perflens.jfr.hot-method-percent`). JDK 25's cooperative sampling (JEP 518) improves accuracy. The overview reports `jfrEvents` and `jfrHandlerErrors` so you can check that the collector is healthy.

Argument and parameter values are rendered **without calling `toString()`**, because on JPA entities that can trigger lazy loading. Entities are shown as `Order{id=42}`, collections by size plus their first elements, and long strings are truncated.

## Overhead (measured)

`OverheadBenchmark` in the demo runs the same closed-loop workload (4 client threads, 15 s warm-up, 30 s measurement) with PerfLens off and on, alternating off/on/off/on. Measured on a Windows 11 laptop with JDK 24:

| Setting | Throughput | CPU per request | p50 | p99 |
|---|---|---|---|---|
| PerfLens on (all collectors) | −9.6% | +10.5% (+0.086 ms) | +0.17 ms | +0.09 ms |
| PerfLens on, JFR disabled | −12.7% | +11.3% (+0.09 ms) | +0.19 ms | +0.21 ms |

How to read this:
- **This workload is a deliberate worst case.** Requests hit an in-memory H2 database and cost only ~0.8 ms of CPU each, yet run ~40 SQL statements each (the demo's N+1), so per-statement instrumentation dominates.
- **The cost is a fixed ~2–4 µs per instrumented operation** (JDBC statement, bean method call), not a percentage. A typical 10–100 ms request with tens of queries pays the same ~0.1–0.2 ms, i.e. well under 1%.
- **JFR sampling is not where the cost goes.** Disabling it changes nothing measurable; the cost is in the JDBC proxy and method hooks, which are the next optimization target.

Run it yourself:

```bash
./mvnw -pl perflens-demo test -Dtest=OverheadBenchmark -Dsurefire.failIfNoSpecifiedTests=false -Dbenchmark=true
# compare variants: -Dbench.on=perflens.jfr.enabled=false
```

## Privacy and safety

PerfLens targets **dev and staging first**, so it captures real values by default:

- Values whose parameter name or SQL column matches `perflens.capture.mask-names` are replaced with `***`. The defaults cover password, secret, token, apikey, authorization, ssn, card, cvv, iban and pin. Query-string params on outbound URLs are masked the same way.
- `perflens.capture.params=false` turns off value capture completely. Do this for production.
- Exposure: if the app has **not** configured `management.endpoints.web.exposure.include`, PerfLens exposes `health,perflens`. If you configured exposure yourself, add `perflens` to it. Turn this behaviour off with `perflens.auto-expose=false`.
- The UI and API are a normal actuator endpoint, so they follow your Spring Security rules and management port. Note that an `/actuator/**` permit-all rule exposes PerfLens too.

## Configuration

Everything is optional:

```properties
perflens.enabled=true
perflens.base-packages=com.acme
perflens.capture.params=true
perflens.capture.mask-names=password,secret,token,ssn
perflens.capture.exemplars-per-item=5
perflens.sql.slow-query-threshold=100ms
perflens.sql.nplusone-threshold=10
perflens.sql.pool-wait-threshold=20ms
perflens.methods.slow-threshold=300ms
perflens.http.slow-threshold=500ms
perflens.http.slow-endpoint-threshold=1000ms
perflens.jfr.cpu-sample-interval=20ms
perflens.jfr.hot-method-percent=10
perflens.storage.path=./.perflens
perflens.storage.retention-days=14
perflens.circuit-breaker.max-heap-after-gc-percent=85
perflens.circuit-breaker.max-gc-time-percent=10
```

### Circuit breaker

PerfLens checks the host JVM every 5 s. If old-generation occupancy after GC stays above 85%, or GC takes more than 10% of wall time, for two checks in a row, it stops JFR, frees its flame-graph memory and stops recording. The UI shows a *PAUSED* banner with the reason. Collection resumes after six consecutive calm checks (about 30 s) with heap at least 15 points under the limit.

## API

| | |
|---|---|
| `GET /actuator/perflens` | UI |
| `GET /actuator/perflens/report` | everything (JSON) |
| `GET /actuator/perflens/findings` | ranked findings |
| `GET /actuator/perflens/flamegraph` | CPU tree (d3-flame-graph format) |
| `GET /actuator/perflens/snapshots` | stored snapshots, newest first |
| `GET /actuator/perflens/compare?baseline=<name>` | what changed since a snapshot (newest when omitted) |
| `POST /actuator/perflens/snapshot` | write a gzipped JSON snapshot to `perflens.storage.path` |
| `POST /actuator/perflens/reset` | clear collected evidence |

## Snapshots and "what got worse"

Snapshots are written daily at 18:00 (`perflens.storage.snapshot-cron`; set it to `-` to turn this off), on graceful shutdown, and on demand. Each one records the application name, version and git commit when `git.properties` / `build-info` are available. The **Changes** tab compares the current state with any snapshot:

- **Regressions** are ranked by `relative change × ln(1 + ms consumed now)`, so a +300% on something that barely runs ranks low.
- Only traffic-independent metrics are compared: avg/p95 time, SQL queries per request, outbound calls per request, CPU share and error rate. A busier day is not a regression.
- Also shown: new and resolved findings, improvements, and entrypoints / SQL statements that didn't exist before.

After each **scheduled** snapshot PerfLens starts a fresh window, so each daily snapshot covers exactly one day and comparisons are like for like. Turn this off with `perflens.storage.reset-after-scheduled-snapshot=false`. Manual snapshots never reset; the UI warns when the compared windows overlap.

Tune with `perflens.storage.compare-min-change-percent` (default 25) and `perflens.storage.compare-min-score` (default 1.0).

**Containers:** `./.perflens` lives inside the container and disappears on redeploy. Mount a volume and point `perflens.storage.path` at it to keep a baseline across deploys.

## Modules

- `perflens-core`: plain-JDK engine (JFR profiler, SQL fingerprinting, registries, rules, suggestion catalog). No Spring.
- `perflens-autoconfigure`: Spring Boot wiring (DataSource proxy, method advisor, servlet filter, observation handler, actuator endpoint, UI).
- `perflens-spring-boot-starter`: the one dependency to add.
- `perflens-demo`: sample app plus an end-to-end test that checks every planted bottleneck is reported.

Suggestions come from deterministic rules: `rules/SqlAdvisor` and `rules/HotMethodAdvisor` in core. To add a pattern, add an entry there.
