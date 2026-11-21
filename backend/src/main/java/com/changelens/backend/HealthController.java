package com.changelens.backend;

import java.util.Map;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

// @RestController = "this class answers web requests and returns JSON".
// @RequestMapping("/api") = every URL in this class starts with /api.
// @CrossOrigin = lets our React page (running on port 5173) call this server (port 8080).
//   Browsers block this by default for safety; this line says "5173 is allowed".
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:5173")
public class HealthController {

    // RestClient is Spring's tool for calling OTHER web servers - here, our Python service.
    private final RestClient aiClient = RestClient.create("http://localhost:8000");

    // @GetMapping("/health") = when someone opens http://localhost:8080/api/health, run this.
    @GetMapping("/health")
    public Map<String, Object> health() {
        Object aiStatus;
        try {
            // Ask the Python service: "are you alive?" and keep its JSON answer.
            aiStatus = aiClient.get().uri("/health").retrieve().body(Map.class);
        } catch (Exception e) {
            // If Python is not running, we don't crash - we report it as DOWN.
            aiStatus = Map.of("status", "DOWN");
        }
        // the product still works without the AI service, so report DEGRADED instead of UP when it is down
        String status = String.valueOf(aiStatus).contains("DOWN") ? "DEGRADED" : "UP";
        return Map.of(
                "service", "backend",
                "status", status,
                "aiService", aiStatus);
    }
}
