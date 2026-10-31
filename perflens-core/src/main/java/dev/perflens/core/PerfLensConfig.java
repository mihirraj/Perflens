package dev.perflens.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * All PerfLens tuning knobs. Plain mutable POJO so Spring can bind it from {@code perflens.*}
 * properties, while the core stays free of Spring dependencies.
 */
public class PerfLensConfig {

    private boolean enabled = true;
    /** Packages treated as "your code". Defaults to the @SpringBootApplication package when empty. */
    private List<String> basePackages = new ArrayList<>();
    private final Capture capture = new Capture();
    private final Jfr jfr = new Jfr();
    private final Sql sql = new Sql();
    private final Methods methods = new Methods();
    private final Http http = new Http();
    private final Storage storage = new Storage();
    private final CircuitBreaker circuitBreaker = new CircuitBreaker();

    public CircuitBreaker getCircuitBreaker() { return circuitBreaker; }

    /** Pauses all collection while the host JVM is under memory / GC pressure. */
    public static class CircuitBreaker {
        private boolean enabled = true;
        private double maxHeapAfterGcPercent = 85.0;
        private double maxGcTimePercent = 10.0;
        private Duration checkInterval = Duration.ofSeconds(5);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getMaxHeapAfterGcPercent() { return maxHeapAfterGcPercent; }
        public void setMaxHeapAfterGcPercent(double v) { this.maxHeapAfterGcPercent = v; }
        public double getMaxGcTimePercent() { return maxGcTimePercent; }
        public void setMaxGcTimePercent(double v) { this.maxGcTimePercent = v; }
        public Duration getCheckInterval() { return checkInterval; }
        public void setCheckInterval(Duration v) { this.checkInterval = v; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public List<String> getBasePackages() { return basePackages; }
    public void setBasePackages(List<String> basePackages) { this.basePackages = basePackages; }
    public Capture getCapture() { return capture; }
    public Jfr getJfr() { return jfr; }
    public Sql getSql() { return sql; }
    public Methods getMethods() { return methods; }
    public Http getHttp() { return http; }
    public Storage getStorage() { return storage; }

    /** What evidence values are recorded and how they are masked. */
    public static class Capture {
        /** Record real SQL bind parameters and method arguments in exemplars. Dev/staging default: on. */
        private boolean params = true;
        /** Parameter / column names containing any of these (case-insensitive) are masked as ***. */
        private List<String> maskNames = new ArrayList<>(List.of(
                "password", "passwd", "pwd", "secret", "token", "apikey", "api_key", "authorization",
                "ssn", "card", "cvv", "iban", "pin"));
        /** Longest rendered value before truncation. */
        private int maxValueLength = 200;
        /** Slowest examples kept per query / method / HTTP route. */
        private int exemplarsPerItem = 5;

        public boolean isParams() { return params; }
        public void setParams(boolean params) { this.params = params; }
        public List<String> getMaskNames() { return maskNames; }
        public void setMaskNames(List<String> maskNames) { this.maskNames = maskNames; }
        public int getMaxValueLength() { return maxValueLength; }
        public void setMaxValueLength(int maxValueLength) { this.maxValueLength = maxValueLength; }
        public int getExemplarsPerItem() { return exemplarsPerItem; }
        public void setExemplarsPerItem(int exemplarsPerItem) { this.exemplarsPerItem = exemplarsPerItem; }
    }

    /** In-process JDK Flight Recorder sampling. */
    public static class Jfr {
        private boolean enabled = true;
        private Duration cpuSampleInterval = Duration.ofMillis(10);
        private boolean allocationSampling = true;
        private Duration lockThreshold = Duration.ofMillis(10);
        private Duration socketThreshold = Duration.ofMillis(20);
        private Duration virtualThreadPinThreshold = Duration.ofMillis(10);
        /** Flame graph node cap (memory bound). */
        private int maxTreeNodes = 50_000;
        /** A user method using at least this share of CPU samples is reported as hot. */
        private double hotMethodPercent = 10.0;
        /** Minimum CPU samples before hot-method findings are trusted. */
        private int minSamples = 30;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getCpuSampleInterval() { return cpuSampleInterval; }
        public void setCpuSampleInterval(Duration v) { this.cpuSampleInterval = v; }
        public boolean isAllocationSampling() { return allocationSampling; }
        public void setAllocationSampling(boolean v) { this.allocationSampling = v; }
        public Duration getLockThreshold() { return lockThreshold; }
        public void setLockThreshold(Duration v) { this.lockThreshold = v; }
        public Duration getSocketThreshold() { return socketThreshold; }
        public void setSocketThreshold(Duration v) { this.socketThreshold = v; }
        public Duration getVirtualThreadPinThreshold() { return virtualThreadPinThreshold; }
        public void setVirtualThreadPinThreshold(Duration v) { this.virtualThreadPinThreshold = v; }
        public int getMaxTreeNodes() { return maxTreeNodes; }
        public void setMaxTreeNodes(int v) { this.maxTreeNodes = v; }
        public double getHotMethodPercent() { return hotMethodPercent; }
        public void setHotMethodPercent(double v) { this.hotMethodPercent = v; }
        public int getMinSamples() { return minSamples; }
        public void setMinSamples(int v) { this.minSamples = v; }
    }

    public static class Sql {
        private boolean enabled = true;
        private Duration slowQueryThreshold = Duration.ofMillis(100);
        /** Same statement executed at least this many times in one request = N+1. */
        private int nplusoneThreshold = 10;
        private Duration poolWaitThreshold = Duration.ofMillis(20);
        private int maxFingerprints = 2_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getSlowQueryThreshold() { return slowQueryThreshold; }
        public void setSlowQueryThreshold(Duration v) { this.slowQueryThreshold = v; }
        public int getNplusoneThreshold() { return nplusoneThreshold; }
        public void setNplusoneThreshold(int v) { this.nplusoneThreshold = v; }
        public Duration getPoolWaitThreshold() { return poolWaitThreshold; }
        public void setPoolWaitThreshold(Duration v) { this.poolWaitThreshold = v; }
        public int getMaxFingerprints() { return maxFingerprints; }
        public void setMaxFingerprints(int v) { this.maxFingerprints = v; }
    }

    /** Timing + argument capture for Spring beans in the base packages. */
    public static class Methods {
        private boolean enabled = true;
        private Duration slowThreshold = Duration.ofMillis(300);
        /** Calls faster than this never have their arguments rendered (keeps overhead low). */
        private Duration exemplarMinDuration = Duration.ofMillis(5);
        private int maxMethods = 5_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getSlowThreshold() { return slowThreshold; }
        public void setSlowThreshold(Duration v) { this.slowThreshold = v; }
        public Duration getExemplarMinDuration() { return exemplarMinDuration; }
        public void setExemplarMinDuration(Duration v) { this.exemplarMinDuration = v; }
        public int getMaxMethods() { return maxMethods; }
        public void setMaxMethods(int v) { this.maxMethods = v; }
    }

    public static class Http {
        private boolean enabled = true;
        private Duration slowThreshold = Duration.ofMillis(500);
        private Duration slowEndpointThreshold = Duration.ofMillis(1000);
        /** Sequential outbound calls in one request at or above this count = waterfall candidate. */
        private int waterfallMinCalls = 3;
        private double errorRatePercent = 5.0;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getSlowThreshold() { return slowThreshold; }
        public void setSlowThreshold(Duration v) { this.slowThreshold = v; }
        public Duration getSlowEndpointThreshold() { return slowEndpointThreshold; }
        public void setSlowEndpointThreshold(Duration v) { this.slowEndpointThreshold = v; }
        public int getWaterfallMinCalls() { return waterfallMinCalls; }
        public void setWaterfallMinCalls(int v) { this.waterfallMinCalls = v; }
        public double getErrorRatePercent() { return errorRatePercent; }
        public void setErrorRatePercent(double v) { this.errorRatePercent = v; }
    }

    /** Local evidence snapshots (JSON files). */
    public static class Storage {
        private boolean enabled = true;
        private String path = "./.perflens";
        private int retentionDays = 14;
        private boolean snapshotOnShutdown = true;
        /** Spring cron for automatic snapshots (default: daily 18:00); "-" disables. */
        private String snapshotCron = "0 0 18 * * *";
        /** Changes smaller than this (relative) are not reported when comparing with a snapshot. */
        private double compareMinChangePercent = 25.0;
        /** Minimum regression score: relative change x ln(1 + time consumed in ms). */
        private double compareMinScore = 1.0;

        /** After a scheduled snapshot, start a fresh window so each snapshot covers one period (e.g. one day). */
        private boolean resetAfterScheduledSnapshot = true;

        public boolean isResetAfterScheduledSnapshot() { return resetAfterScheduledSnapshot; }
        public void setResetAfterScheduledSnapshot(boolean v) { this.resetAfterScheduledSnapshot = v; }
        public String getSnapshotCron() { return snapshotCron; }
        public void setSnapshotCron(String v) { this.snapshotCron = v; }
        public double getCompareMinChangePercent() { return compareMinChangePercent; }
        public void setCompareMinChangePercent(double v) { this.compareMinChangePercent = v; }
        public double getCompareMinScore() { return compareMinScore; }
        public void setCompareMinScore(double v) { this.compareMinScore = v; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public int getRetentionDays() { return retentionDays; }
        public void setRetentionDays(int v) { this.retentionDays = v; }
        public boolean isSnapshotOnShutdown() { return snapshotOnShutdown; }
        public void setSnapshotOnShutdown(boolean v) { this.snapshotOnShutdown = v; }
    }
}
