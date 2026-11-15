package io.github.mihirraj.perflens.autoconfigure.endpoint;

import io.github.mihirraj.perflens.core.PerfLens;
import io.github.mihirraj.perflens.core.report.ReportBuilder;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint;
import org.springframework.core.io.ClassPathResource;
import org.springframework.lang.Nullable;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /actuator/perflens} serves the UI (a single self-contained HTML page);
 * {@code GET /actuator/perflens/{section}} serves JSON; {@code POST /actuator/perflens/{action}}
 * runs snapshot / reset. Being a real actuator endpoint, it follows the app's exposure rules,
 * Spring Security configuration and management port.
 */
@WebEndpoint(id = "perflens")
public class PerfLensEndpoint {
    private final PerfLens perfLens;
    private final PerfLensSnapshots snapshots;
    private volatile String html;

    public PerfLensEndpoint(PerfLens perfLens, PerfLensSnapshots snapshots) {
        this.perfLens = perfLens;
        this.snapshots = snapshots;
    }

    @ReadOperation(produces = "text/html")
    public String ui() throws IOException {
        String page = html;
        if (page == null) {
            try (InputStream in = new ClassPathResource("perflens/ui/index.html").getInputStream()) {
                page = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
            }
            html = page;
        }
        return page;
    }

    @ReadOperation
    public WebEndpointResponse<Object> section(@Selector String section, @Nullable String baseline) {
        if (section.equals("compare")) {
            try {
                return new WebEndpointResponse<>(snapshots.compare(baseline));
            } catch (IllegalArgumentException e) {
                return new WebEndpointResponse<>(Map.of("error", e.getMessage()), 400);
            } catch (IOException e) {
                return new WebEndpointResponse<>(Map.of("error", e.toString()), 500);
            }
        }
        Object body = switch (section) {
            case "report" -> perfLens.report();
            case "findings" -> perfLens.findings();
            case "overview" -> new ReportBuilder(perfLens).overview(perfLens.findings());
            case "flamegraph" -> perfLens.profiler().flameGraph(0.1);
            case "snapshots" -> {
                try {
                    yield snapshots.list();
                } catch (IOException e) {
                    yield Map.of("error", e.toString());
                }
            }
            default -> null;
        };
        if (body == null) {
            return new WebEndpointResponse<>(Map.of("error", "unknown section '" + section
                    + "'; use report, findings, overview, flamegraph, snapshots or compare?baseline=<snapshot>"), 404);
        }
        return new WebEndpointResponse<>(body);
    }

    @WriteOperation
    public WebEndpointResponse<Object> action(@Selector String action) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (action) {
            case "reset" -> {
                perfLens.reset();
                out.put("reset", true);
            }
            case "snapshot" -> {
                try {
                    out.put("file", snapshots.write().toString());
                } catch (IOException e) {
                    return new WebEndpointResponse<>(Map.of("error", e.toString()), 500);
                }
            }
            default -> {
                return new WebEndpointResponse<>(Map.of("error", "unknown action '" + action + "'; use reset or snapshot"), 404);
            }
        }
        return new WebEndpointResponse<>(out);
    }
}
