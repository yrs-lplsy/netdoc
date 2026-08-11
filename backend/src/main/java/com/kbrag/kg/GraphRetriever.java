package com.kbrag.kg;

import com.kbrag.ai.Tokenizer;
import com.pgvector.PGvector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 图谱检索(查询时零 LLM,毫秒级):
 * ① 实体链接:jieba 分词词匹配 kg_entity.name(词典即实体表,按 kb 过滤)
 * ② 一跳邻居:kg_relation 取关联实体
 * ③ 关联文档:实体来源 doc → 该文档的 chunk 候选(图谱路)
 */
@Service
public class GraphRetriever {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private NamedParameterJdbcTemplate namedJdbc;
    @Autowired private Tokenizer tokenizer;

    public List<Long> linkEntities(String query, Long kbId) {
        String seg = tokenizer.segment(query);
        List<String> words = java.util.Arrays.stream(seg.split(" "))
                .filter(w -> w.length() >= 2)   // 单字噪音过滤
                .toList();
        if (words.isEmpty()) return List.of();
        // 实体名精确匹配分词词(实体名通常为专有名词,分词可命中)
        return jdbc.query(
                "SELECT id FROM kg_entity WHERE kb_id = ? AND LOWER(name) IN (" +
                String.join(",", words.stream().map(w -> "?").toList()) + ")",
                (rs, i) -> rs.getLong(1), args(kbId, words.stream().map(String::toLowerCase).toList()));
    }

    /** 命中实体的邻居实体 id(一跳)。 */
    public List<Long> neighborEntities(Long kbId, List<Long> entityIds) {
        if (entityIds.isEmpty()) return List.of();
        return namedJdbc.query(
                "SELECT target_id FROM kg_relation WHERE kb_id = :kbId AND source_id IN (:ids)",
                Map.of("kbId", kbId, "ids", entityIds),
                (rs, i) -> rs.getLong(1));
    }

    /** 实体来源文档的 chunk id(图谱路候选,按 kb 过滤)。 */
    public List<Long> docChunks(Long kbId, List<Long> entityIds) {
        if (entityIds.isEmpty()) return List.of();
        return namedJdbc.query(
                "SELECT c.id FROM document_chunk c " +
                "JOIN kg_entity e ON e.doc_id = c.doc_id AND e.kb_id = c.kb_id " +
                "WHERE c.kb_id = :kbId AND e.id IN (:ids) " +
                "GROUP BY c.id LIMIT :limit",
                Map.of("kbId", kbId, "ids", entityIds, "limit", kgTopK),
                (rs, i) -> rs.getLong(1));
    }

    @Value("${app.retrieval.kg-top-k:20}")
    private int kgTopK;

    private Object[] args(Long kbId, List<String> words) {
        Object[] out = new Object[words.size() + 1];
        out[0] = kbId;
        System.arraycopy(words.toArray(), 0, out, 1, words.size());
        return out;
    }
}
