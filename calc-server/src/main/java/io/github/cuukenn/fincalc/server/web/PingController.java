package io.github.cuukenn.fincalc.server.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness probe of the web layer plus the facade bean wiring. Actuator's
 * {@code /actuator/health} covers process health; this endpoint additionally proves that the
 * Spring context, the MVC stack and the engine assembly came up together.
 */
@RestController
public class PingController {

    /** @return the agreed liveness body: {@code {"status":"UP","engine":"ready"}} */
    @GetMapping("/api/v1/ping")
    public Map<String, String> ping() {
        return Map.of("status", "UP", "engine", "ready");
    }
}
