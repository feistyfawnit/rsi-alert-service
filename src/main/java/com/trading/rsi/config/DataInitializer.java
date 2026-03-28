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
        int updated = 0;
        for (WatchlistProperties.InstrumentConfig config : watchlistProperties.getInstruments()) {
            var existing = instrumentRepository.findBySymbol(config.getSymbol());
            if (existing.isEmpty()) {
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
                Instrument instrument = existing.get();
                instrument.setEnabled(config.getEnabled());
                instrument.setSource(config.getSource());
                instrument.setOversoldThreshold(config.getOversoldThreshold());
                instrument.setOverboughtThreshold(config.getOverboughtThreshold());
                instrument.setTimeframes(config.getTimeframes());
                instrumentRepository.save(instrument);
                log.debug("Synced instrument from YAML: {} (enabled={})", config.getSymbol(), config.getEnabled());
                updated++;
            }
        }

        if (seeded > 0 || updated > 0) {
            log.info("Data initializer complete: {} seeded, {} synced from YAML", seeded, updated);
        }

        log.info("Monitoring {} enabled instruments", instrumentRepository.findByEnabledTrue().size());
    }
}
