package com.power.posval.guice;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import com.google.inject.matcher.Matchers;
import com.power.posval.domain.port.tenant.TenantAware;
import com.power.posval.domain.port.tenant.TenantContext;
import com.power.posval.persistence.tenant.ThreadLocalTenantContext;

/**
 * Guice module for tenant isolation. §16.3, Pattern #32.
 * Binds TenantContext port and installs the @TenantAware interceptor.
 */
public class TenantModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(TenantContext.class)
            .to(ThreadLocalTenantContext.class)
            .in(Singleton.class);

        TenantInterceptor interceptor = new TenantInterceptor();
        requestInjection(interceptor);
        bindInterceptor(
            Matchers.any(),
            Matchers.annotatedWith(TenantAware.class),
            interceptor);
    }
}
