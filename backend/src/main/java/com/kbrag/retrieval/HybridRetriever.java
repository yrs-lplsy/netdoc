package com.kbrag.retrieval;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.kbrag.ai.EmbeddingService;
import com.kbrag.ai.Tokenizer;
import com.pgvector.PGvector;

@Service
public class HybridRetriever {
    
    private final JdbcTemplate jdbc;
    private final EmbeddingService embeddingService;
    private final Tokenizer tokenizer;
    private final int denseTopK, sparseTopK, rrfK, finalTopK;
    private final NamedParameterJdbcTemplate namedJdbc;

    public HybridRetriever(JdbcTemplate jdbc,
                           NamedParameterJdbcTemplate namedJdbc,
                           EmbeddingService embeddingService,
                           Tokenizer tokenizer,
                           @Value("${app.retrieval.dense-top-k}") int denseTopK,
                           @Value("${app.retrieval.sparse-top-k}") int sparseTopK,
                           @Value("${app.retrieval.rrf-k}") int rrfK,
                           @Value("${app.retrieval.final-top-k}") int finalTopK) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.embeddingService = embeddingService;
        this.tokenizer = tokenizer;
        this.denseTopK = denseTopK;
        this.sparseTopK = sparseTopK;
        this.rrfK = rrfK;
        this.finalTopK = finalTopK;
    }

    public List<SearchResult> search(String query, Long kbId, int topK){
        float[] vec = embeddingService.embed(List.of(query)).get(0);
        // 稠密路：余弦距离
        List<Long> denseIds = jdbc.query(
            "SELECT id FROM document_chunk WHERE kb_id = ? " +
            "ORDER BY embedding <=> CAST(? AS vector) LIMIT ?",
            (rs, i) -> rs.getLong(1), kbId, new PGvector(vec), denseTopK);
        // 稀疏路：jieba 分词后 tsvector 检索，ts_rank 排序
        String seg = tokenizer.segment(query);
        List<Long> sparseIds = jdbc.query(
            "SELECT id FROM document_chunk WHERE kb_id = ? AND search_text @@ plainto_tsquery('simple', ?) " +
            "ORDER BY ts_rank(search_text, plainto_tsquery('simple', ?)) DESC LIMIT ?",
            (rs, i) -> rs.getLong(1), kbId, seg, seg, sparseTopK);
        // RRF 融合
        List<Long> fused = RrfFusion.fuse(denseIds, sparseIds, rrfK, Math.min(topK, finalTopK));
        if (fused.isEmpty()) return List.of();
        // ANY (?) 不保证返回顺序：回库后按 fused 顺序重排——TopN 的 Prompt 顺序依赖 RRF 排序
        List<SearchResult> rows = namedJdbc.query(
            "SELECT id, doc_id, content, heading_path FROM document_chunk WHERE id IN (:ids)",
            Map.of("ids", fused),
            (rs, i) -> new SearchResult(
                    rs.getLong("id"), rs.getLong("doc_id"),
                    rs.getString("content"), rs.getString("heading_path")));


        Map<Long, SearchResult> byId = rows.stream()
                .collect(Collectors.toMap(SearchResult::chunkId, r -> r));
        return fused.stream().map(byId::get).filter(Objects::nonNull).toList();
    }
    
}
