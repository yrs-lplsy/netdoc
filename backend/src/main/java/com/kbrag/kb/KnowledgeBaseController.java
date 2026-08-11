package com.kbrag.kb;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/kbs")
public class KnowledgeBaseController {
    @Autowired private KnowledgeBaseRepository kbs;

    @PostMapping
    public KnowledgeBase create(@RequestBody KnowledgeBase kb) {
        kb.setId(null);
        return kbs.save(kb);
    }

    @GetMapping
    public List<KnowledgeBase> list() {
        return kbs.findAll();   // Task 3 改为按数据权限过滤
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        kbs.deleteById(id);   // Task 4 连带清理文档/分块/图谱
    }
}
