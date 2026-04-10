package com.trading.rsi.model;

import java.math.BigDecimal;

public record StochasticResult(BigDecimal k, BigDecimal d) {

    public boolean isOverbought() {
        return k.compareTo(BigDecimal.valueOf(80)) > 0;
    }

    public boolean isOversold() {
        return k.compareTo(BigDecimal.valueOf(20)) < 0;
    }

    public String label() {
        if (isOverbought()) return "OVERBOUGHT";
        if (isOversold()) return "OVERSOLD";
        return "NEUTRAL";
    }
}
