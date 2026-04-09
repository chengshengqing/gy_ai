package com.zzhy.yg_ai.common;

import com.zzhy.yg_ai.domain.enums.SummaryContextType;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class InfectionRedisKeys {

    private static final String PREFIX = "yg_ai:infection";
    private static final DateTimeFormatter MONITOR_BUCKET_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHH");

    private InfectionRedisKeys() {
    }

    public static String patientSummaryContext(SummaryContextType summaryType, String reqno) {
        String safeReqno = reqno == null ? "" : reqno.trim();
        return String.join(":",
                PREFIX,
                "summary_context",
                summaryType == null ? "" : summaryType.name(),
                safeReqno,
                "latest");
    }

    public static String patientSummaryContextPattern(String reqno) {
        String safeReqno = reqno == null ? "" : reqno.trim();
        return String.join(":",
                PREFIX,
                "summary_context",
                "*",
                safeReqno,
                "*");
    }

    public static String pipelineMonitorExecutorRuntime() {
        return String.join(":", PREFIX, "monitor", "runtime", "executor");
    }

    public static String pipelineMonitorModelRuntime() {
        return String.join(":", PREFIX, "monitor", "runtime", "model");
    }

    public static String pipelineMonitorActiveWorkUnits() {
        return String.join(":", PREFIX, "monitor", "runtime", "active_work_units");
    }

    public static String pipelineMonitorBacklogSnapshot() {
        return String.join(":", PREFIX, "monitor", "snapshot", "backlog");
    }

    public static String pipelineMonitorTaskBucket(LocalDateTime bucketHour) {
        return String.join(":",
                PREFIX,
                "monitor",
                "bucket",
                "task",
                formatBucketHour(bucketHour));
    }

    public static String pipelineMonitorLlmBucket(LocalDateTime bucketHour) {
        return String.join(":",
                PREFIX,
                "monitor",
                "bucket",
                "llm",
                formatBucketHour(bucketHour));
    }

    public static String pipelineMonitorLlmBusyBitmap(LocalDateTime bucketHour) {
        return String.join(":",
                PREFIX,
                "monitor",
                "bitmap",
                "llm_busy",
                formatBucketHour(bucketHour));
    }

    private static String formatBucketHour(LocalDateTime bucketHour) {
        return bucketHour == null
                ? ""
                : DateTimeUtils.truncateToMillis(bucketHour).withMinute(0).withSecond(0).withNano(0).format(MONITOR_BUCKET_FORMATTER);
    }
}
