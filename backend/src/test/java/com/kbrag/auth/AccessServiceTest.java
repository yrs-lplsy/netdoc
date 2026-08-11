package com.kbrag.auth;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class AccessServiceTest {
    // 用内存版:直接构造 User/Role,monkeypatch 不了 Repository → 测 canAccess 的级别映射需注入 fake
    // 简便做法:把级别判定抽成静态方法 pureLevel(String required, Set<String> granted):
    @Test
    void level_satisfaction_mapping() {
        assertTrue(AccessService.pureLevel("READ", Set.of("READ")));
        assertTrue(AccessService.pureLevel("READ", Set.of("WRITE")));   // 高覆盖低
        assertTrue(AccessService.pureLevel("WRITE", Set.of("ADMIN")));
        assertFalse(AccessService.pureLevel("WRITE", Set.of("READ")));  // 低不覆盖高
        assertFalse(AccessService.pureLevel("ADMIN", Set.of("WRITE")));
    }
}
