package com.kbrag.obs;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RagSpanRepository extends JpaRepository<RagSpan, Long> {
    List<RagSpan> findTop100ByOrderByIdDesc();   // 最近 100 轮(聚合指标用)
}
