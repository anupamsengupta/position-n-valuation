package com.power.posval.app.tenant;

import com.power.posval.persistence.tenant.ThreadLocalTenantContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Order(1)
public class TenantFilter implements Filter {

    private static final String TENANT_HEADER = "X-Tenant-Id";
    private static final String DEFAULT_TENANT = "default";

    private final ThreadLocalTenantContext tenantContext;

    public TenantFilter(ThreadLocalTenantContext tenantContext) {
        this.tenantContext = tenantContext;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            String tenantId = DEFAULT_TENANT;
            if (request instanceof HttpServletRequest httpReq) {
                String header = httpReq.getHeader(TENANT_HEADER);
                if (header != null && !header.isBlank()) {
                    tenantId = header.trim();
                }
            }
            tenantContext.setTenant(tenantId);
            chain.doFilter(request, response);
        } finally {
            tenantContext.clear();
        }
    }
}
