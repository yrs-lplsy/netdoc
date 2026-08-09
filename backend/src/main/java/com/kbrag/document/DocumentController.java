package com.kbrag.document;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    @Autowired private DocumentService documentService;
    @Autowired private DocumentRepository documents;
    @Autowired private DocumentChunkRepository chunks;

    @PostMapping
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(documentService.upload(file));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping
    public List<Document> list(@RequestParam(required = false) String status) {
        return status == null ? documents.findAll() : documents.findAll().stream()
                .filter(d -> d.getStatus().name().equalsIgnoreCase(status)).toList();
    }

    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable Long id) {
        chunks.deleteByDocId(id);   // 注入 DocumentChunkRepository
        documents.deleteById(id);
    }
}
