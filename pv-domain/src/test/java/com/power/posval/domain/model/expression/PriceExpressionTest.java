package com.power.posval.domain.model.expression;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class PriceExpressionTest {

    @Test
    void constantLeafConstruction() {
        var leaf = new ConstantLeaf("L1", new BigDecimal("85.00"), "EUR/MWh");
        assertEquals("L1", leaf.leafId());
        assertEquals(new BigDecimal("85.00"), leaf.value());
        assertEquals("EUR/MWh", leaf.unit());
    }

    @Test
    void treeConstruction() {
        // price = (base + premium) * factor
        PriceExpression base = new ConstantLeaf("base", new BigDecimal("80"), "EUR/MWh");
        PriceExpression premium = new ConstantLeaf("premium", new BigDecimal("5"), "EUR/MWh");
        PriceExpression factor = new ConstantLeaf("factor", new BigDecimal("1.02"), null);
        PriceExpression tree = new Multiply(new Add(base, premium), factor);

        assertInstanceOf(Multiply.class, tree);
        assertInstanceOf(Add.class, ((Multiply) tree).left());
    }

    @Test
    void patternMatchingSwitchIsExhaustive() {
        PriceExpression expr = new ConstantLeaf("x", BigDecimal.ONE, null);
        String type = switch (expr) {
            case ConstantLeaf c -> "constant";
            case MarketDataLeaf m -> "market";
            case IndexLeaf i -> "index";
            case Add a -> "add";
            case Subtract s -> "subtract";
            case Multiply m -> "multiply";
            case Divide d -> "divide";
            case Clamp c -> "clamp";
            case Escalate e -> "escalate";
            case ConditionalGate g -> "gate";
            case ConditionalPassThrough p -> "passthrough";
            case TimeAverage t -> "timeavg";
            case FxConvert f -> "fxconvert";
        };
        assertEquals("constant", type);
    }

    @Test
    void clampConstruction() {
        var min = new ConstantLeaf("min", new BigDecimal("50"), null);
        var max = new ConstantLeaf("max", new BigDecimal("100"), null);
        var inner = new ConstantLeaf("val", new BigDecimal("75"), null);
        var clamp = new Clamp(min, max, inner);
        assertSame(inner, clamp.inner());
    }

    @Test
    void conditionalGateConstruction() {
        var gate = new ConstantLeaf("gate", BigDecimal.ZERO, null);
        var override = new ConstantLeaf("override", BigDecimal.TEN, null);
        var inner = new ConstantLeaf("inner", new BigDecimal("5"), null);
        var cg = new ConditionalGate(gate, "< 0", override, inner);
        assertEquals("< 0", cg.condition());
    }

    @Test
    void fxConvertConstruction() {
        var value = new ConstantLeaf("val", new BigDecimal("100"), "EUR/MWh");
        var rate = new ConstantLeaf("rate", new BigDecimal("1.10"), "EUR/USD");
        var fx = new FxConvert(value, rate);
        assertSame(value, fx.value());
        assertSame(rate, fx.fxRate());
    }
}
