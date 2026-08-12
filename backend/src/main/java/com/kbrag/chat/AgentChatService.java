package com.kbrag.chat;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbrag.cache.ChatCacheService;
import com.kbrag.chat.ConversationRepository;
import com.kbrag.chat.MessageRepository;
import com.kbrag.document.DocumentRepository;
import com.kbrag.obs.ObservabilityService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import reactor.core.publisher.Flux;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 全链路 SSE 透传:前端 ← Java ← Python Agent。
 * 透传 answer/source/done/error;done 后把完整回答落库 message(行为与 Phase 1 兼容)。
 */
@Service
public class AgentChatService {
    private final WebClient webClient;
    private final MessageRepository messages;
    private final ConversationRepository conversations;
    private final DocumentRepository documents;
    private final ChatCacheService chatCache;
    private final ObjectMapper om = new ObjectMapper();
    @Autowired private ObservabilityService observabilityService;

    public AgentChatService(WebClient.Builder builder,
                            MessageRepository messages,
                            ConversationRepository conversations,
                            DocumentRepository documents,
                            ChatCacheService chatCache,
                            @Value("${app.agent.base-url:http://localhost:9100}") String agentBaseUrl) {
        this.webClient = builder.baseUrl(agentBaseUrl).build();
        this.messages = messages;
        this.conversations = conversations;
        this.documents = documents;
        this.chatCache = chatCache;
    }

    public void stream(String question, Long kbId, Long conversationId, SseEmitter emitter) {
        Long kbVersion = documents.maxUpdatedAt(kbId)
            .map(ldt -> ldt.toEpochSecond(ZoneOffset.UTC))   // 方法引用 → lambda
            .orElse(0L);
        long t0 = System.currentTimeMillis();
        // I3:缓存查询故障(Redis/embedding)降级走 Python 主链路,不挂起
        Optional<ChatCacheService.CacheHit> hit = Optional.empty();
        try {
            hit = chatCache.lookup(question, kbId, kbVersion);
        } catch (Exception e) {
            System.err.println("[cache] lookup failed, degrade to agent: " + e.getMessage());
        }
        if (hit.isPresent()) {
            // I2:命中轮也要落库 + 建会话 + 回传 conversation id(多轮历史不断链)
            Long cid = save(conversationId, question, hit.get().answer());
            try {
                emitter.send(SseEmitter.event().name("cache_hit").data("{\"seq\":1,\"data\":true}"));
                emitter.send(SseEmitter.event().name("answer").data("{\"seq\":2,\"data\":" + om.writeValueAsString(hit.get().answer()) + "}"));
                emitter.send(SseEmitter.event().name("source").data("{\"seq\":3,\"data\":" + hit.get().sourcesJson() + "}"));
                emitter.send(SseEmitter.event().name("conversation").data("{\"seq\":4,\"data\":{\"conversationId\":" + cid + "}}"));
                emitter.send(SseEmitter.event().name("done").data("{\"seq\":5,\"data\":null}"));
            } catch (IOException e) {
                emitter.completeWithError(e);
                return;
            }
            emitter.complete();
            // 命中轮也落 span(cacheHit=true):缓存命中率统计的数据源
            observabilityService.saveSpan(cid, question, System.currentTimeMillis() - t0, Map.of(), true);
            return;
        }
        List<Map<String, String>> history = conversationId == null ? List.of()
                : messages.findByConversationIdOrderByIdAsc(conversationId).stream()
                        .map(m -> Map.of("role", m.getRole(), "content", m.getContent()))
                        .toList();
        // 滑动窗口:只取最近 10 条(升序列表取尾部)
        history = history.size() > 10 ? history.subList(history.size() - 10, history.size()) : history;
        // 用 HashMap:conversation_id 为 null 时(新会话)Map.of 会 NPE(不可变集合禁止 null)
        Map<String, Object> body = new HashMap<>();
        body.put("kb_id", kbId);
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
        String[] sourcesJson = {null};                    // source 事件暂存(lambda 内可变,缓存写入用)
        boolean[] errored = {false};                      // 本轮是否出错(错误轮跳过落库/写缓存)
        boolean[] doneHandled = {false};                  // done 去重
        boolean[] doneVerified = {false};                 // 忠实度自检结果(done 分支 A 赋值,B 用)
        stream.subscribe(
                sse -> {
                    try {
                        String event = sse.event();
                        String data = sse.data();
                        if (data == null) return;   // 心跳/注释帧(sse-starlette 默认 15s ping),透传层过滤,不转发
                        if ("error".equals(event)) {
                            errored[0] = true;      // I4:错误轮不再落库/写缓存
                        } else if ("answer".equals(event)) {
                            // data 形如 {"seq":n,"data":"token"}
                            String token = om.readTree(data).path("data").asText("");
                            answer.append(token);
                        } else if ("phase".equals(event) && data != null) {
                            // data 形如 {"seq":n,"data":{"node":"rewrite","elapsedMs":123}}
                            JsonNode d = om.readTree(data).path("data");
                            String node = d.path("node").asText();
                            phaseMs.put(node, d.path("elapsedMs").asInt(0));
                            // I5:verify 重试 → 第二轮 rewrite 开始:重置累积文本 + 通知前端重绘(避免双段回答)
                            if ("rewrite".equals(node) && phaseMs.containsKey("verify")) {
                                answer.setLength(0);
                                try {
                                    emitter.send(SseEmitter.event().name("regenerate").data("{\"seq\":9999,\"data\":true}"));
                                } catch (Exception ignored) { }
                            }
                        } else if ("source".equals(event) && data != null) {
                            // I1:暂存内层 data(引用数组),命中重放时不再二次包装
                            sourcesJson[0] = om.readTree(data).path("data").toString();
                        } else if ("done".equals(event) && data != null) {
                            JsonNode d = om.readTree(data).path("data");
                            // 拒答/直答路径:answer 未流式,补发一次(否则前端无任何显示)
                            String finalAnswer = d.path("answer").asText("");
                            if (answer.length() == 0 && !finalAnswer.isEmpty()) {
                                emitter.send(SseEmitter.event().name("answer")
                                        .data("{\"seq\":98,\"data\":" + om.writeValueAsString(finalAnswer) + "}"));
                                answer.append(finalAnswer);
                            }
                            // done 的 error 字段 = 忠实度审查未通过(降级拒答,正常路径),非系统异常;
                            // 系统异常只会以 error 事件到达(errored 标记),不在 done 里误报
                            doneVerified[0] = d.path("verified").asBoolean(false);
                        }
                        emitter.send(SseEmitter.event().name(event).data(data));
                        if ("done".equals(event) && !errored[0]) {
                            if (doneHandled[0]) { emitter.complete(); return; }  // 兜底 done 忽略,防双落库
                            doneHandled[0] = true;
                            // I4:错误轮跳过——空消息落库/空回答缓存会污染历史与后续命中
                            Long cid = save(conversationId, question, answer.toString());
                            try {
                                emitter.send(SseEmitter.event().name("conversation")
                                        .data("{\"seq\":999,\"data\":{\"conversationId\":" + cid + "}}"));
                            } catch (Exception ignored) { }
                            observabilityService.saveSpan(cid, question,
                                System.currentTimeMillis() - t0, phaseMs, false);
                            if (answer.length() > 0 && doneVerified[0]) {   // 空答案(拒答/失败轮)不写缓存,防缓存投毒(P-Audit3)
                                try {
                                    chatCache.put(question, answer.toString(),
                                        sourcesJson[0] == null ? "[]" : sourcesJson[0], kbId, kbVersion);
                                } catch (Exception ignored) { }
                            }
                        }
                        if ("done".equals(event)) {
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
    @Transactional
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
