package dev.perflens.core.rules;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Heuristic, text-level advice for a slow SQL statement. Always backed by EXPLAIN on a real execution. */
public final class SqlAdvisor {
    private static final Pattern FUNCTION_ON_COLUMN =
            Pattern.compile("\\b(LOWER|UPPER|TRIM|DATE|CAST|COALESCE|SUBSTRING|SUBSTR|TO_CHAR|YEAR|MONTH)\\s*\\(");
    private static final Pattern LIMIT = Pattern.compile("\\b(LIMIT|FETCH\\s+FIRST|FETCH\\s+NEXT|TOP)\\b");

    private SqlAdvisor() {}

    public static List<String> adviseSlow(String fingerprint, String kind, List<String> paramNames,
                                          List<String> exemplarParams) {
        String u = fingerprint.toUpperCase(Locale.ROOT);
        int where = u.indexOf(" WHERE ");
        String wherePart = where < 0 ? "" : u.substring(where);
        List<String> out = new ArrayList<>();

        Set<String> cols = new LinkedHashSet<>();
        for (String n : paramNames) if (n != null && !n.startsWith(":")) cols.add(n);
        out.add("Run EXPLAIN ANALYZE (PostgreSQL) / EXPLAIN FORMAT=TREE (MySQL) on the slowest real execution below (copy 'executableSql') and look for full/sequential scans"
                + (cols.isEmpty() ? "." : " on " + String.join(", ", cols) + "; an index on these columns is the usual fix."));

        boolean leadingWildcard = exemplarParams != null && exemplarParams.stream()
                .filter(Objects::nonNull).anyMatch(p -> p.startsWith("'%"));
        if (leadingWildcard || u.contains("LIKE '%")) {
            out.add("A LIKE pattern starts with '%' (e.g. '%abc%'), which no B-tree index can serve. Use a trigram/full-text index (PostgreSQL pg_trgm, MySQL FULLTEXT) or a prefix search ('abc%').");
        }
        if (FUNCTION_ON_COLUMN.matcher(wherePart).find()) {
            out.add("A function is applied to a column in WHERE (e.g. LOWER(email) = ?), which disables normal indexes. Add an expression index (CREATE INDEX ... ON t (LOWER(email))) or store a normalized column.");
        }
        if (kind.equals("SELECT") && where < 0 && !LIMIT.matcher(u).find() && !u.contains("COUNT(")) {
            out.add("No WHERE and no LIMIT: this reads the whole table on every call. Filter it, paginate (Spring Data Pageable / Slice), or cache the result if it rarely changes.");
        }
        if (u.contains(" OFFSET ")) {
            out.add("OFFSET pagination scans and discards every skipped row, so deep pages get slower. Use keyset pagination: WHERE id > :lastSeenId ORDER BY id LIMIT :n.");
        }
        if (u.contains(" ORDER BY ") && !LIMIT.matcher(u).find()) {
            out.add("Sorting the full result without LIMIT. Add an index matching the ORDER BY columns or paginate.");
        }
        if (wherePart.contains(" OR ")) {
            out.add("OR across different columns often prevents index use. Consider a composite index or rewriting as UNION ALL of two indexed queries.");
        }
        if (u.contains("COUNT(")) {
            out.add("COUNT over a large table is expensive. If this comes from a Spring Data Page<T>, return Slice<T> instead (no count query), or cache the total.");
        }
        if (u.startsWith("SELECT * ") || u.contains(" SELECT * ")) {
            out.add("SELECT * fetches every column. Select only what you need (DTO/interface projection) so covering indexes can be used and less data is transferred.");
        }
        if (kind.equals("UPDATE") || kind.equals("DELETE")) {
            out.add("Slow writes: make sure the WHERE columns are indexed (otherwise every row is scanned and locked), and keep the surrounding @Transactional short so row locks are released quickly.");
        }
        return out;
    }

    public static List<String> adviseNPlusOne(String kind, String fingerprint) {
        List<String> out = new ArrayList<>();
        String u = fingerprint.toUpperCase(Locale.ROOT);
        if (u.contains("NEXT VALUE FOR") || u.contains("NEXTVAL(") || u.contains(".NEXTVAL")) {
            out.add("This is a sequence fetch per inserted row. Raise the generator's allocation size (@SequenceGenerator(allocationSize = 50) with a matching INCREMENT BY on the DB sequence) so Hibernate's pooled optimizer fetches ids in blocks.");
            out.add("Combine with JDBC batching (spring.jpa.properties.hibernate.jdbc.batch_size=50) so the inserts themselves are batched too.");
            return out;
        }
        if (kind.equals("SELECT")) {
            out.add("Load the association together with the parent: add JOIN FETCH to the repository @Query, or annotate the repository method with @EntityGraph(attributePaths = {\"...\"}).");
            out.add("Or let Hibernate batch the lazy loads: set spring.jpa.properties.hibernate.default_batch_fetch_size=100 (or @BatchSize(size = 100) on the collection), turning N queries into N/100.");
            out.add("If you only need a few fields, use a single DTO projection query instead of walking entities in a loop.");
            out.add("If the repeated lookup is a findById() inside a loop, collect the ids first and use findAllById(ids) once.");
        } else {
            out.add("Same write statement executed one row at a time. Enable JDBC batching: spring.jpa.properties.hibernate.jdbc.batch_size=50, hibernate.order_inserts=true, hibernate.order_updates=true.");
            out.add("Note: GenerationType.IDENTITY disables Hibernate insert batching; use SEQUENCE with an allocationSize, or JdbcTemplate.batchUpdate / saveAll for bulk writes.");
        }
        return out;
    }
}
