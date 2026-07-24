package com.power.posval.domain.port.tenant;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation for methods that require a tenant context.
 * The TenantInterceptor (pv-guice) reads this at runtime via Guice AOP.
 * Pattern #32.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface TenantAware {}
