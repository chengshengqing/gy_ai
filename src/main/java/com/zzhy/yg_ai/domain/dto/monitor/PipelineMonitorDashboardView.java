package com.zzhy.yg_ai.domain.dto.monitor;

import java.time.LocalDateTime;
import java.util.List;

public record PipelineMonitorDashboardView(
        RuntimeView runtime,
        List<ActiveWorkUnitView> activeWorkUnits,
        List<BacklogStageView> backlog,
        List<WindowView> windows
) {

    public record RuntimeView(
            ExecutorRuntimeView executor,
            ModelRuntimeView model
    ) {
    }

    public record ExecutorRuntimeView(
            int totalThreads,
            int activeThreads,
            int idleThreads,
            int queueSize,
            LocalDateTime updatedAt
    ) {
    }

    public record ModelRuntimeView(
            int totalPermits,
            int permitsInUse,
            int permitsIdle,
            LocalDateTime updatedAt
    ) {
    }

    public record ActiveWorkUnitView(
            String unitId,
            String stage,
            String kind,
            String threadName,
            long runningMs,
            LocalDateTime startedAt
    ) {
    }

    public record BacklogStageView(
            String stage,
            long readyPending,
            long delayedPending,
            long running,
            long oldestReadyAgeMs
    ) {
    }

    public record WindowView(
            String label,
            int hours,
            SummaryView summary,
            List<StageMetricView> taskStages,
            List<LlmMetricView> llmStages
    ) {
    }

    public record SummaryView(
            long handledCount,
            long llmCallCount,
            long llmTotalCallMs,
            long llmBusyMs,
            long llmAvgCallMs,
            double llmThroughputPerSecond,
            long estimatedCallsPerDay
    ) {
    }

    public record StageMetricView(
            String stage,
            long handledCount,
            long successCount,
            long failedCount,
            long skippedCount,
            long rescheduledCount,
            long avgRunMs,
            long maxRunMs
    ) {
    }

    public record LlmMetricView(
            String stage,
            String nodeType,
            long callCount,
            long totalCallMs,
            long successCount,
            long failedCount,
            long avgCallMs,
            long maxCallMs,
            long avgPermitWaitMs,
            long slowCount,
            double slowRatio
    ) {
    }
}
