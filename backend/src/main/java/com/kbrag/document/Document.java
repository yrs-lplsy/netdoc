package com.kbrag.document;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "document")
public class Document {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String title;
    public String category;
    public Long uploader;
    @Enumerated(EnumType.STRING)
    public DocumentStatus status = DocumentStatus.PROCESSING;
    public Integer version = 1;
    @Column(columnDefinition = "text")   // 错误信息可能很长,不能用默认 varchar(255)
    public String errorMessage;
    public LocalDateTime createdAt = LocalDateTime.now();
}
