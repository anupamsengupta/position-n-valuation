package com.power.posval.app.kafka;

import com.power.posval.app.event.DashboardDataChangedEvent;
import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.event.PositionEntryCaptured;
import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.kafka.TradeCapturedConsumer;
import com.power.posval.persistence.tenant.ThreadLocalTenantContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Kafka listener for {@code posval.PositionEntryCaptured} events.
 *
 * <p>Each event carries a single {@code positionId}. With multiple partitions
 * and {@code concurrency > 1}, entries from the same trade are settled in
 * parallel across consumer threads.
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
    private final ApplicationEventPublisher eventPublisher;

    public TradeCapturedKafkaListener(TradeCapturedConsumer tradeCapturedConsumer,
                                       TransactionalExecutor txExecutor,
                                       ThreadLocalTenantContext tenantContext,
                                       ApplicationEventPublisher eventPublisher) {
        this.tradeCapturedConsumer = tradeCapturedConsumer;
        this.txExecutor = txExecutor;
        this.tenantContext = tenantContext;
        this.eventPublisher = eventPublisher;
    }

    @KafkaListener(
            topics = "posval.PositionEntryCaptured",
            containerFactory = "positionEntryCapturedListenerFactory"
    )
    public void onPositionEntryCaptured(ConsumerRecord<String, PositionEntryCaptured> record,
                                         Acknowledgment ack) {
        PositionEntryCaptured event = record.value();
        String tenantId = event.tenantId();
        try {
            tenantContext.setTenant(tenantId);

            // TX-0: idempotency check + load entry (read-only scope)
            Optional<PositionLedgerEntry> entryOpt = txExecutor.execute(
                () -> tradeCapturedConsumer.prepareIfNeeded(event));

            if (entryOpt.isEmpty()) {
                ack.acknowledge();
                return;
            }
            PositionLedgerEntry entry = entryOpt.get();

            // Fork S5a + S6b in parallel, each with own transaction + tenant context
            CompletableFuture<Void> settlement = CompletableFuture.runAsync(() -> {
                tenantContext.setTenant(tenantId);
                try {
                    txExecutor.run(() -> tradeCapturedConsumer.materializeSettlement(entry));
                } finally {
                    tenantContext.clear();
                }
            });
            CompletableFuture<Void> cache = CompletableFuture.runAsync(() -> {
                tenantContext.setTenant(tenantId);
                try {
                    txExecutor.run(() -> tradeCapturedConsumer.populateCache(entry));
                } finally {
                    tenantContext.clear();
                }
            });

            // Wait for both — propagates first failure
            CompletableFuture.allOf(settlement, cache).join();

            ack.acknowledge();
            eventPublisher.publishEvent(new DashboardDataChangedEvent(
                    this, tenantId, DashboardDataChangedEvent.ChangeType.POSITION_CAPTURED, null));
            log.info("Processed PositionEntryCaptured positionId={}", event.positionId());
        } finally {
            tenantContext.clear();
        }
    }
}
