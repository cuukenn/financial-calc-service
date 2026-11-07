package io.github.cuukenn.fincalc.server;

import io.github.cuukenn.fincalc.engine.CalculationFacade;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * The Spring Boot thin shell over the calculation engine (Plan 2 Task 1, R78).
 *
 * <p>The core modules stay framework-free, so component scanning is intentionally limited to
 * this package; the engine is assembled by hand below: {@link CalculationFacade#defaults()}
 * wires the built-in function library and every built-in generator/metric/validator. Script
 * directories are not wired in v1 — {@code defaultsWithScripts} remains available when a real
 * consumer needs classpath rules.
 *
 * <p>This module deliberately has no {@code module-info.java}: Spring Boot applications run on
 * the classpath, and the core modules' JPMS descriptors are unaffected by this consumer.
 */
@SpringBootApplication
public class CalcServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CalcServerApplication.class, args);
    }

    /** The R78 minimal assembly: built-in functions only, no external scripts. */
    @Bean
    public CalculationFacade calculationFacade() {
        return CalculationFacade.defaults();
    }
}
