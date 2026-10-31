package dev.perflens.core.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ValueRendererTest {
    static class Order {
        private final Long id = 42L;
        @Override
        public String toString() {
            throw new AssertionError("toString must never be called (lazy loading risk)");
        }
    }

    private final ValueRenderer r = new ValueRenderer(30, List.of("password", "token"));

    @Test
    void masksSensitiveNames() {
        assertThat(r.render("userPassword", "hunter2")).isEqualTo("***");
        assertThat(r.render("u.password_hash", "x")).isEqualTo("***");
        assertThat(r.render("email", "a@b.c")).isEqualTo("'a@b.c'");
    }

    @Test
    void entitiesRenderedByIdWithoutToString() {
        assertThat(r.render(new Order())).isEqualTo("Order{id=42}");
    }

    @Test
    void collectionsAndTruncation() {
        assertThat(r.render(List.of(1, 2, 3, 4, 5))).startsWith("ListN(size=5, first=[1, 2, 3, ...");
        assertThat(r.render("x".repeat(100))).endsWith("chars)");
    }
}
