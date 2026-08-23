package com.power.posval.app.config;

import com.google.inject.Injector;
import com.power.posval.domain.port.service.AuctionFolderPoller;
import com.power.posval.domain.port.service.AuctionImportService;
import com.power.posval.domain.port.service.DaQueryService;
import com.power.posval.domain.port.service.ExchangeFeeService;
import com.power.posval.domain.port.service.ImbalanceSettlementService;
import com.power.posval.domain.port.service.NominationService;
import com.power.posval.domain.port.service.OperationalAlertService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring {@link Configuration} that exposes DA (Day-Ahead) Exchange Spot services as
 * Spring beans by delegating to the Guice {@link Injector}.
 *
 * <p>D-13: All {@code @Bean} methods call {@code injector.getInstance()} and do NOT
 * construct domain classes with {@code new}. Guice remains the canonical DI container;
 * Spring merely bridges instances into the ApplicationContext so that
 * {@code @RestController} and Spring Kafka listeners can consume them.
 *
 * <p>All DA service ports are bound in {@code DaExchangeModule} (installed by
 * {@code DomainModule} in pv-guice). Repository adapter bindings are in
 * {@code ConfigModule} (S9.2, D-13).
 *
 * <p>S9.4, S16.2.2, D-13, D-14.
 */
@Configuration
public class DaServiceConfig {

    /**
     * Exposes {@link AuctionImportService} (bound to {@code DefaultAuctionImportOrchestrator}).
     * S9.1, S9.4.
     */
    @Bean
    public AuctionImportService auctionImportService(Injector injector) {
        return injector.getInstance(AuctionImportService.class);
    }

    /**
     * Exposes {@link NominationService} (bound to {@code DefaultNominationService}).
     * DA-VOL-03, S9.4.
     */
    @Bean
    public NominationService nominationService(Injector injector) {
        return injector.getInstance(NominationService.class);
    }

    /**
     * Exposes {@link ImbalanceSettlementService} (bound to {@code DefaultImbalanceSettlementService}).
     * DA-SET-04, S9.4.
     */
    @Bean
    public ImbalanceSettlementService imbalanceSettlementService(Injector injector) {
        return injector.getInstance(ImbalanceSettlementService.class);
    }

    /**
     * Exposes {@link ExchangeFeeService} (bound to {@code DefaultExchangeFeeService}).
     * DA-SET-03, S9.4.
     */
    @Bean
    public ExchangeFeeService exchangeFeeService(Injector injector) {
        return injector.getInstance(ExchangeFeeService.class);
    }

    /**
     * Exposes {@link OperationalAlertService} (bound to {@code DefaultOperationalAlertService}).
     * DA-OPS-01, S9.4.
     */
    @Bean
    public OperationalAlertService operationalAlertService(Injector injector) {
        return injector.getInstance(OperationalAlertService.class);
    }

    /**
     * Exposes {@link AuctionFolderPoller} (bound to {@code DefaultAuctionFolderPoller}).
     * Used by {@code DaPollerConfig} for {@code @Scheduled} invocation. S9.4, S6.3.
     */
    @Bean
    public AuctionFolderPoller auctionFolderPoller(Injector injector) {
        return injector.getInstance(AuctionFolderPoller.class);
    }

    /**
     * Exposes {@link DaQueryService} (bound to {@code DefaultDaQueryService}).
     * Read-side DA query facade for dashboard views. S16.2.2, DA-UI-02 through DA-UI-07.
     */
    @Bean
    public DaQueryService daQueryService(Injector injector) {
        return injector.getInstance(DaQueryService.class);
    }
}
