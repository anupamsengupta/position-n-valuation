package com.power.posval.app.config;

import com.power.posval.app.provider.SpringEntityManagerProvider;
import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.repository.MarketDataRepository;
import com.power.posval.domain.port.repository.PositionLedgerRepository;
import com.power.posval.domain.port.repository.SettlementCellRepository;
import com.power.posval.domain.port.repository.VolumeSeriesRepository;
import com.power.posval.persistence.adapter.JpaMarketDataRepository;
import com.power.posval.persistence.adapter.JpaPositionLedgerRepository;
import com.power.posval.persistence.adapter.JpaSettlementCellRepository;
import com.power.posval.persistence.adapter.JpaVolumeSeriesRepository;
import com.power.posval.persistence.batch.BatchWriter;
import com.power.posval.persistence.batch.UnitOfWork;
import com.power.posval.persistence.event.OutboxDomainEventPublisher;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.inject.Provider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
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
        return Persistence.createEntityManagerFactory("pv-unit", props);
    }

    @Bean
    public SpringEntityManagerProvider springEntityManagerProvider(EntityManagerFactory emf) {
        return new SpringEntityManagerProvider(emf);
    }

    @Bean
    public Provider<EntityManager> entityManagerProvider(SpringEntityManagerProvider provider) {
        return provider;
    }

    @Bean
    public TransactionalExecutor transactionalExecutor(EntityManagerFactory emf,
                                                        SpringEntityManagerProvider emProvider) {
        return new TransactionalExecutor(emf, emProvider);
    }

    @Bean
    public BatchWriter batchWriter(Provider<EntityManager> emProvider) {
        return new BatchWriter(emProvider);
    }

    @Bean
    public UnitOfWork unitOfWork(Provider<EntityManager> emProvider) {
        return new UnitOfWork(emProvider);
    }

    @Bean
    public JpaMarketDataRepository jpaMarketDataRepository(Provider<EntityManager> emProvider) {
        return new JpaMarketDataRepository(emProvider);
    }

    @Bean
    public MarketDataRepository marketDataRepository(JpaMarketDataRepository repo) {
        return repo;
    }

    @Bean
    public PositionLedgerRepository positionLedgerRepository(Provider<EntityManager> emProvider,
                                                              BatchWriter batchWriter) {
        return new JpaPositionLedgerRepository(emProvider, batchWriter);
    }

    @Bean
    public JpaVolumeSeriesRepository jpaVolumeSeriesRepository(Provider<EntityManager> emProvider,
                                                                BatchWriter batchWriter) {
        return new JpaVolumeSeriesRepository(emProvider, batchWriter);
    }

    @Bean
    public SettlementCellRepository settlementCellRepository(Provider<EntityManager> emProvider,
                                                              BatchWriter batchWriter) {
        return new JpaSettlementCellRepository(emProvider, batchWriter);
    }

    @Bean
    public DomainEventPublisher domainEventPublisher(Provider<EntityManager> emProvider) {
        return new OutboxDomainEventPublisher(emProvider);
    }
}
