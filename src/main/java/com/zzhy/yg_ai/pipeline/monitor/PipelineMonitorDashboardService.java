package com.zzhy.yg_ai.pipeline.monitor;

import com.zzhy.yg_ai.common.DateTimeUtils;
import com.zzhy.yg_ai.domain.dto.monitor.PipelineMonitorDashboardView;
import com.zzhy.yg_ai.pipeline.scheduler.policy.PipelineStage;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PipelineMonitorDashboardService {

    private static final List<Integer> WINDOW_HOURS = List.of(3, 12, 24);

    private final PipelineMonitorRedisStore pipelineMonitorRedisStore;

    public PipelineMonitorDashboardService(PipelineMonitorRedisStore pipelineMonitorRedisStore) {
        this.pipelineMonitorRedisStore = pipelineMonitorRedisStore;
    }

    public PipelineMonitorDashboardView getDashboard() {
        LocalDateTime currentHour = DateTimeUtils.now().truncatedTo(ChronoUnit.HOURS);
        Map<LocalDateTime, Map<String, String>> taskBuckets = loadBuckets(currentHour, true);
        Map<LocalDateTime, Map<String, String>> llmBuckets = loadBuckets(currentHour, false);

        List<PipelineMonitorDashboardView.WindowView> windows = new ArrayList<>();
        for (int hours : WINDOW_HOURS) {
            windows.add(buildWindow(hours, currentHour, taskBuckets, llmBuckets));
        }

        return new PipelineMonitorDashboardView(
                pipelineMonitorRedisStore.loadRuntimeView(),
                pipelineMonitorRedisStore.loadActiveWorkUnits(),
                pipelineMonitorRedisStore.loadBacklogSnapshot(),
                windows
        );
    }

    private Map<LocalDateTime, Map<String, String>> loadBuckets(LocalDateTime currentHour, boolean taskBucket) {
        Map<LocalDateTime, Map<String, String>> result = new LinkedHashMap<>();
        for (int i = 0; i < 24; i++) {
            LocalDateTime bucketHour = currentHour.minusHours(i);
            result.put(bucketHour, taskBucket
                    ? pipelineMonitorRedisStore.loadTaskBucket(bucketHour)
                    : pipelineMonitorRedisStore.loadLlmBucket(bucketHour));
        }
        return result;
    }

    private PipelineMonitorDashboardView.WindowView buildWindow(int hours,
                                                                LocalDateTime currentHour,
                                                                Map<LocalDateTime, Map<String, String>> taskBuckets,
                                                                Map<LocalDateTime, Map<String, String>> llmBuckets) {
        EnumMap<PipelineStage, TaskStageAccumulator> taskStageAccumulators = new EnumMap<>(PipelineStage.class);
        for (PipelineStage stage : PipelineStage.values()) {
            taskStageAccumulators.put(stage, new TaskStageAccumulator());
        }
        Map<String, LlmAccumulator> llmAccumulators = new HashMap<>();
        List<LocalDateTime> bucketHours = new ArrayList<>();

        for (int i = 0; i < hours; i++) {
            LocalDateTime bucketHour = currentHour.minusHours(i);
            bucketHours.add(bucketHour);
            aggregateTaskBucket(taskBuckets.getOrDefault(bucketHour, Map.of()), taskStageAccumulators);
            aggregateLlmBucket(llmBuckets.getOrDefault(bucketHour, Map.of()), llmAccumulators);
        }

        List<PipelineMonitorDashboardView.StageMetricView> taskStages = new ArrayList<>();
        long handledCount = 0L;
        for (PipelineStage stage : PipelineStage.values()) {
            TaskStageAccumulator accumulator = taskStageAccumulators.get(stage);
            handledCount += accumulator.handledCount;
            taskStages.add(new PipelineMonitorDashboardView.StageMetricView(
                    stage.name(),
                    accumulator.handledCount,
                    accumulator.successCount,
                    accumulator.failedCount,
                    accumulator.skippedCount,
                    accumulator.rescheduledCount,
                    safeAvg(accumulator.totalRunMs, accumulator.handledCount),
                    accumulator.maxRunMs
            ));
        }

        List<PipelineMonitorDashboardView.LlmMetricView> llmStages = new ArrayList<>();
        long llmCallCount = 0L;
        long llmTotalCallMs = 0L;
        List<Map.Entry<String, LlmAccumulator>> llmEntries = new ArrayList<>(llmAccumulators.entrySet());
        llmEntries.sort(Comparator.comparing(Map.Entry<String, LlmAccumulator>::getKey));
        for (Map.Entry<String, LlmAccumulator> entry : llmEntries) {
            String[] parts = entry.getKey().split("\\|", 2);
            String stage = parts.length > 0 ? parts[0] : "";
            String nodeType = parts.length > 1 ? parts[1] : "";
            LlmAccumulator accumulator = entry.getValue();
            llmCallCount += accumulator.callCount;
            llmTotalCallMs += accumulator.totalCallMs;
            llmStages.add(new PipelineMonitorDashboardView.LlmMetricView(
                    stage,
                    nodeType,
                    accumulator.callCount,
                    accumulator.totalCallMs,
                    accumulator.successCount,
                    accumulator.failedCount,
                    safeAvg(accumulator.totalCallMs, accumulator.callCount),
                    accumulator.maxCallMs,
                    safeAvg(accumulator.totalPermitWaitMs, accumulator.callCount),
                    accumulator.slowCount,
                    safeRatio(accumulator.slowCount, accumulator.callCount)
            ));
        }
        long llmBusySeconds = pipelineMonitorRedisStore.countLlmBusySeconds(bucketHours);
        long llmBusyMs = llmBusySeconds * 1000L;
        long llmAvgCallMs = safeAvg(llmTotalCallMs, llmCallCount);
        double llmThroughputPerSecond = safeThroughput(llmCallCount, llmBusySeconds);
        long estimatedCallsPerDay = Math.round(llmThroughputPerSecond * 86400D);

        return new PipelineMonitorDashboardView.WindowView(
                hours + "h",
                hours,
                new PipelineMonitorDashboardView.SummaryView(
                        handledCount,
                        llmCallCount,
                        llmTotalCallMs,
                        llmBusyMs,
                        llmAvgCallMs,
                        llmThroughputPerSecond,
                        estimatedCallsPerDay
                ),
                taskStages,
                llmStages
        );
    }

    private void aggregateTaskBucket(Map<String, String> bucketValues,
                                     EnumMap<PipelineStage, TaskStageAccumulator> accumulators) {
        for (Map.Entry<String, String> entry : bucketValues.entrySet()) {
            int separatorIndex = entry.getKey().indexOf(':');
            if (separatorIndex <= 0) {
                continue;
            }
            String stageName = entry.getKey().substring(0, separatorIndex);
            String metric = entry.getKey().substring(separatorIndex + 1);
            PipelineStage stage = parseStage(stageName);
            if (stage == null) {
                continue;
            }
            TaskStageAccumulator accumulator = accumulators.get(stage);
            long value = parseLong(entry.getValue());
            switch (metric) {
                case "handled" -> accumulator.handledCount += value;
                case "success" -> accumulator.successCount += value;
                case "failed" -> accumulator.failedCount += value;
                case "skipped" -> accumulator.skippedCount += value;
                case "rescheduled" -> accumulator.rescheduledCount += value;
                case "total_run_ms" -> accumulator.totalRunMs += value;
                case "max_run_ms" -> accumulator.maxRunMs = Math.max(accumulator.maxRunMs, value);
                default -> {
                    // ignore monitoring internals not rendered on the page
                }
            }
        }
    }

    private void aggregateLlmBucket(Map<String, String> bucketValues, Map<String, LlmAccumulator> accumulators) {
        for (Map.Entry<String, String> entry : bucketValues.entrySet()) {
            int separatorIndex = entry.getKey().lastIndexOf(':');
            if (separatorIndex <= 0) {
                continue;
            }
            String key = entry.getKey().substring(0, separatorIndex);
            String metric = entry.getKey().substring(separatorIndex + 1);
            LlmAccumulator accumulator = accumulators.computeIfAbsent(key, ignored -> new LlmAccumulator());
            long value = parseLong(entry.getValue());
            switch (metric) {
                case "count" -> accumulator.callCount += value;
                case "success" -> accumulator.successCount += value;
                case "failed" -> accumulator.failedCount += value;
                case "total_call_ms" -> accumulator.totalCallMs += value;
                case "max_call_ms" -> accumulator.maxCallMs = Math.max(accumulator.maxCallMs, value);
                case "total_permit_wait_ms" -> accumulator.totalPermitWaitMs += value;
                case "slow_count" -> accumulator.slowCount += value;
                default -> {
                    // ignore monitoring internals not rendered on the page
                }
            }
        }
    }

    private PipelineStage parseStage(String stageName) {
        for (PipelineStage stage : PipelineStage.values()) {
            if (stage.name().equals(stageName)) {
                return stage;
            }
        }
        return null;
    }

    private long safeAvg(long total, long count) {
        return count <= 0 ? 0L : total / count;
    }

    private double safeRatio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0D;
        }
        return ((double) numerator / (double) denominator) * 100D;
    }

    private double safeThroughput(long callCount, long busySeconds) {
        if (busySeconds <= 0) {
            return 0D;
        }
        return (double) callCount / (double) busySeconds;
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private static final class TaskStageAccumulator {
        private long handledCount;
        private long successCount;
        private long failedCount;
        private long skippedCount;
        private long rescheduledCount;
        private long totalRunMs;
        private long maxRunMs;
    }

    private static final class LlmAccumulator {
        private long callCount;
        private long successCount;
        private long failedCount;
        private long totalCallMs;
        private long maxCallMs;
        private long totalPermitWaitMs;
        private long slowCount;
    }
}
