package dev.perflens.autoconfigure.r2dbc;

import dev.perflens.autoconfigure.PerfLensRef;
import dev.perflens.core.PerfLens;
import io.r2dbc.proxy.ProxyConnectionFactory;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Wrapped;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

import java.lang.reflect.Proxy;

/**
 * Wraps every R2DBC ConnectionFactory bean with r2dbc-proxy. The proxy implements
 * {@link Wrapped}, so Boot's pool metrics and health indicators can still reach the real pool.
 */
public final class PerfLensConnectionFactoryPostProcessor implements BeanPostProcessor, Ordered {
    private final PerfLensRef perfLens;

    public PerfLensConnectionFactoryPostProcessor(ObjectProvider<PerfLens> perfLens) {
        this.perfLens = new PerfLensRef(perfLens);
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof ConnectionFactory cf && !isProxy(cf)) {
            return ProxyConnectionFactory.builder(cf).listener(new PerfLensR2dbcListener(perfLens)).build();
        }
        return bean;
    }

    /** Already an r2dbc-proxy (ours or the application's own): don't wrap twice. */
    private static boolean isProxy(ConnectionFactory cf) {
        return Proxy.isProxyClass(cf.getClass()) && cf instanceof Wrapped<?>;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 10;
    }
}
