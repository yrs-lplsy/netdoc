package com.kbrag.document;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 文档实体。字段全 private + Lombok @Data 生成 getter/setter(与 chat 包统一风格)。
 */
@Data
@Entity
@Table(name = "document")
public class Document {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long kbId;
    private String title;
    private String category;
    private Long uploader;
    @Enumerated(EnumType.STRING)
    private DocumentStatus status = DocumentStatus.PROCESSING;
    private Integer version = 1;
    @Column(columnDefinition = "text")   // 错误信息可能很长,不能用默认 varchar(255)
    private String errorMessage;
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();   // 每次保存自动更新(在 Service 更新状态时 set)
}
