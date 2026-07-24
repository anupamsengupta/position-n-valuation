package com.power.posval.domain.port.tenant;

/**
 * Port for tenant context propagation. Pattern #32, FR-120/FR-122.
 * Implementation in pv-guice via TenantInterceptor.
 */
public interface TenantContext {

    String currentTenantId();

    void setTenantId(String tenantId);

    void clear();
}
