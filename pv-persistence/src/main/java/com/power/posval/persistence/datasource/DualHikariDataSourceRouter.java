package com.power.posval.persistence.datasource;

import com.power.posval.domain.port.datasource.DataSourceRouter;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import javax.sql.DataSource;

/**
 * Dual HikariCP pool implementation of DataSourceRouter.
 * Writer pool → Aurora primary endpoint.
 * Reader pool → Aurora reader endpoint (round-robin across replicas).
 * Pattern #22, §14.4.
 */
public class DualHikariDataSourceRouter implements DataSourceRouter {

    private final DataSource writer;
    private final DataSource reader;

    @Inject
    public DualHikariDataSourceRouter(@Named("writer") DataSource writer,
                                       @Named("reader") DataSource reader) {
        this.writer = writer;
        this.reader = reader;
    }

    @Override
    public DataSource writerDataSource() {
        return writer;
    }

    @Override
    public DataSource readerDataSource() {
        return reader;
    }
}
