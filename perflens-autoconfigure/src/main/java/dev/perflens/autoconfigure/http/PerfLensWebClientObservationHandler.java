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
 * Same as {@link PerfLensClientObservationHandler} for WebClient. The trace window is captured
 * when the call starts (on the subscribing thread); the call may complete on a Netty thread, so
 * the window is passed along in the observation context instead of read from a ThreadLocal.
 */
public final class PerfLensWebClientObservationHandler implements ObservationHandler<ClientRequestObservationContext> {
    private static final String START = "perflens.start";
    private static final String WINDOW = "perflens.window";

    private final dev.perflens.autoconfigure.PerfLensRef perfLens;

    public PerfLensWebClientObservationHandler(ObjectProvider<PerfLens> perfLens) {
        this.perfLens = new dev.perflens.autoconfigure.PerfLensRef(perfLens);
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
        PerfLens pl = perfLens.get();
        Long start = context.get(START);
        if (pl == null || !pl.isCollecting() || start == null || !pl.config().getHttp().isEnabled()) return;
        long end = System.nanoTime();
        ClientRequest request = context.getRequest();
        String method = request == null ? "?" : request.method().name();
        URI uri = request == null ? null : request.url();
        String host = uri == null ? "?" : uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        String route = HttpRoutes.route(context.getUriTemplate(), uri);
        int status = 0;
        ClientResponse response = context.getResponse();
        if (response != null) status = response.statusCode().value();
        pl.http().record(method, host, route, uri == null ? null : uri.toString(), status, context.getError(),
                start, end, context.get(WINDOW));
    }
}
