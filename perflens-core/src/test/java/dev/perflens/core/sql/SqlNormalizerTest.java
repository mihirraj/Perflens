package dev.perflens.core.sql;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlNormalizerTest {

    @Test
    void literalsAndInListsCollapse() {
        String raw = "SELECT * FROM trade_positions WHERE account_id = 94821 AND status IN ('OPEN', 'PENDING')";
        assertThat(SqlNormalizer.normalize(raw))
                .isEqualTo("SELECT * FROM trade_positions WHERE account_id = ? AND status IN (?)");
    }

    @Test
    void parameterizedAndLiteralFormsShareFingerprint() {
        assertThat(SqlNormalizer.normalize("select o1_0.id from orders o1_0 where o1_0.id in (?, ?, ?)"))
                .isEqualTo(SqlNormalizer.normalize("select o1_0.id from orders o1_0 where o1_0.id in (1,2)"));
    }

    @Test
    void identifiersWithDigitsAndCommentsAndEscapedQuotes() {
        String raw = "/* hint */ SELECT t1.col2 -- trailing\n FROM t1 WHERE name = 'O''Brien'  AND x=1.5;";
        assertThat(SqlNormalizer.normalize(raw)).isEqualTo("SELECT t1.col2 FROM t1 WHERE name = ? AND x=?");
    }

    @Test
    void multiRowValuesCollapse() {
        assertThat(SqlNormalizer.normalize("insert into t (a,b) values (1,'x'),(2,'y'),(3,'z')"))
                .isEqualTo("insert into t (a,b) values (?)");
    }

    @Test
    void placeholderNamesFindComparedColumns() {
        List<String> names = SqlNormalizer.placeholderNames(
                "select * from users u where u.email = ? and u.password_hash = ? and u.age > ?");
        assertThat(names).containsExactly("u.email", "u.password_hash", "u.age");
    }

    @Test
    void placeholderNamesForInsertUseColumnList() {
        assertThat(SqlNormalizer.placeholderNames("insert into users (name, password, card_no) values (?, ?, ?)"))
                .containsExactly("name", "password", "card_no");
    }

    @Test
    void inlineProducesExecutableSql() {
        assertThat(SqlNormalizer.inline("select * from t where a = ? and b = '?' and c = ?", Arrays.asList("5", "'x'")))
                .isEqualTo("select * from t where a = 5 and b = '?' and c = 'x'");
    }

    @Test
    void dollarPlaceholdersAreNamedAndInlined() {
        String sql = "select * from users where email = $1 and password_hash = $2";
        assertThat(SqlNormalizer.placeholderNames(sql)).containsExactly("email", "password_hash");
        assertThat(SqlNormalizer.inline(sql, Arrays.asList("'a@b.c'", "***")))
                .isEqualTo("select * from users where email = 'a@b.c' and password_hash = ***");
    }

    @Test
    void kinds() {
        assertThat(SqlNormalizer.kind("  with x as (select 1) select * from x")).isEqualTo("SELECT");
        assertThat(SqlNormalizer.kind("update t set a=1")).isEqualTo("UPDATE");
        assertThat(SqlNormalizer.kind("merge into t")).isEqualTo("INSERT");
    }
}
