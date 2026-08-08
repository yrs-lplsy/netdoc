package com.kbrag.document;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {
    List<DocumentChunk> findByDocId(Long docId);
    void deleteByDocId(Long docId);
}
