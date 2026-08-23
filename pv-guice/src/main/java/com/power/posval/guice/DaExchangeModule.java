package com.power.posval.guice;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import com.power.posval.domain.port.AuctionResultParser;
import com.power.posval.domain.port.service.AuctionFolderPoller;
import com.power.posval.domain.port.service.AuctionImportService;
import com.power.posval.domain.port.service.BlockDecompositionService;
import com.power.posval.domain.port.service.DaQueryService;
import com.power.posval.domain.port.service.ExchangeFeeService;
import com.power.posval.domain.port.service.ImbalanceSettlementService;
import com.power.posval.domain.port.service.InstrumentCalculationStrategy;
import com.power.posval.domain.port.service.NominationService;
import com.power.posval.domain.port.service.OperationalAlertService;
import com.power.posval.domain.port.service.PaymentDateService;
import com.power.posval.domain.service.da.DefaultAuctionFolderPoller;
import com.power.posval.domain.service.da.DefaultAuctionImportOrchestrator;
import com.power.posval.domain.service.da.DefaultBlockDecompositionService;
import com.power.posval.domain.service.da.DefaultDaQueryService;
import com.power.posval.domain.service.da.DefaultExchangeFeeService;
import com.power.posval.domain.service.da.DefaultImbalanceSettlementService;
import com.power.posval.domain.service.da.DefaultNominationService;
import com.power.posval.domain.service.da.DefaultOperationalAlertService;
import com.power.posval.domain.service.da.DefaultPaymentDateService;
import com.power.posval.domain.service.instrument.DaExchangeSpotStrategy;
import com.power.posval.domain.service.instrument.InstrumentStrategyRegistry;
import com.power.posval.persistence.adapter.EpexCsvAuctionResultParser;

/**
 * Guice module for DA (Day-Ahead) Exchange Spot feature bindings.
 *
 * <p>Binds all DA domain service interfaces to their default implementations
 * (S9.1) and wires the {@link InstrumentCalculationStrategy} multibinder
 * for the strategy registry (S9b.6).
 *
 * <p>Installed by {@link DomainModule}. All bindings are Spring-free (D-13).
 * The {@link MarketCalendarPort} is bound to {@link StubMarketCalendarAdapter}
 * until a production adapter is delivered (A-1, S9.3).
 *
 * <p>Pattern #18 (Port + Adapter), Pattern #10 (Strategy), S9.1, S9b.6.
 */
public class DaExchangeModule extends AbstractModule {

    @Override
    protected void configure() {

        // --- DA domain service bindings (S9.1) ---

        // AuctionImportService → DefaultAuctionImportOrchestrator. S5.2, S9.1.
        bind(AuctionImportService.class)
            .to(DefaultAuctionImportOrchestrator.class)
            .in(Singleton.class);

        // BlockDecompositionService → DefaultBlockDecompositionService. DA-VOL-02, S9.1.
        bind(BlockDecompositionService.class)
            .to(DefaultBlockDecompositionService.class)
            .in(Singleton.class);

        // NominationService → DefaultNominationService. DA-VOL-03, S9.1.
        bind(NominationService.class)
            .to(DefaultNominationService.class)
            .in(Singleton.class);

        // ImbalanceSettlementService → DefaultImbalanceSettlementService. DA-SET-04, S9.1.
        bind(ImbalanceSettlementService.class)
            .to(DefaultImbalanceSettlementService.class)
            .in(Singleton.class);

        // ExchangeFeeService → DefaultExchangeFeeService. DA-SET-03, S9.1.
        bind(ExchangeFeeService.class)
            .to(DefaultExchangeFeeService.class)
            .in(Singleton.class);

        // OperationalAlertService → DefaultOperationalAlertService. DA-OPS-01, S9.1.
        bind(OperationalAlertService.class)
            .to(DefaultOperationalAlertService.class)
            .in(Singleton.class);

        // PaymentDateService → DefaultPaymentDateService. DA-SET-02, S9.1.
        bind(PaymentDateService.class)
            .to(DefaultPaymentDateService.class)
            .in(Singleton.class);

        // AuctionResultParser → EpexCsvAuctionResultParser. S6.2, S9.1.
        bind(AuctionResultParser.class)
            .to(EpexCsvAuctionResultParser.class)
            .in(Singleton.class);

        // AuctionFolderPoller → DefaultAuctionFolderPoller. S6.3, S9.1.
        // Scheduling is the host's responsibility (pv-app: @Scheduled; production: cron/ECS).
        bind(AuctionFolderPoller.class)
            .to(DefaultAuctionFolderPoller.class)
            .in(Singleton.class);

        // DaQueryService → DefaultDaQueryService. Read-side DA query facade. S16.2.2.
        bind(DaQueryService.class)
            .to(DefaultDaQueryService.class)
            .in(Singleton.class);

        // MarketCalendarPort: NOT bound here. The host is responsible for binding
        // MarketCalendarPort to an appropriate adapter (A-1, S9.3):
        //   - pv-app (simulator): StubMarketCalendarAdapter in ConfigModule
        //   - production host: real MarketCalendar adapter with HolidayCalendar support
        // Binding it here would violate D-14 (simulator pattern in library wiring).

        // --- InstrumentCalculationStrategy multibinder (S9b.6, Pattern #10) ---
        // DaExchangeSpotStrategy handles DA exchange spot post-settlement logic.
        // Future instruments (IntraDayStrategy, PpaStrategy, ForwardStrategy) add one
        // addBinding() line here — no other code changes required (S9b.10).
        Multibinder<InstrumentCalculationStrategy> strategyBinder =
            Multibinder.newSetBinder(binder(), InstrumentCalculationStrategy.class);
        strategyBinder.addBinding().to(DaExchangeSpotStrategy.class).in(Singleton.class);

        // InstrumentStrategyRegistry is injected with the Set<InstrumentCalculationStrategy>
        // populated by the multibinder above. S9b.5.
        bind(InstrumentStrategyRegistry.class).in(Singleton.class);
    }
}
