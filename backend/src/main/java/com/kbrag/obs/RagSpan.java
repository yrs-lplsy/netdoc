package com.kbrag.obs;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 每轮对话的可观测 span(spec §4.1 observability)。
 */
@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "rag_span")
public class RagSpan {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long conversationId;
    @Column(columnDefinition = "text")
    private String question;
    private Integer gatewayMs;    // Java 转发全程耗时
    private Integer rewriteMs;    // 以下来自 Python phase 事件
    private Integer routerMs;
    private Integer toolsMs;      // 检索阶段(≈检索耗时)
    private Integer generateMs;   // LLM 生成
    private Integer verifyMs;
    private Boolean cacheHit;     // 是否语义缓存命中
    private LocalDateTime createdAt = LocalDateTime.now();
}
