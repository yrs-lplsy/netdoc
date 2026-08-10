package com.kbrag.chat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.kbrag.ratelimit.RateLimiter;
import com.kbrag.agent.*;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    @Autowired private AgentChatService agentChatService;
    @Autowired private RateLimiter rateLimiter;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    public record ChatRequest(String message, Long conversationId) {}

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<?> chat(@RequestBody ChatRequest req, HttpServletRequest http) {
        String userId = clientIp(http);   // 无登录态,用 IP 作为用户标识(登录后换 userId,维度不变)
        if (!rateLimiter.tryAcquire(userId)) {
            return ResponseEntity.status(429).body("请求过于频繁,请稍后再试");
        }
        SseEmitter emitter = new SseEmitter(180_000L);
        executor.execute(() -> agentChatService.stream(req.message(), req.conversationId(), emitter));
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    private String clientIp(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        return fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : req.getRemoteAddr();
    }
}
