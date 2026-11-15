package io.github.mihirraj.perflens.core.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Turns "where inside this method did the CPU / allocations go" into concrete fix suggestions.
 * Each pattern is matched against the library call the method makes (callee) and the frame that
 * was actually executing (leaf). Add new patterns here as you learn from real evidence.
 */
public final class HotMethodAdvisor {

    record Pattern(String id, Predicate<String> matches, String advice) {}

    private static Predicate<String> prefix(String... prefixes) {
        return f -> {
            for (String p : prefixes) if (f.startsWith(p)) return true;
            return false;
        };
    }

    private static final List<Pattern> CPU_PATTERNS = List.of(
            new Pattern("jackson-new-mapper", prefix("com.fasterxml.jackson.databind.ObjectMapper.<init>"),
                    "A new ObjectMapper is created on each call (expensive: it builds serializer caches every time). Inject Spring's ObjectMapper bean or keep one in a static final field."),
            new Pattern("jackson", prefix("com.fasterxml.jackson."),
                    "JSON (de)serialization dominates. Reuse a prebuilt ObjectWriter/ObjectReader (mapper.writerFor(Type.class)), avoid serializing inside loops, cache serialized results for repeated values, and stream big payloads with JsonGenerator."),
            new Pattern("regex-compile", prefix("java.util.regex.Pattern.compile", "java.util.regex.Pattern.<init>",
                    "java.lang.String.replaceAll", "java.lang.String.replaceFirst", "java.lang.String.matches", "java.lang.String.split"),
                    "A regex is compiled on every call (String.matches/replaceAll/split compile the pattern each time). Hoist it to a `private static final Pattern` and reuse pattern.matcher(...)."),
            new Pattern("regex-match", prefix("java.util.regex."),
                    "Regex matching dominates. Simplify the pattern (avoid nested quantifiers / leading .*), anchor it, or replace it with indexOf/startsWith checks."),
            new Pattern("exceptions", prefix("java.lang.Throwable.fillInStackTrace", "java.lang.Throwable.<init>",
                    "java.lang.Exception.<init>", "java.lang.RuntimeException.<init>", "java.lang.StackTraceElement"),
                    "Exceptions are created in a hot path (stack trace capture is expensive). Don't use exceptions for control flow; validate first, or for expected cases use an exception that overrides fillInStackTrace."),
            new Pattern("string-format", prefix("java.util.Formatter", "java.lang.String.format", "java.text.MessageFormat"),
                    "String.format / MessageFormat dominates. Use StringBuilder or plain concatenation in hot paths, and use parameterized logging (log.debug(\"x={}\", x)) instead of formatting up front."),
            new Pattern("date-format", prefix("java.time.format.DateTimeFormatter.ofPattern", "java.text.SimpleDateFormat",
                    "java.time.format.DateTimeFormatterBuilder"),
                    "A date formatter is built on every call. Make the DateTimeFormatter a static final constant (it is immutable and thread-safe)."),
            new Pattern("list-scan", prefix("java.util.ArrayList.indexOf", "java.util.ArrayList.contains", "java.util.ArrayList.remove",
                    "java.util.LinkedList.get", "java.util.LinkedList.indexOf", "java.util.AbstractList.indexOf",
                    "java.util.AbstractCollection.contains", "java.util.ArrayList.indexOfRange"),
                    "Linear search in a List, probably inside a loop (O(n^2)). Build a HashSet/HashMap once and look up in O(1); never call LinkedList.get(i) in a loop."),
            new Pattern("resize", prefix("java.util.HashMap.resize", "java.util.ArrayList.grow", "java.util.Arrays.copyOf",
                    "java.lang.AbstractStringBuilder.ensureCapacityInternal", "java.lang.AbstractStringBuilder.newCapacity"),
                    "Collections/StringBuilders keep resizing. Presize them (new ArrayList<>(n), HashMap.newHashMap(n), new StringBuilder(capacity))."),
            new Pattern("string-building", prefix("java.lang.StringConcatHelper", "java.lang.AbstractStringBuilder", "java.lang.StringBuilder",
                    "java.lang.String.concat", "java.lang.StringLatin1", "java.lang.StringUTF16"),
                    "String building/copying dominates. Avoid `+=` on Strings in loops, reuse one presized StringBuilder, and don't build strings that are only needed for disabled log levels."),
            new Pattern("bigdecimal", prefix("java.math.BigDecimal", "java.math.BigInteger", "java.math.MutableBigInteger"),
                    "BigDecimal/BigInteger arithmetic dominates. Cache constants (BigDecimal.valueOf), avoid needless setScale/divide with huge precision, or use long minor units (cents) for money math in tight loops."),
            new Pattern("crypto", prefix("java.security.", "javax.crypto.", "sun.security.", "com.sun.crypto.",
                    "org.springframework.security.crypto."),
                    "Hashing/crypto dominates. Cache results for repeated inputs, keep BCrypt (high cost) to login paths only, and reuse MessageDigest/Cipher instances per thread instead of getInstance() per call."),
            new Pattern("reflection-mapping", prefix("org.springframework.beans.BeanUtils", "org.springframework.beans.BeanWrapperImpl",
                    "org.modelmapper.", "org.dozer.", "com.github.dozermapper.", "java.beans.", "org.apache.commons.beanutils."),
                    "Reflection-based object mapping dominates. Switch to compile-time mapping (MapStruct) or a hand-written mapper."),
            new Pattern("logging", prefix("ch.qos.logback.", "org.apache.logging.log4j.", "org.slf4j.", "java.util.logging."),
                    "Logging dominates. Lower the level in this path, use parameterized messages, avoid logging inside loops, and use an AsyncAppender."),
            new Pattern("hibernate-flush", prefix("org.hibernate.event.internal.AbstractFlushingEventListener",
                    "org.hibernate.event.internal.DefaultFlushEntityEventListener", "org.hibernate.engine.internal.StatefulPersistenceContext"),
                    "Hibernate flush / dirty checking dominates: too many managed entities in the persistence context. Use @Transactional(readOnly = true) for reads, DTO projections instead of entities, and flush()+clear() every N entities in batch jobs."),
            new Pattern("hibernate", prefix("org.hibernate."),
                    "ORM overhead dominates (entity hydration/proxies). Fetch only what you need with DTO/interface projections, and avoid loading large entity graphs to read a few fields."),
            new Pattern("spel", prefix("org.springframework.expression."),
                    "SpEL is evaluated in a hot path. Parse the Expression once and cache it, or replace it with plain Java."),
            new Pattern("streams", prefix("java.util.stream."),
                    "Stream pipeline overhead dominates. For tight loops over large data use primitive streams (mapToInt) or a plain loop; avoid sorted()/boxed()/collect(groupingBy) on every call when the result could be computed once."),
            new Pattern("compression", prefix("java.util.zip.", "java.util.Base64"),
                    "Compression/encoding dominates. Cache encoded results, lower the compression level, or skip compression for small payloads."),
            new Pattern("uuid-random", prefix("java.util.UUID.randomUUID", "java.security.SecureRandom"),
                    "UUID.randomUUID()/SecureRandom dominates. For non-security IDs use a faster generator (e.g. time-based UUIDv7 library or ThreadLocalRandom)."),
            new Pattern("xml", prefix("javax.xml.", "com.sun.org.apache.xerces.", "com.sun.org.apache.xalan.", "jakarta.xml.bind."),
                    "XML parsing/binding dominates. Reuse JAXBContext / DocumentBuilderFactory / TransformerFactory instances (creating them is very expensive) and prefer StAX streaming for large documents.")
    );

    /** Own-code fallback when most samples are inside the method itself. */
    public static final String OWN_CODE = "Most samples are in this method's own code, concentrated on the lines listed. Look for nested loops over large collections, repeated work that could be cached or hoisted out of the loop, and algorithmic shortcuts (sorting once, indexing with a Map).";

    private HotMethodAdvisor() {}

    /** @return pattern id -> advice text, strongest first; each with its share of the method's samples */
    public static List<String> advise(Map<String, Long> callees, Map<String, Long> leaves, long methodSamples,
                                      Map<String, Double> sharesOut) {
        Map<String, Long> scores = new LinkedHashMap<>();
        Map<String, String> advice = new LinkedHashMap<>();
        // the leaf is the most specific signal; the callee tells which API the code invoked
        accumulate(leaves, scores, advice);
        accumulate(callees, scores, advice);
        long own = callees.getOrDefault("(own code)", 0L);

        // Within a family (regex-compile / regex-match, jackson-new-mapper / jackson) the more specific
        // pattern, declared first, wins whenever it has real evidence; the family keeps the larger share.
        List<String> order = CPU_PATTERNS.stream().map(Pattern::id).toList();
        Map<String, String> familyPick = new LinkedHashMap<>();
        Map<String, Long> familyScore = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : scores.entrySet()) {
            if (e.getValue() * 100.0 / Math.max(1, methodSamples) < 10.0) continue;
            String family = e.getKey().split("-")[0];
            String current = familyPick.get(family);
            if (current == null || order.indexOf(e.getKey()) < order.indexOf(current)) familyPick.put(family, e.getKey());
            familyScore.merge(family, e.getValue(), Math::max);
        }
        List<String> ranked = new ArrayList<>(familyPick.keySet());
        ranked.sort((a, b) -> Long.compare(familyScore.get(b), familyScore.get(a)));
        List<String> out = new ArrayList<>();
        for (String family : ranked) {
            if (out.size() >= 3) break;
            String id = familyPick.get(family);
            double share = Math.min(100.0, familyScore.get(family) * 100.0 / Math.max(1, methodSamples));
            out.add(advice.get(id) + String.format(Locale.ROOT, " (%.0f%% of this method's samples)", share));
            Map.Entry<String, Long> e = Map.entry(id, familyScore.get(family));
            if (sharesOut != null) sharesOut.put(e.getKey(), share);
        }
        double ownShare = own * 100.0 / Math.max(1, methodSamples);
        if (ownShare >= 30.0 || out.isEmpty()) {
            out.add(OWN_CODE + String.format(Locale.ROOT, " (%.0f%% of samples in own code)", ownShare));
        }
        return out;
    }

    private static void accumulate(Map<String, Long> frames, Map<String, Long> scores, Map<String, String> advice) {
        Map<String, Long> perPattern = new LinkedHashMap<>();
        for (Map.Entry<String, Long> f : frames.entrySet()) {
            for (Pattern p : CPU_PATTERNS) {
                if (p.matches().test(f.getKey())) {
                    perPattern.merge(p.id(), f.getValue(), Long::sum);
                    advice.putIfAbsent(p.id(), p.advice());
                    break;
                }
            }
        }
        // a pattern's score is the larger of its leaf or callee evidence (they overlap, don't add)
        perPattern.forEach((id, v) -> scores.merge(id, v, Math::max));
    }

    /** Suggestions for an allocation-heavy method based on what it allocates. */
    public static List<String> adviseAllocation(Map<String, Long> allocClasses) {
        List<String> out = new ArrayList<>();
        String top = allocClasses.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
        if (top.equals("byte[]") || top.equals("char[]")) {
            out.add("Mostly " + top + " buffers: stream large payloads instead of reading them fully into memory, reuse buffers, and avoid repeated String<->byte[] conversions.");
        } else if (top.startsWith("java.lang.Long") || top.startsWith("java.lang.Integer") || top.startsWith("java.lang.Double")) {
            out.add("Mostly boxed numbers (" + top + "): use primitive types/arrays or primitive streams (mapToLong) instead of List<Long>/Map<..., Integer> in hot paths.");
        } else if (top.equals("java.lang.String")) {
            out.add("Mostly Strings: avoid building temporary strings (concatenation for map keys, toString for logging); use records/composite keys and parameterized logging.");
        } else if (top.startsWith("java.util.")) {
            out.add("Mostly collection objects (" + top + "): presize collections, avoid copying lists per call, and reuse immutable results.");
        } else if (!top.isEmpty()) {
            out.add("Mostly " + top + " instances: check whether they can be cached, reused, or created once outside a loop.");
        }
        out.add("High allocation rate drives young-GC frequency; reducing it lowers latency spikes for every request.");
        return out;
    }
}
