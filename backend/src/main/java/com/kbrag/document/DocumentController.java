package com.kbrag.document;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    @Autowired DocumentService documentService;
    @Autowired DocumentRepository documents;
    @Autowired DocumentChunkRepository chunks;

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
                .filter(d -> d.status.name().equalsIgnoreCase(status)).toList();
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        chunks.deleteByDocId(id);   // 注入 DocumentChunkRepository
        documents.deleteById(id);
    }
}
