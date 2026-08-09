package com.power.posval.app.config;

import com.power.posval.persistence.tenant.ThreadLocalTenantContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TenantConfig {

    @Bean
    public ThreadLocalTenantContext tenantContext() {
        return new ThreadLocalTenantContext();
    }
}
