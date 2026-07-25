package com.power.posval.persistence.provider;

import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import javax.sql.DataSource;

/**
 * Guice provider for the writer (primary) DataSource.
 * Creates a HikariCP pool from system properties.
 */
@Singleton
public class WriterDataSourceProvider implements Provider<DataSource> {

    @Override
    public DataSource get() {
        var config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl(System.getProperty("pv.writer.jdbc.url",
            "jdbc:postgresql://localhost:5432/posval"));
        config.setUsername(System.getProperty("pv.writer.jdbc.user", "posval"));
        config.setPassword(System.getProperty("pv.writer.jdbc.password", "posval"));
        config.setMaximumPoolSize(Integer.getInteger("pv.writer.pool.max", 10));
        config.setPoolName("pv-writer");
        return new com.zaxxer.hikari.HikariDataSource(config);
    }
}
