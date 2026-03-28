package com.trading.rsi.scheduler;

import com.trading.rsi.domain.Instrument;
import com.trading.rsi.model.Candle;
import com.trading.rsi.repository.InstrumentRepository;
import com.trading.rsi.service.MarketDataService;
import com.trading.rsi.service.PriceHistoryService;
import com.trading.rsi.service.SignalDetectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class MarketDataPollingService {
    
    private final InstrumentRepository instrumentRepository;
    private final MarketDataService marketDataService;
    private final PriceHistoryService priceHistoryService;
    private final SignalDetectionService signalDetectionService;
    
    @Value("${rsi.polling.interval-seconds:30}")
    private int pollingIntervalSeconds;
    
    @Scheduled(fixedDelayString = "${rsi.polling.interval-seconds:30}000")
    public void pollMarketData() {
        List<Instrument> instruments = instrumentRepository.findByEnabledTrue();
        
        if (instruments.isEmpty()) {
            log.debug("No enabled instruments to monitor");
            return;
        }
        
        log.debug("Polling {} instruments", instruments.size());
        
        for (Instrument instrument : instruments) {
            try {
                updateInstrumentData(instrument);
                signalDetectionService.analyzeInstrument(instrument);
            } catch (Exception e) {
                log.error("Error processing instrument {}: {}", instrument.getSymbol(), e.getMessage(), e);
            }
        }
    }
    
    private void updateInstrumentData(Instrument instrument) {
        List<String> timeframes = Arrays.asList(instrument.getTimeframes().split(","));
        
        for (String timeframe : timeframes) {
            String trimmedTimeframe = timeframe.trim();
            
            marketDataService.fetchCandles(instrument, trimmedTimeframe, 1)
                    .subscribe(
                            candles -> {
                                if (!candles.isEmpty()) {
                                    Candle latestCandle = candles.get(0);
                                    String key = priceHistoryService.buildKey(instrument.getSymbol(), trimmedTimeframe);
                                    priceHistoryService.updatePriceHistory(key, latestCandle);
                                    log.debug("Updated {} {} price: {}", instrument.getSymbol(), trimmedTimeframe, latestCandle.getClose());
                                }
                            },
                            error -> log.error("Error fetching candles for {} {}: {}", 
                                    instrument.getSymbol(), trimmedTimeframe, error.getMessage())
                    );
        }
    }
}
