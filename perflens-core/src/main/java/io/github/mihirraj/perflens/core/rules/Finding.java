package io.github.mihirraj.perflens.core.rules;

import io.github.mihirraj.perflens.core.util.Exemplar;

import java.util.List;
import java.util.Map;

/**
 * One ranked, evidence-backed issue.
 *
 * @param location  the code location to look at (class.method:line), when known
 * @param evidence  the measured numbers behind the finding
 * @param exemplars real executions (slowest calls with args / params)
 */
public record Finding(String id, String rule, String category, String severity, double score, String title,
                      String location, String summary, List<String> suggestions, Map<String, Object> evidence,
                      List<Exemplar> exemplars) {

    public static String severityFor(double score) {
        if (score >= 80) return "CRITICAL";
        if (score >= 60) return "HIGH";
        if (score >= 35) return "MEDIUM";
        return "LOW";
    }
}
