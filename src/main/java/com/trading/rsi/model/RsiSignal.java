package com.trading.rsi.model;

import com.trading.rsi.domain.SignalLog;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RsiSignal {
    private String symbol;
    private String instrumentName;
    private SignalLog.SignalType signalType;
    private BigDecimal currentPrice;
    private Map<String, BigDecimal> rsiValues;
    private int timeframesAligned;
    private int totalTimeframes;
    private BigDecimal signalStrength;
    private Candle triggerCandle;
    private Map<String, StochasticResult> stochasticValues;
    /**
     * If true, the signal is recorded (SignalLog + PositionOutcome) but NO Telegram
     * notification is sent. Used for silent P&L tracking of per-instrument signal
     * types the user wants to evaluate without live alerts.
     */
    private boolean silent;
}
