package dev.perflens.autoconfigure.web;

import dev.perflens.core.PerfLens;
import dev.perflens.core.trace.TraceContext;
import dev.perflens.core.trace.TraceWindow;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;

/** Opens one trace window per inbound HTTP request, named by its route template. */
public final class PerfLensServletFilter implements Filter {
    private static final String BEST_MATCHING_PATTERN = "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern";

    private final ObjectProvider<PerfLens> perfLens;
    private final String managementBasePath;

    public PerfLensServletFilter(ObjectProvider<PerfLens> perfLens, String managementBasePath) {
        this.perfLens = perfLens;
        this.managementBasePath = managementBasePath;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        PerfLens pl = perfLens.getIfAvailable();
        if (pl == null || !(req instanceof HttpServletRequest http) || !(res instanceof HttpServletResponse response)
                || http.getRequestURI().startsWith(managementBasePath) || TraceContext.current() != null) {
            chain.doFilter(req, res);
            return;
        }
        TraceWindow w = TraceContext.openIfAbsent(http.getMethod() + " " + http.getRequestURI());
        boolean failed = false;
        try {
            chain.doFilter(req, res);
        } catch (IOException | ServletException | RuntimeException e) {
            failed = true;
            throw e;
        } finally {
            try {
                Object pattern = http.getAttribute(BEST_MATCHING_PATTERN);
                w.entrypoint(http.getMethod() + " " + (pattern != null ? pattern : "[unmapped] " + http.getRequestURI()));
                pl.traces().complete(w, failed || response.getStatus() >= 500);
            } finally {
                TraceContext.close(w);
            }
        }
    }
}
