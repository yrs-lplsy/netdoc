package com.kbrag.chat;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Lombok：@Getter/@Setter 生成访问器，@NoArgsConstructor 是 JPA 要求的无参构造器。
 */
@Entity @Table(name = "conversation")
@NoArgsConstructor @AllArgsConstructor
@Data
public class Conversation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long userId;
    private String title;
    private LocalDateTime createdAt = LocalDateTime.now();
}
