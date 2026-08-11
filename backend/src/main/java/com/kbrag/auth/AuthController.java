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
