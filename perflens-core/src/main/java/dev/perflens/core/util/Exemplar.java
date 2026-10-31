package dev.perflens.core.util;

import java.util.Map;

/** One concrete, real execution kept as evidence (e.g. the slowest call with its arguments). */
public record Exemplar(double durationMs, long timestamp, Map<String, Object> details) {
}
