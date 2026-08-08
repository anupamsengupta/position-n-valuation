package com.power.posval.app.config;

import com.power.posval.app.provider.SpringEntityManagerProvider;
import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import com.power.posval.domain.port.repository.SettlementCellRepository;
import com.power.posval.persistence.adapter.JpaDependencyIndex;
import com.power.posval.persistence.adapter.JpaMarketDataRepository;
import com.power.posval.persistence.adapter.JpaPositionLedgerRepository;
import com.power.posval.persistence.adapter.JpaRollupRepository;
import com.power.posval.persistence.adapter.JpaSettlementCellRepository;
import com.power.posval.persistence.adapter.JpaVolumeSeriesRepository;
import com.power.posval.persistence.batch.BatchWriter;
import com.power.posval.persistence.batch.UnitOfWork;
import com.power.posval.persistence.event.OutboxDomainEventPublisher;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class PersistenceConfig {

    @Value("${pv.datasource.url}")
    private String jdbcUrl;

    @Value("${pv.datasource.username}")
    private String username;

    @Value("${pv.datasource.password}")
    private String password;

    @Value("${pv.datasource.pool-size:10}")
    private int poolSize;

    @Bean
    public DataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setDriverClassName("org.postgresql.Driver");
        return new HikariDataSource(config);
    }

    @Bean(destroyMethod = "close")
    public EntityManagerFactory entityManagerFactory(DataSource dataSource) {
        Map<String, Object> props = new HashMap<>();
        props.put("jakarta.persistence.nonJtaDataSource", dataSource);
        return new HibernatePersistenceProvider()
                .createEntityManagerFactory("pv-unit", props);
    }

    @Bean
    public SpringEntityManagerProvider entityManagerProvider(EntityManagerFactory emf) {
        return new SpringEntityManagerProvider(emf);
    }

    @Bean
    public TransactionalExecutor transactionalExecutor(EntityManagerFactory emf,
                                                        SpringEntityManagerProvider emProvider) {
        return new TransactionalExecutor(emf, emProvider);
    }

    @Bean
    public BatchWriter batchWriter(SpringEntityManagerProvider emProvider) {
        return new BatchWriter(emProvider);
    }

    @Bean
    public UnitOfWork unitOfWork(SpringEntityManagerProvider emProvider) {
        return new UnitOfWork(emProvider);
    }

    @Bean
    public JpaMarketDataRepository marketDataRepository(SpringEntityManagerProvider emProvider) {
        return new JpaMarketDataRepository(emProvider);
    }

    @Bean
    public PositionLedgerRepository positionLedgerRepository(SpringEntityManagerProvider emProvider,
                                                              BatchWriter batchWriter) {
        return new JpaPositionLedgerRepository(emProvider, batchWriter);
    }

    @Bean
    public JpaVolumeSeriesRepository jpaVolumeSeriesRepository(SpringEntityManagerProvider emProvider,
                                                                BatchWriter batchWriter) {
        return new JpaVolumeSeriesRepository(emProvider, batchWriter);
    }

    @Bean
    public SettlementCellRepository settlementCellRepository(SpringEntityManagerProvider emProvider,
                                                              BatchWriter batchWriter) {
        return new JpaSettlementCellRepository(emProvider, batchWriter);
    }

    @Bean
    public JpaRollupRepository rollupRepository(SpringEntityManagerProvider emProvider) {
        return new JpaRollupRepository(emProvider);
    }

    @Bean
    public JpaDependencyIndex dependencyIndex(SpringEntityManagerProvider emProvider) {
        return new JpaDependencyIndex(emProvider);
    }

    @Bean
    public DomainEventPublisher domainEventPublisher(SpringEntityManagerProvider emProvider) {
        return new OutboxDomainEventPublisher(emProvider);
    }
}
