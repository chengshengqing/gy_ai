package com.zzhy.yg_ai.pipeline.model;

public record LoadProcessResult(
        Long taskId,
        String reqno,
        boolean success,
        String message,
        String changeTypes
) {
}
