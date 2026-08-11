package com.kbrag.kg;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface KgRelationRepository extends JpaRepository<KgRelation, Long> {
    List<KgRelation> findByKbIdAndSourceIdIn(Long kbId, List<Long> sourceIds);
}
