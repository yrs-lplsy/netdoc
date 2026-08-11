package com.kbrag.kg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class KgService {
    private final WebClient webClient;
    private final KgEntityRepository entities;
    private final KgRelationRepository relations;
    private final ObjectMapper om = new ObjectMapper();

    public KgService(WebClient.Builder builder,
                     KgEntityRepository entities,
                     KgRelationRepository relations,
                     @Value("${app.agent.base-url:http://localhost:9100}") String agentBaseUrl) {
        this.webClient = builder.baseUrl(agentBaseUrl).build();
        this.entities = entities;
        this.relations = relations;
    }

    /** 抽取并落库;任一步失败抛异常(调用方降级处理,不影响文档状态)。 */
    public void extractAndSave(Long kbId, Long docId, List<String> chunks) {
        Map<?, ?> resp = webClient.post().uri("/extract")
                .bodyValue(Map.of("kb_id", kbId, "doc_id", docId, "chunks", chunks))
                .retrieve().bodyToMono(Map.class).block(Duration.ofSeconds(120));
        if (resp == null) throw new IllegalStateException("KG extract returned null");

        entities.deleteByKbIdAndDocId(kbId, docId);   // 先清旧实体(重建语义)

        Map<String, KgEntity> byName = new HashMap<>();
        JsonNode entNodes = om.valueToTree(resp.get("entities"));
        for (JsonNode n : entNodes) {
            KgEntity e = new KgEntity();
            e.setKbId(kbId); e.setDocId(docId);
            e.setName(n.path("name").asText());
            e.setType(n.path("type").asText());
            e.setNormalizedName(n.has("normalized_name") ? n.path("normalized_name").asText() : e.getName());
            e.setConfidence(n.path("confidence").asDouble());
            entities.save(e);
            byName.putIfAbsent(e.getName(), e);   // 同一文档内同名实体复用同一 id
        }

        JsonNode relNodes = om.valueToTree(resp.get("relations"));
        for (JsonNode n : relNodes) {
            KgEntity src = byName.get(n.path("source").asText());
            KgEntity dst = byName.get(n.path("target").asText());
            if (src == null || dst == null) continue;   // 指向未抽到实体的关系丢弃
            KgRelation r = new KgRelation();
            r.setKbId(kbId);
            r.setSourceId(src.getId());
            r.setTargetId(dst.getId());
            r.setRelation(n.path("relation").asText());
            r.setConfidence(n.path("confidence").asDouble());
            relations.save(r);
        }
    }
}
