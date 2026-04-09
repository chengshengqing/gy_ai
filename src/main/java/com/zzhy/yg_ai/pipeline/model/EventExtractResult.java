package com.zzhy.yg_ai.pipeline.model;

import java.util.List;

public record EventExtractResult(
        List<Long> taskIds,
        String reqno,
        int successCount,
        int failedCount,
        int caseTaskCount,
        String lastErrorMessage,
        boolean skipped,
        String message
) {
}
