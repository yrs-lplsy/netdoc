package com.kbrag.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbrag.ai.EmbeddingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 语义缓存:hash chat:cache:{id} 存 question/embedding/answer/sourcesJson;
 * 索引 list chat:cache:recent(最近 recentMax 条问题 id)——只对候选集算余弦,避免全量遍历。
 */
@Service
public class ChatCacheService {
    private final StringRedisTemplate redis;
    private final EmbeddingService embeddingService;
    private final ObjectMapper om = new ObjectMapper();
    private final double threshold;
    private final int recentMax;

    public ChatCacheService(StringRedisTemplate redis, EmbeddingService embeddingService,
                            @Value("${app.cache.similarity-threshold:0.95}") double threshold,
                            @Value("${app.cache.recent-max:200}") int recentMax) {
        this.redis = redis;
        this.embeddingService = embeddingService;
        this.threshold = threshold;
        this.recentMax = recentMax;
    }

    public record CacheHit(String answer, String sourcesJson) {}

    /** 语义命中:embed 问题 → 与最近 recentMax 条算余弦 → 超过 threshold 取最高。 */
    /**
     * lookup() 是问答语义缓存查询方法，用来实现「相似问题命中缓存」：
     * 用户发来新问题，生成问题向量，和近期历史问答缓存做余弦相似度比对；
     * 如果存在相似度高于阈值的历史问题，则直接返回之前缓存好的回答与资料来源，省去再次调用知识库 RAG 检索，节省向量检索、LLM 调用开销。
     * @param question
     * @return
     */
    public Optional<CacheHit> lookup(String question) {
        float[] qv = embeddingService.embed(List.of(question)).get(0);
        List<String> ids = redis.opsForList().range("chat:cache:recent", 0, -1);
        if (ids == null || ids.isEmpty()) return Optional.empty();
        Map<String, float[]> candidates = new LinkedHashMap<>();
        Map<String, String> answers = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        for (String id : ids) {
            Map<Object, Object> entry = redis.opsForHash().entries("chat:cache:" + id);
            if (entry.isEmpty()) continue;
            candidates.put(id, parseEmbedding((String) entry.get("embedding")));
            answers.put(id, (String) entry.get("answer"));
            sources.put(id, (String) entry.get("sourcesJson"));
        }
        // 遍历每个 id:读 hash 条目 → 解析 embedding → 收集 candidates/answers/sources
        String best = CosineSimilarity.selectBest(candidates, qv, threshold);
        if (best == null) return Optional.empty();
        return Optional.of(new CacheHit(answers.get(best), sources.get(best)));
    }

    /**
     * 语义问答缓存写入方法，当一次问答走完 RAG 检索‑LLM 生成‑可信度校验完整流程之后，
     * 调用该函数把当前用户问题、回答、检索来源、问题向量存入 Redis；并且自动控制缓存最大条数，
     * 实现简易 LRU 最近优先缓存。它和刚刚讲解完的 lookup() 成对配套：lookup 查缓存、put 写入缓存。
     * @param question
     * @param answer
     * @param sourcesJson
     */
    public void put(String question, String answer, String sourcesJson) {
        float[] qv = embeddingService.embed(List.of(question)).get(0);
        String id = String.valueOf(System.nanoTime());
        Map<String, String> entry = Map.of(
                "question", question, "embedding", toJson(qv),
                "answer", answer, "sourcesJson", sourcesJson,
                "ts", String.valueOf(System.currentTimeMillis()));
        redis.opsForHash().putAll("chat:cache:" + id, entry);
        redis.opsForList().leftPush("chat:cache:recent", id);
        redis.opsForList().trim("chat:cache:recent", 0, recentMax - 1);   // 只留最近 recentMax 条
    }

    /**
     * 这是一对向量序列化‑反序列化私有工具函数，配合前面 Redis 语义缓存使用：
     * 1. toJson：把 float [] 嵌入向量转为 JSON 字符串，存入 Redis Hash 的 embedding 字段；
     * 2. parseEmbedding：读取 JSON 字符串，解析回 float [] 向量数组，用于余弦相似度计算；om 一般代表 Jackson ObjectMapper。
     * @param v
     * @return
     */
    private String toJson(float[] v) {
        try { return om.writeValueAsString(v); } catch (Exception e) { return "[]"; }
    }

    private float[] parseEmbedding(String json) {
        try {
            double[] d = om.readValue(json, double[].class);
            float[] f = new float[d.length];
            for (int i = 0; i < d.length; i++) f[i] = (float) d[i];
            return f;
        } catch (Exception e) { return new float[0]; }
    }
}
