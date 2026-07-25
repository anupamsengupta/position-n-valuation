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
        assertEquals(new BigDecimal("3.20"), spread.value());
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

        assertEquals(new BigDecimal("42.00"), ((ConstantLeaf) clamp.min()).value());
        assertEquals(new BigDecimal("95.00"), ((ConstantLeaf) clamp.max()).value());
    }

    @Test
    void loadsThreeLevelEscalatedCollarWithNegPriceProtection() {
        // EXPR-4: if(EPEX < 0) then 0 else clamp(38, 110, 72 * (HICP / 108.70))
        var exprOpt = repo.findById(UUID.fromString("00000000-0000-0000-0000-000000000004"));
        assertTrue(exprOpt.isPresent());
        assertInstanceOf(ConditionalGate.class, exprOpt.get());

        // Level 1: ConditionalGate — negative price protection
        ConditionalGate gate = (ConditionalGate) exprOpt.get();
        assertEquals("< 0", gate.condition());
        assertInstanceOf(MarketDataLeaf.class, gate.gateInput());
        assertInstanceOf(ConstantLeaf.class, gate.overrideValue());
        assertEquals(0, BigDecimal.ZERO.compareTo(((ConstantLeaf) gate.overrideValue()).value()));

        // Level 2: Clamp — collar floor/cap
        assertInstanceOf(Clamp.class, gate.inner());
        Clamp collar = (Clamp) gate.inner();
        assertEquals(new BigDecimal("38.00"), ((ConstantLeaf) collar.min()).value());
        assertEquals(new BigDecimal("110.00"), ((ConstantLeaf) collar.max()).value());

        // Level 3: Escalate — CPI escalation of base price
        assertInstanceOf(Escalate.class, collar.inner());
        Escalate escalation = (Escalate) collar.inner();
        assertEquals(new BigDecimal("72.00"), ((ConstantLeaf) escalation.base()).value());

        // Ratio = HICP_current / HICP_base
        assertInstanceOf(Divide.class, escalation.ratio());
        Divide ratio = (Divide) escalation.ratio();
        assertInstanceOf(IndexLeaf.class, ratio.numerator());
        assertInstanceOf(ConstantLeaf.class, ratio.denominator());

        IndexLeaf hicp = (IndexLeaf) ratio.numerator();
        assertEquals("HICP-DE", hicp.series());
        assertEquals("deliveryYear-1:November", hicp.refMonthExpression());
        assertEquals(new BigDecimal("108.70"), ((ConstantLeaf) ratio.denominator()).value());
    }

    @Test
    void loadsCrossBorderFxConvertExpression() {
        // EXPR-5: (NORDPOOL_SYS - 12.00) * EUR/NOK rate
        var exprOpt = repo.findById(UUID.fromString("00000000-0000-0000-0000-000000000005"));
        assertTrue(exprOpt.isPresent());
        assertInstanceOf(FxConvert.class, exprOpt.get());

        FxConvert fx = (FxConvert) exprOpt.get();

        // Value = NORDPOOL_SYS - 12.00 NOK discount
        assertInstanceOf(Subtract.class, fx.value());
        Subtract sub = (Subtract) fx.value();
        assertInstanceOf(MarketDataLeaf.class, sub.left());
        assertEquals("NORDPOOL_SYS", ((MarketDataLeaf) sub.left()).series());
        assertEquals(new BigDecimal("12.00"), ((ConstantLeaf) sub.right()).value());

        // FX rate = EUR/NOK lookup
        assertInstanceOf(MarketDataLeaf.class, fx.fxRate());
        assertEquals("EUR/NOK", ((MarketDataLeaf) fx.fxRate()).series());
    }

    @Test
    void returnsEmptyForUnknownId() {
        assertTrue(repo.findById(UUID.randomUUID()).isEmpty());
    }
}
