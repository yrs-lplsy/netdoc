package com.kbrag.auth;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "permission")
public class Permission {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(unique = true)
    private String code;   // KB_CREATE / KB_WRITE / KB_READ / CHAT / KG_VIEW / STATS_VIEW
}
