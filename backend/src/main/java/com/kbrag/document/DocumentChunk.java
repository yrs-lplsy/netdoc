package com.kbrag.document;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Data
@Entity
@Table(name = "document_chunk")
public class DocumentChunk {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long docId;
    private Long kbId;
    private Integer chunkIndex;
    @Column(columnDefinition = "text")
    private String content;
    private Integer tokenCount;
    private String headingPath;
    // Hibernate 官方 vector 模块映射(BGE-M3 维度 1024);不要用 com.pgvector.PGvector 做实体字段,否则 Hibernate 会按 bytea 绑定导致插入失败
    @Column(columnDefinition = "vector(1024)")
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = 1024)
    private float[] embedding;
    @Column(columnDefinition = "text")
    private String segmentedText;
}
