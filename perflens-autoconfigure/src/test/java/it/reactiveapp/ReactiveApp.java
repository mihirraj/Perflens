package it.reactiveapp;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/** Minimal WebFlux + R2DBC app used to verify PerfLens' reactive support. */
@SpringBootApplication
public class ReactiveApp {

    @Service
    public static class ItemService {
        private final DatabaseClient db;

        public ItemService(DatabaseClient db) {
            this.db = db;
        }

        /** Returns a Mono: must NOT be timed by the method advisor (it would only time assembly). */
        public Mono<Map<String, Object>> find(long id) {
            return db.sql("SELECT ID, NAME FROM ITEM WHERE ID = :id").bind("id", id).fetch().one();
        }

        /** Deliberately heavy query with a bind parameter. */
        public Mono<Long> heavyCount(int mod) {
            return db.sql("SELECT COUNT(*) AS C FROM SYSTEM_RANGE(1, 400000) WHERE MOD(X, :mod) = 0")
                    .bind("mod", mod).map(r -> r.get("C", Long.class)).one();
        }

        /** Synchronous method: IS timed, with its argument captured. */
        public String label(String name) {
            return "item:" + name;
        }
    }

    @RestController
    public static class ItemController {
        private final ItemService items;

        public ItemController(ItemService items) {
            this.items = items;
        }

        @GetMapping("/items/{id}")
        public Mono<Map<String, Object>> item(@PathVariable long id) {
            return items.find(id);
        }

        @GetMapping("/count")
        public Mono<String> count(@RequestParam int mod) {
            return items.heavyCount(mod).map(c -> items.label(String.valueOf(c)));
        }
    }
}
