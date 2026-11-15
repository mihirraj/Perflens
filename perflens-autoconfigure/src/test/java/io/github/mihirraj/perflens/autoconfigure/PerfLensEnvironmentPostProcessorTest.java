package io.github.mihirraj.perflens.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PerfLensEnvironmentPostProcessorTest {
    private final PerfLensEnvironmentPostProcessor processor = new PerfLensEnvironmentPostProcessor();

    private StandardEnvironment env(Map<String, Object> props, String... profiles) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        env.setActiveProfiles(profiles);
        processor.postProcessEnvironment(env, null);
        return env;
    }

    @Test
    void capturesValuesByDefault() {
        StandardEnvironment env = env(Map.of(), "dev");
        assertThat(env.getProperty("perflens.capture.params")).isNull();
        assertThat(env.getProperty(PerfLensEnvironmentPostProcessor.INCLUDE)).isEqualTo("health,perflens");
    }

    @Test
    void stopsCapturingValuesInProductionProfiles() {
        StandardEnvironment env = env(Map.of(), "eu-west", "Prod");
        assertThat(env.getProperty("perflens.capture.params")).isEqualTo("false");
        assertThat(env.getProperty(PerfLensEnvironmentPostProcessor.CAPTURE_OFF_REASON)).contains("'Prod'");
    }

    @Test
    void explicitSettingWinsOverTheProductionDefault() {
        StandardEnvironment env = env(Map.of("perflens.capture.params", "true"), "prod");
        assertThat(env.getProperty("perflens.capture.params")).isEqualTo("true");
        assertThat(env.getProperty(PerfLensEnvironmentPostProcessor.CAPTURE_OFF_REASON)).isNull();
    }

    @Test
    void productionProfilesAreConfigurable() {
        StandardEnvironment env = env(Map.of("perflens.capture.off-in-profiles", "live, customer"), "customer");
        assertThat(env.getProperty("perflens.capture.params")).isEqualTo("false");
        assertThat(env(Map.of("perflens.capture.off-in-profiles", "live"), "prod").getProperty("perflens.capture.params")).isNull();
    }

    @Test
    void explicitExposureWins() {
        StandardEnvironment env = env(Map.of(PerfLensEnvironmentPostProcessor.INCLUDE, "health"));
        assertThat(env.getProperty(PerfLensEnvironmentPostProcessor.INCLUDE)).isEqualTo("health");
    }
}
