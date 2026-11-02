package com.gatekeeper.qaplatform.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Records an audit event whenever the annotated Spring bean method runs, whether it succeeds or
 * fails. Method arguments are never recorded automatically, because they can contain secrets.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /** The action name, for example {@code USER_UPDATE}. */
    String action();

    /**
     * SpEL over the method parameters that gives the target, for example {@code #id}. The return
     * value is available as {@code #result}, which is null if the method failed.
     */
    String target() default "";

    /** SpEL inline map of extra details, for example {@code {role: #role}}. Never include secrets. */
    String details() default "";
}
