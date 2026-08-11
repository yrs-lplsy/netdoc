package com.kbrag.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RoleKbAccessRepository extends JpaRepository<RoleKbAccess, Long> {
    List<RoleKbAccess> findByRoleId(Long roleId);
    List<RoleKbAccess> findByKbId(Long kbId);
    void deleteByKbId(Long kbId);
}
