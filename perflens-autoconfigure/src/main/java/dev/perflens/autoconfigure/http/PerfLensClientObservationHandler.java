package dev.perflens.autoconfigure.http;

import dev.perflens.core.PerfLens;
import dev.perflens.core.trace.TraceContext;
import dev.perflens.core.trace.TraceWindow;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.observation.ClientRequestObservationContext;

import java.net.URI;

/**
 * Receives Spring's built-in observations for RestClient / RestTemplate calls (Boot wires the
 * ObservationRegistry into builders it creates). Clients built with {@code new RestTemplate()}
 * bypass observations; their socket waits still show up in the JFR "slow sockets" table.
 */
public final class PerfLensClientObservationHandler implements ObservationHandler<ClientRequestObservationContext> {
    private static final String START = "perflens.start";
    private static final String WINDOW = "perflens.window";

    private final ObjectProvider<PerfLens> perfLens;

    public PerfLensClientObservationHandler(ObjectProvider<PerfLens> perfLens) {
        this.perfLens = perfLens;
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
        PerfLens pl = perfLens.getIfAvailable();
        Long start = context.get(START);
        if (pl == null || start == null || !pl.config().getHttp().isEnabled()) return;
        long end = System.nanoTime();
        ClientHttpRequest request = context.getCarrier();
        String method = request == null ? "?" : request.getMethod().name();
        URI uri = request == null ? null : request.getURI();
        String host = uri == null ? "?" : uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        String route = context.getUriTemplate();
        if (route != null && route.contains("://")) {
            // template given as a full URL: keep only the path part
            int slash = route.indexOf('/', route.indexOf("://") + 3);
            route = slash < 0 ? "/" : route.substring(slash);
        }
        if (route == null) route = uri == null ? "?" : uri.getRawPath();
        int q = route.indexOf('?');
        if (q >= 0) route = route.substring(0, q);
        int status = 0;
        ClientHttpResponse response = context.getResponse();
        if (response != null) {
            try {
                status = response.getStatusCode().value();
            } catch (Exception ignored) {
                // response not readable: treated as status 0
            }
        }
        pl.http().record(method, host, route, uri == null ? null : uri.toString(), status, context.getError(),
                start, end, context.get(WINDOW));
    }
}
