package com.kbrag.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 首次启动初始化:权限点、三个角色、admin/agent-service 账号。
 * 幂等:已存在则跳过。
 */
@Component
public class DataInitializer implements CommandLineRunner {
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (users.count() > 0) return;
        Permission pCreate = perm("KB_CREATE"), pWrite = perm("KB_WRITE"), pRead = perm("KB_READ"),
                pChat = perm("CHAT"), pKg = perm("KG_VIEW"), pStats = perm("STATS_VIEW");
        Role admin = role("ADMIN", Set.of(pCreate, pWrite, pRead, pChat, pKg, pStats));
        Role user = role("USER", Set.of(pRead, pChat, pKg));
        Role agent = role("AGENT_SERVICE", Set.of(pRead, pStats));
        user( "admin", admin, "admin123");          // 演示账号,生产必改
        user("agent-service", agent, "agent-secret-123");
    }

    private Permission perm(String code) { return null; } // TODO 由用户补全(见下)
    private Role role(String name, Set<Permission> ps) { return null; }
    private void user(String name, Role r, String pwd) {}
}
