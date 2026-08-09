package com.power.posval.app.kafka;

import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.event.SettlementRevaluationRequested;
import com.power.posval.kafka.SettlementRevaluationConsumer;
import com.power.posval.persistence.tenant.ThreadLocalTenantContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka listener for {@code posval.SettlementRevaluationRequested} events.
 * Triggers interval-level settlement revaluation for a single position.
 *
 * <p>Uses the same error handling and DLQ pattern as
 * {@link TradeCapturedKafkaListener}.
 */
@Component
public class SettlementRevaluationKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(SettlementRevaluationKafkaListener.class);

    private final SettlementRevaluationConsumer consumer;
    private final TransactionalExecutor txExecutor;
    private final ThreadLocalTenantContext tenantContext;

    public SettlementRevaluationKafkaListener(SettlementRevaluationConsumer consumer,
                                               TransactionalExecutor txExecutor,
                                               ThreadLocalTenantContext tenantContext) {
        this.consumer = consumer;
        this.txExecutor = txExecutor;
        this.tenantContext = tenantContext;
    }

    @KafkaListener(
            topics = "posval.SettlementRevaluationRequested",
            containerFactory = "revaluationListenerFactory"
    )
    public void onRevaluationRequested(ConsumerRecord<String, SettlementRevaluationRequested> record,
                                        Acknowledgment ack) {
        SettlementRevaluationRequested event = record.value();
        try {
            tenantContext.setTenant(event.tenantId());
            txExecutor.run(() -> consumer.handle(event));
            ack.acknowledge();
            log.info("Processed SettlementRevaluationRequested positionId={} [{}, {})",
                event.positionId(), event.intervalStart(), event.intervalEnd());
        } finally {
            tenantContext.clear();
        }
    }
}
