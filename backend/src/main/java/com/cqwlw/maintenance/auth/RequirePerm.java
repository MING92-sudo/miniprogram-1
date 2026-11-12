package com.cqwlw.maintenance.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 端点所需能力码（docs/14 §9.3）：取值用 {@link PermissionMatrix} 常量，如
 * {@code @RequirePerm(PermissionMatrix.ORDER_WRITE)}。方法级优先于类级。
 * P1 只预留解析（AdminRoleInterceptor），端点标注随 P3/P4 分模块落地。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePerm {

    /** 能力码，必须是 {@link PermissionMatrix} 已登记的码值 */
    String value();
}
