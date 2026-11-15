package dev.perflens.autoconfigure.http;

import dev.perflens.core.PerfLens;
import dev.perflens.core.trace.TraceContext;
import dev.perflens.core.trace.TraceWindow;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientRequestObservationContext;
import org.springframework.web.reactive.function.client.ClientResponse;

import java.net.URI;

/**
 * Same as {@link PerfLensClientObservationHandler} for WebClient. The call may complete on a Netty
 * thread, so the trace window is captured when it starts: from the ThreadLocal on servlet threads,
 * or, inside WebFlux requests, from {@link PerfLensWebClientCustomizer}.
 */
public final class PerfLensWebClientObservationHandler implements ObservationHandler<ClientRequestObservationContext> {
    private static final String START = "perflens.start";
    private static final String WINDOW = "perflens.window";

    private final dev.perflens.autoconfigure.PerfLensRef perfLens;
    private final PerfLensWebClientCustomizer reactiveWindows;

    public PerfLensWebClientObservationHandler(ObjectProvider<PerfLens> perfLens, PerfLensWebClientCustomizer reactiveWindows) {
        this.perfLens = new dev.perflens.autoconfigure.PerfLensRef(perfLens);
        this.reactiveWindows = reactiveWindows;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ClientRequestObservationContext;
    }

    @Override
    public void onStart(ClientRequestObservationContext context) {
        context.put(START, System.nanoTime());
        TraceWindow w = TraceContext.current();
        if (w != null) context.put(WINDOW, w);
    }

    @Override
    public void onStop(ClientRequestObservationContext context) {
        ClientRequest request = context.getRequest();
        // claim first, so the pending entry is removed even when the call is not recorded
        TraceWindow reactiveWindow = reactiveWindows.claim(request);
        PerfLens pl = perfLens.get();
        Long start = context.get(START);
        if (pl == null || !pl.isCollecting() || start == null || !pl.config().getHttp().isEnabled()) return;
        long end = System.nanoTime();
        String method = request == null ? "?" : request.method().name();
        URI uri = request == null ? null : request.url();
        String host = uri == null ? "?" : uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        String route = HttpRoutes.route(context.getUriTemplate(), uri);
        int status = 0;
        ClientResponse response = context.getResponse();
        if (response != null) status = response.statusCode().value();
        TraceWindow window = context.get(WINDOW);
        if (window == null) window = reactiveWindow;
        pl.http().record(method, host, route, uri == null ? null : uri.toString(), status, context.getError(),
                start, end, window);
    }
}
