package io.github.mihirraj.perflens.autoconfigure;

import io.github.mihirraj.perflens.core.PerfLens;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Lazily resolved, then cached, reference to the PerfLens bean.
 *
 * <p>Hooks run on every SQL statement and bean method call. {@code ObjectProvider.getIfAvailable()}
 * performs a full bean-factory lookup (type matching, primary-bean checks) each time; profiling
 * showed that lookup as a measurable share of PerfLens' overhead. The hooks are created before the
 * PerfLens bean exists (BeanPostProcessors, advisors), so it cannot simply be injected.
 */
public final class PerfLensRef {
    private final ObjectProvider<PerfLens> provider;
    private volatile PerfLens instance;

    public PerfLensRef(ObjectProvider<PerfLens> provider) {
        this.provider = provider;
    }

    /** The PerfLens bean, or null while the context is still starting up. */
    public PerfLens get() {
        PerfLens pl = instance;
        if (pl == null) {
            pl = provider.getIfAvailable();
            if (pl != null) instance = pl;
        }
        return pl;
    }
}
