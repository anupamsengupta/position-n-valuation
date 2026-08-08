package com.power.posval.app.kafka;

import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.event.MarketDataUpdated;
import com.power.posval.kafka.MarketDataUpdatedConsumer;
import com.power.posval.persistence.tenant.ThreadLocalTenantContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka listener for {@code posval.MarketDataUpdated} events.
 *
 * <p>Delegates to {@link MarketDataUpdatedConsumer} which:
 * <ol>
 *   <li>Invalidates the Redis market data cache for the affected series/range</li>
 *   <li>Uses S8 dependency index (FR-103) to find positions whose price expressions
 *       reference the changed series</li>
 *   <li>Publishes {@code SettlementRevaluationRequested} per affected position</li>
 * </ol>
 */
@Component
public class MarketDataUpdatedKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(MarketDataUpdatedKafkaListener.class);

    private final MarketDataUpdatedConsumer marketDataUpdatedConsumer;
    private final TransactionalExecutor txExecutor;
    private final ThreadLocalTenantContext tenantContext;

    public MarketDataUpdatedKafkaListener(MarketDataUpdatedConsumer marketDataUpdatedConsumer,
                                           TransactionalExecutor txExecutor,
                                           ThreadLocalTenantContext tenantContext) {
        this.marketDataUpdatedConsumer = marketDataUpdatedConsumer;
        this.txExecutor = txExecutor;
        this.tenantContext = tenantContext;
    }

    @KafkaListener(
            topics = "posval.MarketDataUpdated",
            containerFactory = "marketDataUpdatedListenerFactory"
    )
    public void onMarketDataUpdated(ConsumerRecord<String, MarketDataUpdated> record,
                                     Acknowledgment ack) {
        MarketDataUpdated event = record.value();
        try {
            tenantContext.setTenant(event.tenantId());
            txExecutor.run(() -> marketDataUpdatedConsumer.handle(event));
            ack.acknowledge();
            log.info("Processed MarketDataUpdated series={} range=[{}, {})",
                event.series(), event.affectedRangeStart(), event.affectedRangeEnd());
        } finally {
            tenantContext.clear();
        }
    }
}
