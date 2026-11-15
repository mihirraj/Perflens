package dev.perflens.autoconfigure.web;

import dev.perflens.autoconfigure.PerfLensRef;
import dev.perflens.core.PerfLens;
import dev.perflens.core.trace.TraceContext;
import dev.perflens.core.trace.TraceWindow;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebFlux counterpart of {@link PerfLensServletFilter}: per-route latency, errors and slowest
 * requests. Reactive code hops threads, so the window travels in the Reactor context instead of
 * a ThreadLocal; R2DBC queries and WebClient calls made for the request are attributed to it.
 */
public final class PerfLensWebFilter implements WebFilter, Ordered {
    private static final String BEST_MATCHING_PATTERN = "org.springframework.web.reactive.HandlerMapping.bestMatchingPattern";

    private final PerfLensRef perfLens;
    private final String managementBasePath;

    public PerfLensWebFilter(ObjectProvider<PerfLens> perfLens, String managementBasePath) {
        this.perfLens = new PerfLensRef(perfLens);
        this.managementBasePath = managementBasePath;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        PerfLens pl = perfLens.get();
        String path = exchange.getRequest().getPath().value();
        if (pl == null || !pl.isCollecting() || path.startsWith(managementBasePath)) return chain.filter(exchange);
        String method = exchange.getRequest().getMethod().name();
        TraceWindow w = new TraceWindow(method + " " + path);
        AtomicBoolean failed = new AtomicBoolean();
        return chain.filter(exchange)
                .doOnError(e -> failed.set(true))
                .doFinally(signal -> {
                    Object pattern = exchange.getAttribute(BEST_MATCHING_PATTERN);
                    w.entrypoint(method + " " + (pattern != null ? pattern.toString() : "[unmapped] " + path));
                    HttpStatusCode status = exchange.getResponse().getStatusCode();
                    pl.traces().complete(w, failed.get() || (status != null && status.is5xxServerError()));
                })
                // R2DBC connections and WebClient calls made for this request read the window from here
                .contextWrite(ctx -> ctx.put(TraceContext.REACTOR_KEY, w));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
