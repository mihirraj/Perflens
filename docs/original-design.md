> This is the original design document PerfLens started from. Some decisions changed during the build (for example: real parameter capture by default, no SQLite or Zstd, Spring Boot 3.5 first, the UI served by the actuator endpoint itself). The [README](../README.md) describes what is actually built.

# **Architecture Design Document: spring-boot-starter-perf-lens**

| Metadata | Details |
| :---- | :---- |
| **Project Title** | **PerfLens** (spring-boot-starter-perf-lens) |
| **Target Runtime** | Java 17+ (Java 21+ Virtual Thread extensions), Spring Boot 3.2+ / 3.4+ |
| **Overhead Budget** | Less than 1.0% CPU overhead, less than 32 MB resident heap footprint |
| **Deployment Model** | In-process Maven/Gradle dependency (Zero \-javaagent, Zero external DB/APM servers) |
| **UI Delivery** | Pre-compiled static SPA served via Spring Boot Actuator (/actuator/perflens) |

## **1\. Executive Summary & Problem Statement**

Java and Spring Boot engineering teams face a visibility gap between local development and heavyweight production APM infrastructure:

> 1. **External APMs & Continuous Profilers** (Datadog, Pyroscope, New Relic, Dynatrace) require \-javaagent JVM flags, sidecar containers, DevOps infrastructure approvals, and costly licensing.  
> 2. **Spring Boot Actuator & Spring Boot Admin** expose high-level metrics (Micrometer counters, raw thread dumps, heap dumps) but cannot answer *"Which Java method is burning CPU today?"*, *"Which repository call introduced an N+1 query regression in today's run?"*, or *"Where are Virtual Threads getting pinned?"*  
> 3. **Desktop Profilers** (JProfiler, VisualVM, JDK Mission Control) require manual attachment and manual human interpretation of raw .jfr files.

**PerfLens** closes this gap via a **single Maven/Gradle starter dependency**. Upon application startup, it programmatically starts an in-process JDK Flight Recorder (RecordingStream) alongside transparent Spring BeanPostProcessor proxies for JDBC and HTTP clients. It aggregates runtime behavior in a bounded in-memory call-tree trie, persists lightweight daily rollup snapshots to local disk, and serves an embedded Web UI that translates raw telemetry into **ranked, code-level optimization recommendations**.

## **2\. Goals & Non-Goals**

### **Goals**

* **Zero-Flag Integration:** Must activate solely by adding the dependency to pom.xml or build.gradle—no \-javaagent or container entrypoint changes.  
* **Full-Stack Bottleneck Coverage:** Simultaneously track JVM internals (CPU hot methods, TLAB heap allocations, GC pauses, lock contention, Virtual Thread pinning) and Spring framework I/O (JDBC slow queries, JPA N+1 queries, connection pool starvation, sequential HTTP waterfalls).  
* **Automated Daily Diff & Prescriptive Advice:** Automatically evaluate daily or batch runs against deterministic heuristics (plus an optional Spring AI / LLM bridge) to output concrete Java/Spring fixes.  
* **Strictly Bounded Overhead:** Enforce hard memory caps and a self-disabling circuit breaker if application GC pressure exceeds safe thresholds.

### **Non-Goals**

* **Distributed Multi-Service Tracing:** PerfLens is an single-service/cluster-node code optimizer, not a replacement for Jaeger, Zipkin, or OpenTelemetry Collector meshes.  
* **Full Line-by-Line Bytecode Instrumentation:** Avoid runtime ASM/ByteBuddy re-transformation of user classes to guarantee startup speed and zero bytecode verification crashes.

## **3\. Multi-Module Repository Structure**

Plaintext  
perf-lens/  
├── perf-lens-core/                      \# Pure JDK 17+ engine (Zero Spring dependencies)  
│   ├── jfr/                             \# RecordingStream consumer & JMC rules adapter  
│   ├── trie/                            \# Bounded Call-Tree Trie & Flamegraph aggregator  
│   ├── sql/                             \# SQL normalizer, N+1 fingerprinter & latency tracker  
│   ├── rules/                           \# Heuristic evaluation engine & Daily Diff scorer  
│   └── store/                           \# Embedded H2 MVStore / SQLite snapshot repository  
├── perf-lens-autoconfigure/             \# Spring Boot 3 AutoConfiguration & Proxies  
│   ├── datasource/                      \# BeanPostProcessor wrapping DataSource  
│   ├── http/                            \# RestClient, WebClient & RestTemplate interceptors  
│   ├── web/                             \# Servlet Filter & @Scheduled Aspect trace context  
│   └── actuator/                        \# @RestControllerEndpoint exposing JSON API & UI  
├── perf-lens-ui/                        \# Preact/React \+ Vite \+ Tailwind \+ d3-flame-graph  
│   └── dist/ → META-INF/resources/      \# Compiled static bundle packaged into JAR  
└── perf-lens-spring-boot-starter/       \# Starter POM pulling core, autoconfigure, and ui

## **4\. Telemetry Collection Engine**

### **4.1 Correlation Context (TraceContextHolder)**

To correlate low-level JDBC/HTTP events with the originating Spring entrypoint (@RestController endpoint or @Scheduled batch method), PerfLens establishes a lightweight execution context:

* **Servlet / WebFlux Filter & @Scheduled Interceptor:** Generates a 64-bit traceId and captures the entrypoint signature (e.g., POST /api/v1/orders or BatchSettlementJob.runDaily()).  
* **Context Propagation:** Uses a ThreadLocal\<TraceWindow\> on JDK 17 (automatically upgrading to ScopedValue on JDK 21+ when Virtual Threads are active) to accumulate SQL statement fingerprints and outbound HTTP spans within that single logical execution unit.

### **4.2 In-Process JFR Collector (RecordingStream)**

Instead of dumping multi-gigabyte .jfr files to disk, JfrContinuousCollector subscribes to the JVM's native event stream in-memory via jdk.jfr.consumer.RecordingStream:

| JFR Event Name | Collection Mode | Threshold / Period | Bottleneck Targeted |
| :---- | :---- | :---- | :---- |
| jdk.ExecutionSample | Periodic Sampling | 10 ms | CPU-bound hot methods, expensive serialization, tight loops |
| jdk.ObjectAllocationSample | Periodic Throttled | 150 ops/sec | TLAB memory churn, excessive object creation, GC pressure |
| jdk.GarbageCollection | All Events | 0 ms | STW pause duration, Young vs. Old Gen collection frequency |
| jdk.JavaMonitorEnter | Threshold | 10 ms | Synchronized lock contention across platform threads |
| jdk.VirtualThreadPinned | Threshold | 5 ms | JDK 21+ carrier thread pinning inside synchronized or JNI |
| jdk.SocketRead / SocketWrite | Threshold | 50 ms | Uninstrumented blocking network calls (e.g., raw sockets, Redis/Kafka) |

**Stack Frame Filtering & Compression:** When an ExecutionSample or ObjectAllocationSample arrives, the collector walks the RecordedStackTrace bottom-up:

> 1. Frames matching user-configured base packages (auto-detected via @SpringBootApplication package root) are preserved in full (ClassName.methodName:lineNumber).  
> 2. Intermediate framework plumbing frames (org.springframework.aop.\*, jdk.internal.reflect.\*, org.apache.catalina.\*) are collapsed into single synthetic group nodes (\[Spring AOP Proxy\], \[Tomcat Container\]) to reduce trie memory by 75% and keep flamegraphs readable.

### **4.3 Database Interception & SQL Fingerprinting**

A custom BeanPostProcessor wraps any Spring DataSource bean in a lightweight dynamic proxy (or integrates with datasource-micrometer if already present):

> 1. **SQL Normalization (Fingerprinting):** Raw SQL queries are stripped of literal values using a fast zero-regex lexer so parameterized and unparameterized queries hash to the same fingerprint:  
>    SQL  
>    \-- Raw Input:  
>    SELECT \* FROM trade\_positions WHERE account\_id \= 94821 AND status IN ('OPEN', 'PENDING')  
>    \-- Normalized Fingerprint:  
>    SELECT \* FROM trade\_positions WHERE account\_id \= ? AND status IN (?)

> 2. **Per-Request N+1 Detection:** Inside the active TraceWindow, a Map\<SqlFingerprint, CallStats\> tracks execution counts. If a single HTTP request or batch step executes the same SqlFingerprint more than $N$ times (default: 10), the collector walks the current thread's stack trace (StackWalker.getInstance()) to record the exact Java line inside the user's @Service or @Repository that triggered the loop.  
> 3. **Connection Pool Wait Tracking:** Measures the delta between DataSource.getConnection() invocation and return. If acquisition latency exceeds 25 ms, it flags HikariCP connection pool starvation.

### **4.4 Outbound Network Waterfall Detection**

Auto-configured RestClientCustomizer, RestTemplateCustomizer, and WebClient ExchangeFilterFunction hooks record each outbound HTTP call's \[startTimestamp, endTimestamp, targetHost, uriTemplate, statusCode\] inside the active TraceWindow.

* **Sequential Waterfall Detection:** When the TraceWindow closes, if 3 or more outbound HTTP calls occurred sequentially (${t}_{start,i+1}\geq {t}_{end,i}$) and consumed more than 50% of total request latency, the entrypoint is flagged for async parallelization.

## **5\. Memory Management & Local Storage Design**

### **5.1 Bounded In-Memory Aggregation (Max 32 MB Heap)**

To guarantee PerfLens never causes an OutOfMemoryError in the host application:

* **Call-Tree Trie (BoundedCallTrie):** Stack traces are aggregated into an atomic prefix tree where each node stores only four primitive AtomicLong counters: cpuSamples, allocatedBytes, lockWaitNanos, and pinnedNanos.  
* **Node Cap & LRU Eviction:** The trie is hard-capped at 50,000 nodes (occupying roughly 8 MB of heap). Leaves with only 1 sample are pruned every 15 minutes during sliding window compaction.  
* **SQL Stats Map:** Capped at 2,000 unique normalized SQL fingerprints using a high-performance LFU eviction policy.

### **5.2 Embedded Daily Rollup Persistence**

At the end of a configured daily window (e.g., 18:00 cron, graceful shutdown hook, or manual UI trigger), PerfLens flushes a compressed snapshot to a single local file (./.perflens/perflens.mv.db via H2's standalone MVStore—which requires only a 300 KB jar and zero JDBC overhead, or SQLite).

SQL  
\-- Logical Schema for Embedded Snapshot Store  
CREATE TABLE daily\_snapshots (  
    snapshot\_id      VARCHAR(36) PRIMARY KEY,  
    run\_date         DATE NOT NULL,  
    git\_commit\_sha   VARCHAR(40),  
    uptime\_seconds   BIGINT,  
    total\_cpu\_samples BIGINT,  
    total\_gc\_pause\_ms BIGINT,  
    heap\_peak\_mb     INT,  
    flamegraph\_json  BLOB,  \-- Zstd-compressed Call-Tree Trie JSON (\~60 KB)  
    sql\_metrics\_json BLOB,  \-- Top 200 SQL queries & N+1 callers (\~40 KB)  
    http\_metrics\_json BLOB, \-- Top 100 outbound & inbound endpoints (\~25 KB)  
    suggestions\_json BLOB   \-- Evaluated Rule Findings & Diffs (\~15 KB)  
);

* **Retention Policy:** Automatically purges snapshots older than 14 days (configurable), keeping total disk usage under 5 MB.

## **6\. Heuristic Rules Engine & Daily Regression Scoring**

### **6.1 Built-in Deterministic Heuristics**

PerfLens merges **OpenJDK JMC Rules (org.openjdk.jmc:flightrecorder.rules.jdk)** for low-level JVM telemetry with **6 custom Spring Boot rules**:

| Rule ID | Trigger Condition | Severity Calculation | Prescriptive Recommendation Template |
| :---- | :---- | :---- | :---- |
| SPRING-SQL-01 **JPA N+1 Query** | count(SqlFingerprint) \>= 10 within a single TraceWindow | $\min\limits_{}(100,avgCount\times 2.5)$ | *"Method {callerClass}.{callerMethod}:{line} executed {sql} an average of {avgCount} times per call (total {totalTimeMs}ms). Add @EntityGraph or JOIN FETCH to the repository method, or batch fetch via @BatchSize(size \= 50)."* |
| SPRING-SQL-02 **Unindexed / Slow Query** | P95 execution time greater than 150 ms | $\min\limits_{}(100,p95Ms/10)$ | *"Query {sql} in {callerMethod} has a P95 latency of {p95Ms}ms. Inspect execution plan (EXPLAIN ANALYZE) on predicates {whereColumns}."* |
| SPRING-POOL-01 **Connection Pool Starvation** | getConnection() wait P95 greater than 30 ms | $\min\limits_{}(100,waitP95Ms\times 1.5)$ | *"Threads waited an average of {waitP95Ms}ms for a JDBC connection while DB CPU was low. Increase spring.datasource.hikari.maximum-pool-size or shorten @Transactional scope in {topHolderMethod}."* |
| JVM-CPU-01 **Hot Method / Serialization Loop** | Single user method exclusive/inclusive CPU samples greater than 15% of total app samples | $sampleSharePct\times 2.0$ | *"Method {class}.{method}:{line} consumed {pct}% of application CPU time today (dominated by {dominantCallee}). Consider caching, hoisting object mapping out of loops, or pre-compiling regex/Jackson writers."* |
| JVM-MEM-01 **TLAB Allocation Churn** | Method responsible for greater than 20% of ObjectAllocationSample bytes \+ Young GC frequency above baseline | $allocSharePct\times 1.8$ | *"Method {class}.{method}:{line} allocated {gbAllocated} GB of {topAllocatedClass} today, driving {gcCount} GC pauses. Switch to streaming parsers, primitive collections, or reuse buffers."* |
| JVM-VT-01 **Virtual Thread Pinning** | Any jdk.VirtualThreadPinned event exceeding 10 ms | $\min\limits_{}(100,maxPinnedMs/2)$ | *"Virtual thread pinned to carrier thread for {pinnedMs}ms at {class}.{method}:{line} while holding a synchronized monitor during blocking I/O. Replace synchronized with java.util.concurrent.locks.ReentrantLock."* |
| SPRING-NET-01 **Sequential HTTP Waterfall** | 3+ sequential outbound HTTP calls in one trace totaling greater than 200 ms | $\min\limits_{}(100,savableMs/5)$ | *"Method {class}.{method} executed {callCount} sequential REST calls totaling {totalMs}ms. Parallelize independent calls with CompletableFuture or StructuredTaskScope to save \~{savableMs}ms per request."* |

### **6.2 Daily Run Regression Diff Formula**

To highlight what degraded in **Today's Run (${T}_{1}$)** compared to **Yesterday's Baseline (${T}_{0}$)**, the engine computes a normalized **Regression Impact Score (${S}_{reg}$)** for every method and SQL fingerprint:

$${S}_{reg}(m)=\left({\frac{{W}_{1}(m)-{W}_{0}(m)}{\max\limits_{}({W}_{0}(m),\epsilon )}}\right)\times \ln \left({1+Impac{t}_{1}(m)}\right)$$

Where:

* ${W}_{t}(m)$ is the normalized weight of method/query $m$ on day $t$ (e.g., percentage share of total CPU samples, bytes allocated per request, or SQL queries per transaction, making it invariant to daily traffic volume fluctuations).  
* $Impac{t}_{1}(m)$ is the absolute wall-clock time or CPU seconds consumed on day ${T}_{1}$ (preventing a 500% increase in a 0.1ms method from triggering false alarms).  
* Any item with ${S}_{reg}(m)$ greater than threshold $\tau$ is surfaced at the top of the UI under **"New Regressions in Today's Run"**.

## **7\. Embedded Web UI & Actuator API Specification**

### **7.1 Actuator REST Endpoints**

Exposed via @RestControllerEndpoint(id \= "perflens") at /actuator/perflens:

* GET /actuator/perflens → Serves the SPA index.html (perf-lens-ui).  
* GET /actuator/perflens/api/overview?date=2026-10-31\&compareTo=2026-10-30 → Returns health scores, GC/CPU/SQL summary cards, and top regressions.  
* GET /actuator/perflens/api/suggestions → Returns ranked list of prescriptive code improvements with exact class, method, line number, and estimated savings.  
* GET /actuator/perflens/api/flamegraph?type=CPU\|ALLOCATION\|LOCK\&diff=true → Returns hierarchical d3-flame-graph JSON (with red/green differential coloring when diff=true).  
* GET /actuator/perflens/api/sql → Returns normalized SQL table, N+1 offenders, execution histograms, and caller stack traces.  
* POST /actuator/perflens/api/snapshot → Triggers an immediate rollup analysis and snapshot write.

### **7.2 Sample JSON Payload (/actuator/perflens/api/suggestions)**

JSON  
{  
  "runDate": "2026-10-31",  
  "comparedAgainst": "2026-10-30",  
  "gitCommit": "a8f921c",  
  "overallHealthScore": 74,  
  "recommendations": \[  
    {  
      "id": "rec\_901",  
      "ruleId": "SPRING-SQL-01",  
      "category": "DATABASE",  
      "severity": "CRITICAL",  
      "score": 92.5,  
      "regressionDelta": "+640% vs yesterday",  
      "title": "N+1 Query Detected in RiskPositionService.calculateExposure()",  
      "location": {  
        "className": "com.acme.risk.service.RiskPositionService",  
        "methodName": "calculateExposure",  
        "fileName": "RiskPositionService.java",  
        "lineNumber": 142  
      },  
      "evidence": {  
        "normalizedSql": "SELECT \* FROM instrument\_Delta WHERE position\_id \= ?",  
        "avgExecutionsPerRequest": 118.4,  
        "p95TotalLatencyMs": 412.0,  
        "entrypoint": "POST /api/v1/risk/evaluate"  
      },  
      "suggestedFix": "Replace lazy loop iteration at RiskPositionService.java:142 with a @Query(\\"SELECT p FROM Position p JOIN FETCH p.instrumentDeltas WHERE ...\\") or annotate Position.instrumentDeltas with @BatchSize(size \= 100).",  
      "estimatedImpact": "Reduces endpoint P95 latency by \~380ms (-82% DB roundtrips)"  
    }  
  \]  
}

### **7.3 Embedded UI Layout (4 Core Views)**

> 1. **Daily Advisor Briefing (Landing View):** A clean, high-signal feed showing the Overall Efficiency Score, a side-by-side comparison card (*Today vs. Yesterday*), and the prioritized list of **Actionable Code Fixes** with copyable code snippets.  
> 2. **Differential Flamegraph Explorer:** Toggle between **CPU Time**, **Heap Allocations (TLAB)**, and **Lock/Pinning Wait**. Includes a "Diff Mode" toggle that colors methods red if their CPU/memory share grew since yesterday's run and green if they improved.  
> 3. **Database & Network I/O Inspector:** Sortable table of normalized SQL queries (showing N+1 badges, total time, avg rows, Hikari pool wait) and outbound HTTP dependencies (highlighting sequential waterfalls).  
> 4. **JVM & GC Vital Signs:** Timeline charts of GC pause frequency, heap utilization after collection, and Virtual Thread pinning events.

## **8\. Configuration Properties (application.yml)**

PerfLens works out-of-the-box with zero configuration, but exposes fine-grained tuning via @ConfigurationProperties(prefix \= "perflens"):

YAML  
perflens:  
  enabled: true  
  base-packages:  
    \- com.yourcompany.app          \# Defaults to @SpringBootApplication package  
  jfr:  
    cpu-sample-interval: 10ms      \# Increase to 20ms for ultra-low overhead  
    lock-threshold: 10ms  
    virtual-thread-pin-threshold: 5ms  
  sql:  
    enabled: true  
    n-plus-one-threshold: 10       \# Queries per trace to trigger N+1 warning  
    slow-query-threshold: 150ms  
  storage:  
    path: ./.perflens/history.db   \# Embedded MVStore file path  
    retention-days: 14  
    daily-snapshot-cron: "0 0 18 \* \* \*" \# Auto-evaluate daily at 6:00 PM  
  circuit-breaker:  
    max-heap-usage-percent: 85     \# Pauses collection if Old Gen \> 85%  
  ai-advisor:  
    enabled: false                 \# Optional: Wire into Spring AI ChatModel for code diffs

## **9\. Security, Privacy & Production Safety**

> 1. **Zero PII Leakage (SQL Parameter Scrubbing):** The SQL normalizer strips all string and numeric literals *before* storing fingerprints in memory or on disk (WHERE ssn \= '123-45-6789' is immediately converted to WHERE ssn \= ? in the interception layer). Raw bind parameters are never persisted.  
> 2. **Self-Disabling Circuit Breaker:** A lightweight watchdog monitors MemoryMXBean. If tenured heap utilization exceeds 85% or GC time exceeds 10% of wall-clock time, PerfLens automatically pauses RecordingStream and flushes its trie buffer to ensure it never destabilizes a struggling JVM.  
> 3. **Actuator Security Inheritance:** Because the UI and API are mounted as a native Spring Boot Actuator endpoint (/actuator/perflens), they automatically obey the host application's Spring Security filters and Actuator exposure rules (management.endpoints.web.exposure.include).

## **10\. Phased Implementation Roadmap (MVP to Viral Launch)**

| Milestone | Scope | Target Duration |
| :---- | :---- | :---- |
| **M1: Core Telemetry & JFR Engine** | Build perf-lens-core with RecordingStream, BoundedCallTrie, package filtering, and the DataSource N+1 proxy interceptor. | Days 1–4 |
| **M2: Heuristic Rules & Snapshot Store** | Implement the 7 core Spring/JVM rules, daily regression diff math, and embedded H2 MVStore snapshot persistence. | Days 5–7 |
| **M3: AutoConfiguration & Embedded UI** | Build the React/Preact \+ Tailwind \+ d3-flame-graph SPA, wire the Actuator endpoint, and configure Maven resource bundling. | Days 8–12 |
| **M4: Demo App & Benchmark Suite** | Create a sample Spring Boot app with intentional bottlenecks (N+1, Jackson loop, pinned virtual thread) to record the README GIF and JMH overhead benchmarks. | Days 13–14 |

