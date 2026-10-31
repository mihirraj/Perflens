package com.acme.shop.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Pretend downstream services, served by the same app so the demo is self-contained. */
@RestController
@RequestMapping("/mock")
public class MockDownstreamController {

    @GetMapping("/credit/{id}")
    public Map<String, Object> credit(@PathVariable long id) throws InterruptedException {
        Thread.sleep(550 + ThreadLocalRandom.current().nextInt(150)); // slow dependency
        return Map.of("customerId", id, "limit", 5000);
    }

    @GetMapping("/shipping/{id}")
    public Map<String, Object> shipping(@PathVariable long id) throws InterruptedException {
        Thread.sleep(120);
        return Map.of("customerId", id, "eta", "2 days");
    }

    @GetMapping("/loyalty/{id}")
    public Map<String, Object> loyalty(@PathVariable long id) throws InterruptedException {
        Thread.sleep(60);
        if (ThreadLocalRandom.current().nextInt(4) == 0) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "loyalty service overloaded");
        }
        return Map.of("customerId", id, "points", id * 7);
    }
}
