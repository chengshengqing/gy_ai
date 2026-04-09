package com.zzhy.yg_ai.pipeline.monitor;

import com.zzhy.yg_ai.pipeline.scheduler.policy.PipelineStage;
import org.springframework.util.StringUtils;

public final class PipelineMonitorExecutionContextHolder {

    private static final ThreadLocal<WorkUnitContext> CONTEXT = new ThreadLocal<>();

    private PipelineMonitorExecutionContextHolder() {
    }

    public static void bind(WorkUnitContext context) {
        if (context == null) {
            CONTEXT.remove();
            return;
        }
        CONTEXT.set(context);
    }

    public static WorkUnitContext current() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }

    public static void updateReqnoHint(String reqnoHint) {
        WorkUnitContext context = CONTEXT.get();
        if (context == null || !StringUtils.hasText(reqnoHint)) {
            return;
        }
        context.setReqnoHint(reqnoHint.trim());
    }

    public static void updateTaskSummary(PipelineMonitorTaskSummary taskSummary) {
        WorkUnitContext context = CONTEXT.get();
        if (context == null || taskSummary == null) {
            return;
        }
        context.setTaskSummary(taskSummary);
    }

    public static String currentReqnoHint() {
        WorkUnitContext context = CONTEXT.get();
        return context == null ? null : context.reqnoHint();
    }

    public static final class WorkUnitContext {

        private final PipelineStage stage;
        private final String unitId;
        private final boolean business;
        private final String threadName;
        private final long submittedAtEpochMillis;
        private final long startedAtEpochMillis;
        private String reqnoHint;
        private PipelineMonitorTaskSummary taskSummary;

        public WorkUnitContext(PipelineStage stage,
                               String unitId,
                               boolean business,
                               String threadName,
                               long submittedAtEpochMillis,
                               long startedAtEpochMillis) {
            this.stage = stage;
            this.unitId = unitId;
            this.business = business;
            this.threadName = threadName;
            this.submittedAtEpochMillis = submittedAtEpochMillis;
            this.startedAtEpochMillis = startedAtEpochMillis;
        }

        public PipelineStage stage() {
            return stage;
        }

        public String unitId() {
            return unitId;
        }

        public boolean business() {
            return business;
        }

        public String kind() {
            return business ? "business" : "coordinator";
        }

        public String threadName() {
            return threadName;
        }

        public long submittedAtEpochMillis() {
            return submittedAtEpochMillis;
        }

        public long startedAtEpochMillis() {
            return startedAtEpochMillis;
        }

        public String reqnoHint() {
            return reqnoHint;
        }

        public PipelineMonitorTaskSummary taskSummary() {
            return taskSummary;
        }

        private void setReqnoHint(String reqnoHint) {
            this.reqnoHint = reqnoHint;
        }

        private void setTaskSummary(PipelineMonitorTaskSummary taskSummary) {
            this.taskSummary = taskSummary;
        }
    }
}
