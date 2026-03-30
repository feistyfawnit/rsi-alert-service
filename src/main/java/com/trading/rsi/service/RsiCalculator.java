package com.trading.rsi.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class RsiCalculator {
    
    private static final int DEFAULT_PERIOD = 14;
    
    public BigDecimal calculateRsi(List<BigDecimal> closePrices, int period) {
        if (closePrices.size() < period + 1) {
            return null;
        }
        
        BigDecimal avgGain = BigDecimal.ZERO;
        BigDecimal avgLoss = BigDecimal.ZERO;
        
        for (int i = 1; i <= period; i++) {
            BigDecimal change = closePrices.get(i).subtract(closePrices.get(i - 1));
            
            if (change.compareTo(BigDecimal.ZERO) > 0) {
                avgGain = avgGain.add(change);
            } else {
                avgLoss = avgLoss.add(change.abs());
            }
        }
        
        avgGain = avgGain.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
        avgLoss = avgLoss.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
        
        for (int i = period + 1; i < closePrices.size(); i++) {
            BigDecimal change = closePrices.get(i).subtract(closePrices.get(i - 1));
            
            BigDecimal gain = change.compareTo(BigDecimal.ZERO) > 0 ? change : BigDecimal.ZERO;
            BigDecimal loss = change.compareTo(BigDecimal.ZERO) < 0 ? change.abs() : BigDecimal.ZERO;
            
            avgGain = avgGain.multiply(BigDecimal.valueOf(period - 1))
                    .add(gain)
                    .divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
            
            avgLoss = avgLoss.multiply(BigDecimal.valueOf(period - 1))
                    .add(loss)
                    .divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
        }
        
        if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.valueOf(100);
        }
        
        BigDecimal rs = avgGain.divide(avgLoss, 8, RoundingMode.HALF_UP);
        BigDecimal rsi = BigDecimal.valueOf(100)
                .subtract(BigDecimal.valueOf(100)
                        .divide(BigDecimal.ONE.add(rs), 4, RoundingMode.HALF_UP));
        
        return rsi;
    }
    
    public BigDecimal calculateRsi(List<BigDecimal> closePrices) {
        return calculateRsi(closePrices, DEFAULT_PERIOD);
    }
}
