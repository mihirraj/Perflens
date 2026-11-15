package io.github.mihirraj.perflens.autoconfigure;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Zero-configuration defaults, each overridden by any explicit setting:
 * <ul>
 *   <li>If the application has not configured actuator web exposure at all, expose
 *       {@code health,perflens} so the UI works out of the box. Disable with
 *       {@code perflens.auto-expose=false}.</li>
 *   <li>If a production profile is active ({@code perflens.capture.off-in-profiles}, default
 *       {@code prod,production}), stop recording real parameter and argument values.</li>
 * </ul>
 */
public class PerfLensEnvironmentPostProcessor implements EnvironmentPostProcessor {
    static final String INCLUDE = "management.endpoints.web.exposure.include";
    static final String CAPTURE_PARAMS = "perflens.capture.params";
    static final String OFF_IN_PROFILES = "perflens.capture.off-in-profiles";
    /** Why value capture was turned off by default; shown in the startup log. */
    static final String CAPTURE_OFF_REASON = "perflens.capture.off-reason";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (!env.getProperty("perflens.enabled", Boolean.class, true)) return;
        Map<String, Object> defaults = new HashMap<>();
        if (env.getProperty("perflens.auto-expose", Boolean.class, true) && !env.containsProperty(INCLUDE)) {
            defaults.put(INCLUDE, "health,perflens");
        }
        if (!env.containsProperty(CAPTURE_PARAMS)) {
            productionProfile(env).ifPresent(profile -> {
                defaults.put(CAPTURE_PARAMS, "false");
                defaults.put(CAPTURE_OFF_REASON, "profile '" + profile + "' is active");
            });
        }
        if (!defaults.isEmpty()) env.getPropertySources().addLast(new MapPropertySource("perflensDefaults", defaults));
    }

    private static Optional<String> productionProfile(ConfigurableEnvironment env) {
        String[] off = env.getProperty(OFF_IN_PROFILES, "prod,production").split(",");
        for (String active : env.getActiveProfiles()) {
            String a = active.trim().toLowerCase(Locale.ROOT);
            if (Arrays.stream(off).map(p -> p.trim().toLowerCase(Locale.ROOT)).anyMatch(a::equals)) return Optional.of(active);
        }
        return Optional.empty();
    }
}
