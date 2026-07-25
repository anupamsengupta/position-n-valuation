package com.power.posval.persistence.provider;

import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import javax.sql.DataSource;

/**
 * Guice provider for the reader (replica) DataSource.
 * Creates a HikariCP pool from system properties.
 */
@Singleton
public class ReaderDataSourceProvider implements Provider<DataSource> {

    @Override
    public DataSource get() {
        var config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl(System.getProperty("pv.reader.jdbc.url",
            "jdbc:postgresql://localhost:5432/posval"));
        config.setUsername(System.getProperty("pv.reader.jdbc.user", "posval"));
        config.setPassword(System.getProperty("pv.reader.jdbc.password", "posval"));
        config.setMaximumPoolSize(Integer.getInteger("pv.reader.pool.max", 10));
        config.setPoolName("pv-reader");
        config.setReadOnly(true);
        return new com.zaxxer.hikari.HikariDataSource(config);
    }
}
