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
