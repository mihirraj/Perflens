package dev.perflens.autoconfigure.http;

import dev.perflens.core.trace.TraceContext;
import dev.perflens.core.trace.TraceWindow;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which WebFlux request each WebClient call belongs to, so
 * {@link PerfLensWebClientObservationHandler} can attribute it to that request's trace window.
 *
 * <p>The window lives in the Reactor context, which observation handlers cannot read, and the
 * observation context holds the request as it was before filters ran (with immutable attributes).
 * So the filter records the window against that request object and the handler claims it.
 */
public final class PerfLensWebClientCustomizer implements WebClientCustomizer {
    /** Far above any realistic number of in-flight calls; reached only if observations never stop. */
    private static final int MAX_PENDING = 10_000;

    private final Map<ClientRequest, TraceWindow> pending = new ConcurrentHashMap<>();

    private final ExchangeFilterFunction rememberWindow = (request, next) -> Mono.deferContextual(ctx -> {
        TraceWindow window = ctx.getOrDefault(TraceContext.REACTOR_KEY, null);
        if (window != null) {
            if (pending.size() >= MAX_PENDING) pending.clear();
            pending.put(request, window);
        }
        return next.exchange(request);
    });

    @Override
    public void customize(WebClient.Builder builder) {
        builder.filter(rememberWindow);
    }

    /** The window recorded for this request, removed so the entry does not outlive the call. */
    TraceWindow claim(ClientRequest request) {
        return request == null ? null : pending.remove(request);
    }
}
