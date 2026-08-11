package com.kbrag.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbrag.document.Document;
import com.kbrag.document.DocumentChunk;
import com.kbrag.document.DocumentChunkRepository;
import com.kbrag.document.DocumentRepository;
import com.kbrag.retrieval.HybridRetriever;
import com.kbrag.retrieval.SearchResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 工具端点(spec §4.1 tool-service):供 Python Agent 服务反向调用。
 * 每次调用全量落库 tool_call_log(安全审计)+ 幂等键防重复执行(spec §9)。
 */
@RestController
@RequestMapping("/api/agent/tools")
public class ToolController {
    @Autowired private HybridRetriever retriever;
    @Autowired private DocumentRepository documents;
    @Autowired private DocumentChunkRepository chunks;
    @Autowired private ToolCallLogRepository logs;
    private final ObjectMapper om = new ObjectMapper();

    /**
     * 向量相似度检索
     * @param req
     * @return
     */
    @PostMapping("/search")
    public Object search(@RequestBody ToolRequest req) {
        Optional<Object> dup = idempotent(req);
        if (dup.isPresent()) return dup.get();
        long t0 = System.currentTimeMillis();
        try {
            List<SearchResult> hits = retriever.search(req.query(), req.kbId(), req.topK() == 0 ? 5 : req.topK());
            log("search_kb", req, hits.size() + " hits", t0, true, req);
            return hits;
        } catch (Exception e) {
            log("search_kb", req, e.getMessage(), t0, false, req);
            throw e;
        }
    }

    /**
     * 获取单篇文档完整详情
     * @param req
     * @return
     */
    @PostMapping("/get-doc-detail")
    public Object getDocDetail(@RequestBody ToolRequest req) {
        Optional<Object> dup = idempotent(req);
        if (dup.isPresent()) return dup.get();
        long t0 = System.currentTimeMillis();
        Long docId = req.docId();
        try {
            Document doc = documents.findById(docId).orElseThrow();
            List<DocumentChunk> chunkList = chunks.findByDocId(docId);
            Map<String, Object> result = Map.of(
                    "id", doc.getId(), "title", doc.getTitle(), "status", doc.getStatus(),
                    "chunks", chunkList.stream().map(c -> Map.of(
                            "id", c.getId(), "content", c.getContent(), "headingPath", c.getHeadingPath())).toList());
            log("get_doc_detail", Map.of("docId", docId), chunkList.size() + " chunks", t0, true, req);
            return result;
        } catch (Exception e) {
            log("get_doc_detail", Map.of("docId", docId), e.getMessage(), t0, false, req);
            throw e;
        }
    }

    /**
     * 让 AI 智能体查询知识库文档总数、文本分片总数，用来自主调整 RAG 策略。
     * @param req
     * @return
     */
    @PostMapping("/get-stats")
    public Object getStats(@RequestBody ToolRequest req) {
        Optional<Object> dup = idempotent(req);
        if (dup.isPresent()) return dup.get();
        long t0 = System.currentTimeMillis();
        try {
            long docCount = documents.count();
            long chunkCount = chunks.count();
            log("get_stats", Map.of(), docCount + " docs / " + chunkCount + " chunks", t0, true, req);
            return Map.of("docCount", docCount, "chunkCount", chunkCount);
        } catch (Exception e) {
            log("get_stats", Map.of(), e.getMessage(), t0, false, req);
            throw e;
        }
    }

    /** 幂等校验:conversationId+agentStepId 已执行过 → 直接返回上次结果,不重复执行。 */
    private Optional<Object> idempotent(ToolRequest req) {
        if (req.conversationId() == null || req.agentStepId() == null) return Optional.empty();
        String key = req.conversationId() + ":" + req.agentStepId();
        Optional<ToolCallLog> prev = logs.findFirstByIdempotentKey(key);
        if (prev.isPresent()) {
            return Optional.of(Map.of("idempotent", true, "output", prev.get().getOutputSummary()));
        }
        return Optional.empty();
    }

    private void log(String tool, Object input, String output, long t0, boolean ok, ToolRequest req) {
        ToolCallLog l = new ToolCallLog();
        l.setToolName(tool);
        if (req.conversationId() != null) l.setConversationId(req.conversationId());
        if (req.conversationId() != null && req.agentStepId() != null) {
            l.setIdempotentKey(req.conversationId() + ":" + req.agentStepId());
        }
        l.setInputJson(write(input));
        l.setOutputSummary(output);
        l.setLatencyMs((int) (System.currentTimeMillis() - t0));
        l.setOk(ok);
        logs.save(l);
    }

    private String write(Object o) {
        try { return om.writeValueAsString(o); }
        catch (Exception e) { return "{}"; }
    }

    public record ToolRequest(String query, Integer topK, Long docId, Long conversationId, Integer agentStepId, Long kbId) {}
}
