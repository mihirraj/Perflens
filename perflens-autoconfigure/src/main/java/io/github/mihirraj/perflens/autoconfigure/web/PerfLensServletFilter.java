package io.github.mihirraj.perflens.autoconfigure.web;

import io.github.mihirraj.perflens.autoconfigure.PerfLensRef;
import io.github.mihirraj.perflens.core.PerfLens;
import io.github.mihirraj.perflens.core.trace.TraceContext;
import io.github.mihirraj.perflens.core.trace.TraceWindow;
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

    private final PerfLensRef perfLens;
    private final String managementBasePath;

    public PerfLensServletFilter(ObjectProvider<PerfLens> perfLens, String managementBasePath) {
        this.perfLens = new PerfLensRef(perfLens);
        this.managementBasePath = managementBasePath;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        PerfLens pl = perfLens.get();
        if (pl == null || !pl.isCollecting() || !(req instanceof HttpServletRequest http) || !(res instanceof HttpServletResponse response)
                || http.getRequestURI().startsWith(managementBasePath) || TraceContext.current() != null) {
            chain.doFilter(req, res);
            return;
        }
        // Never name the window by its raw URI: paths carry ids, emails and tokens. Entries saved
        // mid-request ask the resolver, which returns the route pattern once Spring has matched it.
        String method = http.getMethod();
        TraceWindow w = TraceContext.openIfAbsent(method + " " + TraceWindow.BEFORE_ROUTING);
        w.entrypointResolver(() -> {
            Object pattern = http.getAttribute(BEST_MATCHING_PATTERN);
            return pattern == null ? null : method + " " + pattern;
        });
        boolean failed = false;
        try {
            chain.doFilter(req, res);
        } catch (IOException | ServletException | RuntimeException e) {
            failed = true;
            throw e;
        } finally {
            try {
                Object pattern = http.getAttribute(BEST_MATCHING_PATTERN);
                w.entrypoint(method + " " + (pattern != null ? pattern : TraceWindow.UNMAPPED));
                pl.traces().complete(w, failed || response.getStatus() >= 500);
            } finally {
                TraceContext.close(w);
            }
        }
    }
}
