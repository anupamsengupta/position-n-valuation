package com.power.posval.app.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.power.posval.app.provider.TransactionalExecutor;
import com.power.posval.domain.event.PositionCaptured;
import com.power.posval.kafka.TradeCapturedConsumer;
import com.power.posval.persistence.tenant.ThreadLocalTenantContext;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class TradeCapturedKafkaListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TradeCapturedKafkaListener.class);
    private static final String TOPIC = "posval.PositionCaptured";

    private final KafkaConsumer<String, String> consumer;
    private final TradeCapturedConsumer tradeCapturedConsumer;
    private final TransactionalExecutor txExecutor;
    private final ThreadLocalTenantContext tenantContext;
    private final ObjectMapper objectMapper;

    private volatile boolean running;
    private Thread pollingThread;

    public TradeCapturedKafkaListener(KafkaConsumer<String, String> consumer,
                                       TradeCapturedConsumer tradeCapturedConsumer,
                                       TransactionalExecutor txExecutor,
                                       ThreadLocalTenantContext tenantContext,
                                       ObjectMapper objectMapper) {
        this.consumer = consumer;
        this.tradeCapturedConsumer = tradeCapturedConsumer;
        this.txExecutor = txExecutor;
        this.tenantContext = tenantContext;
        this.objectMapper = objectMapper;
    }

    @Override
    public void start() {
        running = true;
        pollingThread = new Thread(this::pollLoop, "kafka-trade-captured-poller");
        pollingThread.setDaemon(true);
        pollingThread.start();
        log.info("Started Kafka listener for topic: {}", TOPIC);
    }

    @Override
    public void stop() {
        running = false;
        consumer.wakeup();
        if (pollingThread != null) {
            pollingThread.interrupt();
        }
        log.info("Stopped Kafka listener for topic: {}", TOPIC);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void pollLoop() {
        consumer.subscribe(List.of(TOPIC));
        while (running) {
            try {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
                records.forEach(record -> {
                    try {
                        PositionCaptured event = deserialize(record.value());
                        tenantContext.setTenant(event.tenantId());
                        txExecutor.run(() -> tradeCapturedConsumer.handle(event));
                        log.debug("Processed PositionCaptured for trade={} version={}",
                                event.tradeId(), event.tradeVersion());
                    } catch (Exception e) {
                        log.error("Failed to process PositionCaptured message: {}", e.getMessage(), e);
                    } finally {
                        tenantContext.clear();
                    }
                });
            } catch (org.apache.kafka.common.errors.WakeupException e) {
                if (!running) break;
            } catch (Exception e) {
                log.error("Kafka poll loop error: {}", e.getMessage(), e);
            }
        }
    }

    private PositionCaptured deserialize(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return new PositionCaptured(
                    node.get("tenantId").asText(),
                    node.get("tradeId").asText(),
                    node.get("tradeLegId").asText(),
                    node.get("tradeVersion").asInt(),
                    node.get("entryCount").asInt(),
                    Instant.parse(node.get("eventTime").asText())
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize PositionCaptured: " + e.getMessage(), e);
        }
    }
}
