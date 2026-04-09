package com.zzhy.yg_ai.pipeline.monitor;

import java.time.LocalDateTime;

public record PipelineMonitorBacklogRow(
        String stage,
        long readyPending,
        long delayedPending,
        long running,
        LocalDateTime oldestReadyAt
) {
}
