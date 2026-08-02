package com.power.posval.app.config;

import com.power.posval.domain.port.tenant.TenantContext;
import com.power.posval.persistence.tenant.ThreadLocalTenantContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TenantConfig {

    @Bean
    public ThreadLocalTenantContext threadLocalTenantContext() {
        return new ThreadLocalTenantContext();
    }

    @Bean
    public TenantContext tenantContext(ThreadLocalTenantContext ctx) {
        return ctx;
    }
}
