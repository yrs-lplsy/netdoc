package com.kbrag.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AccessService {
    @Autowired private RoleKbAccessRepository accesses;

    private static final Set<String> LEVELS = Set.of("READ", "WRITE", "ADMIN");
    private static final Set<String> ADMIN_ROLES = Set.of("ADMIN");

    /** 判定用户对某库的访问级别是否满足要求(level 需在 READ/WRITE/ADMIN)。 */
    public boolean canAccess(User u, Long kbId, String level) {
        if (u == null || !LEVELS.contains(level)) return false;
        if (u.getRoles().stream().anyMatch(r -> ADMIN_ROLES.contains(r.getName()))) return true; // ADMIN 全见
        Set<String> granted = u.getRoles().stream()
                .flatMap(r -> accesses.findByRoleId(r.getId()).stream())
                .filter(a -> a.getKbId().equals(kbId))
                .map(RoleKbAccess::getAccess)
                .collect(Collectors.toSet());
        // 级别满足:ADMIN > WRITE > READ
        return pureLevel(level, granted);
    }

    /** 用户可访问的知识库列表(me 接口与库列表过滤用)。 */
    public List<Long> accessibleKbIds(User u) {
        if (u.getRoles().stream().anyMatch(r -> ADMIN_ROLES.contains(r.getName()))) {
            return null;   // null = 全部
        }
        return u.getRoles().stream()
                .flatMap(r -> accesses.findByRoleId(r.getId()).stream())
                .map(RoleKbAccess::getKbId).distinct().toList();
    }

    /** 校验失败抛 403(切面调用)。 */
    public void requireAccess(User u, Long kbId, String level) {
        if (!canAccess(u, kbId, level)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限访问该知识库(kbId=" + kbId + ")");
        }
    }

    // AccessService 加一个 static 方法,canAccess 改为调用它:
    /** 纯逻辑:required 级别是否被 granted 集合满足(高覆盖低:ADMIN > WRITE > READ)。 */
    static boolean pureLevel(String required, Set<String> granted) {
        return switch (required) {
            case "READ" -> granted.contains("READ") || granted.contains("WRITE") || granted.contains("ADMIN");
            case "WRITE" -> granted.contains("WRITE") || granted.contains("ADMIN");
            case "ADMIN" -> granted.contains("ADMIN");
            default -> false;
        };
    }
}
