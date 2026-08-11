package com.kbrag.chat;

import com.kbrag.retrieval.HybridRetriever;
import com.kbrag.retrieval.SearchResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ChatService {
    private final HybridRetriever retriever;
    private final OpenAiStreamingChatModel chatModel;
    private final MessageRepository messages;
    private final ConversationRepository conversations;

    public ChatService(HybridRetriever retriever, MessageRepository messages,
                       ConversationRepository conversations,
                       @Value("${app.llm.chat-base-url}") String baseUrl,
                       @Value("${app.llm.chat-model}") String model,
                       @Value("${app.llm.chat-api-key}") String apiKey) {
        this.retriever = retriever;
        this.messages = messages;
        this.conversations = conversations;
        this.chatModel = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl).apiKey(apiKey).modelName(model)
                .temperature(0.3)
                .build();
    }

    public void streamAnswer(String question, Long kbId, Long conversationId, SseEmitter emitter) {
        AtomicInteger seq = new AtomicInteger();
        try {
            // 1. 检索
            List<SearchResult> hits = retriever.search(question, kbId, 5);
            if (hits.isEmpty()) {
                emitter.send(event("answer", seq, "资料库中暂未找到相关信息。"));
                emitter.send(event("done", seq, null));
                emitter.complete();
                save(conversationId, question, "资料库中暂未找到相关信息。", "[]");
                return;
            }
            // 2. 组 Prompt（强约束 + 引用编号）
            StringBuilder sb = new StringBuilder("你是一个企业知识库问答助手。只能根据下面提供的资料回答，禁止编造。\n\n资料：\n");
            for (int i = 0; i < hits.size(); i++) {
                SearchResult h = hits.get(i);
                sb.append("[").append(i + 1).append("] ")
                  .append(h.headingPath()).append("：").append(h.content()).append("\n\n");
            }
            sb.append("要求：回答中标注引用编号（如 [1][2]）；资料中没有的内容直接说明“资料中未找到相关信息”；用中文回答。\n\n问题：").append(question);
            String prompt = sb.toString();
            // 3. 流式生成
            StringBuilder answer = new StringBuilder();
            chatModel.chat(prompt, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String token) {
                    answer.append(token);
                    try { emitter.send(event("answer", seq, token)); }
                    catch (Exception ignored) { }
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    try {
                        List<Map<String, String>> sources = hits.stream()
                                .map(h -> Map.of(
                                        "title", safe(h.headingPath()),
                                        "snippet", h.content().substring(0, Math.min(120, h.content().length()))))
                                .toList();
                        emitter.send(event("source", seq, sources));  // Jackson 序列化为 JSON 数组
                        emitter.send(event("done", seq, null));
                        emitter.complete();
                    } catch (Exception ignored) { }
                    save(conversationId, question, answer.toString(), "[]"); // Phase 3 落真实 sources
                }

                @Override
                public void onError(Throwable error) {
                    try { emitter.send(event("error", seq, error.getMessage())); }
                    catch (Exception ignored) { }
                    emitter.complete();
                }
            });
        } catch (Exception e) {
            try { emitter.send(event("error", seq, e.getMessage())); } catch (Exception ignored) { }
            emitter.complete();
        }
    }

    private final ObjectMapper om = new ObjectMapper();  // Spring Boot 自带 Jackson，所有事件统一走 JSON 序列化

    private SseEmitter.SseEventBuilder event(String type, AtomicInteger seq, Object data) {
        try {
            return SseEmitter.event().name(type)
                    .data("{\"seq\":" + seq.incrementAndGet() + ",\"data\":" + om.writeValueAsString(data) + "}");
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private String safe(String s) { return s == null ? "" : s.replace("\"", "'"); }

    private void save(Long conversationId, String user, String assistant, String sources) {
        if (conversationId == null) {
            Conversation c = new Conversation();
            c.setTitle(user.length() > 20 ? user.substring(0, 20) : user);
            conversations.save(c);
            conversationId = c.getId();
        }
        Message m1 = new Message();
        m1.setConversationId(conversationId);
        m1.setRole("user");
        m1.setContent(user);
        Message m2 = new Message();
        m2.setConversationId(conversationId);
        m2.setRole("assistant");
        m2.setContent(assistant);
        m2.setSourcesJson(sources);
        messages.save(m1); messages.save(m2);
    }

}
