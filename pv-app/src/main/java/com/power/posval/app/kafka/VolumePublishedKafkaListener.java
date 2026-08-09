package com.power.posval.app.kafka;

import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.event.VolumePublished;
import com.power.posval.kafka.VolumePublishedConsumer;
import com.power.posval.persistence.tenant.ThreadLocalTenantContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka listener for {@code posval.VolumePublished} events.
 *
 * <p>Delegates to {@link VolumePublishedConsumer} which rebuilds
 * S6b trade interval cache for positions referencing the published
 * volume series (FR-052c initial population).
 *
 * <p>Simulator note (D-14): VolumePublished has no tenantId field.
 * Use "default" for the simulator's ThreadLocalTenantContext.
 */
@Component
public class VolumePublishedKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(VolumePublishedKafkaListener.class);

    private final VolumePublishedConsumer volumePublishedConsumer;
    private final TransactionalExecutor txExecutor;
    private final ThreadLocalTenantContext tenantContext;

    public VolumePublishedKafkaListener(VolumePublishedConsumer volumePublishedConsumer,
                                         TransactionalExecutor txExecutor,
                                         ThreadLocalTenantContext tenantContext) {
        this.volumePublishedConsumer = volumePublishedConsumer;
        this.txExecutor = txExecutor;
        this.tenantContext = tenantContext;
    }

    @KafkaListener(
            topics = "posval.VolumePublished",
            containerFactory = "volumePublishedListenerFactory"
    )
    public void onVolumePublished(ConsumerRecord<String, VolumePublished> record,
                                   Acknowledgment ack) {
        VolumePublished event = record.value();
        try {
            // Simulator-scope: VolumePublished has no tenantId; use "default"
            tenantContext.setTenant("default");
            txExecutor.run(() -> volumePublishedConsumer.handle(event));
            ack.acknowledge();
            log.info("Processed VolumePublished series={} range=[{}, {})",
                event.seriesKey(), event.deliveryRange().start(), event.deliveryRange().end());
        } finally {
            tenantContext.clear();
        }
    }
}
