package com.trading.rsi.service;

import com.trading.rsi.domain.SignalLog;
import com.trading.rsi.repository.SignalLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class HistoryArchivalService {

    private static final String CSV_HEADER =
            "id,symbol,instrument_name,signal_type,current_price,rsi_1m,rsi_5m,rsi_15m,rsi_30m,rsi_1h,rsi_4h," +
            "timeframes_aligned,signal_strength,created_at";

    private final SignalLogRepository signalLogRepository;

    @Value("${archive.enabled:true}")
    private boolean enabled;

    @Value("${archive.dir:./signal_archive}")
    private String archiveDir;

    @Value("${archive.retention-days:90}")
    private int retentionDays;

    public HistoryArchivalService(SignalLogRepository signalLogRepository) {
        this.signalLogRepository = signalLogRepository;
    }

    /**
     * Runs every Sunday at 03:00 UTC. Exports signal_logs older than retention-days
     * to monthly CSV files in archive.dir, then deletes them from the DB.
     *
     * Files are named: signal_logs_YYYY-MM.csv — appended if the file already exists
     * for that month (e.g. if archival runs twice in the same calendar month).
     */
    @Scheduled(cron = "0 0 3 * * SUN", zone = "UTC")
    public void archiveOldSignalLogs() {
        if (!enabled) {
            log.debug("Signal log archival is disabled (archive.enabled=false)");
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(retentionDays);
        List<SignalLog> oldLogs = signalLogRepository.findByCreatedAtBefore(cutoff);

        if (oldLogs.isEmpty()) {
            log.info("Archival: no signal_logs older than {} days to archive (cutoff: {})", retentionDays, cutoff);
            return;
        }

        log.info("Archival: found {} signal_logs older than {} days (cutoff: {})",
                oldLogs.size(), retentionDays, cutoff);

        Path archivePath = Path.of(archiveDir);
        try {
            Files.createDirectories(archivePath);
        } catch (IOException e) {
            log.error("Archival: failed to create archive directory '{}': {}", archiveDir, e.getMessage());
            return;
        }

        Map<String, List<SignalLog>> byMonth = oldLogs.stream()
                .collect(Collectors.groupingBy(entry ->
                        entry.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM"))));

        List<Long> archivedIds = new ArrayList<>();

        for (Map.Entry<String, List<SignalLog>> entry : byMonth.entrySet()) {
            String month = entry.getKey();
            List<SignalLog> monthLogs = entry.getValue();
            Path csvFile = archivePath.resolve("signal_logs_" + month + ".csv");

            boolean fileExists = Files.exists(csvFile);
            try (BufferedWriter writer = Files.newBufferedWriter(csvFile, StandardCharsets.UTF_8,
                    fileExists ? StandardOpenOption.APPEND : StandardOpenOption.CREATE)) {

                if (!fileExists) {
                    writer.write(CSV_HEADER);
                    writer.newLine();
                }

                for (SignalLog record : monthLogs) {
                    writer.write(toCsvRow(record));
                    writer.newLine();
                    archivedIds.add(record.getId());
                }

                log.info("Archival: wrote {} records to {}", monthLogs.size(), csvFile.toAbsolutePath());

            } catch (IOException e) {
                log.error("Archival: failed to write '{}': {} — aborting to avoid data loss", csvFile, e.getMessage());
                return;
            }
        }

        signalLogRepository.deleteAllById(archivedIds);
        log.info("Archival complete: {} records moved from DB to CSV files in '{}'",
                archivedIds.size(), archivePath.toAbsolutePath());
    }

    private String toCsvRow(SignalLog s) {
        return String.join(",",
                String.valueOf(s.getId()),
                csvEscape(s.getSymbol()),
                csvEscape(s.getInstrumentName()),
                csvEscape(String.valueOf(s.getSignalType())),
                nullOrValue(s.getCurrentPrice()),
                nullOrValue(s.getRsi1m()),
                nullOrValue(s.getRsi5m()),
                nullOrValue(s.getRsi15m()),
                nullOrValue(s.getRsi30m()),
                nullOrValue(s.getRsi1h()),
                nullOrValue(s.getRsi4h()),
                nullOrValue(s.getTimeframesAligned()),
                nullOrValue(s.getSignalStrength()),
                nullOrValue(s.getCreatedAt())
        );
    }

    private String csvEscape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String nullOrValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
