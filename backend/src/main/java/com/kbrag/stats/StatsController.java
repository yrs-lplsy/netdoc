package com.kbrag.stats;

import com.kbrag.document.DocumentChunkRepository;
import com.kbrag.document.DocumentRepository;
import com.kbrag.obs.RagSpan;
import com.kbrag.obs.RagSpanRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 知识库统计(spec §7 GET /api/stats):文档/分块数、平均检索耗时、缓存命中率。
 * 功能权限:STATS_VIEW(ADMIN/AGENT_SERVICE 持有)。
 */
@RestController
public class StatsController {
    @Autowired private DocumentRepository documents;
    @Autowired private DocumentChunkRepository chunks;
    @Autowired private RagSpanRepository spans;

    @GetMapping("/api/stats")
    @PreAuthorize("hasAuthority('STATS_VIEW')")
    public Map<String, Object> stats() {
        List<RagSpan> recent = spans.findTop100ByOrderByIdDesc();
        double avgTools = recent.stream().mapToInt(RagSpan::getToolsMs)
                .filter(v -> v > 0).average().orElse(0);
        long hitCount = recent.stream().filter(RagSpan::getCacheHit).count();
        double hitRate = recent.isEmpty() ? 0 : hitCount * 1.0 / recent.size();
        return Map.of(
                "docCount", documents.count(),
                "chunkCount", chunks.count(),
                "avgRetrievalMs", Math.round(avgTools),      // 近 100 轮检索(工具阶段)均值
                "cacheHitRate", Math.round(hitRate * 100) / 100.0);
    }
}
