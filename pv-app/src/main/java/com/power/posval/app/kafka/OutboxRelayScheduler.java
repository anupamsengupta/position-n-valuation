package com.power.posval.app.kafka;

import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.kafka.OutboxRelayProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelayProducer relayProducer;
    private final TransactionalExecutor txExecutor;

    public OutboxRelayScheduler(OutboxRelayProducer relayProducer,
                                 TransactionalExecutor txExecutor) {
        this.relayProducer = relayProducer;
        this.txExecutor = txExecutor;
    }

    @Scheduled(fixedRateString = "${pv.outbox.relay-interval-ms:500}")
    public void relay() {
        try {
            int count = txExecutor.execute(relayProducer::relay);
            if (count > 0) {
                log.info("Outbox relay published {} events to Kafka", count);
            }
        } catch (Exception e) {
            log.warn("Outbox relay cycle failed: {}", e.getMessage());
        }
    }
}
