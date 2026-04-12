package com.CS335_Project3.api_gateway.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

@Service
public class MetricsExportService {
    private static final Logger log = LoggerFactory.getLogger(MetricsExportService.class);

    private final MetricsService metricsService;
    private final ObjectMapper objectMapper;

    @Value("${metrics.export.directory:./data/metrics-exports}")
    private String exportDirectory;

    @Value("${metrics.export.lookback-minutes:1440}")
    private int exportLookbackMinutes;

    @Value("${metrics.export.recent-events-limit:500}")
    private int exportRecentEventsLimit;

    public MetricsExportService(MetricsService metricsService, ObjectMapper objectMapper) {
        this.metricsService = metricsService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${metrics.export.interval-ms:3600000}")
    public void scheduledExport() {
        writeSnapshot();
    }

    @PreDestroy
    public void exportBeforeShutdown() {
        writeSnapshot();
    }

    private void writeSnapshot() {
        try {
            Path dir = Path.of(exportDirectory);
            Files.createDirectories(dir);

            Map<String, Object> payload = Map.of(
                    "exportedAtEpochMs", Instant.now().toEpochMilli(),
                    "dashboard", metricsService.getDashboardData(exportLookbackMinutes),
                    "recentEvents", metricsService.getRecentEventsForExport(exportRecentEventsLimit)
            );

            String filename = "metrics-" + Instant.now().toEpochMilli() + ".json";
            Files.writeString(dir.resolve(filename), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload));
        } catch (IOException e) {
            log.warn("Failed to export metrics snapshot to {}", exportDirectory, e);
        }
    }
}
