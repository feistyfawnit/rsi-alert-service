package com.trading.rsi.scheduler;

import com.trading.rsi.domain.Instrument;
import com.trading.rsi.model.Candle;
import com.trading.rsi.repository.InstrumentRepository;
import com.trading.rsi.service.MarketDataService;
import com.trading.rsi.service.PriceHistoryService;
import com.trading.rsi.service.SignalDetectionService;
import com.trading.rsi.service.VolumeAnomalyDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class MarketDataPollingService {
    
    private final InstrumentRepository instrumentRepository;
    private final MarketDataService marketDataService;
    private final PriceHistoryService priceHistoryService;
    private final SignalDetectionService signalDetectionService;
    private final VolumeAnomalyDetector volumeAnomalyDetector;
    
    // Track last candle timestamp per instrument+timeframe to avoid duplicate updates
    private final Map<String, Instant> lastCandleTimestamps = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${rsi.polling.interval-seconds:30}000")
    public void pollMarketData() {
        List<Instrument> instruments = instrumentRepository.findByEnabledTrue();
        
        if (instruments.isEmpty()) {
            log.debug("No enabled instruments to monitor");
            return;
        }
        
        log.debug("Polling {} instruments", instruments.size());
        
        for (int i = 0; i < instruments.size(); i++) {
            Instrument instrument = instruments.get(i);
            try {
                if (i > 0) {
                    Thread.sleep(500);
                }
                updateInstrumentData(instrument);
                signalDetectionService.analyzeInstrument(instrument);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Polling interrupted");
                return;
            } catch (Exception e) {
                log.error("Error processing instrument {}: {}", instrument.getSymbol(), e.getMessage(), e);
            }
        }
    }
    
    private void updateInstrumentData(Instrument instrument) {
        List<String> timeframes = Arrays.asList(instrument.getTimeframes().split(","));
        
        for (String timeframe : timeframes) {
            String trimmedTimeframe = timeframe.trim();
            String timestampKey = instrument.getSymbol() + ":" + trimmedTimeframe;
            
            marketDataService.fetchCandles(instrument, trimmedTimeframe, 1)
                    .subscribe(
                            candles -> {
                                if (!candles.isEmpty()) {
                                    Candle latestCandle = candles.get(0);
                                    Instant candleTimestamp = latestCandle.getTimestamp();
                                    
                                    // Only update if this is a new candle we haven't seen
                                    Instant lastTimestamp = lastCandleTimestamps.get(timestampKey);
                                    if (lastTimestamp == null || candleTimestamp.isAfter(lastTimestamp)) {
                                        String key = priceHistoryService.buildKey(instrument.getSymbol(), trimmedTimeframe);
                                        priceHistoryService.updatePriceHistory(key, latestCandle);
                                        lastCandleTimestamps.put(timestampKey, candleTimestamp);
                                        volumeAnomalyDetector.onNewCandle(
                                                instrument.getSymbol(), instrument.getName(),
                                                trimmedTimeframe, latestCandle);
                                        log.info("Updated {} {} with new candle at {}: {}",
                                                instrument.getSymbol(), trimmedTimeframe,
                                                candleTimestamp, latestCandle.getClose());
                                    } else {
                                        log.debug("Skipping duplicate candle for {} {} at {} (last was {})",
                                                instrument.getSymbol(), trimmedTimeframe,
                                                candleTimestamp, lastTimestamp);
                                    }
                                }
                            },
                            error -> log.error("Error fetching candles for {} {}: {}", 
                                    instrument.getSymbol(), trimmedTimeframe, error.getMessage())
                    );
        }
    }
}
