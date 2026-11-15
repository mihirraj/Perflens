package io.github.mihirraj.perflens.autoconfigure.r2dbc;

import io.github.mihirraj.perflens.core.trace.TraceWindow;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which request borrowed which R2DBC connection. r2dbc-proxy listeners cannot see the Reactor
 * context, so the window is captured when the connection is acquired (where the context is
 * available) and looked up again by connection when its queries complete.
 */
final class R2dbcTraceWindows {
    /** Far above any realistic pool size; reached only if connection close events are lost. */
    private static final int MAX_BOUND = 10_000;

    private final Map<Object, TraceWindow> byConnection = new ConcurrentHashMap<>();

    void bind(Object connection, TraceWindow window) {
        if (byConnection.size() >= MAX_BOUND) byConnection.clear();
        byConnection.put(connection, window);
    }

    TraceWindow get(Object connection) {
        return connection == null ? null : byConnection.get(connection);
    }

    void unbind(Object connection) {
        if (connection != null) byConnection.remove(connection);
    }
}
