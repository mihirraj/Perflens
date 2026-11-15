package io.github.mihirraj.perflens.core.trace;

/**
 * Thread-bound current {@link TraceWindow}. ThreadLocal works on virtual threads too; async
 * hand-offs (@Async, CompletableFuture) start their own window at the first instrumented method.
 */
public final class TraceContext {
    /**
     * Key of the current window in a Reactor {@code Context}. WebFlux requests are not bound to a
     * thread, so the WebFilter publishes the window there instead of in the ThreadLocal.
     */
    public static final String REACTOR_KEY = "perflens.traceWindow";

    private static final ThreadLocal<TraceWindow> CURRENT = new ThreadLocal<>();

    private TraceContext() {}

    public static TraceWindow current() {
        return CURRENT.get();
    }

    /** Opens a window if none is active. Returns the new window, or null if one was already open. */
    public static TraceWindow openIfAbsent(String entrypoint) {
        if (CURRENT.get() != null) return null;
        TraceWindow w = new TraceWindow(entrypoint);
        CURRENT.set(w);
        return w;
    }

    public static void close(TraceWindow window) {
        if (window != null && CURRENT.get() == window) CURRENT.remove();
    }
}
