package com.power.posval.app.kafka;

import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.event.VolumeSuperseded;
import com.power.posval.kafka.VolumeSupersededConsumer;
import com.power.posval.persistence.tenant.ThreadLocalTenantContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka listener for {@code posval.VolumeSuperseded} events.
 *
 * <p>Delegates to {@link VolumeSupersededConsumer} which:
 * <ol>
 *   <li>Invalidates volume cache for the affected series/range</li>
 *   <li>Rebuilds S6b trade interval cache for affected positions (FR-086b)</li>
 *   <li>Publishes {@code SettlementRevaluationRequested} per affected position</li>
 * </ol>
 *
 * <p>Simulator note (D-14): VolumeSuperseded has no tenantId field.
 * The consumer derives real tenantId from position entries. We set "default"
 * here for the simulator's ThreadLocalTenantContext.
 */
@Component
public class VolumeSupersededKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(VolumeSupersededKafkaListener.class);

    private final VolumeSupersededConsumer volumeSupersededConsumer;
    private final TransactionalExecutor txExecutor;
    private final ThreadLocalTenantContext tenantContext;

    public VolumeSupersededKafkaListener(VolumeSupersededConsumer volumeSupersededConsumer,
                                          TransactionalExecutor txExecutor,
                                          ThreadLocalTenantContext tenantContext) {
        this.volumeSupersededConsumer = volumeSupersededConsumer;
        this.txExecutor = txExecutor;
        this.tenantContext = tenantContext;
    }

    @KafkaListener(
            topics = "posval.VolumeSuperseded",
            containerFactory = "volumeSupersededListenerFactory"
    )
    public void onVolumeSuperseded(ConsumerRecord<String, VolumeSuperseded> record,
                                    Acknowledgment ack) {
        VolumeSuperseded event = record.value();
        try {
            // Simulator-scope: VolumeSuperseded has no tenantId; use "default"
            tenantContext.setTenant("default");
            txExecutor.run(() -> volumeSupersededConsumer.handle(event));
            ack.acknowledge();
            log.info("Processed VolumeSuperseded series={} range=[{}, {})",
                event.seriesKey(), event.affectedRange().start(), event.affectedRange().end());
        } finally {
            tenantContext.clear();
        }
    }
}
