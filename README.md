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
  <groupId>dev.perflens</groupId>
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
| Outbound HTTP | Spring's built-in observations for `RestClient` / `RestTemplate` built from Boot's builders | `new RestTemplate()` and WebClient are not observed yet; their socket waits still show under *Slow socket I/O* |
| Request context | servlet filter per request; the first application method opens a context for jobs, listeners and async work | ThreadLocal: async hand-offs start a new context |

*Base packages* default to your `@SpringBootApplication` package.

**About CPU sample counts:** JFR drops a sample whenever it can't walk the thread's stack at that instant. On a Windows dev box with JDK 24 we measured ~50% of the nominal rate for realistic code, and far less for tight JIT-compiled loops (for example inside an in-memory database). Percentages remain meaningful, but low-traffic apps need a few minutes of load before a method crosses the hot-method thresholds (`perflens.jfr.min-samples`, `perflens.jfr.hot-method-percent`). JDK 25's cooperative sampling (JEP 518) improves accuracy. The overview reports `jfrEvents` and `jfrHandlerErrors` so you can check that the collector is healthy.

Argument and parameter values are rendered **without calling `toString()`**, because on JPA entities that can trigger lazy loading. Entities are shown as `Order{id=42}`, collections by size plus their first elements, and long strings are truncated.

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
```

## API

| | |
|---|---|
| `GET /actuator/perflens` | UI |
| `GET /actuator/perflens/report` | everything (JSON) |
| `GET /actuator/perflens/findings` | ranked findings |
| `GET /actuator/perflens/flamegraph` | CPU tree (d3-flame-graph format) |
| `POST /actuator/perflens/snapshot` | write a gzipped JSON snapshot to `perflens.storage.path` |
| `POST /actuator/perflens/reset` | clear collected evidence |

A snapshot is also written on graceful shutdown.

## Modules

- `perflens-core`: plain-JDK engine (JFR profiler, SQL fingerprinting, registries, rules, suggestion catalog). No Spring.
- `perflens-autoconfigure`: Spring Boot wiring (DataSource proxy, method advisor, servlet filter, observation handler, actuator endpoint, UI).
- `perflens-spring-boot-starter`: the one dependency to add.
- `perflens-demo`: sample app plus an end-to-end test that checks every planted bottleneck is reported.

Suggestions come from deterministic rules: `rules/SqlAdvisor` and `rules/HotMethodAdvisor` in core. To add a pattern, add an entry there.
