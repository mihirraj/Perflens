package io.github.mihirraj.perflens.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mihirraj.perflens.autoconfigure.endpoint.PerfLensEndpoint;
import io.github.mihirraj.perflens.autoconfigure.endpoint.PerfLensSnapshots;
import io.github.mihirraj.perflens.autoconfigure.http.PerfLensClientObservationHandler;
import io.github.mihirraj.perflens.autoconfigure.http.PerfLensWebClientCustomizer;
import io.github.mihirraj.perflens.autoconfigure.http.PerfLensWebClientObservationHandler;
import io.github.mihirraj.perflens.autoconfigure.jdbc.PerfLensDataSourcePostProcessor;
import io.github.mihirraj.perflens.autoconfigure.method.PerfLensMethodAdvisor;
import io.github.mihirraj.perflens.autoconfigure.r2dbc.PerfLensConnectionFactoryPostProcessor;
import io.github.mihirraj.perflens.autoconfigure.web.PerfLensServletFilter;
import io.github.mihirraj.perflens.autoconfigure.web.PerfLensWebFilter;
import io.github.mihirraj.perflens.core.PerfLens;
import io.github.mihirraj.perflens.core.PerfLensConfig;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything PerfLens needs, activated by adding the starter. Each integration switches on only
 * when its library is on the classpath and can be turned off with {@code perflens.*.enabled}.
 */
@AutoConfiguration(after = JacksonAutoConfiguration.class)
@ConditionalOnBooleanProperty(name = "perflens.enabled", matchIfMissing = true)
public class PerfLensAutoConfiguration {
    private static final Log log = LogFactory.getLog(PerfLensAutoConfiguration.class);

    @Bean
    @ConfigurationProperties("perflens")
    @ConditionalOnMissingBean
    public PerfLensConfig perfLensConfig() {
        return new PerfLensConfig();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public PerfLens perfLens(PerfLensConfig config, BeanFactory beanFactory, Environment env,
                             ObjectProvider<GitProperties> git, ObjectProvider<BuildProperties> build) {
        if (config.getBasePackages().isEmpty()) {
            config.setBasePackages(new ArrayList<>(resolveBasePackages(beanFactory)));
        }
        PerfLens pl = new PerfLens(config);
        // identity recorded in every report/snapshot, so a regression can be tied to a deploy
        pl.meta().put("application", env.getProperty("spring.application.name", "application"));
        build.ifAvailable(b -> pl.meta().put("version", b.getVersion()));
        git.ifAvailable(g -> {
            if (g.getShortCommitId() != null) pl.meta().put("gitCommit", g.getShortCommitId());
            if (g.getBranch() != null) pl.meta().put("gitBranch", g.getBranch());
        });
        pl.meta().put("startedAt", pl.startedAt());
        pl.start();
        return pl;
    }

    static List<String> resolveBasePackages(BeanFactory beanFactory) {
        return AutoConfigurationPackages.has(beanFactory) ? AutoConfigurationPackages.get(beanFactory) : List.of();
    }

    /** Base packages are needed while beans are being proxied, before PerfLensConfig may exist. */
    static List<String> basePackagesFromEnvironment(Environment env, BeanFactory beanFactory) {
        List<String> configured = Binder.get(env).bind("perflens.base-packages",
                org.springframework.boot.context.properties.bind.Bindable.listOf(String.class)).orElse(List.of());
        return configured.isEmpty() ? resolveBasePackages(beanFactory) : configured;
    }

    @Bean
    @ConditionalOnMissingBean
    public PerfLensSnapshots perfLensSnapshots(PerfLens perfLens, ObjectProvider<ObjectMapper> mapper) {
        return new PerfLensSnapshots(perfLens, mapper.getIfAvailable(ObjectMapper::new));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnAvailableEndpoint
    public PerfLensEndpoint perfLensEndpoint(PerfLens perfLens, PerfLensSnapshots snapshots) {
        return new PerfLensEndpoint(perfLens, snapshots);
    }

    @Bean
    public ApplicationListener<ApplicationReadyEvent> perfLensStartupBanner(PerfLens perfLens, Environment env) {
        return event -> {
            String port = env.getProperty("local.management.port", env.getProperty("local.server.port", "8080"));
            String base = env.getProperty("management.endpoints.web.base-path", "/actuator");
            log.info("PerfLens is watching " + perfLens.callers().basePackages() + " (JFR: "
                    + perfLens.profiler().status() + ", params captured: " + perfLens.config().getCapture().isParams()
                    + "). UI: http://localhost:" + port + base + "/perflens");
            String offReason = env.getProperty(PerfLensEnvironmentPostProcessor.CAPTURE_OFF_REASON);
            if (offReason != null) {
                log.info("PerfLens is not recording parameter and argument values because " + offReason
                        + ". Set perflens.capture.params=true to record them anyway.");
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {"net.ttddyy.dsproxy.support.ProxyDataSourceBuilder",
            "org.springframework.jdbc.datasource.DelegatingDataSource"})
    @ConditionalOnBooleanProperty(name = "perflens.sql.enabled", matchIfMissing = true)
    static class JdbcConfiguration {
        @Bean
        static PerfLensDataSourcePostProcessor perfLensDataSourcePostProcessor(ObjectProvider<PerfLens> perfLens) {
            return new PerfLensDataSourcePostProcessor(perfLens);
        }
    }

    /** Opt-in: active when the application adds io.r2dbc:r2dbc-proxy next to its R2DBC driver. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {"io.r2dbc.proxy.ProxyConnectionFactory", "io.r2dbc.spi.ConnectionFactory"})
    @ConditionalOnBooleanProperty(name = "perflens.sql.enabled", matchIfMissing = true)
    static class R2dbcConfiguration {
        @Bean
        static PerfLensConnectionFactoryPostProcessor perfLensConnectionFactoryPostProcessor(
                ObjectProvider<PerfLens> perfLens) {
            return new PerfLensConnectionFactoryPostProcessor(perfLens);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnClass(name = "org.springframework.web.server.WebFilter")
    static class ReactiveWebConfiguration {
        @Bean
        PerfLensWebFilter perfLensWebFilter(ObjectProvider<PerfLens> perfLens, Environment env) {
            return new PerfLensWebFilter(perfLens,
                    env.getProperty("management.endpoints.web.base-path", "/actuator"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBooleanProperty(name = "perflens.methods.enabled", matchIfMissing = true)
    static class MethodConfiguration {
        @Bean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        static PerfLensMethodAdvisor perfLensMethodAdvisor(ObjectProvider<PerfLens> perfLens, Environment env,
                                                           BeanFactory beanFactory) {
            return new PerfLensMethodAdvisor(perfLens, basePackagesFromEnvironment(env, beanFactory));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "jakarta.servlet.Filter")
    static class ServletConfiguration {
        @Bean
        FilterRegistrationBean<PerfLensServletFilter> perfLensServletFilter(ObjectProvider<PerfLens> perfLens, Environment env) {
            String base = env.getProperty("management.endpoints.web.base-path", "/actuator");
            FilterRegistrationBean<PerfLensServletFilter> reg =
                    new FilterRegistrationBean<>(new PerfLensServletFilter(perfLens, base));
            reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
            reg.setName("perfLensFilter");
            return reg;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {"io.micrometer.observation.ObservationHandler",
            "org.springframework.http.client.observation.ClientRequestObservationContext"})
    @ConditionalOnBooleanProperty(name = "perflens.http.enabled", matchIfMissing = true)
    static class HttpClientConfiguration {
        @Bean
        PerfLensClientObservationHandler perfLensClientObservationHandler(ObjectProvider<PerfLens> perfLens) {
            return new PerfLensClientObservationHandler(perfLens);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {"io.micrometer.observation.ObservationHandler",
            "org.springframework.web.reactive.function.client.ClientRequestObservationContext"})
    @ConditionalOnBooleanProperty(name = "perflens.http.enabled", matchIfMissing = true)
    static class WebClientConfiguration {
        @Bean
        PerfLensWebClientObservationHandler perfLensWebClientObservationHandler(ObjectProvider<PerfLens> perfLens,
                                                                                PerfLensWebClientCustomizer reactiveWindows) {
            return new PerfLensWebClientObservationHandler(perfLens, reactiveWindows);
        }

        @Bean
        PerfLensWebClientCustomizer perfLensWebClientCustomizer() {
            return new PerfLensWebClientCustomizer();
        }
    }
}
