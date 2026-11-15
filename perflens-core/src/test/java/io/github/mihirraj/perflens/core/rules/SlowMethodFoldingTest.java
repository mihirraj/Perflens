package io.github.mihirraj.perflens.core.rules;

import io.github.mihirraj.perflens.core.PerfLens;
import io.github.mihirraj.perflens.core.PerfLensConfig;
import io.github.mihirraj.perflens.core.method.MethodRegistry;
import io.github.mihirraj.perflens.core.method.MethodRegistry.MethodStat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The slow-method rule folds a thin wrapper into the method it calls, and nothing else. */
class SlowMethodFoldingTest {

    public static class Controller {
        public void dashboard() {}
    }

    public static class Service {
        public void load() {}
    }

    public static class Report {
        public void pairs() {}
    }

    @Test
    void foldsOnlyRealWrappersNotCoincidentalLookalikes() throws Exception {
        PerfLensConfig cfg = new PerfLensConfig();
        cfg.setBasePackages(List.of("io.github.mihirraj.perflens.core.rules"));
        try (PerfLens pl = new PerfLens(cfg)) {
            MethodRegistry methods = pl.methods();
            MethodStat dashboard = methods.stat(Controller.class.getMethod("dashboard"), Controller.class);
            MethodStat load = methods.stat(Service.class.getMethod("load"), Service.class);
            MethodStat pairs = methods.stat(Report.class.getMethod("pairs"), Report.class);
            for (int i = 0; i < 3; i++) {
                // controller -> service: same count, the controller adds a few ms
                methods.record(load, 800_000, null, new Object[0], MethodRegistry.Before.of(null), null, dashboard);
                methods.record(dashboard, 803_000, null, new Object[0], MethodRegistry.Before.of(null), null, null);
                // unrelated method, same count, total time within 3% of the service by coincidence
                methods.record(pairs, 785_000, null, new Object[0], MethodRegistry.Before.of(null), null, null);
            }

            List<String> slow = pl.findings().stream().filter(f -> f.rule().equals("METHOD-SLOW"))
                    .map(Finding::location).toList();

            assertThat(slow).anyMatch(l -> l.startsWith(Service.class.getName() + ".load"))
                    .anyMatch(l -> l.startsWith(Report.class.getName() + ".pairs"))
                    .noneMatch(l -> l.startsWith(Controller.class.getName() + ".dashboard"));
        }
    }
}
