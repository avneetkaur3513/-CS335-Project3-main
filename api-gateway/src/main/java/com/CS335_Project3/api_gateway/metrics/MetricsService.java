package com.CS335_Project3.api_gateway.metrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

//@Component makes Spring create one single instance shared across the whole app
@Component
public class MetricsService {
    private static final Logger log = LoggerFactory.getLogger(MetricsService.class);

    private static final String EVENTS_KEY = "gateway:metrics:events";
    private static final DateTimeFormatter MINUTE_BUCKET_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").withZone(ZoneOffset.UTC);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${metrics.max-events:20000}")
    private int maxEvents;

    // Upper bound for event feed queries to protect Redis-backed dashboard reads.
    @Value("${metrics.events-query.max-limit:500}")
    private int maxEventQueryLimit;

    public MetricsService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void recordRequest(String apiKey, boolean wasBlocked) {
        recordRequest(apiKey, "default", "default", "unknown", "unknown",
                wasBlocked ? "BLOCKED" : "ALLOWED",
                wasBlocked ? "blocked" : "ok",
                "token", wasBlocked ? 429 : 200, 0L);
    }

    public void recordRequest(String apiKey,
                              String tenantId,
                              String appId,
                              String ip,
                              String path,
                              String decision,
                              String reason,
                              String algorithm,
                              int statusCode,
                              long latencyMs) {
        Event event = new Event(
                Instant.now().toEpochMilli(),
                normalize(apiKey, "MISSING"),
                normalize(tenantId, "default"),
                normalize(appId, "default"),
                normalize(ip, "unknown"),
                normalize(path, "unknown"),
                normalize(decision, "ALLOWED"),
                normalize(reason, "ok"),
                normalize(algorithm, "token"),
                statusCode,
                Math.max(0L, latencyMs)
        );
        try {
            redisTemplate.opsForList().rightPush(EVENTS_KEY, objectMapper.writeValueAsString(event));
            redisTemplate.opsForList().trim(EVENTS_KEY, -maxEvents, -1);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize metrics event for apiKey={}", event.apiKey(), e);
        }
    }

    public Map<String, Object> getSnapshot() {
        List<Event> events = getRecentEvents();
        long blocked = events.stream().filter(Event::isBlocked).count();
        Map<String, Long> perKey = events.stream()
                .collect(Collectors.groupingBy(Event::apiKey, Collectors.counting()));

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("totalRequests", events.size());
        snapshot.put("blockedRequests", blocked);
        snapshot.put("allowedRequests", events.size() - blocked);
        snapshot.put("perKey", perKey);
        snapshot.put("statusCodes", toSortedStringLongMap(
                events.stream().collect(Collectors.groupingBy(e -> String.valueOf(e.statusCode()), Collectors.counting()))
        ));
        return snapshot;
    }

    public Map<String, Object> getDashboardData(int lookbackMinutes) {
        List<Event> filtered = filterByMinutes(getRecentEvents(), lookbackMinutes);

        Map<String, Object> data = new LinkedHashMap<>();
        long total = filtered.size();
        long blocked = filtered.stream().filter(Event::isBlocked).count();
        long allowed = total - blocked;

        Map<String, Long> statusCodes = toSortedStringLongMap(
                filtered.stream().collect(Collectors.groupingBy(e -> String.valueOf(e.statusCode()), Collectors.counting()))
        );
        Map<String, Long> perClient = topN(toSortedStringLongMap(
                filtered.stream().collect(Collectors.groupingBy(Event::apiKey, Collectors.counting()))
        ), 12);
        Map<String, Long> blocksByAlgorithm = toSortedStringLongMap(
                filtered.stream()
                        .filter(Event::isBlocked)
                        .collect(Collectors.groupingBy(Event::algorithm, Collectors.counting()))
        );
        double avgLatency = filtered.stream().mapToLong(Event::latencyMs).average().orElse(0.0);

        data.put("lookbackMinutes", lookbackMinutes);
        data.put("summary", Map.of(
                "totalRequests", total,
                "blockedRequests", blocked,
                "allowedRequests", allowed,
                "blockRate", total == 0 ? 0.0 : round(((double) blocked / (double) total) * 100.0),
                "avgLatencyMs", round(avgLatency)
        ));
        data.put("statusCodes", statusCodes);
        data.put("perClient", perClient);
        data.put("blocksByAlgorithm", blocksByAlgorithm);
        data.put("timeseries", buildTimeSeries(filtered));
        data.put("riskyClients", buildRiskyClients(filtered));
        return data;
    }

    public Map<String, Object> getClientDetails(String clientId, int lookbackMinutes) {
        String normalizedClient = normalize(clientId, "missing");
        List<Event> events = filterByMinutes(getRecentEvents(), lookbackMinutes).stream()
                .filter(e -> e.apiKey().equalsIgnoreCase(normalizedClient))
                .toList();

        long blocked = events.stream().filter(Event::isBlocked).count();
        return Map.of(
                "clientId", normalizedClient,
                "lookbackMinutes", lookbackMinutes,
                "totalRequests", events.size(),
                "blockedRequests", blocked,
                "allowedRequests", events.size() - blocked,
                "avgLatencyMs", round(events.stream().mapToLong(Event::latencyMs).average().orElse(0.0)),
                "statusCodes", toSortedStringLongMap(events.stream().collect(Collectors.groupingBy(e -> String.valueOf(e.statusCode()), Collectors.counting()))),
                "algorithms", toSortedStringLongMap(events.stream().collect(Collectors.groupingBy(Event::algorithm, Collectors.counting()))),
                "recent", events.stream().sorted(Comparator.comparingLong(Event::timestamp).reversed()).limit(30).toList()
        );
    }

    public Map<String, Object> getFilteredEvents(int lookbackMinutes,
                                                 String tenant,
                                                 String app,
                                                 String client,
                                                 String ip,
                                                 Integer status,
                                                 String algorithm,
                                                 int limit) {
        String tenantFilter = normalizeFilter(tenant);
        String appFilter = normalizeFilter(app);
        String clientFilter = normalizeFilter(client);
        String ipFilter = normalizeFilter(ip);
        String algorithmFilter = normalizeFilter(algorithm);
        int safeLimit = Math.max(1, Math.min(limit, maxEventQueryLimit));

        List<Event> matched = filterByMinutes(getRecentEvents(), lookbackMinutes).stream()
                .filter(e -> tenantFilter == null || e.tenantId().equals(tenantFilter))
                .filter(e -> appFilter == null || e.appId().equals(appFilter))
                .filter(e -> clientFilter == null || e.apiKey().equals(clientFilter))
                .filter(e -> ipFilter == null || e.ip().equals(ipFilter))
                .filter(e -> status == null || e.statusCode() == status)
                .filter(e -> algorithmFilter == null || e.algorithm().equals(algorithmFilter))
                .sorted(Comparator.comparingLong(Event::timestamp).reversed())
                .toList();

        List<Map<String, Object>> rows = matched.stream()
                .limit(safeLimit)
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("timestamp", e.timestamp());
                    row.put("tenantId", e.tenantId());
                    row.put("appId", e.appId());
                    row.put("apiKey", e.apiKey());
                    row.put("ip", e.ip());
                    row.put("path", e.path());
                    row.put("statusCode", e.statusCode());
                    row.put("decision", e.decision());
                    row.put("reason", e.reason());
                    row.put("algorithm", e.algorithm());
                    row.put("latencyMs", e.latencyMs());
                    return row;
                })
                .toList();

        return Map.of(
                "lookbackMinutes", lookbackMinutes,
                "totalMatched", matched.size(),
                "requestedLimit", limit,
                "effectiveLimit", safeLimit,
                "returned", rows.size(),
                "events", rows
        );
    }

    public List<Map<String, Object>> getRecentEventsForExport(int limit) {
        List<Event> events = getRecentEvents();
        return events.stream()
                .sorted(Comparator.comparingLong(Event::timestamp).reversed())
                .limit(Math.max(1, limit))
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("timestamp", e.timestamp());
                    row.put("apiKey", e.apiKey());
                    row.put("tenantId", e.tenantId());
                    row.put("appId", e.appId());
                    row.put("ip", e.ip());
                    row.put("path", e.path());
                    row.put("decision", e.decision());
                    row.put("reason", e.reason());
                    row.put("algorithm", e.algorithm());
                    row.put("statusCode", e.statusCode());
                    row.put("latencyMs", e.latencyMs());
                    return row;
                }).toList();
    }

    private Map<String, Object> buildTimeSeries(List<Event> events) {
        Map<String, List<Event>> minuteBuckets = events.stream()
                .collect(Collectors.groupingBy(e -> MINUTE_BUCKET_FORMATTER.format(Instant.ofEpochMilli(e.timestamp()))));

        List<String> labels = minuteBuckets.keySet().stream().sorted().toList();
        List<Long> totalSeries = new ArrayList<>();
        List<Long> blockedSeries = new ArrayList<>();
        List<Double> latencySeries = new ArrayList<>();
        List<Long> status200Series = new ArrayList<>();
        List<Long> status403Series = new ArrayList<>();
        List<Long> status429Series = new ArrayList<>();
        List<Long> status5xxSeries = new ArrayList<>();

        for (String label : labels) {
            List<Event> bucket = minuteBuckets.get(label);
            long total = bucket.size();
            long blocked = bucket.stream().filter(Event::isBlocked).count();
            double avgLatency = bucket.stream().mapToLong(Event::latencyMs).average().orElse(0.0);
            long status200 = bucket.stream().filter(e -> e.statusCode() == 200).count();
            long status403 = bucket.stream().filter(e -> e.statusCode() == 403).count();
            long status429 = bucket.stream().filter(e -> e.statusCode() == 429).count();
            long status5xx = bucket.stream().filter(e -> e.statusCode() >= 500 && e.statusCode() <= 599).count();
            totalSeries.add(total);
            blockedSeries.add(blocked);
            latencySeries.add(round(avgLatency));
            status200Series.add(status200);
            status403Series.add(status403);
            status429Series.add(status429);
            status5xxSeries.add(status5xx);
        }

        return Map.of(
                "labels", labels,
                "requests", totalSeries,
                "blocked", blockedSeries,
                "avgLatencyMs", latencySeries,
                "status200", status200Series,
                "status403", status403Series,
                "status429", status429Series,
                "status5xx", status5xxSeries
        );
    }

    private List<Map<String, Object>> buildRiskyClients(List<Event> events) {
        Map<String, List<Event>> byClient = events.stream().collect(Collectors.groupingBy(Event::apiKey));
        return byClient.entrySet().stream()
                .map(entry -> {
                    List<Event> clientEvents = entry.getValue();
                    long total = clientEvents.size();
                    long blocked = clientEvents.stream().filter(Event::isBlocked).count();
                    double blockedRate = total == 0 ? 0.0 : ((double) blocked / (double) total) * 100.0;
                    double volumeFactor = Math.min(100.0, total);
                    double riskScore = round((blockedRate * 0.7) + (volumeFactor * 0.3));
                    return Map.<String, Object>of(
                            "clientId", entry.getKey(),
                            "riskScore", riskScore,
                            "totalRequests", total,
                            "blockedRequests", blocked,
                            "blockedRate", round(blockedRate)
                    );
                })
                .sorted((a, b) -> Double.compare((Double) b.get("riskScore"), (Double) a.get("riskScore")))
                .limit(10)
                .toList();
    }

    private Map<String, Long> toSortedStringLongMap(Map<String, Long> input) {
        return input.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    private Map<String, Long> topN(Map<String, Long> map, int topN) {
        return map.entrySet().stream().limit(topN)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    private List<Event> filterByMinutes(List<Event> events, int lookbackMinutes) {
        if (lookbackMinutes <= 0) {
            return events;
        }
        long cutoff = Instant.now().minusSeconds((long) lookbackMinutes * 60L).toEpochMilli();
        return events.stream().filter(e -> e.timestamp() >= cutoff).toList();
    }

    private List<Event> getRecentEvents() {
        List<String> values = redisTemplate.opsForList().range(EVENTS_KEY, 0, -1);
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<Event> events = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                events.add(objectMapper.readValue(value, Event.class));
            } catch (JsonProcessingException e) {
                log.warn("Skipping invalid metrics event payload from Redis", e);
            }
        }
        return events;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String normalize(String value, String fallback) {
        String out = (value == null || value.isBlank()) ? fallback : value.trim();
        return out.toLowerCase();
    }

    private String normalizeFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase();
    }

    private record Event(long timestamp,
                         String apiKey,
                         String tenantId,
                         String appId,
                         String ip,
                         String path,
                         String decision,
                         String reason,
                         String algorithm,
                         int statusCode,
                         long latencyMs) {
        boolean isBlocked() {
            return "blocked".equalsIgnoreCase(decision) || statusCode == 401 || statusCode == 403 || statusCode == 429;
        }
    }
}
