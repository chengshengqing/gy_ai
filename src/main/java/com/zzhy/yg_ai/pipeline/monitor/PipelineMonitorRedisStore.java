package com.zzhy.yg_ai.pipeline.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.common.DateTimeUtils;
import com.zzhy.yg_ai.common.InfectionRedisKeys;
import com.zzhy.yg_ai.config.PipelineMonitorProperties;
import com.zzhy.yg_ai.domain.dto.monitor.PipelineMonitorDashboardView;
import com.zzhy.yg_ai.pipeline.scheduler.policy.PipelineStage;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.BoundHashOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineMonitorRedisStore {

    private static final Duration RUNTIME_KEY_TTL = Duration.ofHours(2);
    private static final long FAILURE_LOG_WINDOW_MS = 60_000L;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final PipelineMonitorProperties properties;
    private final AtomicLong lastFailureLogAt = new AtomicLong(0L);

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public void refreshExecutorRuntime(int totalThreads, int activeCount, int queueSize) {
        if (!isEnabled()) {
            return;
        }
        executeSafely("refreshExecutorRuntime", () -> {
            LocalDateTime now = DateTimeUtils.now();
            Map<String, String> values = new HashMap<>();
            values.put("totalThreads", String.valueOf(Math.max(0, totalThreads)));
            values.put("activeThreads", String.valueOf(Math.max(0, activeCount)));
            values.put("idleThreads", String.valueOf(Math.max(0, totalThreads - activeCount)));
            values.put("queueSize", String.valueOf(Math.max(0, queueSize)));
            values.put("updatedAt", DateTimeUtils.format(now));
            putHashAndExpire(InfectionRedisKeys.pipelineMonitorExecutorRuntime(), values, RUNTIME_KEY_TTL);
        });
    }

    public void refreshModelRuntime(int totalPermits, int permitsInUse) {
        if (!isEnabled()) {
            return;
        }
        executeSafely("refreshModelRuntime", () -> {
            LocalDateTime now = DateTimeUtils.now();
            Map<String, String> values = new HashMap<>();
            values.put("totalPermits", String.valueOf(Math.max(0, totalPermits)));
            values.put("permitsInUse", String.valueOf(Math.max(0, permitsInUse)));
            values.put("permitsIdle", String.valueOf(Math.max(0, totalPermits - permitsInUse)));
            values.put("updatedAt", DateTimeUtils.format(now));
            putHashAndExpire(InfectionRedisKeys.pipelineMonitorModelRuntime(), values, RUNTIME_KEY_TTL);
        });
    }

    public void markWorkUnitStarted(PipelineStage stage,
                                    String unitId,
                                    boolean business,
                                    String threadName,
                                    long startedAtEpochMillis) {
        if (!isEnabled() || stage == null || !StringUtils.hasText(unitId)) {
            return;
        }
        ActiveWorkUnitCacheValue cacheValue = new ActiveWorkUnitCacheValue(
                unitId,
                stage.name(),
                business ? "business" : "coordinator",
                threadName,
                startedAtEpochMillis
        );
        executeSafely("markWorkUnitStarted", () -> {
            stringRedisTemplate.opsForHash().put(
                    InfectionRedisKeys.pipelineMonitorActiveWorkUnits(),
                    unitId,
                    objectMapper.writeValueAsString(cacheValue)
            );
            stringRedisTemplate.expire(InfectionRedisKeys.pipelineMonitorActiveWorkUnits(), RUNTIME_KEY_TTL);
        });
    }

    public void markWorkUnitFinished(String unitId) {
        if (!isEnabled() || !StringUtils.hasText(unitId)) {
            return;
        }
        executeSafely("markWorkUnitFinished", () ->
                stringRedisTemplate.opsForHash().delete(InfectionRedisKeys.pipelineMonitorActiveWorkUnits(), unitId));
    }

    public void recordTaskSummary(PipelineStage stage,
                                  PipelineMonitorTaskSummary taskSummary,
                                  long queueWaitMs,
                                  long runMs) {
        if (!isEnabled() || stage == null || taskSummary == null || !StringUtils.hasText(taskSummary.outcome())) {
            return;
        }
        executeSafely("recordTaskSummary", () -> {
            String key = InfectionRedisKeys.pipelineMonitorTaskBucket(currentBucketHour());
            BoundHashOperations<String, Object, Object> ops = stringRedisTemplate.boundHashOps(key);
            increment(ops, taskField(stage, "handled"), 1L);
            increment(ops, taskField(stage, taskSummary.outcome()), 1L);
            increment(ops, taskField(stage, "total_run_ms"), Math.max(0L, runMs));
            increment(ops, taskField(stage, "total_queue_wait_ms"), Math.max(0L, queueWaitMs));
            updateMax(ops, taskField(stage, "max_run_ms"), Math.max(0L, runMs));
            updateMax(ops, taskField(stage, "max_queue_wait_ms"), Math.max(0L, queueWaitMs));
            expireBucket(key);
        });
    }

    public void recordLlmCall(PipelineStage stage,
                              String nodeType,
                              boolean success,
                              long permitWaitMs,
                              long callMs,
                              long callStartedAtEpochMillis,
                              long callFinishedAtEpochMillis) {
        if (!isEnabled() || stage == null) {
            return;
        }
        executeSafely("recordLlmCall", () -> {
            String key = InfectionRedisKeys.pipelineMonitorLlmBucket(currentBucketHour());
            BoundHashOperations<String, Object, Object> ops = stringRedisTemplate.boundHashOps(key);
            String safeNodeType = StringUtils.hasText(nodeType) ? nodeType.trim() : "UNSPECIFIED";
            increment(ops, llmField(stage, safeNodeType, "count"), 1L);
            increment(ops, llmField(stage, safeNodeType, success ? "success" : "failed"), 1L);
            increment(ops, llmField(stage, safeNodeType, "total_call_ms"), Math.max(0L, callMs));
            increment(ops, llmField(stage, safeNodeType, "total_permit_wait_ms"), Math.max(0L, permitWaitMs));
            updateMax(ops, llmField(stage, safeNodeType, "max_call_ms"), Math.max(0L, callMs));
            updateMax(ops, llmField(stage, safeNodeType, "max_permit_wait_ms"), Math.max(0L, permitWaitMs));
            if (callMs >= Math.max(1L, properties.getLlmSlowThresholdMs())) {
                increment(ops, llmField(stage, safeNodeType, "slow_count"), 1L);
            }
            expireBucket(key);
            markLlmBusySeconds(callStartedAtEpochMillis, callFinishedAtEpochMillis);
        });
    }

    public void saveBacklogSnapshot(List<PipelineMonitorBacklogRow> rows) {
        if (!isEnabled()) {
            return;
        }
        executeSafely("saveBacklogSnapshot", () -> {
            LocalDateTime now = DateTimeUtils.now();
            Map<String, String> values = new HashMap<>();
            for (PipelineStage stage : PipelineStage.values()) {
                values.put(backlogField(stage.name(), "ready_pending"), "0");
                values.put(backlogField(stage.name(), "delayed_pending"), "0");
                values.put(backlogField(stage.name(), "running"), "0");
                values.put(backlogField(stage.name(), "oldest_ready_at"), "");
            }
            if (rows != null) {
                for (PipelineMonitorBacklogRow row : rows) {
                    if (row == null || !StringUtils.hasText(row.stage())) {
                        continue;
                    }
                    String stage = row.stage().trim();
                    values.put(backlogField(stage, "ready_pending"), String.valueOf(Math.max(0L, row.readyPending())));
                    values.put(backlogField(stage, "delayed_pending"), String.valueOf(Math.max(0L, row.delayedPending())));
                    values.put(backlogField(stage, "running"), String.valueOf(Math.max(0L, row.running())));
                    values.put(backlogField(stage, "oldest_ready_at"), DateTimeUtils.format(row.oldestReadyAt()));
                }
            }
            values.put("updatedAt", DateTimeUtils.format(now));
            putHashAndExpire(InfectionRedisKeys.pipelineMonitorBacklogSnapshot(), values, RUNTIME_KEY_TTL);
        });
    }

    public PipelineMonitorDashboardView.RuntimeView loadRuntimeView() {
        Map<String, String> executorValues = readHash(InfectionRedisKeys.pipelineMonitorExecutorRuntime());
        Map<String, String> modelValues = readHash(InfectionRedisKeys.pipelineMonitorModelRuntime());
        PipelineMonitorDashboardView.ExecutorRuntimeView executor = new PipelineMonitorDashboardView.ExecutorRuntimeView(
                readInt(executorValues, "totalThreads"),
                readInt(executorValues, "activeThreads"),
                readInt(executorValues, "idleThreads"),
                readInt(executorValues, "queueSize"),
                readDateTime(executorValues.get("updatedAt"))
        );
        PipelineMonitorDashboardView.ModelRuntimeView model = new PipelineMonitorDashboardView.ModelRuntimeView(
                readInt(modelValues, "totalPermits"),
                readInt(modelValues, "permitsInUse"),
                readInt(modelValues, "permitsIdle"),
                readDateTime(modelValues.get("updatedAt"))
        );
        return new PipelineMonitorDashboardView.RuntimeView(executor, model);
    }

    public List<PipelineMonitorDashboardView.ActiveWorkUnitView> loadActiveWorkUnits() {
        Map<String, String> rawValues = readHash(InfectionRedisKeys.pipelineMonitorActiveWorkUnits());
        List<PipelineMonitorDashboardView.ActiveWorkUnitView> result = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (String rawValue : rawValues.values()) {
            if (!StringUtils.hasText(rawValue)) {
                continue;
            }
            try {
                ActiveWorkUnitCacheValue cacheValue = objectMapper.readValue(rawValue, ActiveWorkUnitCacheValue.class);
                LocalDateTime startedAt = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(cacheValue.startedAtEpochMillis()),
                        ZoneId.systemDefault()
                );
                result.add(new PipelineMonitorDashboardView.ActiveWorkUnitView(
                        cacheValue.unitId(),
                        cacheValue.stage(),
                        cacheValue.kind(),
                        cacheValue.threadName(),
                        Math.max(0L, now - cacheValue.startedAtEpochMillis()),
                        startedAt
                ));
            } catch (Exception e) {
                log.warn("解析活跃 work unit 监控信息失败", e);
            }
        }
        result.sort(Comparator.comparingLong(PipelineMonitorDashboardView.ActiveWorkUnitView::runningMs).reversed());
        return result;
    }

    public List<PipelineMonitorDashboardView.BacklogStageView> loadBacklogSnapshot() {
        Map<String, String> rawValues = readHash(InfectionRedisKeys.pipelineMonitorBacklogSnapshot());
        List<PipelineMonitorDashboardView.BacklogStageView> result = new ArrayList<>();
        LocalDateTime now = DateTimeUtils.now();
        for (PipelineStage stage : PipelineStage.values()) {
            LocalDateTime oldestReadyAt = readDateTime(rawValues.get(backlogField(stage.name(), "oldest_ready_at")));
            long oldestReadyAgeMs = oldestReadyAt == null ? 0L : Math.max(0L, Duration.between(oldestReadyAt, now).toMillis());
            result.add(new PipelineMonitorDashboardView.BacklogStageView(
                    stage.name(),
                    readLong(rawValues, backlogField(stage.name(), "ready_pending")),
                    readLong(rawValues, backlogField(stage.name(), "delayed_pending")),
                    readLong(rawValues, backlogField(stage.name(), "running")),
                    oldestReadyAgeMs
            ));
        }
        return result;
    }

    public Map<String, String> loadTaskBucket(LocalDateTime bucketHour) {
        return readHash(InfectionRedisKeys.pipelineMonitorTaskBucket(bucketHour));
    }

    public Map<String, String> loadLlmBucket(LocalDateTime bucketHour) {
        return readHash(InfectionRedisKeys.pipelineMonitorLlmBucket(bucketHour));
    }

    public long countLlmBusySeconds(List<LocalDateTime> bucketHours) {
        if (!isEnabled() || bucketHours == null || bucketHours.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        for (LocalDateTime bucketHour : bucketHours) {
            String key = InfectionRedisKeys.pipelineMonitorLlmBusyBitmap(bucketHour);
            Long count = executeSafely("countLlmBusySeconds",
                    () -> stringRedisTemplate.execute((RedisCallback<Long>) connection ->
                            connection.stringCommands().bitCount(key.getBytes(StandardCharsets.UTF_8))),
                    0L);
            total += count == null ? 0L : count;
        }
        return total;
    }

    private LocalDateTime currentBucketHour() {
        LocalDateTime now = DateTimeUtils.now();
        return now.truncatedTo(ChronoUnit.HOURS);
    }

    private void increment(BoundHashOperations<String, Object, Object> ops, String field, long value) {
        ops.increment(field, value);
    }

    private void updateMax(BoundHashOperations<String, Object, Object> ops, String field, long candidate) {
        Object existingValue = ops.get(field);
        long existing = existingValue == null ? 0L : parseLong(String.valueOf(existingValue));
        if (candidate > existing) {
            ops.put(field, String.valueOf(candidate));
        }
    }

    private void expireBucket(String key) {
        stringRedisTemplate.expire(key, Duration.ofHours(Math.max(1, properties.getBucketTtlHours())));
    }

    private void markLlmBusySeconds(long callStartedAtEpochMillis, long callFinishedAtEpochMillis) {
        if (callStartedAtEpochMillis <= 0L || callFinishedAtEpochMillis < callStartedAtEpochMillis) {
            return;
        }
        LocalDateTime cursor = LocalDateTime.ofInstant(Instant.ofEpochMilli(callStartedAtEpochMillis), ZoneId.systemDefault())
                .truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime end = LocalDateTime.ofInstant(Instant.ofEpochMilli(callFinishedAtEpochMillis), ZoneId.systemDefault())
                .truncatedTo(ChronoUnit.SECONDS);
        while (!cursor.isAfter(end)) {
            LocalDateTime bucketHour = cursor.truncatedTo(ChronoUnit.HOURS);
            String key = InfectionRedisKeys.pipelineMonitorLlmBusyBitmap(bucketHour);
            long offset = cursor.getMinute() * 60L + cursor.getSecond();
            stringRedisTemplate.opsForValue().setBit(key, offset, true);
            stringRedisTemplate.expire(key, Duration.ofHours(Math.max(1, properties.getBucketTtlHours())));
            cursor = cursor.plusSeconds(1);
        }
    }

    private void putHashAndExpire(String key, Map<String, String> values, Duration ttl) {
        if (values == null || values.isEmpty()) {
            return;
        }
        stringRedisTemplate.opsForHash().putAll(key, values);
        stringRedisTemplate.expire(key, ttl);
    }

    private Map<String, String> readHash(String key) {
        Map<Object, Object> source = executeSafely("readHash", () -> stringRedisTemplate.opsForHash().entries(key), Map.of());
        Map<String, String> result = new HashMap<>();
        if (source == null || source.isEmpty()) {
            return result;
        }
        for (Map.Entry<Object, Object> entry : source.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
        return result;
    }

    private int readInt(Map<String, String> values, String field) {
        return (int) readLong(values, field);
    }

    private long readLong(Map<String, String> values, String field) {
        return parseLong(values == null ? null : values.get(field));
    }

    private long parseLong(String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return 0L;
        }
        try {
            return Long.parseLong(rawValue.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private LocalDateTime readDateTime(String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return null;
        }
        for (var formatter : DateTimeUtils.DATE_TIME_PARSE_FORMATTERS) {
            try {
                return DateTimeUtils.truncateToMillis(LocalDateTime.parse(rawValue.trim(), formatter));
            } catch (Exception ignore) {
                // try next formatter
            }
        }
        return null;
    }

    private String taskField(PipelineStage stage, String metric) {
        return stage.name() + ":" + metric;
    }

    private String llmField(PipelineStage stage, String nodeType, String metric) {
        return stage.name() + "|" + nodeType + ":" + metric;
    }

    private String backlogField(String stage, String metric) {
        return stage + ":" + metric;
    }

    private void executeSafely(String operation, RedisAction action) {
        try {
            action.run();
        } catch (Exception e) {
            logMonitorFailure(operation, e);
        }
    }

    private <T> T executeSafely(String operation, RedisSupplier<T> supplier, T fallback) {
        try {
            return supplier.get();
        } catch (Exception e) {
            logMonitorFailure(operation, e);
            return fallback;
        }
    }

    private void logMonitorFailure(String operation, Exception exception) {
        long now = System.currentTimeMillis();
        long lastLoggedAt = lastFailureLogAt.get();
        if (now - lastLoggedAt >= FAILURE_LOG_WINDOW_MS && lastFailureLogAt.compareAndSet(lastLoggedAt, now)) {
            log.warn("Redis 监控存储不可用，已跳过本次监控操作，operation={}", operation, exception);
            return;
        }
        log.debug("Redis 监控存储不可用，operation={}", operation, exception);
    }

    @FunctionalInterface
    private interface RedisAction {

        void run() throws Exception;
    }

    @FunctionalInterface
    private interface RedisSupplier<T> {

        T get() throws Exception;
    }

    private record ActiveWorkUnitCacheValue(
            String unitId,
            String stage,
            String kind,
            String threadName,
            long startedAtEpochMillis
    ) {
    }
}
