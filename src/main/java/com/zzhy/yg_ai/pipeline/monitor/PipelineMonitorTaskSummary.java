package com.zzhy.yg_ai.pipeline.monitor;

public record PipelineMonitorTaskSummary(
        String reqnoHint,
        String outcome
) {

    public static final String SUCCESS = "success";
    public static final String FAILED = "failed";
    public static final String SKIPPED = "skipped";
    public static final String RESCHEDULED = "rescheduled";

    public static PipelineMonitorTaskSummary success(String reqnoHint) {
        return new PipelineMonitorTaskSummary(reqnoHint, SUCCESS);
    }

    public static PipelineMonitorTaskSummary failed(String reqnoHint) {
        return new PipelineMonitorTaskSummary(reqnoHint, FAILED);
    }

    public static PipelineMonitorTaskSummary skipped(String reqnoHint) {
        return new PipelineMonitorTaskSummary(reqnoHint, SKIPPED);
    }

    public static PipelineMonitorTaskSummary rescheduled(String reqnoHint) {
        return new PipelineMonitorTaskSummary(reqnoHint, RESCHEDULED);
    }
}
