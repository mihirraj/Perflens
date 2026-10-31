package com.acme.shop;

import com.acme.loadgen.LoadGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Demo shop with deliberate performance problems. PerfLens is active only because
 * perflens-spring-boot-starter is on the classpath: there is no PerfLens code or config here.
 */
@SpringBootApplication
@Import(LoadGenerator.class)
public class ShopApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShopApplication.class, args);
    }
}
