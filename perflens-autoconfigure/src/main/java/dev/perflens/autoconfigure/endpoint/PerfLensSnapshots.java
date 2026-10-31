package dev.perflens.autoconfigure.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.perflens.core.PerfLens;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.DisposableBean;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Writes the full evidence report to {@code <path>/snapshot-<timestamp>.json.gz} on shutdown or
 * on demand, and deletes snapshots older than the retention period.
 */
public final class PerfLensSnapshots implements DisposableBean {
    private static final Log log = LogFactory.getLog(PerfLensSnapshots.class);
    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final PerfLens perfLens;
    private final ObjectMapper mapper;

    public PerfLensSnapshots(PerfLens perfLens, ObjectMapper mapper) {
        this.perfLens = perfLens;
        this.mapper = mapper.copy().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    public synchronized Path write() throws IOException {
        Path dir = Paths.get(perfLens.config().getStorage().getPath()).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        Path file = dir.resolve("snapshot-" + LocalDateTime.now().format(NAME) + ".json.gz");
        Map<String, Object> report = perfLens.report();
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            mapper.writeValue(out, report);
        }
        purgeOld(dir);
        return file;
    }

    public List<String> list() throws IOException {
        Path dir = Paths.get(perfLens.config().getStorage().getPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            List<String> out = new ArrayList<>();
            files.filter(p -> p.getFileName().toString().startsWith("snapshot-")).sorted()
                    .forEach(p -> out.add(p.toString()));
            return out;
        }
    }

    private void purgeOld(Path dir) throws IOException {
        Instant cutoff = Instant.now().minus(perfLens.config().getStorage().getRetentionDays(), ChronoUnit.DAYS);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (p.getFileName().toString().startsWith("snapshot-")
                        && Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    @Override
    public void destroy() {
        if (!perfLens.config().getStorage().isEnabled() || !perfLens.config().getStorage().isSnapshotOnShutdown()) return;
        boolean hasData = perfLens.sql().stats().size() + perfLens.methods().stats().size()
                + perfLens.traces().entrypoints().size() > 0 || perfLens.profiler().totalSamples() > 0;
        if (!hasData) return;
        try {
            log.info("PerfLens evidence snapshot written to " + write());
        } catch (Exception e) {
            log.warn("PerfLens could not write shutdown snapshot: " + e);
        }
    }
}
