package com.kbrag.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

/**
 * 管理端点(ADMIN):建用户、角色授权、知识库授权。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private RoleKbAccessRepository accesses;
    @Autowired private PasswordEncoder passwordEncoder;

    public record CreateUserRequest(String username, String password, String role) {}

    @PostMapping("/users")
    public User createUser(@RequestBody CreateUserRequest req) {
        Role r = roles.findByName(req.role()).orElseThrow();
        User u = new User();
        u.setUsername(req.username());
        u.setPasswordHash(passwordEncoder.encode(req.password()));
        u.setRoles(Set.of(r));
        return users.save(u);
    }

    public record GrantRequest(String role, String access) {}

    @PostMapping("/kbs/{kbId}/grant")
    public RoleKbAccess grant(@PathVariable Long kbId, @RequestBody GrantRequest req) {
        Role r = roles.findByName(req.role()).orElseThrow();
        RoleKbAccess a = new RoleKbAccess();
        a.setRoleId(r.getId());
        a.setKbId(kbId);
        a.setAccess(req.access());
        return accesses.save(a);   // 唯一约束防重复;重复抛异常由全局处理
    }
}
