package dev.perflens.autoconfigure.endpoint;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.perflens.core.PerfLens;
import dev.perflens.core.PerfLensConfig;
import dev.perflens.core.diff.ReportDiff;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.support.CronExpression;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Writes the full evidence report to {@code <path>/snapshot-<timestamp>.json.gz} on a schedule,
 * on shutdown and on demand; deletes snapshots past retention; and compares the current state
 * against any snapshot to show what got worse.
 *
 * <p>In containers, point {@code perflens.storage.path} at a mounted volume, otherwise snapshots
 * (and therefore the baseline to compare against) disappear with each redeploy.
 */
public final class PerfLensSnapshots implements InitializingBean, DisposableBean {
    private static final Log log = LogFactory.getLog(PerfLensSnapshots.class);
    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** Only names we produce are ever opened: the baseline parameter cannot traverse paths. */
    private static final Pattern SNAPSHOT_NAME = Pattern.compile("snapshot-\\d{8}-\\d{6}(-\\d+)?\\.json\\.gz");

    private final PerfLens perfLens;
    private final ObjectMapper mapper;
    private ScheduledExecutorService scheduler;

    public PerfLensSnapshots(PerfLens perfLens, ObjectMapper mapper) {
        this.perfLens = perfLens;
        this.mapper = mapper.copy().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    private PerfLensConfig.Storage storage() {
        return perfLens.config().getStorage();
    }

    private Path dir() {
        return Paths.get(storage().getPath()).toAbsolutePath().normalize();
    }

    // ---------------- write ----------------

    public synchronized Path write() throws IOException {
        Path dir = dir();
        Files.createDirectories(dir);
        String base = "snapshot-" + LocalDateTime.now().format(NAME);
        Path file = dir.resolve(base + ".json.gz");
        for (int i = 2; Files.exists(file); i++) file = dir.resolve(base + "-" + i + ".json.gz");
        Map<String, Object> report = perfLens.report();
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            mapper.writeValue(out, report);
        }
        purgeOld(dir);
        return file;
    }

    private void purgeOld(Path dir) throws IOException {
        Instant cutoff = Instant.now().minus(storage().getRetentionDays(), ChronoUnit.DAYS);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (SNAPSHOT_NAME.matcher(p.getFileName().toString()).matches()
                        && Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    // ---------------- read ----------------

    /** Snapshots, newest first: name, size, time. */
    public List<Map<String, Object>> list() throws IOException {
        Path dir = dir();
        if (!Files.isDirectory(dir)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String name = p.getFileName().toString();
                if (!SNAPSHOT_NAME.matcher(name).matches()) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("bytes", Files.size(p));
                m.put("modified", Files.getLastModifiedTime(p).toMillis());
                out.add(m);
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> (String) m.get("name")).reversed());
        return out;
    }

    public Map<String, Object> load(String name) throws IOException {
        if (name == null || !SNAPSHOT_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("not a snapshot name: " + name);
        }
        Path file = dir().resolve(name);
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            return mapper.readValue(in, new TypeReference<Map<String, Object>>() {});
        }
    }

    /**
     * Compares the current evidence with a snapshot (the newest one when {@code baselineName} is
     * null). Both sides go through the same JSON mapping so they are compared like for like.
     */
    public Map<String, Object> compare(String baselineName) throws IOException {
        List<Map<String, Object>> available = list();
        String name = baselineName;
        if (name == null || name.isBlank()) {
            if (available.isEmpty()) {
                Map<String, Object> none = new LinkedHashMap<>();
                none.put("error", "No snapshot to compare with yet. Take one with the Snapshot button (or POST /actuator/perflens/snapshot); one is also written daily and on shutdown.");
                none.put("snapshots", available);
                return none;
            }
            name = (String) available.get(0).get("name");
        }
        Map<String, Object> baseline = load(name);
        Map<String, Object> current = mapper.convertValue(perfLens.report(), new TypeReference<Map<String, Object>>() {});
        PerfLensConfig.Storage s = storage();
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("snapshot", name);
        b.put("meta", baseline.get("meta"));
        out.put("baseline", b);
        out.put("current", Map.of("meta", current.getOrDefault("meta", Map.of())));
        out.put("snapshots", available);
        out.putAll(ReportDiff.diff(baseline, current, s.getCompareMinChangePercent() / 100.0, s.getCompareMinScore()));
        return out;
    }

    // ---------------- schedule ----------------

    @Override
    public void afterPropertiesSet() {
        String cron = storage().getSnapshotCron();
        if (!storage().isEnabled() || cron == null || cron.isBlank() || cron.equals("-")) return;
        CronExpression expression;
        try {
            expression = CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            log.warn("PerfLens: invalid perflens.storage.snapshot-cron '" + cron + "': " + e.getMessage());
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PerfLens-snapshots");
            t.setDaemon(true);
            return t;
        });
        scheduleNext(expression);
    }

    private void scheduleNext(CronExpression expression) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = expression.next(now);
        if (next == null || scheduler == null) return;
        long delay = Math.max(1000, Duration.between(now, next).toMillis());
        scheduler.schedule(() -> {
            try {
                if (hasData()) {
                    log.info("PerfLens scheduled snapshot written to " + write());
                    // each scheduled snapshot then covers exactly one period, so comparing two of them
                    // (or the current window with the last one) compares like with like
                    if (storage().isResetAfterScheduledSnapshot()) perfLens.reset();
                }
            } catch (Exception e) {
                log.warn("PerfLens scheduled snapshot failed: " + e);
            } finally {
                scheduleNext(expression);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private boolean hasData() {
        return perfLens.sql().stats().size() + perfLens.methods().stats().size()
                + perfLens.traces().entrypoints().size() > 0 || perfLens.profiler().totalSamples() > 0;
    }

    @Override
    public void destroy() {
        if (scheduler != null) scheduler.shutdownNow();
        if (!storage().isEnabled() || !storage().isSnapshotOnShutdown() || !hasData()) return;
        try {
            log.info("PerfLens evidence snapshot written to " + write());
        } catch (Exception e) {
            log.warn("PerfLens could not write shutdown snapshot: " + e);
        }
    }
}
