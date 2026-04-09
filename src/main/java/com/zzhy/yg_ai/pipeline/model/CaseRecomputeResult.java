package com.zzhy.yg_ai.pipeline.model;

import java.util.List;

public record CaseRecomputeResult(
        List<Long> taskIds,
        String reqno,
        int successCount,
        int failedCount,
        boolean skipped,
        boolean rescheduled,
        String lastErrorMessage,
        String message
) {
}
