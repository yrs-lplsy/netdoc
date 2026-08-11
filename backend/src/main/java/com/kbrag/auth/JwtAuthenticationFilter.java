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
