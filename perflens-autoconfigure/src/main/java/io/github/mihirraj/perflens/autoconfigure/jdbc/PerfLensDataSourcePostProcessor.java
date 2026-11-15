package io.github.mihirraj.perflens.autoconfigure.jdbc;

import io.github.mihirraj.perflens.core.PerfLens;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

import javax.sql.DataSource;

/** Wraps every DataSource bean so all statements and connection acquisitions are measured. */
public final class PerfLensDataSourcePostProcessor implements BeanPostProcessor, Ordered {
    private final ObjectProvider<PerfLens> perfLens;

    public PerfLensDataSourcePostProcessor(ObjectProvider<PerfLens> perfLens) {
        this.perfLens = perfLens;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource ds && !(bean instanceof PerfLensDataSource) && !(bean instanceof ProxyDataSource)) {
            DataSource proxied = ProxyDataSourceBuilder.create(ds)
                    .name(beanName)
                    .listener(new PerfLensQueryListener(perfLens))
                    .build();
            return new PerfLensDataSource(proxied, perfLens);
        }
        return bean;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 10;
    }
}
