package com.trading.rsi.config;

import com.trading.rsi.domain.Instrument;
import com.trading.rsi.repository.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final InstrumentRepository instrumentRepository;
    private final WatchlistProperties watchlistProperties;

    @Override
    public void run(ApplicationArguments args) {
        if (watchlistProperties.getInstruments() == null || watchlistProperties.getInstruments().isEmpty()) {
            log.warn("No instruments configured in application.yml watchlist section");
            return;
        }

        int seeded = 0;
        for (WatchlistProperties.InstrumentConfig config : watchlistProperties.getInstruments()) {
            if (!instrumentRepository.existsBySymbol(config.getSymbol())) {
                Instrument instrument = Instrument.builder()
                        .symbol(config.getSymbol())
                        .name(config.getName())
                        .source(config.getSource())
                        .type(config.getType())
                        .enabled(config.getEnabled())
                        .oversoldThreshold(config.getOversoldThreshold())
                        .overboughtThreshold(config.getOverboughtThreshold())
                        .timeframes(config.getTimeframes())
                        .build();
                instrumentRepository.save(instrument);
                log.info("Seeded instrument: {} ({})", config.getName(), config.getSymbol());
                seeded++;
            } else {
                log.debug("Instrument already exists, skipping: {}", config.getSymbol());
            }
        }

        if (seeded > 0) {
            log.info("Data initializer complete: {} new instruments seeded", seeded);
        }

        log.info("Monitoring {} enabled instruments", instrumentRepository.findByEnabledTrue().size());
    }
}
