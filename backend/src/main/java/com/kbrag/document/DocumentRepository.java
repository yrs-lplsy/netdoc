package com.kbrag.document;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DocumentRepository extends JpaRepository<Document, Long> {
    // @Query("select coalesce(max(d.updatedAt), 0L) from Document d where d.kbId = ?1")
    // Long kbVersion(Long kbId);
    @Query("select max(d.updatedAt) from Document d where d.kbId = ?1")
    Optional<LocalDateTime> maxUpdatedAt(Long kbId);
}
