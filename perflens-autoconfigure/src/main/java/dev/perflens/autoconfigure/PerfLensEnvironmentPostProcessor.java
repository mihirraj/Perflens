package dev.perflens.autoconfigure;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Dev/staging convenience: if the application has not configured actuator web exposure at all,
 * expose {@code health,perflens} so the UI works with zero configuration. Any explicit
 * {@code management.endpoints.web.exposure.include} wins. Disable with
 * {@code perflens.auto-expose=false}.
 */
public class PerfLensEnvironmentPostProcessor implements EnvironmentPostProcessor {
    static final String INCLUDE = "management.endpoints.web.exposure.include";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        boolean enabled = env.getProperty("perflens.enabled", Boolean.class, true);
        boolean autoExpose = env.getProperty("perflens.auto-expose", Boolean.class, true);
        if (!enabled || !autoExpose || env.containsProperty(INCLUDE)) return;
        env.getPropertySources().addLast(new MapPropertySource("perflensDefaults", Map.of(INCLUDE, "health,perflens")));
    }
}
