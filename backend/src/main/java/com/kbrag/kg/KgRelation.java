package com.kbrag.kg;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "kg_relation",
       indexes = @Index(name = "idx_kg_rel_src", columnList = "kb_id, source_id"))
public class KgRelation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long kbId;
    private Long sourceId;
    private Long targetId;
    private String relation;
    private Double confidence;
    private LocalDateTime createdAt = LocalDateTime.now();
}