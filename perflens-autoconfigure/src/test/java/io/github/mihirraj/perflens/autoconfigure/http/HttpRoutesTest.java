package io.github.mihirraj.perflens.autoconfigure.http;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class HttpRoutesTest {

    @Test
    void usesTheUriTemplateWhenThereIsOne() {
        assertThat(HttpRoutes.route("/users/{id}/orders", URI.create("http://api/users/42/orders"))).isEqualTo("/users/{id}/orders");
        assertThat(HttpRoutes.route("http://api:8080/users/{id}?x={x}", null)).isEqualTo("/users/{id}");
    }

    @Test
    void replacesIdSegmentsWhenTheUrlWasBuiltByConcatenation() {
        // RestClient reports the concatenated string as its "template"
        assertThat(HttpRoutes.route("/items/1999", URI.create("http://api/items/1999"))).isEqualTo("/items/{id}");
        assertThat(HttpRoutes.route(null, URI.create("http://api/v2/items/1999/parts/7?expand=all"))).isEqualTo("/v2/items/{id}/parts/{id}");
        assertThat(HttpRoutes.route(null, URI.create("http://api/orders/3f2b8c1e-9d4a-4b7e-8c21-5a6f0e9d1b2c")))
                .isEqualTo("/orders/{id}");
        assertThat(HttpRoutes.route(null, URI.create("http://api/blobs/9f86d081884c7d659a2feaa0c55ad015"))).isEqualTo("/blobs/{id}");
    }

    @Test
    void leavesOrdinaryPathSegmentsAlone() {
        assertThat(HttpRoutes.route(null, URI.create("http://api/v2/health/"))).isEqualTo("/v2/health/");
        assertThat(HttpRoutes.route(null, URI.create("http://api/cafe/beef"))).isEqualTo("/cafe/beef");
        assertThat(HttpRoutes.route(null, URI.create("http://api"))).isEqualTo("/");
    }
}
