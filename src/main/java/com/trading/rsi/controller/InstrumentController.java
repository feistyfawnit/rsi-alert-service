package com.trading.rsi.controller;

import com.trading.rsi.domain.Instrument;
import com.trading.rsi.repository.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/instruments")
@RequiredArgsConstructor
public class InstrumentController {
    
    private final InstrumentRepository instrumentRepository;
    
    @GetMapping
    public List<Instrument> getAllInstruments() {
        return instrumentRepository.findAll();
    }
    
    @GetMapping("/enabled")
    public List<Instrument> getEnabledInstruments() {
        return instrumentRepository.findByEnabledTrue();
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<Instrument> getInstrument(@PathVariable Long id) {
        return instrumentRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    @PostMapping
    public Instrument createInstrument(@RequestBody Instrument instrument) {
        return instrumentRepository.save(instrument);
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<Instrument> updateInstrument(@PathVariable Long id, @RequestBody Instrument instrument) {
        return instrumentRepository.findById(id)
                .map(existing -> {
                    existing.setSymbol(instrument.getSymbol());
                    existing.setName(instrument.getName());
                    existing.setSource(instrument.getSource());
                    existing.setType(instrument.getType());
                    existing.setEnabled(instrument.getEnabled());
                    existing.setOversoldThreshold(instrument.getOversoldThreshold());
                    existing.setOverboughtThreshold(instrument.getOverboughtThreshold());
                    existing.setTimeframes(instrument.getTimeframes());
                    return ResponseEntity.ok(instrumentRepository.save(existing));
                })
                .orElse(ResponseEntity.notFound().build());
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteInstrument(@PathVariable Long id) {
        return instrumentRepository.findById(id)
                .map(instrument -> {
                    instrumentRepository.delete(instrument);
                    return ResponseEntity.ok().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }
    
    @PatchMapping("/{id}/toggle")
    public ResponseEntity<Instrument> toggleInstrument(@PathVariable Long id) {
        return instrumentRepository.findById(id)
                .map(instrument -> {
                    instrument.setEnabled(!instrument.getEnabled());
                    return ResponseEntity.ok(instrumentRepository.save(instrument));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
