package com.zzhy.yg_ai.pipeline.model;

public record LoadEnqueueResult(
        boolean success,
        int enqueuedCount,
        String message
) {
}
