package com.kbrag.chat;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lombok：@Getter/@Setter 生成访问器，@NoArgsConstructor 是 JPA 要求的无参构造器。
 */
@Entity @Table(name = "message")
@Data @NoArgsConstructor @AllArgsConstructor
public class Message {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long conversationId;
    private String role;             // user / assistant
    @Column(columnDefinition = "text") private String content;
    @Column(columnDefinition = "text") private String sourcesJson;  // assistant 回答的来源
    private Short feedback;          // 0 无反馈 / 1 赞 / -1 踩（Phase 3 用）
    private LocalDateTime createdAt = LocalDateTime.now();
}