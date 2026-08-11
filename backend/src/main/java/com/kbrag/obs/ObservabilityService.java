package com.kbrag.obs;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ObservabilityService {
    @Autowired private RagSpanRepository spans;

    public void saveSpan(Long conversationId, String question, long gatewayMs,
                         Map<String, Integer> phaseMs, boolean cacheHit) {
        RagSpan s = new RagSpan();
        s.setConversationId(conversationId);
        s.setQuestion(question);
        s.setGatewayMs((int) gatewayMs);
        s.setRewriteMs(phaseMs.getOrDefault("rewrite", 0));
        s.setRouterMs(phaseMs.getOrDefault("router", 0));
        s.setToolsMs(phaseMs.getOrDefault("tools", 0));
        s.setGenerateMs(phaseMs.getOrDefault("generate", 0));
        s.setVerifyMs(phaseMs.getOrDefault("verify", 0));
        s.setCacheHit(cacheHit);
        spans.save(s);
    }
}
