package com.power.posval.domain.service;

import com.power.posval.domain.model.PositionLedgerEntry;
import com.power.posval.domain.model.VolumeUnit;
import com.power.posval.domain.model.value.DeliveryPeriod;
import com.power.posval.domain.model.value.DeliveryRange;
import com.power.posval.domain.model.value.SeriesKey;
import com.power.posval.domain.port.marketdata.MarketDataPort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AbstractMaterializationJobTest {

    private static final ZoneId CET = ZoneId.of("Europe/Berlin");

    @Test
    void executeCallsHooksInOrder() {
        var hookOrder = new ArrayList<String>();

        var job = new AbstractMaterializationJob(null, null, null) {
            @Override
            protected List<VolumeRecord> resolveVolume(PositionLedgerEntry pos, DeliveryRange range) {
                hookOrder.add("resolveVolume");
                return List.of(new VolumeRecord(
                    Instant.parse("2025-03-01T00:00:00Z"),
                    Instant.parse("2025-03-01T01:00:00Z"),
                    BigDecimal.TEN, BigDecimal.ONE,
                    1L, null, null, null, BigDecimal.ONE));
            }

            @Override
            protected PriceResolution evaluatePrice(UUID exprId, DeliveryPeriod interval) {
                hookOrder.add("evaluatePrice");
                return new PriceResolution(new BigDecimal("85.00"), Set.of(), Map.of());
            }

            @Override
            protected void writeResult(PositionLedgerEntry pos, VolumeRecord vol, PriceResolution price) {
                hookOrder.add("writeResult");
            }
        };

        var position = PositionLedgerEntry.builder()
            .id(UUID.randomUUID())
            .tenantId("TN_0042")
            .tradeId("T-7788")
            .tradeLegId("LEG-1")
            .tradeVersion(1)
            .deliveryRange(DeliveryRange.ofMonth(YearMonth.of(2025, 3), CET))
            .quantity(BigDecimal.TEN)
            .volumeUnit(VolumeUnit.MW_CAPACITY)
            .priceExpressionId(UUID.randomUUID())
            .validFrom(Instant.now())
            .knownFrom(Instant.now())
            .build();

        job.execute(position, DeliveryRange.ofMonth(YearMonth.of(2025, 3), CET));

        assertEquals(List.of("resolveVolume", "evaluatePrice", "writeResult"), hookOrder);
    }
}
