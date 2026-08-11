package com.kbrag.kg;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface KgEntityRepository extends JpaRepository<KgEntity, Long> {
    List<KgEntity> findByKbIdAndDocId(Long kbId, Long docId);
    void deleteByKbIdAndDocId(Long kbId, Long docId);
}