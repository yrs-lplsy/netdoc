package com.kbrag.auth;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 数据权限:角色对知识库的访问级别 READ/WRITE/ADMIN。
 * 功能权限(permission)+ 数据权限(本表)= RBAC0 两层。
 */
@Data
@Entity
@Table(name = "role_kb_access",
       uniqueConstraints = @UniqueConstraint(name = "uk_role_kb", columnNames = {"role_id", "kb_id"}))
public class RoleKbAccess {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long roleId;
    private Long kbId;
    private String access;   // READ / WRITE / ADMIN
}
