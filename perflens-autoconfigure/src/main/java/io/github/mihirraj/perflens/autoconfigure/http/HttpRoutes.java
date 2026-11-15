package io.github.mihirraj.perflens.autoconfigure.http;

import java.net.URI;

/** Route naming shared by the blocking and reactive client handlers. */
final class HttpRoutes {
    private HttpRoutes() {}

    /**
     * The URI template ("/users/{id}") when the caller used one, else the raw path. Query strings
     * are dropped so routes don't explode into one entry per parameter value.
     */
    static String route(String uriTemplate, URI uri) {
        String route = uriTemplate;
        if (route != null && route.contains("://")) {
            int slash = route.indexOf('/', route.indexOf("://") + 3);
            route = slash < 0 ? "/" : route.substring(slash);
        }
        if (route == null) route = uri == null || uri.getRawPath() == null ? "?" : uri.getRawPath();
        int q = route.indexOf('?');
        if (q >= 0) route = route.substring(0, q);
        return route.isEmpty() ? "/" : route;
    }
}
