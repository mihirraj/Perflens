package dev.perflens.core.util;

import java.lang.reflect.Field;
import java.time.temporal.TemporalAccessor;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Renders argument / parameter values for evidence WITHOUT calling arbitrary toString():
 * on JPA entities that can trigger lazy loading (extra queries or LazyInitializationException).
 * Unknown objects are shown as {@code TypeName{id=..}} when an {@code id} field exists.
 */
public final class ValueRenderer {
    public static final String MASKED = "***";

    private final int maxLength;
    private final List<String> maskNames;

    public ValueRenderer(int maxLength, List<String> maskNames) {
        this.maxLength = Math.max(16, maxLength);
        this.maskNames = maskNames.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }

    public boolean isSensitive(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        for (String m : maskNames) if (n.contains(m)) return true;
        return false;
    }

    public String render(String name, Object value) {
        return isSensitive(name) ? MASKED : render(value);
    }

    public String render(Object v) {
        return truncate(renderRaw(v, 0));
    }

    private String renderRaw(Object v, int depth) {
        if (v == null) return "null";
        if (v instanceof CharSequence s) return "'" + s + "'";
        if (v instanceof Number || v instanceof Boolean || v instanceof Character || v instanceof Enum<?>
                || v instanceof TemporalAccessor || v instanceof UUID || v instanceof Date) {
            return String.valueOf(v);
        }
        if (v instanceof byte[] b) return "byte[" + b.length + "]";
        if (v.getClass().isArray()) {
            return v.getClass().getComponentType().getSimpleName() + "[" + java.lang.reflect.Array.getLength(v) + "]";
        }
        if (v instanceof Optional<?> o) return o.map(x -> "Optional[" + renderRaw(x, depth + 1) + "]").orElse("Optional.empty");
        if (v instanceof Collection<?> c) {
            StringBuilder sb = new StringBuilder(v.getClass().getSimpleName()).append("(size=").append(c.size());
            if (depth == 0 && !c.isEmpty()) {
                sb.append(", first=[");
                int i = 0;
                for (Object o : c) {
                    if (i == 3) { sb.append(", ..."); break; }
                    if (i++ > 0) sb.append(", ");
                    sb.append(renderRaw(o, depth + 1));
                }
                sb.append(']');
            }
            return sb.append(')').toString();
        }
        if (v instanceof Map<?, ?> m) return v.getClass().getSimpleName() + "(size=" + m.size() + ")";
        return describeObject(v);
    }

    private static String describeObject(Object v) {
        Class<?> type = v.getClass();
        String name = type.getSimpleName();
        int cglib = name.indexOf("$$");
        if (cglib > 0) name = name.substring(0, cglib);
        if (name.isEmpty()) name = type.getName();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("id");
                f.setAccessible(true);
                Object id = f.get(v);
                return name + "{id=" + (id instanceof CharSequence ? "'" + id + "'" : id) + "}";
            } catch (NoSuchFieldException ignored) {
                // try superclass
            } catch (RuntimeException | IllegalAccessException e) {
                break; // module boundaries etc.
            }
        }
        return name + "@" + Integer.toHexString(System.identityHashCode(v));
    }

    private String truncate(String s) {
        return s.length() <= maxLength ? s : s.substring(0, maxLength) + "...(" + s.length() + " chars)";
    }
}
