package io.github.mihirraj.perflens.autoconfigure.http;

import java.net.URI;

/** Route naming shared by the blocking and reactive client handlers. */
final class HttpRoutes {
    static final String ID = "{id}";
    /** Hex tokens at least this long (hashes, object ids) are treated as ids. */
    private static final int MIN_HEX_ID_LENGTH = 16;

    private HttpRoutes() {}

    /**
     * The URI template ("/users/{id}") when the caller used one. Otherwise the path with id-like
     * segments (numbers, UUIDs, long hex tokens) replaced by {@code {id}}, so a client that builds
     * URLs by concatenation ({@code "/items/" + id}) still gets one route, and the ids themselves
     * are never stored. Query strings are dropped.
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
        if (route.isEmpty()) return "/";
        return route.indexOf('{') >= 0 ? route : normalizeIds(route);
    }

    static String normalizeIds(String path) {
        StringBuilder out = new StringBuilder(path.length());
        int start = 0;
        while (start <= path.length()) {
            int end = path.indexOf('/', start);
            if (end < 0) end = path.length();
            String segment = path.substring(start, end);
            out.append(isId(segment) ? ID : segment);
            if (end < path.length()) out.append('/');
            start = end + 1;
        }
        return out.toString();
    }

    static boolean isId(String s) {
        if (s.isEmpty()) return false;
        return allDigits(s) || isUuid(s) || (s.length() >= MIN_HEX_ID_LENGTH && allHex(s));
    }

    private static boolean allDigits(String s) {
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    private static boolean allHex(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.digit(s.charAt(i), 16) < 0) return false;
        return true;
    }

    /** 8-4-4-4-12 hex digits. */
    private static boolean isUuid(String s) {
        if (s.length() != 36) return false;
        for (int i = 0; i < 36; i++) {
            char c = s.charAt(i);
            boolean dash = i == 8 || i == 13 || i == 18 || i == 23;
            if (dash ? c != '-' : Character.digit(c, 16) < 0) return false;
        }
        return true;
    }
}
