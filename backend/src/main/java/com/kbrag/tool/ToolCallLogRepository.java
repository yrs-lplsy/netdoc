package com.kbrag.tool;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ToolCallLogRepository extends JpaRepository<ToolCallLog, Long> {
    Optional<ToolCallLog> findFirstByIdempotentKey(String idempotentKey);   // 幂等校验
}
