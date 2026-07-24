package com.power.posval.guice;

import com.power.posval.domain.port.tenant.TenantContext;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

/**
 * Guice MethodInterceptor for @TenantAware methods. §14.3, Pattern #32.
 * Sets PostgreSQL session variable for RLS enforcement.
 */
public class TenantInterceptor implements MethodInterceptor {

    @Inject private Provider<TenantContext> tenantContext;
    @Inject private Provider<EntityManager> entityManager;

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        String tenantId = extractTenantId(invocation);
        TenantContext ctx = tenantContext.get();

        try {
            ctx.setTenantId(tenantId);

            entityManager.get()
                .createNativeQuery("SET LOCAL app.tenant_id = :tid")
                .setParameter("tid", tenantId)
                .executeUpdate();

            return invocation.proceed();
        } finally {
            ctx.clear();
        }
    }

    private String extractTenantId(MethodInvocation invocation) {
        // Extract tenant ID from first String parameter
        for (Object arg : invocation.getArguments()) {
            if (arg instanceof String s && !s.isEmpty()) {
                return s;
            }
        }
        throw new IllegalStateException(
            "No tenant ID found for @TenantAware method: "
                + invocation.getMethod().getName());
    }
}
