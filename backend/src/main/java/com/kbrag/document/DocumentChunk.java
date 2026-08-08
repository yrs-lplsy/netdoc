package com.kbrag.document;

import jakarta.persistence.*;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "document_chunk")
public class DocumentChunk {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long docId;
    public Integer chunkIndex;
    @Column(columnDefinition = "text")
    public String content;
    public Integer tokenCount;
    public String headingPath;
    // Hibernate 官方 vector 模块映射(BGE-M3 维度 1024);不要用 com.pgvector.PGvector 做实体字段,否则 Hibernate 会按 bytea 绑定导致插入失败
    @Column(columnDefinition = "vector(1024)")
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = 1024)
    public float[] embedding;
    @Column(columnDefinition = "text")
    public String segmentedText;
}