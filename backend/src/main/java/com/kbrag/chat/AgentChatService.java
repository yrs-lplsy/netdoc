package com.kbrag.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbrag.chat.ConversationRepository;
import com.kbrag.chat.MessageRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 全链路 SSE 透传:前端 ← Java ← Python Agent。
 * 透传 answer/source/done/error;done 后把完整回答落库 message(行为与 Phase 1 兼容)。
 */
@Service
public class AgentChatService {
    private final WebClient webClient;
    private final MessageRepository messages;
    private final ConversationRepository conversations;
    private final ObjectMapper om = new ObjectMapper();

    public AgentChatService(WebClient.Builder builder,
                            MessageRepository messages,
                            ConversationRepository conversations,
                            @Value("${app.agent.base-url:http://localhost:9100}") String agentBaseUrl) {
        this.webClient = builder.baseUrl(agentBaseUrl).build();
        this.messages = messages;
        this.conversations = conversations;
    }

    public void stream(String question, Long conversationId, SseEmitter emitter) {
        List<Map<String, String>> history = conversationId == null ? List.of()
                : messages.findByConversationIdOrderByIdAsc(conversationId).stream()
                        .map(m -> Map.of("role", m.getRole(), "content", m.getContent()))
                        .toList();
        // 滑动窗口:只取最近 10 条(升序列表取尾部)
        history = history.size() > 10 ? history.subList(history.size() - 10, history.size()) : history;
        // 用 HashMap:conversation_id 为 null 时(新会话)Map.of 会 NPE(不可变集合禁止 null)
        Map<String, Object> body = new HashMap<>();
        body.put("message", question);
        body.put("conversation_id", conversationId);
        body.put("history", history);

        Flux<ServerSentEvent<String>> stream = webClient.post()
                .uri("/agent/chat")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .timeout(Duration.ofSeconds(180));

        StringBuilder answer = new StringBuilder();
        Map<String, Integer> phaseMs = new HashMap<>();   // 各阶段耗时(供 Task 9 可观测落库)
        stream.subscribe(
                sse -> {
                    try {
                        String event = sse.event();
                        String data = sse.data();
                        if ("answer".equals(event)) {
                            // data 形如 {"seq":n,"data":"token"}
                            String token = om.readTree(data).path("data").asText("");
                            answer.append(token);
                        } else if ("phase".equals(event) && data != null) {
                            // data 形如 {"seq":n,"data":{"node":"rewrite","elapsedMs":123}}
                            JsonNode d = om.readTree(data).path("data");
                            phaseMs.put(d.path("node").asText(), d.path("elapsedMs").asInt(0));
                        } else if ("done".equals(event) && data != null) {
                            // data 形如 {"seq":n,"data":{...}}
                            boolean verified = om.readTree(data).path("data").path("verified").asBoolean(false);
                            String error = om.readTree(data).path("data").path("error").asText("");
                            if (error != null && !error.isEmpty()) {
                                emitter.send(SseEmitter.event().name("error").data(data));
                            }
                        }
                        emitter.send(SseEmitter.event().name(event).data(data));
                        if ("done".equals(event)) {
                            // 落库并回传会话 id(新建会话时前端需要它续聊——多轮对话闭环)
                            Long cid = save(conversationId, question, answer.toString());
                            try {
                                emitter.send(SseEmitter.event().name("conversation")
                                        .data("{\"seq\":999,\"data\":{\"conversationId\":" + cid + "}}"));
                            } catch (Exception ignored) { }
                            emitter.complete();
                        }
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    }
                },
                err -> {
                    try {
                        emitter.send(SseEmitter.event().name("error")
                                .data("{\"seq\":-1,\"data\":\"Agent 服务暂不可用:" + err.getMessage() + "\"}"));
                    } catch (Exception ignored) { }
                    emitter.complete();
                },
                emitter::complete);
    }

    /** 落库 user/assistant;返回会话 id(conversationId 为 null 时新建并返回新 id)。 */
    private Long save(Long conversationId, String user, String assistant) {
        if (conversationId == null) {
            Conversation c = new Conversation();
            c.setTitle(user.length() > 20 ? user.substring(0, 20) : user);
            conversations.save(c);
            conversationId = c.getId();
        }
        Message m1 = new Message();
        m1.setConversationId(conversationId); m1.setRole("user"); m1.setContent(user);
        Message m2 = new Message();
        m2.setConversationId(conversationId); m2.setRole("assistant"); m2.setContent(assistant);
        messages.save(m1);
        messages.save(m2);
        return conversationId;
    }
}
