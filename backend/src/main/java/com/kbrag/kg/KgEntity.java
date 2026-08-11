package com.kbrag.kg;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "kg_entity",
       indexes = @Index(name = "idx_kg_entity_name", columnList = "kb_id, name"))
public class KgEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long kbId;
    private String name;
    private String type;
    private String normalizedName;
    private Long docId;              // 实体来源文档
    private Double confidence;
    @Column(columnDefinition = "vector(1024)")   // 实体向量(可选:实体语义检索/去重)
    private float[] embedding;       // 本任务先不填,Task 5 可视化不需要;留升级位
    private LocalDateTime createdAt = LocalDateTime.now();
}
