package com.kbrag.kg;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.kbrag.auth.KbAccess;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/kg")
public class KgController {
    @Autowired private KgEntityRepository entities;
    @Autowired private KgRelationRepository relations;

    @GetMapping("/graph") @KbAccess("READ")
    public Map<String, Object> graph(@RequestParam Long kbId) {
        List<KgEntity> es = entities.findAll().stream().filter(e -> e.getKbId().equals(kbId)).toList();
        List<KgRelation> rs = relations.findAll().stream().filter(r -> r.getKbId().equals(kbId)).toList();
        return Map.of(
                "nodes", es.stream().map(e -> Map.of("id", e.getId(), "name", e.getName(), "type", e.getType())).toList(),
                "edges", rs.stream().map(r -> Map.of(
                        "source", r.getSourceId(), "target", r.getTargetId(), "relation", r.getRelation())).toList());
    }

    @GetMapping("/entities") @KbAccess("READ")
    public List<KgEntity> entities(@RequestParam Long kbId, @RequestParam(required = false) String q) {
        return entities.findAll().stream()
                .filter(e -> e.getKbId().equals(kbId))
                .filter(e -> q == null || e.getName().toLowerCase().contains(q.toLowerCase()))
                .toList();
    }
}
