package com.power.posval.guice;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.power.posval.domain.port.cache.TradeIntervalCache;
import com.power.posval.domain.port.datasource.DataSourceRouter;
import com.power.posval.domain.port.repository.*;
import com.power.posval.persistence.adapter.*;
import com.power.posval.persistence.batch.BatchWriter;
import com.power.posval.persistence.batch.UnitOfWork;
import com.power.posval.persistence.datasource.DualHikariDataSourceRouter;

/**
 * Guice module for JPA persistence bindings. §16.2.
 */
public class PersistenceModule extends AbstractModule {

    @Override
    protected void configure() {
        // DataSource routing
        bind(DataSourceRouter.class)
            .to(DualHikariDataSourceRouter.class)
            .in(Singleton.class);

        // Repository adapters
        bind(PositionLedgerRepository.class)
            .to(JpaPositionLedgerRepository.class)
            .in(Singleton.class);

        bind(VolumeSeriesRepository.class)
            .to(JpaVolumeSeriesRepository.class)
            .in(Singleton.class);

        bind(SettlementCellRepository.class)
            .to(JpaSettlementCellRepository.class)
            .in(Singleton.class);

        bind(RollupRepository.class)
            .to(JpaRollupRepository.class)
            .in(Singleton.class);

        bind(DependencyIndex.class)
            .to(JpaDependencyIndex.class)
            .in(Singleton.class);

        bind(TradeIntervalCache.class)
            .to(JpaTradeIntervalCache.class)
            .in(Singleton.class);

        // Infrastructure
        bind(BatchWriter.class).in(Singleton.class);
        bind(UnitOfWork.class).in(Singleton.class);
    }
}
