# Contributing to PerfLens

Thanks for helping. Bug reports, new suggestion patterns, docs fixes and code are all welcome.

## Reporting a bug

Open an issue with:

- Spring Boot version, JDK vendor and version, OS
- Servlet or WebFlux, JDBC or R2DBC, and which HTTP clients you use
- What you expected PerfLens to report and what it reported instead
- If you can, the relevant part of `GET /actuator/perflens/report`. **Check it for real data first**: by default it contains real SQL parameters and method arguments.

## Building

You need JDK 17 or newer. The Maven wrapper downloads Maven itself.

```bash
./mvnw install          # build and run all tests
./mvnw install -DskipTests
```

The UI syntax check (`UiScriptSyntaxTest`) runs `node --check` on the UI's script and is skipped when Node.js is not installed.

To see your change in a running app, use the demo shop:

```bash
java -jar perflens-demo/target/perflens-demo-0.1.0-SNAPSHOT.jar
# open http://localhost:8080/actuator/perflens
```

## Where things live

- `perflens-core`: the engine, with no Spring dependency. Registries (`sql`, `method`, `http`, `trace`), the JFR profiler (`jfr`), rules and suggestions (`rules`), snapshot comparison (`diff`).
- `perflens-autoconfigure`: everything that hooks into Spring: JDBC/R2DBC proxies, the method advisor, servlet and WebFlux filters, HTTP client observation handlers, the actuator endpoint, and the UI (`src/main/resources/perflens/ui/index.html`, a single file with no build step).
- `perflens-demo`: a shop with deliberate bottlenecks. `PerfLensEndToEndTest` checks that each one is reported; new detections should get a planted example and an assertion there.

## Adding a suggestion pattern

Suggestions are deterministic rules, not AI. SQL advice lives in `rules/SqlAdvisor`, hot-method advice in `rules/HotMethodAdvisor`. Add the pattern, a unit test next to the existing ones (`HotMethodAdvisorTest`), and keep the advice concrete: say what to change, not just what is wrong.

## Performance

PerfLens runs on every SQL statement and bean method call of the host app, so the hot paths matter. If your change touches `SqlRegistry.record`, `MethodRegistry.record`, the listeners or the filters, run the overhead benchmark before and after and put both results in the pull request:

```bash
./mvnw -pl perflens-demo test -Dtest=OverheadBenchmark -Dsurefire.failIfNoSpecifiedTests=false -Dbenchmark=true
```

Run `./mvnw install -DskipTests` first: the benchmark uses the installed core and autoconfigure jars. Results vary by a few percent between runs; CPU per request is the most stable number.

## Pull requests

- One topic per pull request, with tests for behaviour changes.
- `./mvnw install` must pass.
- Match the surrounding code style. There is no formatter config; keep lines under ~130 characters and comment the *why*, not the *what*.
- By contributing you agree that your contribution is licensed under the [MIT License](LICENSE).
