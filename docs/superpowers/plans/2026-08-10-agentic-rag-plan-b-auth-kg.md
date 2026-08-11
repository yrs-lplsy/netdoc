# Plan B:认证权限 + 多知识库 + 知识图谱 + 缓存一致性实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> 协作协议:用户自己写代码,助手教学/验收/答疑。对应新 spec《2026-08-10-agentic-rag-enterprise-design.md》第 4 周里程碑(8/23 认证+多库+KG 可演示)。前置:Plan A(2026-08-09-agentic-rag-phase2-python-agent.md)已交付(Agent/工具端点/SSE/限流)。

**Goal:** 8/23 前交付:RBAC0 认证(JWT + 功能权限 + 知识库数据权限)+ 多知识库隔离 + 知识图谱(构建/三路融合检索/可视化)+ 语义缓存一致性。

**Architecture:** 在 Plan A 基础上增量:① Spring Security 6 + JWT 无状态认证;② document/chunk/conversation 挂 kb_id 实现多库,权限切面统一校验(数据访问不绕过权限层);③ KG 构建在 Python(入库时批量 LLM 抽取三元组),存储/检索/可视化在 Java;④ 检索升级为关键词+向量+图谱三路 RRF;⑤ 语义缓存加 kb 命名空间/版本戳/TTL 三件套。

**Tech Stack:** Spring Security 6、jjwt 0.12、Spring AOP、Spring WebClient(已有)、jieba(已有)、LangChain4j(已有);Python:FastAPI(已有)+ chat_model(已有)。

## Global Constraints

- kbId 一律走 **query 参数**(如 `POST /api/chat?kbId=1`)——权限切面从 HttpServletRequest 统一取,不解析 body(一致且简单)
- **DDL 托管**:所有新表/新列写入 `backend/src/main/resources/schema.sql`(Hibernate `ddl-auto=none`);各 Task 的 Files 隐含 Modify schema.sql,新表用 `CREATE TABLE IF NOT EXISTS`,已有表加列用 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` + `CREATE INDEX IF NOT EXISTS`(幂等,兼容旧库)
- 权限模型:功能权限(permission)+ 数据权限(role_kb_access READ/WRITE/ADMIN)两层(RBAC0)
- KG:构建在 Python(抽取/归一/置信度过滤),落库/检索/可视化在 Java;查询路径零 LLM
- 三路召回:关键词/向量/图谱各 Top20 → RRF(k=60)→ Top10 → Top5 进 Prompt;图谱上下文段随行
- 图谱抽取失败不影响文档 READY(文档状态独立,图谱可重建)
- 语义缓存三件套:主动失效(kb 前缀删除)+ 版本戳比对 + TTL 24h
- 工具幂等/端口 9100/双线并行等约束沿用 Plan A

---

| 任务 | 内容 | 验收 |
|---|---|---|
| Task 1 | RBAC0 认证基础(JWT 登录 + Security 链 + 初始化) | 登录拿 token,未带 token 401 |
| Task 2 | 多知识库改造(kb 表 + 实体挂 kb_id + 接口带 kbId) | 双库建库/传文档/检索互不串 |
| Task 3 | 数据权限(role_kb_access + AOP 切面 + 管理端点) | 无权限访问返回 403 |
| Task 4 | KG 构建(Python /extract + Java 落库) | 上传文档后 kg_entity/kg_relation 有数据 |
| Task 5 | 图谱检索(三路融合)+ 可视化(/api/kg/*) | 图谱增强问题命中提升,前端出图 |
| Task 6 | 语义缓存一致性改造 | 文档更新后旧缓存自动失效 |

---

### Task 1: RBAC0 认证基础(JWT 登录 + Security 链)

**Files:**
- Modify: `backend/pom.xml`(security + jjwt)
- Create: `backend/src/main/java/com/kbrag/auth/User.java`、`Role.java`、`Permission.java`(实体)
- Create: `backend/src/main/java/com/kbrag/auth/UserRepository.java`、`RoleRepository.java`
- Create: `backend/src/main/java/com/kbrag/auth/JwtUtil.java`
- Create: `backend/src/main/java/com/kbrag/auth/JwtAuthenticationFilter.java`
- Create: `backend/src/main/java/com/kbrag/auth/SecurityConfig.java`
- Create: `backend/src/main/java/com/kbrag/auth/AuthController.java`
- Create: `backend/src/main/java/com/kbrag/auth/DataInitializer.java`(初始化 admin/角色/权限)
- Modify: `backend/src/main/resources/application.yml`(app.jwt.secret)

**Interfaces:**
- Consumes: 无(独立)
- Produces: `POST /api/auth/login {username,password} → {token,username,roles}`;`GET /api/auth/me`;SecurityContext principal = User;权限注解与切面在 Task 3

- [ ] **Step 1: pom.xml 加依赖**

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
  <groupId>io.jsonwebtoken</groupId>
  <artifactId>jjwt-api</artifactId>
  <version>0.12.6</version>
</dependency>
<dependency>
  <groupId>io.jsonwebtoken</groupId>
  <artifactId>jjwt-impl</artifactId>
  <version>0.12.6</version>
  <scope>runtime</scope>
</dependency>
<dependency>
  <groupId>io.jsonwebtoken</groupId>
  <artifactId>jjwt-jackson</artifactId>
  <version>0.12.6</version>
  <scope>runtime</scope>
</dependency>
```

- [ ] **Step 2: 实体与仓库(RBAC0:user ↔ user_role ↔ role ↔ role_permission ↔ permission)**

```java
package com.kbrag.auth;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Data
@Entity
@Table(name = "usr")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(unique = true)
    private String username;
    private String passwordHash;   // BCrypt
    private Boolean enabled = true;
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();
    private LocalDateTime createdAt = LocalDateTime.now();
}
```

```java
package com.kbrag.auth;

import jakarta.persistence.*;
import lombok.Data;
import java.util.HashSet;
import java.util.Set;

@Data
@Entity
@Table(name = "role")
public class Role {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(unique = true)
    private String name;   // ADMIN / USER / AGENT_SERVICE
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "role_permission",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions = new HashSet<>();
}
```

```java
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
```

```java
package com.kbrag.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
}
public interface RoleRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByName(String name);
}
```

- [ ] **Step 3: JwtUtil(jjwt 0.12 API)**

```java
package com.kbrag.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

@Component
public class JwtUtil {
    private final SecretKey key;
    private final long expireMillis;

    public JwtUtil(@Value("${app.jwt.secret}") String secret,
                   @Value("${app.jwt.expire-hours:24}") long expireHours) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMillis = expireHours * 3600_000L;
    }

    public String generate(Long userId, String username, List<String> roles) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("roles", roles)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expireMillis))
                .signWith(key)
                .compact();
    }

    /** 解析并校验签名/过期;非法抛异常。 */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
    }
}
```

- [ ] **Step 4: JwtAuthenticationFilter(无状态,写 SecurityContext)**

```java
package com.kbrag.auth;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 无状态 JWT 过滤器:Authorization: Bearer <token> → SecurityContext。
 * 校验失败直接放行(由 Security 链后续判定 401),不抛错中断。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    @Autowired private JwtUtil jwtUtil;
    @Autowired private UserRepository users;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims c = jwtUtil.parse(header.substring(7));
                User u = users.findById(Long.valueOf(c.getSubject())).orElse(null);
                if (u != null && Boolean.TRUE.equals(u.getEnabled())) {
                    List<SimpleGrantedAuthority> authorities = u.getRoles().stream()
                            .map(r -> new SimpleGrantedAuthority("ROLE_" + r.getName()))
                            .toList();
                    var auth = new UsernamePasswordAuthenticationToken(u, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (Exception ignored) { /* 无效 token → 未认证,链上判定 */ }
        }
        chain.doFilter(req, resp);
    }
}
```

- [ ] **Step 5: SecurityConfig(无状态 + 放行白名单)**

```java
package com.kbrag.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Autowired private JwtAuthenticationFilter jwtFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/agent/health",
                                "/", "/index.html", "/static/**", "/favicon.ico").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

- [ ] **Step 6: AuthController(登录 + me)**

```java
package com.kbrag.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwtUtil;

    public record LoginRequest(String username, String password) {}

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest req) {
        User u = users.findByUsername(req.username())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
        if (!passwordEncoder.matches(req.password(), u.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        List<String> roles = u.getRoles().stream().map(Role::getName).toList();
        String token = jwtUtil.generate(u.getId(), u.getUsername(), roles);
        return Map.of("token", token, "username", u.getUsername(), "roles", roles);
    }

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal User u) {
        List<String> perms = u.getRoles().stream()
                .flatMap(r -> r.getPermissions().stream().map(Permission::getCode)).distinct().toList();
        return Map.of("username", u.getUsername(),
                "roles", u.getRoles().stream().map(Role::getName).toList(),
                "permissions", perms);
    }
}
```

- [ ] **Step 7: application.yml 配置**

```yaml
  jwt:
    secret: netdoc-demo-jwt-secret-please-change-in-prod-0123456789abcdef   # ≥32 字节(HS256)
    expire-hours: 24
```

- [ ] **Step 8: DataInitializer(首次启动建 admin/角色/权限/服务账号)**

```java
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
```

> ⚠️ 上面 DataInitializer 为示意骨架——**三个私有方法需按标准 JPA 写法补全**(save 前先 findByName/ByCode 判存在,权限点不存在则 new+save,角色关联权限集,用户 BCrypt 加密密码)。这是本 Task 唯一留白处,补全后验证:

- [ ] **Step 9: 验证**

```bash
cd backend && mvn spring-boot:run
TOKEN=$(curl -s -X POST http://localhost:9000/api/auth/login -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")
echo $TOKEN   # 拿到 JWT
curl -s http://localhost:9000/api/auth/me -H "Authorization: Bearer $TOKEN"   # 用户名/角色/权限
curl -s http://localhost:9000/api/documents    # 无 token → 401
curl -s http://localhost:9000/api/documents -H "Authorization: Bearer $TOKEN" # 200
```

- [ ] **Step 10: 提交**

```bash
git add -A && git commit -m "feat: jwt auth with rbac0 user-role-permission model"
```

**验收**:登录拿 token;me 返回角色/权限;无 token 401;admin 可访问文档接口。

---

### Task 2: 多知识库改造

**Files:**
- Create: `backend/src/main/java/com/kbrag/kb/KnowledgeBase.java`、`KnowledgeBaseRepository.java`、`KnowledgeBaseController.java`
- Modify: `Document.java`/`DocumentChunk.java`/`Conversation.java`(+kbId)
- Modify: `DocumentController.java`/`DocumentService.java`(带 kbId)
- Modify: `HybridRetriever.java`(search 加 kbId 过滤)
- Modify: `RetrievalController.java`/`ToolController.java`(带 kbId)
- Modify: `ChatController.java`/`AgentChatService.java`(带 kbId)

**Interfaces:**
- Consumes: Task 1 认证(登录态可访问);Plan A 检索/工具/SSE
- Produces: `POST/GET/DELETE /api/kbs`;所有数据接口带 kbId(query 参数);`HybridRetriever.search(query, kbId, topK)`;Agent 工具带 kbId

- [ ] **Step 1: KnowledgeBase 实体与 Controller**

```java
package com.kbrag.kb;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "knowledge_base")
public class KnowledgeBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    @Column(columnDefinition = "text")
    private String description;
    private Long ownerId;
    private LocalDateTime createdAt = LocalDateTime.now();
}
```

```java
package com.kbrag.kb;

import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {}
```

```java
package com.kbrag.kb;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/kbs")
public class KnowledgeBaseController {
    @Autowired private KnowledgeBaseRepository kbs;

    @PostMapping
    public KnowledgeBase create(@RequestBody KnowledgeBase kb) {
        kb.setId(null);
        return kbs.save(kb);
    }

    @GetMapping
    public List<KnowledgeBase> list() {
        return kbs.findAll();   // Task 3 改为按数据权限过滤
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        kbs.deleteById(id);   // Task 4 连带清理文档/分块/图谱
    }
}
```

- [ ] **Step 2: 实体挂 kb_id(document/chunk/conversation)**

```java
// Document 加:
private Long kbId;
// DocumentChunk 加:
private Long kbId;
// Conversation 加:
private Long kbId;
```

- [ ] **Step 3: DocumentController/Service 带 kbId**

```java
// DocumentController.upload 加参数:
@PostMapping
public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                @RequestParam("kbId") Long kbId) {
    ...
}
// DocumentService.upload(MultipartFile file, Long kbId):doc.setKbId(kbId)
// DocumentService.processAsync:entities.get(i).setKbId(kbId)
// list 加 kbId 过滤:
@GetMapping
public List<Document> list(@RequestParam(required = false) String status,
                           @RequestParam(required = false) Long kbId) {
    return documents.findAll().stream()
            .filter(d -> status == null || d.getStatus().name().equalsIgnoreCase(status))
            .filter(d -> kbId == null || kbId.equals(d.getKbId()))
            .toList();
}
```

- [ ] **Step 4: HybridRetriever 加 kbId 过滤(三路融合在 Task 5,此处先双路+过滤)**

```java
public List<SearchResult> search(String query, Long kbId, int topK) {
    float[] vec = embeddingService.embed(List.of(query)).get(0);
    List<Long> denseIds = jdbc.query(
            "SELECT id FROM document_chunk WHERE kb_id = ? " +
            "ORDER BY embedding <=> CAST(? AS vector) LIMIT ?",
            (rs, i) -> rs.getLong(1), kbId, new PGvector(vec), denseTopK);
    String seg = tokenizer.segment(query);
    List<Long> sparseIds = jdbc.query(
            "SELECT id FROM document_chunk WHERE kb_id = ? AND search_text @@ plainto_tsquery('simple', ?) " +
            "ORDER BY ts_rank(search_text, plainto_tsquery('simple', ?)) DESC LIMIT ?",
            (rs, i) -> rs.getLong(1), kbId, seg, seg, sparseTopK);
    List<Long> fused = RrfFusion.fuse(denseIds, sparseIds, rrfK, Math.min(topK, finalTopK));
    if (fused.isEmpty()) return List.of();
    List<SearchResult> rows = jdbc.query(
            "SELECT id, doc_id, content, heading_path FROM document_chunk WHERE id = ANY (?)",
            (rs, i) -> new SearchResult(
                    rs.getLong("id"), rs.getLong("doc_id"),
                    rs.getString("content"), rs.getString("heading_path")),
            fused.toArray(Long[]::new));
    Map<Long, SearchResult> byId = rows.stream()
            .collect(Collectors.toMap(SearchResult::chunkId, r -> r));
    return fused.stream().map(byId::get).filter(Objects::nonNull).toList();
}
```

- [ ] **Step 5: 调用方带 kbId(RetrievalController/ToolController/ChatController)**

```java
// RetrievalController:
@PostMapping
public List<SearchResult> retrieve(@RequestBody RetrieveRequest req, @RequestParam Long kbId) {
    return retriever.search(req.query(), kbId, req.topK() == 0 ? 5 : req.topK());
}
public record RetrieveRequest(String query, int topK) {}

// ToolController.search:req 加 kbId 字段,SQL 过滤同上
// ChatController.chat:加 @RequestParam Long kbId → AgentChatService.stream(..., kbId)
// AgentChatService:body 加 "kb_id",Python build_input 透传 → state["kb_id"] → tools_node 的 search_kb 带 kb_id
// Python tools.py execute_tool("search_kb", {"query":..., "kb_id":...})
// JavaClient.search_kb(query, top_k, kb_id) → payload 带 kbId
```

> Python 侧改动(机械性):`AgentState` 加 `kb_id`;`build_input` 透传;`tools_node` 执行 search_kb 时带 `kb_id`;`JavaClient.search_kb(query, top_k=5, kb_id=None)`。

- [ ] **Step 6: 验证**

```bash
TOKEN=$(curl -s -X POST localhost:9000/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")
# 建两个库
curl -s -X POST localhost:9000/api/kbs -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"name":"企业文档库","description":"通用企业文档"}'
curl -s -X POST localhost:9000/api/kbs -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"name":"OpenWrt 库","description":"网络设备手册"}'
# 分别上传文档
curl -s -F "file=@sample.md" "localhost:9000/api/documents?kbId=1" -H "Authorization: Bearer $TOKEN"
curl -s -F "file=@openwrt.md" "localhost:9000/api/documents?kbId=2" -H "Authorization: Bearer $TOKEN"
# 检索隔离
curl -s -X POST "localhost:9000/api/retrieve?kbId=1" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"query":"OpenWrt 配置","topK":3}'
# 期望:kbId=1 的检索不回 OpenWrt 库的内容
```

- [ ] **Step 7: 提交**

```bash
git add -A && git commit -m "feat: multi knowledge base isolation with kbId dimension"
```

**验收**:建双库、分别传文档、检索按库隔离;chunk 表 kb_id 正确;Agent 工具带 kbId。

---

### Task 3: 数据权限(role_kb_access + AOP 切面)

**Files:**
- Create: `backend/src/main/java/com/kbrag/auth/RoleKbAccess.java`、`RoleKbAccessRepository.java`
- Create: `backend/src/main/java/com/kbrag/auth/KbAccess.java`(注解)
- Create: `backend/src/main/java/com/kbrag/auth/KbAccessAspect.java`
- Create: `backend/src/main/java/com/kbrag/auth/AccessService.java`
- Create: `backend/src/main/java/com/kbrag/auth/AdminController.java`(建用户/授权)
- Modify: `KnowledgeBaseController.java`(list 按权限过滤 + @KbAccess)

**Interfaces:**
- Consumes: Task 1(User/Role)、Task 2(KnowledgeBase)
- Produces: `@KbAccess("READ"|"WRITE"|"ADMIN")` 注解 + 切面;`AccessService.canAccess(User, kbId, level) -> boolean`;`POST /api/admin/users`、`POST /api/admin/kbs/{kbId}/grant {role, access}`

- [ ] **Step 1: role_kb_access 实体**

```java
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
```

```java
package com.kbrag.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RoleKbAccessRepository extends JpaRepository<RoleKbAccess, Long> {
    List<RoleKbAccess> findByRoleId(Long roleId);
    List<RoleKbAccess> findByKbId(Long kbId);
    void deleteByKbId(Long kbId);
}
```

- [ ] **Step 2: AccessService(权限判定核心,纯逻辑可单测)**

```java
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
        return switch (level) {
            case "READ" -> granted.contains("READ") || granted.contains("WRITE") || granted.contains("ADMIN");
            case "WRITE" -> granted.contains("WRITE") || granted.contains("ADMIN");
            case "ADMIN" -> granted.contains("ADMIN");
            default -> false;
        };
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
}
```

- [ ] **Step 3: TDD——AccessService 判定单测(纯逻辑,不依赖 Spring)**

```java
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
```

> 将 canAccess 的级别映射抽为 `static boolean pureLevel(String required, Set<String> granted)`(canAccess 调用它),单测直接测纯逻辑——与 Phase 1 的 RrfFusion 模式一致。

- [ ] **Step 4: @KbAccess 注解 + AOP 切面**

```java
package com.kbrag.auth;

import java.lang.annotation.*;

/**
 * 知识库数据权限注解:标注在需要校验 kbId 访问级别的 Controller 方法上。
 * value: READ / WRITE / ADMIN(默认 READ)。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface KbAccess {
    String value() default "READ";
}
```

```java
package com.kbrag.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

/**
 * 权限切面:统一从请求 query 取 kbId(Global Constraints),校验当前用户访问级别。
 * 一处实现,全部标注接口生效——数据访问不绕过权限层(面试点)。
 */
@Aspect
@Component
public class KbAccessAspect {
    @Autowired private AccessService accessService;

    @Before("@annotation(kbAccess)")
    public void check(JoinPoint jp, KbAccess kbAccess) {
        HttpServletRequest req = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        String kbIdStr = req.getParameter("kbId");
        if (kbIdStr == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 kbId");
        }
        Long kbId;
        try { kbId = Long.valueOf(kbIdStr); }
        catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "kbId 格式错误");
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User u)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        accessService.requireAccess(u, kbId, kbAccess.value());
    }
}
```

- [ ] **Step 5: 标注接口 + 库列表按权限过滤**

```java
// DocumentController:
@PostMapping @KbAccess("WRITE")
public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file, @RequestParam Long kbId) {...}
@GetMapping @KbAccess("READ")
public List<Document> list(@RequestParam(required = false) String status, @RequestParam Long kbId) {...}
@DeleteMapping("/{id}") @KbAccess("WRITE")
public void delete(@PathVariable Long id, @RequestParam Long kbId) {...}

// RetrievalController / ChatController: @KbAccess("READ")
// ToolController: 三个工具 @KbAccess("READ")(AGENT_SERVICE 角色被授权)
// KnowledgeBaseController.list: 按 accessibleKbIds 过滤(null=全部):
@GetMapping
public List<KnowledgeBase> list(@AuthenticationPrincipal User u) {
    List<Long> ids = accessService.accessibleKbIds(u);
    return ids == null ? kbs.findAll()
            : kbs.findAll().stream().filter(kb -> ids.contains(kb.getId())).toList();
}
@DeleteMapping("/{id}") @KbAccess("ADMIN")
public void delete(@PathVariable Long id, @RequestParam Long kbId) {...}
```

- [ ] **Step 6: AdminController(建用户/授权最小集)**

```java
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
```

> 管理端点本身要求 ADMIN:在 AdminController 类上加 `@PreAuthorize("hasRole('ADMIN')")` 并在 SecurityConfig 开启 `@EnableMethodSecurity`。

- [ ] **Step 7: 验证**

```bash
# 建普通用户并授权
curl -s -X POST localhost:9000/api/admin/users -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"alice123","role":"USER"}'
curl -s -X POST localhost:9000/api/admin/kbs/1/grant -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"role":"USER","access":"READ"}'
# alice 登录
ATOKEN=$(curl -s -X POST localhost:9000/api/auth/login -H "Content-Type: application/json" -d '{"username":"alice","password":"alice123"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")
curl -s "localhost:9000/api/documents?kbId=1" -H "Authorization: Bearer $ATOKEN"   # 200(READ 已授)
curl -s -F "file=@sample.md" "localhost:9000/api/documents?kbId=1" -H "Authorization: Bearer $ATOKEN"  # 403(仅 READ)
curl -s "localhost:9000/api/documents?kbId=2" -H "Authorization: Bearer $ATOKEN"   # 403(未授权库)
```

- [ ] **Step 8: 提交**

```bash
git add -A && git commit -m "feat: kb data permission with rbac0 role-kb-access and aop aspect"
```

**验收**:权限单测通过;READ 用户可查不可传;未授权库 403;ADMIN 全见。

---

### Task 4: KG 构建(Python /extract + Java 落库)

**Files:**
- Create: `python/app/extract.py`(抽取端点 + 抽取 prompt + 归一化)
- Modify: `python/app/main.py`(挂 /extract)
- Create: `backend/src/main/java/com/kbrag/kg/KgEntity.java`、`KgRelation.java`、`KgRepository.java`
- Create: `backend/src/main/java/com/kbrag/kg/KgService.java`(调 Python + 落库)
- Modify: `backend/src/main/java/com/kbrag/document/DocumentService.java`(入库后触发抽取)

**Interfaces:**
- Consumes: Python `chat_model`(Plan A);Java `WebClient`、`DocumentChunkRepository`
- Produces: `POST http://localhost:9100/extract {kb_id, doc_id, chunks} → {entities:[{name,type,normalized_name,confidence}], relations:[{source,target,relation,confidence}]}`;`KgService.extractAndSave(kbId, docId)`;`GET /api/kg/entities`/`GET /api/kg/graph`(Task 5)

- [ ] **Step 1: Python 抽取端点(LLM function calling 输出三元组)**

```python
# python/app/extract.py
from fastapi import APIRouter
from pydantic import BaseModel

from app.llm import chat_model

router = APIRouter()


class ExtractRequest(BaseModel):
    kb_id: int
    doc_id: int
    chunks: list[str]


class ExtractResponse(BaseModel):
    entities: list[dict]
    relations: list[dict]


# 实体类型限 6 类,控制抽取质量(spec §13 风险表)
ENTITY_TYPES = ("DEVICE", "SOFTWARE", "COMMAND", "CONFIG", "PROTOCOL", "VENDOR")

EXTRACT_TOOL = {
    "type": "function",
    "function": {
        "name": "submit_kg",
        "description": "提交从文档片段中抽取的实体与关系。只抽取确定的事实,不要猜测。",
        "parameters": {
            "type": "object",
            "properties": {
                "entities": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "name": {"type": "string", "description": "实体名,如 OpenWrt"},
                            "type": {"type": "string", "enum": list(ENTITY_TYPES)},
                            "confidence": {"type": "number", "description": "0-1 置信度"},
                        },
                        "required": ["name", "type", "confidence"],
                    },
                },
                "relations": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "source": {"type": "string"},
                            "target": {"type": "string"},
                            "relation": {"type": "string", "description": "如 USES/REQUIRES/CONFIGURES/SUPPORTS"},
                            "confidence": {"type": "number"},
                        },
                        "required": ["source", "target", "relation", "confidence"],
                    },
                },
            },
            "required": ["entities", "relations"],
        },
    },
}

SYSTEM = (
    "你是知识图谱构建器。从设备技术文档片段中抽取实体与关系,只抽确定事实。"
    "实体类型限 DEVICE/SOFTWARE/COMMAND/CONFIG/PROTOCOL/VENDOR。"
    "关系如:OpenWrt -[SUPPORTS]-> MT799X。置信度 <0.7 的不要提交。"
)

# 别名归一化:不同写法映射到同一规范名(面试点:实体消歧)
ALIASES = {
    "openwrt": "OpenWrt",
    "opkg": "opkg",
    "luci": "Luci",
    "mt799x": "MT799X",
}


def normalize(name: str) -> str:
    return ALIASES.get(name.strip().lower(), name.strip())


@router.post("/extract", response_model=ExtractResponse)
async def extract(req: ExtractRequest):
    entities, relations = [], []
    for chunk in req.chunks[:20]:   # 单次重建上限 20 chunk,防超时
        resp = await chat_model.ainvoke(
            [
                {"role": "system", "content": SYSTEM},
                {"role": "user", "content": f"文档片段:\n{chunk[:1500]}"},
            ],
            tools=[EXTRACT_TOOL],
        )
        calls = getattr(resp, "tool_calls", None) or []
        for c in calls:
            args = c.get("args") or {}
            for e in args.get("entities", []):
                if e.get("confidence", 0) >= 0.7:
                    e["normalized_name"] = normalize(e["name"])
                    entities.append(e)
            for r in args.get("relations", []):
                if r.get("confidence", 0) >= 0.7:
                    relations.append(r)
    return ExtractResponse(entities=entities, relations=relations)
```

- [ ] **Step 2: main.py 挂路由**

```python
from app.extract import router as extract_router

app.include_router(extract_router)
```

- [ ] **Step 3: Python 测试(mock LLM 返回 tool_calls)**

```python
# python/tests/test_extract.py
from types import SimpleNamespace

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)

FAKE_CALL = {"name": "submit_kg", "args": {
    "entities": [
        {"name": "OpenWrt", "type": "SOFTWARE", "confidence": 0.95},
        {"name": "opkg", "type": "COMMAND", "confidence": 0.6},   # 低置信应被过滤
    ],
    "relations": [
        {"source": "OpenWrt", "target": "MT799X", "relation": "SUPPORTS", "confidence": 0.9},
    ],
}}


def test_extract_filters_low_confidence_and_normalizes(monkeypatch):
    class FakeChat:
        async def ainvoke(self, messages, **kwargs):
            return SimpleNamespace(content="", tool_calls=[FAKE_CALL])

    import app.extract
    monkeypatch.setattr(app.extract, "chat_model", FakeChat())

    r = client.post("/extract", json={"kb_id": 1, "doc_id": 1, "chunks": ["OpenWrt 支持 MT799X 芯片"]})
    assert r.status_code == 200
    data = r.json()
    names = [e["name"] for e in data["entities"]]
    assert "OpenWrt" in names
    assert len(data["entities"]) == 1      # opkg(0.6) 被置信度过滤
    assert data["entities"][0]["normalized_name"] == "OpenWrt"
    assert len(data["relations"]) == 1
```

- [ ] **Step 4: Java 图谱实体与仓库**

```java
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
```

```java
package com.kbrag.kg;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface KgEntityRepository extends JpaRepository<KgEntity, Long> {
    List<KgEntity> findByKbIdAndDocId(Long kbId, Long docId);
    void deleteByKbIdAndDocId(Long kbId, Long docId);
}

public interface KgRelationRepository extends JpaRepository<KgRelation, Long> {
    List<KgRelation> findByKbIdAndSourceIdIn(Long kbId, List<Long> sourceIds);
}
```

- [ ] **Step 5: KgService(调 Python → 落库;实体名↔id 映射)**

```java
package com.kbrag.kg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class KgService {
    private final WebClient webClient;
    private final KgEntityRepository entities;
    private final KgRelationRepository relations;
    private final ObjectMapper om = new ObjectMapper();

    public KgService(WebClient.Builder builder,
                     KgEntityRepository entities,
                     KgRelationRepository relations,
                     @Value("${app.agent.base-url:http://localhost:9100}") String agentBaseUrl) {
        this.webClient = builder.baseUrl(agentBaseUrl).build();
        this.entities = entities;
        this.relations = relations;
    }

    /** 抽取并落库;任一步失败抛异常(调用方降级处理,不影响文档状态)。 */
    public void extractAndSave(Long kbId, Long docId, List<String> chunks) {
        Map<?, ?> resp = webClient.post().uri("/extract")
                .bodyValue(Map.of("kb_id", kbId, "doc_id", docId, "chunks", chunks))
                .retrieve().bodyToMono(Map.class).block(Duration.ofSeconds(120));
        if (resp == null) throw new IllegalStateException("KG extract returned null");

        entities.deleteByKbIdAndDocId(kbId, docId);   // 先清旧实体(重建语义)

        Map<String, KgEntity> byName = new HashMap<>();
        JsonNode entNodes = om.valueToTree(resp.get("entities"));
        for (JsonNode n : entNodes) {
            KgEntity e = new KgEntity();
            e.setKbId(kbId); e.setDocId(docId);
            e.setName(n.path("name").asText());
            e.setType(n.path("type").asText());
            e.setNormalizedName(n.has("normalized_name") ? n.path("normalized_name").asText() : e.getName());
            e.setConfidence(n.path("confidence").asDouble());
            entities.save(e);
            byName.putIfAbsent(e.getName(), e);   // 同一文档内同名实体复用同一 id
        }

        JsonNode relNodes = om.valueToTree(resp.get("relations"));
        for (JsonNode n : relNodes) {
            KgEntity src = byName.get(n.path("source").asText());
            KgEntity dst = byName.get(n.path("target").asText());
            if (src == null || dst == null) continue;   // 指向未抽到实体的关系丢弃
            KgRelation r = new KgRelation();
            r.setKbId(kbId);
            r.setSourceId(src.getId());
            r.setTargetId(dst.getId());
            r.setRelation(n.path("relation").asText());
            r.setConfidence(n.path("confidence").asDouble());
            relations.save(r);
        }
    }
}
```

- [ ] **Step 6: DocumentService 触发抽取(失败降级,不影响 READY)**

```java
// processAsync 中,chunks.saveAll(entities) 与 doc.setStatus(READY) 之间插入:
try {
    kgService.extractAndSave(kbId, docId,
            parsed.stream().map(Chunk::content).toList());
} catch (Exception ex) {
    log.warn("KG extraction failed for doc {}: {}", docId, ex.getMessage());
}
```

- [ ] **Step 7: 验证**

```bash
# 重启 Java + Python(9100)
TOKEN=$(...login...)
curl -s -F "file=@openwrt.md" "localhost:9000/api/documents?kbId=2" -H "Authorization: Bearer $TOKEN"
sleep 8   # 入库 + 抽取
psql postgresql://kbrag:kbrag123@localhost:5433/kbrag -c \
  "SELECT name, type, confidence FROM kg_entity WHERE kb_id=2 ORDER BY id LIMIT 20;"
psql postgresql://kbrag:kbrag123@localhost:5433/kbrag -c \
  "SELECT count(*) FROM kg_relation WHERE kb_id=2;"
# 期望:实体/关系有数据;低置信被过滤;文档状态仍 READY
```

- [ ] **Step 8: 提交**

```bash
git add -A && git commit -m "feat: kg extraction pipeline via python llm and java persistence"
```

**验收**:上传文档后 kg_entity/kg_relation 自动有数据;抽取失败不影响文档 READY;pytest test_extract 通过。

---

### Task 5: 图谱检索(三路融合)+ 可视化

**Files:**
- Create: `backend/src/main/java/com/kbrag/kg/GraphRetriever.java`
- Modify: `backend/src/main/java/com/kbrag/retrieval/RrfFusion.java`(三路支持)
- Modify: `backend/src/main/java/com/kbrag/retrieval/HybridRetriever.java`(三路融合 + 图谱上下文)
- Create: `backend/src/main/java/com/kbrag/kg/KgController.java`(/api/kg/graph、/entities)
- Modify: `backend/src/main/resources/application.yml`(app.retrieval.kg-top-k / kg-context-enabled)
- Modify: `backend/src/main/resources/static/index.html`(图谱 Tab,力导向图)

**Interfaces:**
- Consumes: KgEntityRepository/KgRelationRepository(Task 4)、Tokenizer(Phase 1)
- Produces: `GraphRetriever.entitiesFor(String query, Long kbId) -> List<KgEntity>`(实体链接)、`neighborDocs(...)`;`HybridRetriever.search` 三路融合;`GET /api/kg/graph?kbId=` → {nodes, edges}、`GET /api/kg/entities?kbId=&q=`

- [ ] **Step 1: RrfFusion 支持三路(重载,保留旧签名兼容 Plan A)**

```java
public class RrfFusion {
    public static List<Long> fuse(List<Long> a, List<Long> b, int k, int topN) {
        return fuse(List.of(a, b), k, topN);
    }

    /** 多路 RRF:score = sum(1/(k+rank)),rank 从 1 开始;结果按分数降序取 topN。 */
    public static List<Long> fuse(List<List<Long>> rankedLists, int k, int topN) {
        Map<Long, Double> scores = new HashMap<>();
        for (List<Long> list : rankedLists) {
            add(scores, list, k);
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(topN)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static void add(Map<Long, Double> scores, List<Long> ids, int k) {
        for (int i = 0; i < ids.size(); i++) {
            scores.merge(ids.get(i), 1.0 / (k + i + 1), Double::sum);
        }
    }
}
```

- [ ] **Step 2: 写 TDD 测试(三路融合)**

```java
class RrfFusionTest {
    @Test
    void three_way_fusion_prefers_agreement() {
        // 图谱路与向量路一致:[1,2];关键词路:[1,3] → 1 三路命中排第一
        List<Long> fused = RrfFusion.fuse(
                List.of(List.of(1L, 2L, 3L), List.of(1L, 2L, 4L), List.of(1L, 3L, 5L)), 60, 3);
        assertEquals(1L, fused.get(0));
        assertTrue(fused.contains(2L));
        assertEquals(3, fused.size());
    }
}
```

- [ ] **Step 3: GraphRetriever(实体链接 + 邻居扩展 + 关联文档)**

```java
package com.kbrag.kg;

import com.kbrag.ai.Tokenizer;
import com.pgvector.PGvector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 图谱检索(查询时零 LLM,毫秒级):
 * ① 实体链接:jieba 分词词匹配 kg_entity.name(词典即实体表,按 kb 过滤)
 * ② 一跳邻居:kg_relation 取关联实体
 * ③ 关联文档:实体来源 doc → 该文档的 chunk 候选(图谱路)
 */
@Service
public class GraphRetriever {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Tokenizer tokenizer;

    public List<Long> linkEntities(String query, Long kbId) {
        String seg = tokenizer.segment(query);
        List<String> words = java.util.Arrays.stream(seg.split(" "))
                .filter(w -> w.length() >= 2)   // 单字噪音过滤
                .toList();
        if (words.isEmpty()) return List.of();
        // 实体名精确匹配分词词(实体名通常为专有名词,分词可命中)
        return jdbc.query(
                "SELECT id FROM kg_entity WHERE kb_id = ? AND name IN (" +
                        String.join(",", words.stream().map(w -> "?").toList()) + ")",
                (rs, i) -> rs.getLong(1), args(kbId, words));
    }

    /** 命中实体的邻居实体 id(一跳)。 */
    public List<Long> neighborEntities(Long kbId, List<Long> entityIds) {
        if (entityIds.isEmpty()) return List.of();
        return jdbc.query(
                "SELECT target_id FROM kg_relation WHERE kb_id = ? AND source_id = ANY (?)",
                (rs, i) -> rs.getLong(1), kbId, entityIds.toArray(Long[]::new));
    }

    /** 实体来源文档的 chunk id(图谱路候选,按 kb 过滤)。 */
    public List<Long> docChunks(Long kbId, List<Long> entityIds) {
        if (entityIds.isEmpty()) return List.of();
        return jdbc.query(
                "SELECT c.id FROM document_chunk c " +
                "JOIN kg_entity e ON e.doc_id = c.doc_id AND e.kb_id = c.kb_id " +
                "WHERE c.kb_id = ? AND e.id = ANY (?) " +
                "GROUP BY c.id LIMIT ?",
                (rs, i) -> rs.getLong(1), kbId, entityIds.toArray(Long[]::new), kgTopK);
    }

    @Value("${app.retrieval.kg-top-k:20}")
    private int kgTopK;

    private Object[] args(Long kbId, List<String> words) {
        Object[] out = new Object[words.size() + 1];
        out[0] = kbId;
        System.arraycopy(words.toArray(), 0, out, 1, words.size());
        return out;
    }
}
```

> 注:`docChunks` 的 GROUP BY 去重 + LIMIT kgTopK;实体链接用 `name IN (分词词)`——专有名词分词可命中,面试讲"词典即实体表,零额外维护"。

- [ ] **Step 4: HybridRetriever 三路融合 + 图谱上下文**

```java
// search 中,稀疏/稠密查询之后:
List<Long> entityIds = graphRetriever.linkEntities(query, kbId);
List<Long> kgIds = List.of();
if (!entityIds.isEmpty()) {
    List<Long> neighbors = graphRetriever.neighborEntities(kbId, entityIds);
    List<Long> all = new ArrayList<>(entityIds);
    all.addAll(neighbors);
    kgIds = graphRetriever.docChunks(kbId, all);
    this.lastGraphContext = graphContextText(kbId, all);   // 图谱上下文段(供 Prompt)
}
List<Long> fused = RrfFusion.fuse(List.of(denseIds, sparseIds, kgIds), rrfK, Math.min(topK, finalTopK));
// ...回库取 chunk(同 Plan A),SearchResult 增加可选 graphContext 字段或由 ChatService 追加
```

```java
/** 图谱上下文段:命中实体与关系文本(随 Prompt 进 LLM)。 */
private String graphContextText(Long kbId, List<Long> entityIds) {
    List<Map<String, Object>> rows = jdbc.query(
            "SELECT e.name AS src, r.relation AS rel, t.name AS dst " +
            "FROM kg_relation r JOIN kg_entity e ON r.source_id = e.id " +
            "JOIN kg_entity t ON r.target_id = t.id " +
            "WHERE r.kb_id = ? AND r.source_id = ANY (?) LIMIT 10",
            (rs, i) -> Map.of("src", rs.getString("src"), "rel", rs.getString("rel"), "dst", rs.getString("dst")),
            kbId, entityIds.toArray(Long[]::new));
    return rows.stream()
            .map(r -> "实体[" + r.get("src") + "] -[" + r.get("rel") + "]-> 实体[" + r.get("dst") + "]")
            .collect(Collectors.joining("\n"));
}
```

- [ ] **Step 5: application.yml + ChatService 注入图谱上下文**

```yaml
  retrieval:
    dense-top-k: 30
    sparse-top-k: 30
    kg-top-k: 20          # 图谱路召回数
    rrf-k: 60
    final-top-k: 5
    kg-context-enabled: true   # 图谱上下文段开关
```

ChatService(Plan A 直连版)与 AgentChatService(透传版)的 Prompt 组装处:检索结果片段前追加图谱上下文段:

```java
// AgentChatService 无需改(Python 侧由 tools_node 组装);Plan A ChatService 直连版:
String kgCtx = retriever.lastGraphContext();   // 或 search 返回结果附带
if (StringUtils.hasText(kgCtx)) prompt.append("\n图谱关系:\n").append(kgCtx).append("\n");
```

- [ ] **Step 6: KgController(可视化 + 实体搜索)**

```java
package com.kbrag.kg;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/kg")
public class KgController {
    @Autowired private KgEntityRepository entities;
    @Autowired private KgRelationRepository relations;

    @GetMapping("/graph")
    public Map<String, Object> graph(@RequestParam Long kbId) {
        List<KgEntity> es = entities.findAll().stream().filter(e -> e.getKbId().equals(kbId)).toList();
        List<KgRelation> rs = relations.findAll().stream().filter(r -> r.getKbId().equals(kbId)).toList();
        return Map.of(
                "nodes", es.stream().map(e -> Map.of("id", e.getId(), "name", e.getName(), "type", e.getType())).toList(),
                "edges", rs.stream().map(r -> Map.of(
                        "source", r.getSourceId(), "target", r.getTargetId(), "relation", r.getRelation())).toList());
    }

    @GetMapping("/entities")
    public List<KgEntity> entities(@RequestParam Long kbId, @RequestParam(required = false) String q) {
        return entities.findAll().stream()
                .filter(e -> e.getKbId().equals(kbId))
                .filter(e -> q == null || e.getName().toLowerCase().contains(q.toLowerCase()))
                .toList();
    }
}
```

- [ ] **Step 7: 演示页图谱 Tab(力导向图,原生 JS 简单渲染)**

```html
<!-- index.html 追加:登录区 + 图谱按钮 -->
<div style="margin:12px 0">
  <input id="u" placeholder="用户名" value="admin"/><input id="p" type="password" placeholder="密码" value="admin123"/>
  <button onclick="login()">登录</button>
  <button onclick="loadGraph()">图谱</button>
  <canvas id="kg" width="720" height="360" style="border:1px solid #ccc;display:none"></canvas>
</div>
<script>
let TOKEN = '';
async function login() {
  const r = await fetch('/api/auth/login', {method:'POST', headers:{'Content-Type':'application/json'},
    body: JSON.stringify({username:u.value, password:p.value})});
  TOKEN = (await r.json()).token;
  alert('登录成功');
}
// 所有 fetch 加 headers:{'Authorization':'Bearer '+TOKEN}
async function loadGraph() {
  const r = await fetch('/api/kg/graph?kbId=1', {headers:{'Authorization':'Bearer '+TOKEN}});
  const g = await r.json();
  drawGraph(g);   // 简易力导向:节点随机散布→按边迭代收敛→连线;70 行内实现
}
</script>
```

> drawGraph 简易力导向(教学版):节点初始随机位置,迭代 200 次(斥力 + 弹簧力),画线画点。面试可讲"力导向布局原理(斥力+弹簧+收敛)"。

- [ ] **Step 8: 验证**

```bash
# 检索含实体的问题,对比图谱上下文是否进入
curl -s -X POST "localhost:9000/api/retrieve?kbId=2" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"query":"OpenWrt 支持什么芯片"}'
# 期望:命中 MT799X 相关 chunk(图谱路召回)
curl -s "localhost:9000/api/kg/graph?kbId=2" -H "Authorization: Bearer $TOKEN" | python3 -m json.tool | head -30
# 期望:nodes/edges 有数据
# 浏览器:登录 → 图谱 Tab 出图
```

- [ ] **Step 9: 提交**

```bash
git add -A && git commit -m "feat: three-way retrieval fusion with kg entity recall and graph viz"
```

**验收**:RRF 三路单测通过;实体问题命中提升;graph 接口出数据;前端出图;图谱上下文进 Prompt。

---

### Task 6: 语义缓存一致性改造

**Files:**
- Modify: `backend/src/main/java/com/kbrag/cache/ChatCacheService.java`(kb 命名空间 + 版本戳 + TTL)
- Modify: `backend/src/main/java/com/kbrag/chat/AgentChatService.java`(lookup/put 带 kbId + 版本)
- Modify: `backend/src/main/java/com/kbrag/document/DocumentService.java`(变更后清 kb 缓存)

**Interfaces:**
- Consumes: Plan A Task 8(ChatCacheService)、Task 2(kbId)
- Produces: `ChatCacheService.lookup(question, kbId, kbVersion)`、`put(question, answer, sources, kbId, kbVersion)`;文档变更主动失效

- [ ] **Step 1: 版本戳(库内文档最新 updated_at)**

```java
// DocumentService/DocumentRepository 提供:
public interface DocumentRepository extends JpaRepository<Document, Long> {
    @Query("select coalesce(max(d.updatedAt), 0L) from Document d where d.kbId = ?1")
    Long kbVersion(Long kbId);
}
// Document 实体补 updatedAt:
private LocalDateTime updatedAt = LocalDateTime.now();   // 每次保存自动更新(在 Service 更新状态时 set)
```

- [ ] **Step 2: ChatCacheService 三件套改造**

```java
// key 结构:chat:cache:{kbId}:{id};recent:chat:cache:recent:{kbId}
// 条目新增字段:kbVersion
public Optional<CacheHit> lookup(String question, Long kbId, Long kbVersion) {
    float[] qv = embeddingService.embed(List.of(question)).get(0);
    List<String> ids = redis.opsForList().range("chat:cache:recent:" + kbId, 0, -1);
    if (ids == null || ids.isEmpty()) return Optional.empty();
    Map<String, float[]> candidates = new LinkedHashMap<>();
    Map<String, String> answers = new LinkedHashMap<>();
    Map<String, String> sources = new LinkedHashMap<>();
    for (String id : ids) {
        Map<Object, Object> entry = redis.opsForHash().entries("chat:cache:" + kbId + ":" + id);
        if (entry.isEmpty()) continue;
        // 版本戳比对:库被更新过 → 该条视为失效(面试点:主动失效的补充)
        if (!String.valueOf(kbVersion).equals(entry.get("kbVersion"))) continue;
        candidates.put(id, parseEmbedding((String) entry.get("embedding")));
        answers.put(id, (String) entry.get("answer"));
        sources.put(id, (String) entry.get("sourcesJson"));
    }
    String best = CosineSimilarity.selectBest(candidates, qv, threshold);
    if (best == null) return Optional.empty();
    return Optional.of(new CacheHit(answers.get(best), sources.get(best)));
}

public void put(String question, String answer, String sourcesJson, Long kbId, Long kbVersion) {
    float[] qv = embeddingService.embed(List.of(question)).get(0);
    String id = String.valueOf(System.nanoTime());
    Map<String, String> entry = Map.of(
            "question", question, "embedding", toJson(qv),
            "answer", answer, "sourcesJson", sourcesJson,
            "kbVersion", String.valueOf(kbVersion),
            "ts", String.valueOf(System.currentTimeMillis()));
    String key = "chat:cache:" + kbId + ":" + id;
    redis.opsForHash().putAll(key, entry);
    redis.expire(key, Duration.ofHours(24));                       // TTL 兜底
    redis.opsForList().leftPush("chat:cache:recent:" + kbId, id);
    redis.opsForList().trim("chat:cache:recent:" + kbId, 0, recentMax - 1);
}

/** 主动失效:文档变更后清该 kb 全部缓存条目与索引。 */
public void invalidateKb(Long kbId) {
    List<String> ids = redis.opsForList().range("chat:cache:recent:" + kbId, 0, -1);
    if (ids != null) {
        ids.forEach(id -> redis.delete("chat:cache:" + kbId + ":" + id));
    }
    redis.delete("chat:cache:recent:" + kbId);
}
```

- [ ] **Step 3: AgentChatService 接入(kbId/版本透传)**

```java
public void stream(String question, Long conversationId, Long kbId, SseEmitter emitter) {
    Long kbVersion = documents.kbVersion(kbId);
    Optional<ChatCacheService.CacheHit> hit = chatCache.lookup(question, kbId, kbVersion);
    if (hit.isPresent()) { emitCacheAnswer(hit.get(), emitter); return; }
    ... // 透传流程(done 时 chatCache.put(question, answer.toString(), sourcesJson, kbId, kbVersion))
}
```

- [ ] **Step 4: DocumentService 变更后主动失效**

```java
// upload(新文档保存后)与 delete 时:
chatCache.invalidateKb(kbId);
// processAsync 完成(向量化入库后)也失效一次(新内容已入库,旧缓存不可用)
```

- [ ] **Step 5: 验证**

```bash
# 问两次相同问题 → 第二次 cache_hit
# 上传新文档到该库 → 再问相同问题 → 不再命中(主动失效),重新走 Agent
# Redis 检查:
redis-cli KEYS 'chat:cache:*'          # 文档变更后该 kb 的键消失
redis-cli TTL $(redis-cli KEYS 'chat:cache:1:*' | head -1)   # 86400(TTL 兜底)
```

- [ ] **Step 6: 提交**

```bash
git add -A && git commit -m "feat: semantic cache consistency with kb namespace, version stamp and ttl"
```

**验收**:同问题二次命中;文档变更后缓存失效;TTL 24h;版本戳命中后校验。

---

## Self-Review 记录

**Spec 覆盖(2026-08-10 企业级 spec):**
- §5.1-5.3 RBAC0 两层(JWT + permission + role_kb_access + 切面)→ Task 1 + Task 3
- §6 数据模型(user/role/.../knowledge_base/kg_entity/kg_relation)→ Task 1/2/4
- §7 API(/api/auth/*、/api/kbs、/api/kg/*、/api/admin/*)→ Task 1/2/4/5
- §4.2 图谱构建(入库时 Python 抽取/归一/置信度过滤)→ Task 4
- §4.3 三路召回 RRF 融合 + 图谱上下文 + 可配置参数 → Task 5
- §4.5 图谱可视化 → Task 5
- §9 语义缓存一致性三件套 → Task 6
- §9 数据访问不绕过权限层(切面统一校验)→ Task 3。**无遗漏。**
- 知识包导出/评测/可观测 → Plan C(下一计划)

**占位符扫描:** Task 1 Step 8 的 DataInitializer 明确标注为"示意骨架,三个私有方法需按标准 JPA 补全"(唯一教学留白,标注了补全点与验证);其余全部完整代码。Task 5 Step 7 drawGraph 标注"70 行内实现"(演示页工具代码,给出布局原理,不逐行贴)。

**类型一致性:**
- `HybridRetriever.search(query, kbId, topK)` 签名在 Task 2 变更,RetrievalController/ToolController/ChatService 调用同步更新
- `ChatCacheService.lookup/put` 签名在 Task 6 变更(加 kbId/kbVersion),AgentChatService 同步
- Python `JavaClient.search_kb(query, top_k, kb_id)` 与 Java `ToolRequest(kbId)` 契约一致
- `/extract` 请求/响应字段与 KgService 解析一致(entities: name/type/normalized_name/confidence;relations: source/target/relation/confidence)
- 权限注解 `@KbAccess` 与切面 `@annotation(kbAccess)` 一致;kbId 一律 query 参数(切面取 request)
- RrfFusion 重载保持旧签名(Plan A 兼容),三路版 `fuse(List<List<Long>>, k, topN)`
- kg_relation 通过 KgEntity 表做实体名→id 映射(byName),与 Python 返回的 source/target 名称对应

---

## 修订记录(2026-08-11 审计回写)

> 来源:docs/audits/2026-08-11-code-audit.md(首个里程碑前系统性审计)。审计结论:Agent 工具检索链路三断点 + 授权层零落地,属上线阻塞,修复项映射回本计划 Task。

| # | 问题 | 修复方案 | 所属 Task |
|---|---|---|---|
| P-AuditB1 | kbId 未贯穿问答链路:ChatController.stream(kbId 参数未用)→ body 无 kb_id → Python AgentChatRequest 无 kb_id → JavaClient 不带 kbId → ToolController.search 的 kbId=null → `WHERE kb_id = ?` 永远查空 → Agent 全链路"查无资料"拒答 | kbId 全链路透传:ChatRequest→AgentChatService.stream body→Python AgentChatRequest→JavaClient.search_kb(kb_id)→ToolRequest.kbId;工具端点校验 kbId 非空 | Task 2(多知识库改造) |
| P-AuditB2 | 工具端点 /api/agent/tools/* 仅要求"已登录",Python JavaClient 又不带任何凭证 → 每次工具调用 401;AGENT_SERVICE 账号已建但从未使用 | JavaClient 配 AGENT_SERVICE 长 token 带 Authorization 头;SecurityConfig 对 /api/agent/tools/** 放行 + 服务凭证校验(或内网段隔离) | Task 1(RBAC0 认证) |
| P-AuditB3 | role_kb_access 表/实体/切面零实现:0 个 @PreAuthorize/@KbAccess;任意登录用户可带任意 kbId 检索/问答/上传/删除,get-doc-detail 按任意 docId 读全文(IDOR) | 按 Task 3 原设计落地:role_kb_access 表 + @KbAccess 切面 + 各业务端点挂载;工具端点加 hasRole("AGENT_SERVICE");/api/stats 加 STATS_VIEW | Task 3(数据权限) |
| P-AuditB4 | 语义缓存仍为旧签名(无 kbId/版本戳/失效/TTL):跨库相似问题互返答案(数据串流);缓存 hash 无 TTL → Redis 内存无界增长;lookup 每轮 1+200 次 Redis 往返(N+1) | Task 6 原设计落地 + 性能修正:kbId 命名空间 + kbVersion 戳 + 主动失效 + expire 24h;lookup 改 pipeline 批量读 | Task 6(语义缓存一致性) |
| P-AuditB5 | 限流粒度/信任错误:用 IP(已有 JWT)+ 盲信 X-Forwarded-For 首段可伪造绕过;login/retrieve/upload 无限流;429 无 Retry-After | 限流 key 改 userId;XFF 仅可信代理后采信;login 挂 IP+用户名维度限流;429 带 Retry-After | Task 1(限流随认证落地) |
| P-AuditB6 | 并发准入缺失:8 固定线程 + 无界队列 + 无拒绝策略;SseEmitter 180s 墙钟入队即倒计时,排队超时任务白跑(embedding/Redis/DB 全做) | 控制器层 Semaphore(目标并发)tryAcquire 失败 503;或 spring.threads.virtual.enabled;有界队列 + 拒绝策略;SSE 心跳 15-30s(Spring 文档:无心跳无法感知断连) | Task 1(弹性) |
| P-AuditB7 | @Async 用 Boot 默认 applicationTaskExecutor(core=8, max 无界, 队列无界):并发上传线程/队列无上限;上传无显式大小限制 | 自定义 ThreadPoolTaskExecutor(有界)+ @Async("docExecutor");multipart max-file-size 显式配置 | Task 2(文档管线加固) |
| P-AuditB8 | HNSW 索引不含 kb_id:pgvector 过滤在近似扫描后,多库下 top-30 可能全属别库 → 召回空/质量下降 | 按 kb 建独立 HNSW 索引或 list 分区(见 pgvector README 多租户建议);ef_search 调优 | Task 5(图谱检索三路融合) |
