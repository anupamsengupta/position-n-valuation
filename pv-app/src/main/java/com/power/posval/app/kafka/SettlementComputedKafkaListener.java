package com.power.posval.app.kafka;

import com.power.posval.app.event.DashboardDataChangedEvent;
import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.event.SettlementComputed;
import com.power.posval.kafka.SettlementPublishedConsumer;
import com.power.posval.persistence.tenant.ThreadLocalTenantContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka listener for {@code posval.SettlementComputed} events.
 *
 * <p>Delegates to {@link SettlementPublishedConsumer} which triggers S7 rollup
 * materialization for the affected position. Rollup aggregation is idempotent
 * (re-derive-from-source), so duplicate events are safe.
 *
 * <p>Uses the standard simulator listener pattern:
 * <ul>
 *   <li>Set tenant context from event</li>
 *   <li>Run consumer in a transaction</li>
 *   <li>Ack only after TX success</li>
 * </ul>
 */
@Component
public class SettlementComputedKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(SettlementComputedKafkaListener.class);

    private final SettlementPublishedConsumer settlementPublishedConsumer;
    private final TransactionalExecutor txExecutor;
    private final ThreadLocalTenantContext tenantContext;
    private final ApplicationEventPublisher eventPublisher;

    public SettlementComputedKafkaListener(SettlementPublishedConsumer settlementPublishedConsumer,
                                            TransactionalExecutor txExecutor,
                                            ThreadLocalTenantContext tenantContext,
                                            ApplicationEventPublisher eventPublisher) {
        this.settlementPublishedConsumer = settlementPublishedConsumer;
        this.txExecutor = txExecutor;
        this.tenantContext = tenantContext;
        this.eventPublisher = eventPublisher;
    }

    @KafkaListener(
            topics = "posval.SettlementComputed",
            containerFactory = "settlementComputedListenerFactory"
    )
    public void onSettlementComputed(ConsumerRecord<String, SettlementComputed> record,
                                      Acknowledgment ack) {
        SettlementComputed event = record.value();
        try {
            tenantContext.setTenant(event.tenantId());
            txExecutor.run(() -> settlementPublishedConsumer.handle(event));
            ack.acknowledge();
            eventPublisher.publishEvent(new DashboardDataChangedEvent(
                    this, event.tenantId(), DashboardDataChangedEvent.ChangeType.SETTLEMENT_COMPUTED, null));
            log.info("Processed SettlementComputed positionId={} range=[{}, {})",
                event.positionId(), event.intervalStart(), event.intervalEnd());
        } finally {
            tenantContext.clear();
        }
    }
}
