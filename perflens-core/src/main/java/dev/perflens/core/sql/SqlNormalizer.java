package dev.perflens.core.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns SQL into a stable fingerprint: literals become {@code ?}, comments and whitespace are
 * dropped, and IN-lists / multi-row VALUES collapse so {@code IN (1,2,3)} and {@code IN (?,?)}
 * group together. Hand-written lexer; regex is only used for the final list collapse.
 */
public final class SqlNormalizer {
    private static final Pattern PLACEHOLDER_LIST = Pattern.compile("\\(\\s*\\?(?:\\s*,\\s*\\?)+\\s*\\)");
    private static final Pattern ROW_LIST = Pattern.compile("\\(\\?\\)(?:\\s*,\\s*\\(\\?\\))+");

    private SqlNormalizer() {}

    public static String normalize(String sql) {
        if (sql == null) return "";
        int n = sql.length();
        StringBuilder out = new StringBuilder(n);
        boolean pendingSpace = false;
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                pendingSpace = out.length() > 0;
                i++;
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') i++;
                pendingSpace = out.length() > 0;
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                pendingSpace = out.length() > 0;
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            if (c == '\'') {
                i = skipString(sql, i);
                out.append('?');
            } else if (c == '"' || c == '`' || c == '[') {
                char close = c == '[' ? ']' : c;
                int end = sql.indexOf(close, i + 1);
                end = end < 0 ? n : end + 1;
                out.append(sql, i, end);
                i = end;
            } else if (isIdentStart(c)) {
                int start = i;
                while (i < n && isIdentPart(sql.charAt(i))) i++;
                out.append(sql, start, i);
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(sql.charAt(i + 1)))) {
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '.')) i++;
                out.append('?');
            } else {
                out.append(c);
                i++;
            }
        }
        String s = out.toString();
        if (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
        s = PLACEHOLDER_LIST.matcher(s).replaceAll("(?)");
        s = ROW_LIST.matcher(s).replaceAll("(?)");
        return s;
    }

    /** SELECT / INSERT / UPDATE / DELETE / CALL / OTHER. */
    public static String kind(String sql) {
        String s = sql == null ? "" : sql.stripLeading();
        int end = 0;
        while (end < s.length() && Character.isLetter(s.charAt(end))) end++;
        String word = s.substring(0, end).toUpperCase(Locale.ROOT);
        return switch (word) {
            case "SELECT", "WITH" -> "SELECT";
            case "INSERT", "MERGE" -> "INSERT";
            case "UPDATE" -> "UPDATE";
            case "DELETE" -> "DELETE";
            case "CALL", "EXEC", "EXECUTE" -> "CALL";
            default -> "OTHER";
        };
    }

    /**
     * Best-effort name for each {@code ?} placeholder (the column it is compared with), used to
     * mask sensitive bind values. Handles {@code col = ?}, {@code col LIKE ?}, {@code col IN (?)}
     * and {@code INSERT INTO t (a, b) VALUES (?, ?)}. Unknown positions are null.
     */
    public static List<String> placeholderNames(String sql) {
        List<String> names = new ArrayList<>();
        if (sql == null) return names;
        List<String> insertColumns = insertColumns(sql);
        int insertIdx = 0;
        boolean inValues = false;
        String lastIdent = null;
        int n = sql.length();
        for (int i = 0; i < n; i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                i = skipString(sql, i) - 1;
            } else if (isIdentStart(c)) {
                int start = i;
                while (i < n && isIdentPart(sql.charAt(i))) i++;
                String word = sql.substring(start, i);
                i--;
                if (isDollarPlaceholder(word)) {
                    if (inValues && insertColumns != null && insertIdx < insertColumns.size()) names.add(insertColumns.get(insertIdx++));
                    else names.add(lastIdent);
                    continue;
                }
                String upper = word.toUpperCase(Locale.ROOT);
                if (upper.equals("VALUES")) inValues = true;
                if (!isKeyword(upper)) lastIdent = word;
            } else if (c == '?') {
                if (inValues && insertColumns != null && insertIdx < insertColumns.size()) {
                    names.add(insertColumns.get(insertIdx++));
                } else {
                    names.add(lastIdent);
                }
            }
        }
        return names;
    }

    private static List<String> insertColumns(String sql) {
        String upper = sql.toUpperCase(Locale.ROOT);
        if (!upper.stripLeading().startsWith("INSERT")) return null;
        int open = sql.indexOf('(');
        int values = upper.indexOf("VALUES");
        if (open < 0 || values < 0 || open > values) return null;
        int close = sql.indexOf(')', open);
        if (close < 0 || close > values) return null;
        List<String> cols = new ArrayList<>();
        for (String part : sql.substring(open + 1, close).split(",")) cols.add(part.trim());
        return cols;
    }

    /** Replaces each placeholder with its rendered value: a copy-paste-ready statement for EXPLAIN. */
    public static String inline(String sql, List<String> values) {
        if (sql == null || values == null || values.isEmpty()) return sql;
        StringBuilder out = new StringBuilder(sql.length() + values.size() * 8);
        int v = 0;
        int n = sql.length();
        for (int i = 0; i < n; i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                int end = skipString(sql, i);
                out.append(sql, i, end);
                i = end - 1;
            } else if (c == '?' && v < values.size()) {
                out.append(values.get(v++));
            } else if (c == '$' && i + 1 < n && Character.isDigit(sql.charAt(i + 1))) {
                // PostgreSQL / R2DBC style positional placeholder: $1 is the first value
                int j = i + 1;
                while (j < n && Character.isDigit(sql.charAt(j))) j++;
                int idx = Integer.parseInt(sql.substring(i + 1, j)) - 1;
                out.append(idx >= 0 && idx < values.size() ? values.get(idx) : sql.substring(i, j));
                i = j - 1;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isDollarPlaceholder(String word) {
        if (word.length() < 2 || word.charAt(0) != '$') return false;
        for (int k = 1; k < word.length(); k++) if (!Character.isDigit(word.charAt(k))) return false;
        return true;
    }

    private static int skipString(String sql, int i) {
        int n = sql.length();
        i++;
        while (i < n) {
            char d = sql.charAt(i);
            if (d == '\'') {
                if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return n;
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$' || c == '@' || c == ':';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.';
    }

    private static boolean isKeyword(String upper) {
        return switch (upper) {
            case "AND", "OR", "NOT", "IN", "LIKE", "ILIKE", "IS", "NULL", "BETWEEN", "WHERE", "SET", "VALUES",
                 "LIMIT", "OFFSET", "FETCH", "FIRST", "NEXT", "ROWS", "ONLY", "ON", "ESCAPE", "LOWER", "UPPER",
                 "ANY", "ALL", "SOME", "EXISTS", "CASE", "WHEN", "THEN", "ELSE", "END", "CAST", "AS" -> true;
            default -> false;
        };
    }
}
