package com.power.posval.domain.service.stub;

import com.power.posval.domain.model.expression.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JsonPriceExpressionRepositoryTest {

    private static JsonPriceExpressionRepository repo;

    @BeforeAll
    static void setUp() {
        repo = new JsonPriceExpressionRepository();
    }

    @Test
    void loadsConstantLeafExpression() {
        var exprOpt = repo.findById(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertTrue(exprOpt.isPresent());
        assertInstanceOf(ConstantLeaf.class, exprOpt.get());

        ConstantLeaf leaf = (ConstantLeaf) exprOpt.get();
        assertEquals("FIXED_85", leaf.leafId());
        assertEquals(new BigDecimal("85.00"), leaf.value());
        assertEquals("EUR/MWh", leaf.unit());
    }

    @Test
    void loadsIndexPlusSpreadExpression() {
        var exprOpt = repo.findById(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        assertTrue(exprOpt.isPresent());
        assertInstanceOf(Add.class, exprOpt.get());

        Add add = (Add) exprOpt.get();
        assertInstanceOf(MarketDataLeaf.class, add.left());
        assertInstanceOf(ConstantLeaf.class, add.right());

        MarketDataLeaf mdLeaf = (MarketDataLeaf) add.left();
        assertEquals("EPEX_DA15", mdLeaf.series());
        assertEquals("EPEX_DA15_SETTLE", mdLeaf.settlementSeries());

        ConstantLeaf spread = (ConstantLeaf) add.right();
        assertEquals(new BigDecimal("5.00"), spread.value());
    }

    @Test
    void loadsCollarPpaExpression() {
        var exprOpt = repo.findById(UUID.fromString("00000000-0000-0000-0000-000000000003"));
        assertTrue(exprOpt.isPresent());
        assertInstanceOf(Clamp.class, exprOpt.get());

        Clamp clamp = (Clamp) exprOpt.get();
        assertInstanceOf(MarketDataLeaf.class, clamp.inner());
        assertInstanceOf(ConstantLeaf.class, clamp.min());
        assertInstanceOf(ConstantLeaf.class, clamp.max());

        assertEquals(new BigDecimal("40.00"), ((ConstantLeaf) clamp.min()).value());
        assertEquals(new BigDecimal("120.00"), ((ConstantLeaf) clamp.max()).value());
    }

    @Test
    void returnsEmptyForUnknownId() {
        assertTrue(repo.findById(UUID.randomUUID()).isEmpty());
    }
}
