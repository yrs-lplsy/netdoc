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
