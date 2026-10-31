package dev.perflens.autoconfigure.jdbc;

import dev.perflens.core.PerfLens;
import dev.perflens.core.trace.TraceContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Times connection acquisition (pool wait) and delegates to the datasource-proxy wrapper.
 * Extends DelegatingDataSource so Boot's DataSourceUnwrapper (Hikari metrics, health) still
 * reaches the real pool.
 */
public final class PerfLensDataSource extends DelegatingDataSource {
    private final ObjectProvider<PerfLens> perfLens;

    PerfLensDataSource(DataSource proxied, ObjectProvider<PerfLens> perfLens) {
        super(proxied);
        this.perfLens = perfLens;
    }

    @Override
    public Connection getConnection() throws SQLException {
        long t0 = System.nanoTime();
        Connection c = super.getConnection();
        record(t0);
        return c;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        long t0 = System.nanoTime();
        Connection c = super.getConnection(username, password);
        record(t0);
        return c;
    }

    private void record(long t0) {
        PerfLens pl = perfLens.getIfAvailable();
        if (pl != null && pl.isCollecting()) pl.sql().recordConnectionAcquire((System.nanoTime() - t0) / 1000, TraceContext.current());
    }
}
