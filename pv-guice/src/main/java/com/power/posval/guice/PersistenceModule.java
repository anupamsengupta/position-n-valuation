package com.power.posval.guice;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import com.google.inject.name.Names;
import com.power.posval.domain.port.cache.TradeIntervalCache;
import com.power.posval.domain.port.datasource.DataSourceRouter;
import com.power.posval.domain.port.repository.*;
import com.power.posval.persistence.adapter.*;
import com.power.posval.persistence.batch.BatchWriter;
import com.power.posval.persistence.batch.UnitOfWork;
import com.power.posval.persistence.datasource.DualHikariDataSourceRouter;
import com.power.posval.persistence.provider.EntityManagerFactoryProvider;
import com.power.posval.persistence.provider.EntityManagerProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

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

        bind(StruckMarkRepository.class)
            .to(JpaStruckMarkRepository.class)
            .in(Singleton.class);

        bind(TradeIntervalCache.class)
            .to(JpaTradeIntervalCache.class)
            .in(Singleton.class);

        // --- DA Exchange Spot repository adapters (S9.2) ---

        // AuctionImportSessionRepository → JpaAuctionImportSessionRepository. S5.1, S9.2.
        bind(AuctionImportSessionRepository.class)
            .to(JpaAuctionImportSessionRepository.class)
            .in(Singleton.class);

        // NominationRepository → JpaNominationRepository. DA-VOL-03, S9.2.
        bind(NominationRepository.class)
            .to(JpaNominationRepository.class)
            .in(Singleton.class);

        // ImbalanceRecordRepository → JpaImbalanceRecordRepository. DA-SET-04, S9.2.
        bind(ImbalanceRecordRepository.class)
            .to(JpaImbalanceRecordRepository.class)
            .in(Singleton.class);

        // OperationalAlertRepository → JpaOperationalAlertRepository. DA-OPS-01, S9.2.
        bind(OperationalAlertRepository.class)
            .to(JpaOperationalAlertRepository.class)
            .in(Singleton.class);

        // ExchangeFeeScheduleRepository → JpaExchangeFeeScheduleRepository. DA-SET-03, S9.2.
        bind(ExchangeFeeScheduleRepository.class)
            .to(JpaExchangeFeeScheduleRepository.class)
            .in(Singleton.class);

        // HolidayCalendarRepository → JpaHolidayCalendarRepository. DA-VOL-02, S9.2.
        // System-level; no tenant_id (S5.1, S7.1).
        bind(HolidayCalendarRepository.class)
            .to(JpaHolidayCalendarRepository.class)
            .in(Singleton.class);

        // BlockDefinitionRepository → JpaBlockDefinitionRepository. DA-VOL-02, S9.2.
        // System-level reference data; no tenant_id.
        bind(BlockDefinitionRepository.class)
            .to(JpaBlockDefinitionRepository.class)
            .in(Singleton.class);

        // BalancingGroupRepository → JpaBalancingGroupRepository. DA-VOL-03, S9.2.
        bind(BalancingGroupRepository.class)
            .to(JpaBalancingGroupRepository.class)
            .in(Singleton.class);

        // TARGET2CalendarRepository → JpaTargetCalendarRepository. DA-SET-02, S9.2.
        // System-level banking calendar; no tenant_id.
        bind(TARGET2CalendarRepository.class)
            .to(JpaTargetCalendarRepository.class)
            .in(Singleton.class);

        // Infrastructure
        bind(EntityManagerFactory.class)
            .toProvider(EntityManagerFactoryProvider.class)
            .in(Singleton.class);

        bind(EntityManager.class)
            .toProvider(EntityManagerProvider.class);

        bindConstant().annotatedWith(Names.named("pv.batch.size"))
            .to(50);
        bind(BatchWriter.class).in(Singleton.class);
        bind(UnitOfWork.class).in(Singleton.class);
    }
}
