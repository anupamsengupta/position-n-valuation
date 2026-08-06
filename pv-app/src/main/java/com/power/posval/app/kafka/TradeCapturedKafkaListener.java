package com.power.posval.app.kafka;

import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.event.PositionCaptured;
import com.power.posval.kafka.TradeCapturedConsumer;
import com.power.posval.persistence.tenant.ThreadLocalTenantContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka listener for {@code posval.PositionCaptured} events.
 *
 * <p>Uses Spring {@code @KafkaListener} with:
 * <ul>
 *   <li>{@code AckMode.MANUAL_IMMEDIATE} — commit only after TX success</li>
 *   <li>{@code DefaultErrorHandler} with exponential backoff + DLQ</li>
 *   <li>Typed {@code JsonDeserializer} — deserialization errors go straight to DLQ</li>
 * </ul>
 *
 * <p>Exceptions propagate to the container error handler — no swallowing.
 * Idempotency is handled by {@link TradeCapturedConsumer}.
 */
@Component
public class TradeCapturedKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(TradeCapturedKafkaListener.class);

    private final TradeCapturedConsumer tradeCapturedConsumer;
    private final TransactionalExecutor txExecutor;
    private final ThreadLocalTenantContext tenantContext;

    public TradeCapturedKafkaListener(TradeCapturedConsumer tradeCapturedConsumer,
                                       TransactionalExecutor txExecutor,
                                       ThreadLocalTenantContext tenantContext) {
        this.tradeCapturedConsumer = tradeCapturedConsumer;
        this.txExecutor = txExecutor;
        this.tenantContext = tenantContext;
    }

    @KafkaListener(
            topics = "posval.PositionCaptured",
            containerFactory = "positionCapturedListenerFactory"
    )
    public void onPositionCaptured(ConsumerRecord<String, PositionCaptured> record,
                                    Acknowledgment ack) {
        PositionCaptured event = record.value();
        try {
            tenantContext.setTenant(event.tenantId());
            txExecutor.run(() -> tradeCapturedConsumer.handle(event));
            ack.acknowledge();
            log.info("Processed PositionCaptured for trade={} leg={} version={}",
                    event.tradeId(), event.tradeLegId(), event.tradeVersion());
        } finally {
            tenantContext.clear();
        }
    }
}
