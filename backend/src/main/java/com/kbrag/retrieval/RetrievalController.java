package com.kbrag.retrieval;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/retrieve")
public class RetrievalController {
    @Autowired HybridRetriever retriever;

    @PostMapping
    public List<SearchResult> retrieve(@RequestBody RetrieveRequest req, @RequestParam Long kbId) {
        return retriever.search(req.query(), kbId, req.topK() == 0 ? 5 : req.topK());
    }

    public record RetrieveRequest(String query, int topK) {}
}