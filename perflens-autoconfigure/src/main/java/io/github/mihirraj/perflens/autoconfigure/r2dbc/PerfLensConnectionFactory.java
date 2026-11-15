package io.github.mihirraj.perflens.autoconfigure.r2dbc;

import io.github.mihirraj.perflens.core.trace.TraceContext;
import io.github.mihirraj.perflens.core.trace.TraceWindow;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import io.r2dbc.spi.Wrapped;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

/**
 * Outermost wrapper around the r2dbc-proxy factory: when a connection is acquired inside a WebFlux
 * request, binds it to that request's trace window (read from the Reactor context), so its queries
 * count towards the request's SQL breakdown and N+1 detection.
 *
 * <p>Implements {@link Wrapped}, so Boot's pool metrics and health checks still reach the pool.
 */
final class PerfLensConnectionFactory implements ConnectionFactory, Wrapped<ConnectionFactory> {
    private final ConnectionFactory delegate;
    private final R2dbcTraceWindows windows;

    PerfLensConnectionFactory(ConnectionFactory delegate, R2dbcTraceWindows windows) {
        this.delegate = delegate;
        this.windows = windows;
    }

    @Override
    public Publisher<? extends Connection> create() {
        return Mono.deferContextual(ctx -> {
            Mono<Connection> connection = Mono.from(delegate.create());
            TraceWindow window = ctx.getOrDefault(TraceContext.REACTOR_KEY, null);
            return window == null ? connection : connection.doOnNext(c -> windows.bind(original(c), window));
        });
    }

    /** r2dbc-proxy reports the unwrapped connection to its listeners, so bind that one. */
    private static Object original(Connection c) {
        return c instanceof Wrapped<?> w ? w.unwrap() : c;
    }

    @Override
    public ConnectionFactoryMetadata getMetadata() {
        return delegate.getMetadata();
    }

    @Override
    public ConnectionFactory unwrap() {
        return delegate;
    }
}
