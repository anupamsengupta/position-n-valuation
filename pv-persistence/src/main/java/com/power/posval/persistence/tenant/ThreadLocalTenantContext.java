package com.power.posval.persistence.tenant;

import com.power.posval.domain.port.tenant.TenantContext;

/**
 * Thread-local implementation of TenantContext.
 * Pattern #32, §14.1.
 */
public class ThreadLocalTenantContext implements TenantContext {

    private static final ThreadLocal<String> TENANT = new ThreadLocal<>();

    @Override
    public String currentTenantId() {
        String id = TENANT.get();
        if (id == null) {
            throw new IllegalStateException("No tenant set in current context");
        }
        return id;
    }

    @Override
    public void setTenantId(String tenantId) {
        TENANT.set(tenantId);
    }

    @Override
    public void clear() {
        TENANT.remove();
    }
}
