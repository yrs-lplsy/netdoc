package com.kbrag.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * /api/agent/health:检查 Python Agent 服务连通性(供运维/前端探测)。
 */
@RestController
public class AgentHealthController {
    private final WebClient webClient;

    public AgentHealthController(WebClient.Builder builder,
                                 @Value("${app.agent.base-url:http://localhost:9100}") String agentBaseUrl) {
        this.webClient = builder.baseUrl(agentBaseUrl).build();
    }

    @GetMapping("/api/agent/health")
    public Map<String, String> health() {
        String agent = "DOWN";
        try {
            Map<?, ?> body = webClient.get().uri("/health")
                    .retrieve().bodyToMono(Map.class).block(Duration.ofSeconds(2));
            if (body != null && "UP".equals(body.get("status"))) agent = "UP";
        } catch (Exception ignored) { }
        return Map.of("java", "UP", "agent", agent);
    }
}
